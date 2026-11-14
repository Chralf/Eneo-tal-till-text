import Foundation
import Observation
import SwiftData

/// Runs conversions and the waiting queue. Lives outside the views so queued
/// recordings can be converted when no recording is open.
@Observable
@MainActor
final class ConversionCoordinator {
    /// Failure from a conversion in this session, with symbol and sign-in
    /// hint. Persisted text takes over after a relaunch.
    private(set) var lastErrors: [UUID: ErrorPresentation] = [:]
    private(set) var isProcessingQueue = false

    private let uploadService: RecordingUploadService
    private let authService: EneoAuthService
    private let requestNotificationPermission: @MainActor () async -> Void

    /// `requestNotificationPermission` is replaceable because the system
    /// prompt would block unit tests.
    init(
        uploadService: RecordingUploadService,
        authService: EneoAuthService,
        requestNotificationPermission: @escaping @MainActor () async -> Void = {
            await TranscriptionNotificationService.requestAuthorizationIfNeeded()
        }
    ) {
        self.uploadService = uploadService
        self.authService = authService
        self.requestNotificationPermission = requestNotificationPermission
    }

    /// Starts converting `recording` with `appID`. Returns nil when another
    /// upload is already running.
    @discardableResult
    func convert(
        _ recording: Recording,
        appID: String,
        context: ModelContext,
        fromQueue: Bool = false
    ) -> Task<Void, Never>? {
        guard !uploadService.isUploading else { return nil }

        // Capture the app that owns a resumable run before storing the new
        // one, so changing the app never attaches an old run's result.
        let previousRunAppID = recording.uploadAppID?.trimmingCharacters(in: .whitespacesAndNewlines)
        recording.uploadAppID = appID
        if recording.hasTranscription {
            recording.uploadServerID = nil
        } else if let existingRun = recording.uploadServerID?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !existingRun.isEmpty,
                  let runApp = previousRunAppID,
                  !runApp.isEmpty,
                  runApp != appID {
            recording.uploadServerID = nil
        }
        lastErrors[recording.id] = nil
        recording.uploadError = nil
        recording.uploadStatus = "processing"
        recording.uploadProgressText = String(localized: "Laddar upp ljud...", comment: "Status medan ljudfilen skickas")
        try? context.save()

        return uploadService.performUpload(recordingID: recording.id) { [self] in
            await requestNotificationPermission()

            do {
                let response = try await uploadService.upload(recording, appID: appID) { fileID, runID in
                    guard Self.isAlive(recording) else { return }
                    if let fileID {
                        recording.uploadFileID = fileID
                    }
                    if let runID {
                        recording.uploadServerID = runID
                    }
                    try? context.save()
                }
                guard Self.isAlive(recording) else { return }
                recording.uploadFileID = response.fileID
                recording.uploadServerID = response.id
                recording.uploadStatus = response.status ?? "complete"
                recording.uploadProgressText = nil
                recording.uploadedAt = Date()
                recording.transcriptionText = response.output
                updateMetadata(of: recording, from: response.output)
                recording.uploadError = nil
                try? context.save()
                if recording.hasTranscription {
                    await TranscriptionNotificationService.notifyCompleted()
                }
            } catch is CancellationError {
                resetAfterCancel(recording, context: context)
            } catch let error as URLError where error.code == .cancelled {
                resetAfterCancel(recording, context: context)
            } catch {
                handleFailure(error, for: recording, context: context, fromQueue: fromQueue)
            }
        }
    }

    /// Marks the recording to be converted once Eneo is reachable.
    func enqueue(_ recording: Recording, context: ModelContext) {
        lastErrors[recording.id] = nil
        recording.uploadError = nil
        recording.uploadProgressText = nil
        recording.uploadStatus = Recording.queuedStatus
        try? context.save()
    }

    func cancelWaiting(_ recording: Recording, context: ModelContext) {
        guard recording.uploadStatus == Recording.queuedStatus else { return }
        recording.uploadStatus = nil
        try? context.save()
    }

    func clearError(for recording: Recording, context: ModelContext) {
        lastErrors[recording.id] = nil
        recording.uploadError = nil
        try? context.save()
    }

