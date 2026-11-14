import Foundation
import SwiftData
import Testing
@testable import Eneo_tal_till_text

extension StubbedEneoTests {
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ConversionQueueTests {
    @Test func transcriptionStateReportsQueued() {
        let recording = Recording(title: "Möte", audioFileName: "x.m4a")
        recording.uploadStatus = Recording.queuedStatus
        #expect(recording.transcriptionState == .queued)
    }

    @Test func queuedRecordingIsConvertedWhenEneoIsReachable() async throws {
        try await withQueueEnvironment(defaultAppID: "a1") { coordinator, context in
            let recording = try makeRecording(in: context)
            coordinator.enqueue(recording, context: context)

            await coordinator.processQueue(context: context)

            #expect(recording.transcriptionState == .completed)
            #expect(recording.uploadServerID == "r1")
            #expect(recording.title == "Veckomöte")
        }
    }

    @Test func queueLeavesRecordingWaitingWhenNoAppIsChosen() async throws {
        // Two audio apps and no default: the queue must not guess.
        try await withQueueEnvironment(defaultAppID: nil, extraApp: true) { coordinator, context in
            let recording = try makeRecording(in: context)
            coordinator.enqueue(recording, context: context)

            await coordinator.processQueue(context: context)

            #expect(recording.transcriptionState == .queued)
        }
    }

    @Test func queueDoesNothingWhileEneoIsUnreachable() async throws {
        try await withQueueEnvironment(defaultAppID: "a1", reachability: .unreachable) { coordinator, context in
            let recording = try makeRecording(in: context)
            coordinator.enqueue(recording, context: context)

            await coordinator.processQueue(context: context)

            #expect(recording.transcriptionState == .queued)
        }
    }

    @Test func cancelWaitingClearsQueuedState() throws {
        let container = try ModelContainer(for: Recording.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let coordinator = ConversionCoordinator(uploadService: RecordingUploadService(), authService: EneoAuthService())
        let recording = Recording(title: "Möte", audioFileName: "x.m4a")
        container.mainContext.insert(recording)

        coordinator.enqueue(recording, context: container.mainContext)
        coordinator.cancelWaiting(recording, context: container.mainContext)

        #expect(recording.transcriptionState == .none)
    }

    // MARK: - Helpers

    private func makeRecording(in context: ModelContext) throws -> Recording {
        let directory = try Recording.ensureRecordingsDirectory()
        let fileName = "queue-test-\(UUID().uuidString).m4a"
        try Data(repeating: 1, count: 2048).write(to: directory.appendingPathComponent(fileName))
        let recording = Recording(title: "Inspelning 2026-11-14 10:00", audioFileName: fileName)
        context.insert(recording)
        try context.save()
        return recording
    }

    /// A stubbed Eneo with app a1 (and optionally a2) that converts any file
    /// to a short result. Restores the real settings afterwards.
    private func withQueueEnvironment(
        defaultAppID: String?,
        extraApp: Bool = false,
        reachability: ServerReachability = .reachable,
        _ body: (ConversionCoordinator, ModelContext) async throws -> Void
    ) async throws {
        let defaults = UserDefaults.standard
        let previousBaseURL = defaults.string(forKey: APISettings.baseURLStorageKey)
        let previousAppID = defaults.string(forKey: APISettings.appIDStorageKey)
        let previousToken = AuthTokenStore.loadAccessToken()
        defer {
            defaults.set(previousBaseURL, forKey: APISettings.baseURLStorageKey)
            defaults.set(previousAppID, forKey: APISettings.appIDStorageKey)
            if previousToken.isEmpty {
                AuthTokenStore.clearAccessToken()
            } else {
                AuthTokenStore.saveAccessToken(previousToken)
            }
            StubURLProtocol.responses = [:]
        }

        defaults.set("https://eneo.test", forKey: APISettings.baseURLStorageKey)
        defaults.set(defaultAppID, forKey: APISettings.appIDStorageKey)
        AuthTokenStore.saveAccessToken("test-token")

        let appItems = extraApp
            ? #"{"items":[{"id":"a1","name":"Ett"},{"id":"a2","name":"Två"}]}"#
            : #"{"items":[{"id":"a1","name":"Ett"}]}"#
        StubURLProtocol.responses = [
            "/api/v1/spaces/": (200, #"[{"id":"s1","name":"Space"}]"#),
            "/api/v1/spaces/s1/applications/": (200, appItems),
            "/api/v1/apps/a1/": (200, #"{"input_fields":[{"type":"audio-upload"}]}"#),
            "/api/v1/apps/a2/": (200, #"{"input_fields":[{"type":"audio-upload"}]}"#),
            "/api/v1/files/": (200, #"{"id":"f1"}"#),
            "/api/v1/apps/a1/runs/": (200, #"{"id":"r1"}"#),
            "/api/v1/app-runs/r1/": (200, ##"{"id":"r1","status":"complete","output":"# Veckomöte\nVi gick igenom planen."}"##)
        ]

        let container = try ModelContainer(for: Recording.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let uploadService = RecordingUploadService(urlSession: StubURLProtocol.makeSession())
        uploadService.serverReachability = reachability
        let coordinator = ConversionCoordinator(
            uploadService: uploadService,
            authService: EneoAuthService(),
            requestNotificationPermission: {}
        )

        try await body(coordinator, container.mainContext)

        for recording in (try? container.mainContext.fetch(FetchDescriptor<Recording>())) ?? [] {
            try? FileManager.default.removeItem(at: recording.audioFileURL)
        }
    }
}
}
