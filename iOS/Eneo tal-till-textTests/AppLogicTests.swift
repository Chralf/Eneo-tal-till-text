import Foundation
import Testing
@testable import Eneo_tal_till_text

struct AppLogicTests {
    @Test func importFileNamesAreUniqueForSameOriginalName() throws {
        let firstID = try #require(UUID(uuidString: "00000000-0000-0000-0000-000000000001"))
        let secondID = try #require(UUID(uuidString: "00000000-0000-0000-0000-000000000002"))
        let first = EneoTalTillTextApp.importFileName(
            for: "möte.m4a",
            id: firstID
        )
        let second = EneoTalTillTextApp.importFileName(
            for: "möte.m4a",
            id: secondID
        )

        #expect(first != second)
        #expect(first.hasSuffix("_möte.m4a"))
        #expect(second.hasSuffix("_möte.m4a"))
    }

    @Test func importFileNameSanitizesUnsupportedCharacters() throws {
        let id = try #require(UUID(uuidString: "00000000-0000-0000-0000-000000000001"))
        let fileName = EneoTalTillTextApp.importFileName(
            for: "möte/#1?.m4a",
            id: id
        )

        #expect(fileName == "shared_00000000-0000-0000-0000-000000000001_möte__1_.m4a")
    }

    @Test func retentionExpiresAtConfiguredDayBoundary() {
        let now = Date(timeIntervalSince1970: 2 * 24 * 60 * 60)
        let justBeforeBoundary = now.addingTimeInterval(-(24 * 60 * 60) + 1)
        let atBoundary = now.addingTimeInterval(-(24 * 60 * 60))

        #expect(!RecordingRetention.isExpired(dateCreated: justBeforeBoundary, retentionDays: 1, now: now))
        #expect(RecordingRetention.isExpired(dateCreated: atBoundary, retentionDays: 1, now: now))
    }

    @Test func baseURLNormalizationAddsHTTPSAndRemovesTrailingSlashes() {
        #expect(APISettings.normalizedBaseURLString(" eneo.kommun.se/// ") == "https://eneo.kommun.se")
        #expect(APISettings.makeBaseURL(from: "eneo.kommun.se")?.absoluteString == "https://eneo.kommun.se")
        #expect(APISettings.makeBaseURL(from: "http://eneo.kommun.se") == nil)
    }

    @Test func parseAccountEmailPrefersEmailThenUsername() throws {
        let withEmail = Data(#"{"email": " anna@kommun.se ", "username": "anna"}"#.utf8)
        #expect(EneoAuthService.parseAccountEmail(from: withEmail) == "anna@kommun.se")

        let withoutEmail = Data(#"{"username": "anna", "name": "Anna"}"#.utf8)
        #expect(EneoAuthService.parseAccountEmail(from: withoutEmail) == "anna")

        let emptyEmail = Data(#"{"email": "", "name": "Anna"}"#.utf8)
        #expect(EneoAuthService.parseAccountEmail(from: emptyEmail) == "Anna")

        let empty = Data("{}".utf8)
        #expect(EneoAuthService.parseAccountEmail(from: empty) == nil)

        let notAnObject = Data("[1, 2]".utf8)
        #expect(EneoAuthService.parseAccountEmail(from: notAnObject) == nil)
    }
}
