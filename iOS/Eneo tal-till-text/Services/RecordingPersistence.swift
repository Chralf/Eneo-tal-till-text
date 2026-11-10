import Foundation
import SwiftData
import AVFoundation

enum RecordingPersistence {
    /// Resets stuck transcription flags and recovers audio files without DB rows.
    @MainActor
    static func recoverOnLaunch(context: ModelContext) {
        resetStuckTranscribingFlags(context: context)
        Task { @MainActor in
            await recoverSegmentSessions(context: context)
            refreshMissingDurations(context: context)
            recoverOrphanAudioFiles(context: context)
            purgeExpiredRecordings(context: context)
            try? context.save()
        }
    }

    /// Applies the retention setting. Also run when the app returns to the
    /// foreground: iOS keeps the process alive for days, so launch alone would
    /// let expired audio linger far past the configured limit.
    @MainActor
    static func purgeExpiredRecordings(context: ModelContext) {
        guard let recordings = try? context.fetch(FetchDescriptor<Recording>()) else { return }
        let now = Date()
        for recording in recordings {
            if recording.isTranscribing || recording.uploadStatus == "processing" {
                continue
            }
            guard RecordingRetention.isExpired(
                dateCreated: recording.dateCreated,
                now: now
            ) else { continue }

            if recording.audioFileExists {
                try? FileManager.default.removeItem(at: recording.audioFileURL)
            }
            deleteSessionPartFiles(forFinalFileName: recording.audioFileName)
            context.delete(recording)
        }
        try? context.save()
    }

    @MainActor
    private static func resetStuckTranscribingFlags(context: ModelContext) {
        guard let recordings = try? context.fetch(FetchDescriptor<Recording>()) else { return }
        for recording in recordings where recording.isTranscribing || recording.uploadStatus == "processing" {
            recording.isTranscribing = false
            if recording.uploadStatus == "processing" {
                recording.uploadStatus = nil
                recording.uploadProgressText = nil
            }
        }
    }

    @MainActor
    private static func refreshMissingDurations(context: ModelContext) {
        let descriptor = FetchDescriptor<Recording>(
            predicate: #Predicate { $0.duration <= 0 }
        )
        guard let recordings = try? context.fetch(descriptor) else { return }

        for recording in recordings where recording.audioFileExists {
            updateDuration(for: recording, fileURL: recording.audioFileURL, context: context)
        }
    }

