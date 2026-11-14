import Foundation
import SwiftData

struct TextSegment: Codable, Identifiable, Sendable, Hashable {
    let id: UUID
    let startTime: TimeInterval
    let endTime: TimeInterval
    let text: String

    init(startTime: TimeInterval, endTime: TimeInterval, text: String) {
        self.id = UUID()
        self.startTime = startTime
        self.endTime = endTime
        self.text = text
    }

    var formattedTimeRange: String {
        let start = Self.formatTime(startTime)
        let end = Self.formatTime(endTime)
        return "[\(start) → \(end)]"
    }

    private static func formatTime(_ time: TimeInterval) -> String {
        DurationFormatting.timestamp(time)
    }
}

@Model
final class Recording {
    static let maxRecordingDuration: TimeInterval = 2 * 60 * 60

    @Attribute(.unique) var id: UUID
    var title: String
    var dateCreated: Date
    var duration: TimeInterval
    var audioFileName: String
    var transcriptionText: String?
    var segments: [TextSegment]
    var isTranscribing: Bool
    var isImported: Bool
    var summaryText: String?
    var meetingNotes: String?
    var keyPoints: [String]?
    var decisionPoints: [String]?
    var actionItems: [String]?
    var uploadServerID: String?
    var uploadFileID: String?
    var uploadStatus: String?
    var uploadProgressText: String?
    var uploadedAt: Date?
    var uploadError: String?
    var uploadAppID: String?
    var uploadAppName: String?
    var uploadSpaceName: String?
    /// Unused; retention is app-wide in Settings. Kept for SwiftData schema stability.
    var retentionDaysOverride: Int?

    var hasSummary: Bool {
        summaryText != nil && !(summaryText?.isEmpty ?? true)
    }

    var audioFileURL: URL {
        Recording.recordingsDirectory.appendingPathComponent(audioFileName)
    }

    var audioFileExists: Bool {
        FileManager.default.fileExists(atPath: audioFileURL.path)
    }

    static var recordingsDirectory: URL {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return FileManager.default.temporaryDirectory.appendingPathComponent("Recordings")
        }
        return documents.appendingPathComponent("Recordings")
    }

    static func ensureRecordingsDirectory() throws -> URL {
        let directory = recordingsDirectory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        protectFile(at: directory)
        return directory
    }

    static func protectFile(at url: URL) {
        #if os(iOS)
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: url.path
        )
        #endif

        var protectedURL = url
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        try? protectedURL.setResourceValues(resourceValues)
    }

    var hasTranscription: Bool {
        !(transcriptionText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }

    enum TranscriptionState {
        case none
        case processing
        case failed
        case completed
    }

    var transcriptionState: TranscriptionState {
        if uploadStatus == "processing" || isTranscribing {
            return .processing
        }
        // An expired sign-in keeps its explanation in uploadError but is not
        // a failed conversion, so only the status decides.
        if uploadStatus == "failed" {
            return .failed
        }
        if hasTranscription {
            return .completed
        }
        return .none
    }

    var hasPlaceholderTitle: Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("Inspelning ") || trimmed.hasPrefix("Återställd ")
    }

    var formattedDuration: String {
        DurationFormatting.elapsed(duration)
    }

    var formattedDate: String {
        dateCreated.formatted(date: .abbreviated, time: .shortened)
    }

    init(title: String, audioFileName: String, duration: TimeInterval = 0, isImported: Bool = false) {
        self.id = UUID()
        self.title = title
        self.dateCreated = Date()
        self.duration = duration
        self.audioFileName = audioFileName
        self.transcriptionText = nil
        self.segments = []
        self.isTranscribing = false
        self.isImported = isImported
        self.summaryText = nil
        self.meetingNotes = nil
        self.keyPoints = nil
        self.decisionPoints = nil
        self.actionItems = nil
        self.uploadServerID = nil
        self.uploadFileID = nil
        self.uploadStatus = nil
        self.uploadProgressText = nil
        self.uploadedAt = nil
        self.uploadError = nil
        self.uploadAppID = nil
        self.uploadAppName = nil
        self.uploadSpaceName = nil
        self.retentionDaysOverride = nil
    }

    func clearTranscription() {
        transcriptionText = nil
        segments = []
        summaryText = nil
        meetingNotes = nil
        keyPoints = nil
        decisionPoints = nil
        actionItems = nil
        isTranscribing = false
    }
}