    /// Converts waiting recordings one at a time, oldest first. Stops as soon
    /// as Eneo or the sign-in is lost; the rest stay queued for next time.
    func processQueue(context: ModelContext) async {
        guard !isProcessingQueue else { return }
        guard authService.isAuthenticated, uploadService.serverReachability == .reachable else { return }
        isProcessingQueue = true
        defer { isProcessingQueue = false }

        var attempted: Set<UUID> = []
        var cachedApps: [EneoApp]?

        while authService.isAuthenticated, !uploadService.isUploading {
            let queued = ((try? context.fetch(FetchDescriptor<Recording>(sortBy: [SortDescriptor(\.dateCreated)]))) ?? [])
                .filter { $0.uploadStatus == Recording.queuedStatus && $0.audioFileExists && !attempted.contains($0.id) }
            guard let recording = queued.first else { return }
            attempted.insert(recording.id)

            var appID = recording.uploadAppID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if appID.isEmpty {
                appID = APISettings.storedDefaultAppID ?? ""
            }
            if appID.isEmpty {
                // Without a chosen app only a single available app is safe
                // to pick; otherwise the recording waits for the user.
                if cachedApps == nil {
                    cachedApps = (try? await uploadService.fetchApps()) ?? []
                }
                guard let apps = cachedApps, apps.count == 1 else { continue }
                appID = apps[0].id
                recording.uploadAppName = apps[0].name
                recording.uploadSpaceName = apps[0].spaceName
            }

            guard let task = convert(recording, appID: appID, context: context, fromQueue: true) else { return }
            await task.value

            // Put back in the queue: connection or sign-in was lost.
            if recording.uploadStatus == Recording.queuedStatus { return }
        }
    }

    private func resetAfterCancel(_ recording: Recording, context: ModelContext) {
        guard Self.isAlive(recording) else { return }
        recording.uploadStatus = nil
        recording.uploadProgressText = nil
        recording.uploadError = nil
        try? context.save()
    }

    private func handleFailure(_ error: Error, for recording: Recording, context: ModelContext, fromQueue: Bool) {
        guard Self.isAlive(recording) else { return }
        let presentation = ErrorPresentation(error)
        recording.uploadProgressText = nil

        if presentation.requiresSignIn {
            authService.handleExpiredSession()
        }

        // A queued recording that loses the network or the sign-in simply
        // keeps waiting; that is not a failed conversion.
        if fromQueue, presentation.requiresSignIn || Self.isConnectivityError(error) {
            recording.uploadStatus = Recording.queuedStatus
            try? context.save()
            return
        }

        lastErrors[recording.id] = presentation
        recording.uploadError = [presentation.title, presentation.message]
            .compactMap { $0 }
            .joined(separator: "\n")

        // An expired session is a normal event, not a failed conversion: the
        // recording stays untouched, the banner offers "Logga in igen", and
        // the upload resumes automatically after sign-in.
        if presentation.requiresSignIn {
            recording.uploadStatus = nil
            try? context.save()
            return
        }

        recording.uploadStatus = "failed"
        try? context.save()

        Task {
            await TranscriptionNotificationService.notifyFailed()
        }
    }

    private func updateMetadata(of recording: Recording, from output: String?) {
        guard let output, !output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            recording.summaryText = nil
            return
        }
        let metadata = uploadService.metadata(from: output)
        recording.summaryText = metadata.shortSummary
        if let suggested = metadata.suggestedTitle?.trimmingCharacters(in: .whitespacesAndNewlines),
           !suggested.isEmpty,
           recording.hasPlaceholderTitle {
            recording.title = suggested
        }
    }

    /// A recording deleted while its upload ran must not be written to.
    private static func isAlive(_ recording: Recording) -> Bool {
        !recording.isDeleted && recording.modelContext != nil
    }

    static func isConnectivityError(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost,
             .cannotFindHost, .timedOut, .dnsLookupFailed, .internationalRoamingOff,
             .dataNotAllowed, .secureConnectionFailed:
            return true
        default:
            return false
        }
    }
}
