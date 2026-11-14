import Foundation
import SwiftData
import Testing
@testable import Eneo_tal_till_text

extension StubbedEneoTests {
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ConversionCoordinatorTests {
    @Test func convertStoresResultAndSuggestedTitle() async throws {
        try await withStubbedEneo { coordinator, context in
            let recording = try makeRecording(in: context)

            let task = try #require(coordinator.convert(recording, appID: "a1", context: context))
            await task.value

            #expect(recording.transcriptionState == .completed)
            #expect(recording.uploadServerID == "r1")
            #expect(recording.title == "Veckomöte")
        }
    }

    @Test func leftoverQueuedStatusIsNotShownAsAState() {
        let recording = Recording(title: "Möte", audioFileName: "x.m4a")
        recording.uploadStatus = "queued"
        #expect(recording.transcriptionState == .none)
    }

    // MARK: - Helpers

    private func makeRecording(in context: ModelContext) throws -> Recording {
        let directory = try Recording.ensureRecordingsDirectory()
        let fileName = "coordinator-test-\(UUID().uuidString).m4a"
        try Data(repeating: 1, count: 2048).write(to: directory.appendingPathComponent(fileName))
        let recording = Recording(title: "Inspelning 2026-11-14 10:00", audioFileName: fileName)
        context.insert(recording)
        try context.save()
        return recording
    }

    /// A stubbed Eneo with app a1 that converts any file to a short result.
    /// Restores the real settings afterwards.
    private func withStubbedEneo(
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
        defaults.set("a1", forKey: APISettings.appIDStorageKey)
        AuthTokenStore.saveAccessToken("test-token")

        StubURLProtocol.responses = [
            "/api/v1/files/": (200, #"{"id":"f1"}"#),
            "/api/v1/apps/a1/runs/": (200, #"{"id":"r1"}"#),
            "/api/v1/app-runs/r1/": (200, ##"{"id":"r1","status":"complete","output":"# Veckomöte\nVi gick igenom planen."}"##)
        ]

        let container = try ModelContainer(for: Recording.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let uploadService = RecordingUploadService(urlSession: StubURLProtocol.makeSession())
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
