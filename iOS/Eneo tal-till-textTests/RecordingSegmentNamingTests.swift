import Foundation
import Testing
@testable import Eneo_tal_till_text

struct RecordingSegmentNamingTests {
    @Test func partFileNamesAreZeroPaddedAndRoundTrip() throws {
        let name = RecordingSegmentNaming.partFileName(baseName: "recording_1700000000", index: 7)

        #expect(name == "recording_1700000000_p007.m4a")
        let parsed = try #require(RecordingSegmentNaming.parsePart(name))
        #expect(parsed.base == "recording_1700000000")
        #expect(parsed.index == 7)
    }

    @Test func finalFileNamesAreNotMistakenForParts() {
        #expect(!RecordingSegmentNaming.isPartFileName("recording_1700000000.m4a"))
        #expect(!RecordingSegmentNaming.isPartFileName("recording_p001.wav"))
        #expect(!RecordingSegmentNaming.isPartFileName("recording_1700000000_p1.m4a"))
        #expect(RecordingSegmentNaming.isPartFileName("Recording_1700000000_P002.M4A"))
    }

    @Test func baseNameStripsOnlyTheM4aExtension() {
        #expect(RecordingSegmentNaming.baseName(fromFinalFileName: "recording_1.m4a") == "recording_1")
        #expect(RecordingSegmentNaming.baseName(fromFinalFileName: "shared_x_möte.wav") == "shared_x_möte.wav")
        #expect(RecordingSegmentNaming.finalFileName(baseName: "recording_1") == "recording_1.m4a")
    }

    @Test func groupingSortsPartsByIndexWithinEachSession() {
        let directory = URL(fileURLWithPath: "/tmp/Recordings", isDirectory: true)
        let files = [
            RecordingSegmentNaming.partURL(directory: directory, baseName: "a", index: 10),
            RecordingSegmentNaming.partURL(directory: directory, baseName: "b", index: 2),
            RecordingSegmentNaming.partURL(directory: directory, baseName: "a", index: 2),
            directory.appendingPathComponent("a.m4a"),
            RecordingSegmentNaming.partURL(directory: directory, baseName: "a", index: 1),
        ]

        let grouped = RecordingSegmentNaming.groupPartFiles(files)

        #expect(grouped.keys.sorted() == ["a", "b"])
        #expect(grouped["a"]?.map(\.lastPathComponent) == ["a_p001.m4a", "a_p002.m4a", "a_p010.m4a"])
        #expect(grouped["b"]?.map(\.lastPathComponent) == ["b_p002.m4a"])
    }
}
