import Foundation
import SwiftData
import Testing
@testable import Eneo_tal_till_text

/// Serves canned responses keyed by URL path, so upload-service calls can be
/// tested without a real Eneo server.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var responses: [String: (status: Int, body: String)] = [:]

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        // path() keeps the trailing slash that Eneo's endpoints use.
        let stub = Self.responses[url.path(percentEncoded: false)] ?? (404, "{}")
        let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

/// Suites that stub Eneo share `StubURLProtocol.responses`, UserDefaults and
/// the Keychain token, so they must never run in parallel with each other.
@Suite(.serialized)
struct StubbedEneoTests {}

extension StubbedEneoTests {
@MainActor
@Suite
struct BugRegressionTests {
    @Test func mergeTempFileIsNeitherAPartNorARecording() {
        let name = "recording_1700000000\(RecordingSegmentNaming.mergeTempSuffix)"
        #expect(RecordingSegmentNaming.isMergeTempFileName(name))
        #expect(!RecordingSegmentNaming.isPartFileName(name))
        #expect(!RecordingSegmentNaming.isMergeTempFileName("recording_1700000000.m4a"))
    }

    @Test func retentionSkipsRecordingThatIsOpenOnScreen() throws {
        let container = try ModelContainer(
            for: Recording.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let recording = Recording(title: "Gammal", audioFileName: "missing-\(UUID().uuidString).m4a")
        recording.dateCreated = Date().addingTimeInterval(-40 * 24 * 60 * 60)
        context.insert(recording)
        try context.save()

        RecordingPersistence.openRecordingIDs.insert(recording.id)
        RecordingPersistence.purgeExpiredRecordings(context: context)
        #expect(try context.fetchCount(FetchDescriptor<Recording>()) == 1)

        RecordingPersistence.openRecordingIDs.remove(recording.id)
        RecordingPersistence.purgeExpiredRecordings(context: context)
        #expect(try context.fetchCount(FetchDescriptor<Recording>()) == 0)
    }

    @Test func fetchAppsSkipsAppTheAccountCannotRead() async throws {
        try await withStubbedEneo(lockedAppStatus: 403) { service in
            let apps = try await service.fetchApps()
            #expect(apps.map(\.id) == ["a1"])
        }
    }

    @Test func fetchAppsStillFailsWhenSignInHasExpired() async throws {
        try await withStubbedEneo(lockedAppStatus: 401) { service in
            do {
                _ = try await service.fetchApps()
                Issue.record("Expected fetchApps to throw")
            } catch RecordingUploadError.unauthorized {
                // Expected: an expired sign-in must still surface.
            } catch {
                Issue.record("Unexpected error: \(error)")
            }
        }
    }

    /// One space with a readable audio app (a1) and a second app (a2) that
    /// answers with `lockedAppStatus`. Restores the real settings afterwards.
    private func withStubbedEneo(
        lockedAppStatus: Int,
        _ body: (RecordingUploadService) async throws -> Void
    ) async throws {
        let defaults = UserDefaults.standard
        let previousBaseURL = defaults.string(forKey: APISettings.baseURLStorageKey)
        let previousToken = AuthTokenStore.loadAccessToken()
        defer {
            defaults.set(previousBaseURL, forKey: APISettings.baseURLStorageKey)
            if previousToken.isEmpty {
                AuthTokenStore.clearAccessToken()
            } else {
                AuthTokenStore.saveAccessToken(previousToken)
            }
            StubURLProtocol.responses = [:]
        }

        defaults.set("https://eneo.test", forKey: APISettings.baseURLStorageKey)
        AuthTokenStore.saveAccessToken("test-token")
        StubURLProtocol.responses = [
            "/api/v1/spaces/": (200, #"[{"id":"s1","name":"Space"}]"#),
            "/api/v1/spaces/s1/applications/": (200, #"{"items":[{"id":"a1","name":"Bra"},{"id":"a2","name":"Låst"}]}"#),
            "/api/v1/apps/a1/": (200, #"{"input_fields":[{"type":"audio-upload"}]}"#),
            "/api/v1/apps/a2/": (lockedAppStatus, "{}")
        ]

        try await body(RecordingUploadService(urlSession: StubURLProtocol.makeSession()))
    }
}
}