    @MainActor
    private static func recoverOrphanAudioFiles(context: ModelContext) {
        let directory = Recording.recordingsDirectory
        let fm = FileManager.default

        guard fm.fileExists(atPath: directory.path),
              let files = try? fm.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.fileSizeKey, .creationDateKey],
                options: [.skipsHiddenFiles]
              )
        else { return }

        let knownNames: Set<String>
        if let all = try? context.fetch(FetchDescriptor<Recording>()) {
            knownNames = Set(all.map(\.audioFileName))
        } else {
            knownNames = []
        }

        let audioExtensions: Set<String> = ["m4a", "wav", "mp3", "caf", "aac", "flac"]

        for fileURL in files {
            let name = fileURL.lastPathComponent
            guard audioExtensions.contains(fileURL.pathExtension.lowercased()) else { continue }
            guard !RecordingSegmentNaming.isPartFileName(name) else { continue }
            Recording.protectFile(at: fileURL)
            guard !knownNames.contains(name) else { continue }

            let size = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size > 1024 else { continue }

            let creationDate = (try? fileURL.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date()
            let timestamp = creationDate.formatted(date: .numeric, time: .shortened)
            let title = String(
                localized: "Återställd \(timestamp)",
                comment: "Titel på inspelning som återfunnits vid start. Platshållaren är datum och tid."
            )

            let recording = Recording(
                title: title,
                audioFileName: name,
                duration: 0,
                isImported: false
            )
            recording.dateCreated = creationDate
            context.insert(recording)
            updateDuration(for: recording, fileURL: fileURL, context: context)
        }
    }

    @MainActor
    private static func recoverSegmentSessions(context: ModelContext) async {
        let directory = Recording.recordingsDirectory
        let fm = FileManager.default
        guard fm.fileExists(atPath: directory.path),
              let files = try? fm.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.fileSizeKey, .creationDateKey],
                options: [.skipsHiddenFiles]
              )
        else { return }

        let playableParts = files.filter { url in
            guard RecordingSegmentNaming.isPartFileName(url.lastPathComponent) else { return false }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return size > 1024
        }
        let grouped = RecordingSegmentNaming.groupPartFiles(playableParts)

        for (baseName, parts) in grouped {
            guard !parts.isEmpty else { continue }
            let finalURL = RecordingSegmentNaming.finalURL(directory: directory, baseName: baseName)

            // Leftover parts next to a complete final file mean the app was
            // killed while deleting them after a successful merge. Merging
            // again would overwrite the full recording with only the parts
            // that survived, so just finish the cleanup instead.
            let finalSize = (try? finalURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if finalSize > 1024 {
                deleteSessionPartFiles(forFinalFileName: finalURL.lastPathComponent)
                continue
            }

            guard await mergeParts(parts, into: finalURL) else { continue }

            let finalName = finalURL.lastPathComponent
            let existing = (try? context.fetch(FetchDescriptor<Recording>()))?
                .first(where: { $0.audioFileName == finalName })

            if let existing {
                if existing.duration <= 0 {
                    updateDuration(for: existing, fileURL: finalURL, context: context)
                }
            } else {
                let creationDate = (try? parts.first?.resourceValues(forKeys: [.creationDateKey]).creationDate)
                    ?? Date()
                let timestamp = creationDate.formatted(date: .numeric, time: .shortened)
                let title = String(
                    localized: "Återställd \(timestamp)",
                    comment: "Titel på inspelning som återfunnits vid start. Platshållaren är datum och tid."
                )
                let recording = Recording(
                    title: title,
                    audioFileName: finalName,
                    duration: 0,
                    isImported: false
                )
                recording.dateCreated = creationDate
                context.insert(recording)
                updateDuration(for: recording, fileURL: finalURL, context: context)
            }
        }
    }

    /// Autosaved segment files that belong to this recording but have not yet
    /// been merged into its final audio file.
    static func pendingPartFiles(for recording: Recording) -> [URL] {
        let base = RecordingSegmentNaming.baseName(fromFinalFileName: recording.audioFileName)
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: Recording.recordingsDirectory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return [] }
        let parts = files.filter { url in
            guard RecordingSegmentNaming.parsePart(url.lastPathComponent)?.base == base else { return false }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return size > 1024
        }
        return RecordingSegmentNaming.groupPartFiles(parts)[base] ?? []
    }

    /// Retries merging leftover segments when a recording's final file is
    /// missing, e.g. because the merge after stopping was interrupted.
    static func recoverAudioFile(for recording: Recording, context: ModelContext) async -> Bool {
        guard !recording.audioFileExists else { return true }
        let parts = pendingPartFiles(for: recording)
        guard !parts.isEmpty else { return false }

        guard await mergeParts(parts, into: recording.audioFileURL) else { return false }

        updateDuration(for: recording, fileURL: recording.audioFileURL, context: context)
        return true
    }

    private static func mergeParts(_ parts: [URL], into finalURL: URL) async -> Bool {
        guard await M4aSegmentConcatenator.concatenate(parts: parts, output: finalURL) else { return false }
        deleteSessionPartFiles(forFinalFileName: finalURL.lastPathComponent)
        return true
    }

    static func deleteSessionPartFiles(forFinalFileName fileName: String) {
        let directory = Recording.recordingsDirectory
        let base = RecordingSegmentNaming.baseName(fromFinalFileName: fileName)
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else { return }
        for file in files {
            if RecordingSegmentNaming.parsePart(file.lastPathComponent)?.base == base {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    @MainActor
    private static func updateDuration(for recording: Recording, fileURL: URL, context: ModelContext) {
        Task { @MainActor in
            let asset = AVURLAsset(url: fileURL)
            if let duration = try? await asset.load(.duration) {
                let seconds = duration.seconds
                if seconds.isFinite, seconds > 0 {
                    recording.duration = seconds
                    try? context.save()
                }
            }
        }
    }
}
