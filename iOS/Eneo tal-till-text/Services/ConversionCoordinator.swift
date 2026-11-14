import Foundation
import Observation
import SwiftData

/// Runs a conversion and stores its result. Kept out of the views so the
/// upload, its error state and the result handling live in one place.
@Observable
@MainActor
final class ConversionCoordinator {
    /// Failure from a conversion in this session, with symbol and sign-in
    /// hint. Persisted text takes over after a relaunch.
    private(set) var lastErrors: [UUID: ErrorPresentation] = [:]

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
        context: ModelContext
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
                handleFailure(error, for: recording, context: context)
            }
        }
    }

    func clearError(for recording: Recording, context: ModelContext) {
        lastErrors[recording.id] = nil
        recording.uploadError = nil
        try? context.save()
    }

    private func resetAfterCancel(_ recording: Recording, context: ModelContext) {
        guard Self.isAlive(recording) else { return }
        recording.uploadStatus = nil
        recording.uploadProgressText = nil
        recording.uploadError = nil
        try? context.save()
    }

    private func handleFailure(_ error: Error, for recording: Recording, context: ModelContext) {
        guard Self.isAlive(recording) else { return }
        let presentation = ErrorPresentation(error)
        recording.uploadProgressText = nil

        if presentation.requiresSignIn {
            authService.handleExpiredSession()
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
}
