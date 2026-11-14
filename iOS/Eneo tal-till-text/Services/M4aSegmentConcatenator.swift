import Foundation
import AVFoundation
#if os(iOS)
import UIKit
#endif

enum RecordingSegmentNaming {
    private static let partPattern = try! NSRegularExpression(pattern: #"^(.+)_p(\d{3})\.m4a$"#, options: [.caseInsensitive])

    static func baseName(fromFinalFileName fileName: String) -> String {
        if fileName.lowercased().hasSuffix(".m4a") {
            return String(fileName.dropLast(4))
        }
        return fileName
    }

    static func finalFileName(baseName: String) -> String { "\(baseName).m4a" }

    static func partFileName(baseName: String, index: Int) -> String {
        String(format: "%@_p%03d.m4a", baseName, index)
    }

    static func partURL(directory: URL, baseName: String, index: Int) -> URL {
        directory.appendingPathComponent(partFileName(baseName: baseName, index: index))
    }

    static func finalURL(directory: URL, baseName: String) -> URL {
        directory.appendingPathComponent(finalFileName(baseName: baseName))
    }

    /// Scratch file an interrupted merge can leave behind.
    static func isMergeTempFileName(_ fileName: String) -> Bool {
        fileName.lowercased().hasSuffix(mergeTempSuffix)
    }

    static let mergeTempSuffix = "_concat_tmp.m4a"

    static func isPartFileName(_ fileName: String) -> Bool {
        parsePart(fileName) != nil
    }

    static func parsePart(_ fileName: String) -> (base: String, index: Int)? {
        let range = NSRange(fileName.startIndex..<fileName.endIndex, in: fileName)
        guard let match = partPattern.firstMatch(in: fileName, options: [], range: range),
              match.numberOfRanges == 3,
              let baseRange = Range(match.range(at: 1), in: fileName),
              let indexRange = Range(match.range(at: 2), in: fileName),
              let index = Int(fileName[indexRange])
        else { return nil }
        return (String(fileName[baseRange]), index)
    }

    static func groupPartFiles(_ files: [URL]) -> [String: [URL]] {
        var grouped: [String: [(Int, URL)]] = [:]
        for file in files {
            guard let parsed = parsePart(file.lastPathComponent) else { continue }
            grouped[parsed.base, default: []].append((parsed.index, file))
        }
        return grouped.mapValues { parts in
            parts.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }
}

enum M4aSegmentConcatenator {
    /// Output paths currently being written, so launch recovery and the detail
    /// view never merge the same session concurrently.
    private static var inFlightOutputs: Set<String> = []

    static func concatenate(parts: [URL], output: URL) async -> Bool {
        let key = output.standardizedFileURL.path
        guard !inFlightOutputs.contains(key) else { return false }
        inFlightOutputs.insert(key)
        defer { inFlightOutputs.remove(key) }

        // The audio session is deactivated before merging, so without extra
        // background time the export is suspended if the user locks the phone
        // or leaves the app right after tapping stop.
        #if os(iOS)
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "MergeRecordingSegments")
        defer {
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
            }
        }
        #endif

        return await merge(parts: parts, output: output)
    }

    private static func merge(parts: [URL], output: URL) async -> Bool {
        let playable = parts.filter { url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return size > 1024
        }
        guard !playable.isEmpty else { return false }

        if playable.count == 1 {
            let only = playable[0]
            if only.standardizedFileURL == output.standardizedFileURL { return true }
            try? FileManager.default.removeItem(at: output)
            do {
                try FileManager.default.copyItem(at: only, to: output)
                Recording.protectFile(at: output)
                return true
            } catch {
                return false
            }
        }

        let composition = AVMutableComposition()
        guard let track = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else { return false }

        var insertAt = CMTime.zero
        var skippedPart = false
        for part in playable {
            let asset = AVURLAsset(url: part)
            guard let assetTrack = try? await asset.loadTracks(withMediaType: .audio).first else {
                skippedPart = true
                continue
            }
            let duration = (try? await asset.load(.duration)) ?? .zero
            guard duration.isNumeric, duration.seconds > 0 else {
                skippedPart = true
                continue
            }
            do {
                try track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: assetTrack, at: insertAt)
                insertAt = CMTimeAdd(insertAt, duration)
            } catch {
                skippedPart = true
                continue
            }
        }

        guard insertAt.seconds > 0 else { return false }

        // A skipped part is either corrupt (e.g. cut off by a phone call) or
        // temporarily unreadable because the device is locked. Only in the
        // first case is it right to publish what we have; otherwise one bad
        // part would block the whole recording forever. When locked, fail so
        // the caller keeps every part for a later retry.
        if skippedPart {
            #if os(iOS)
            guard UIApplication.shared.isProtectedDataAvailable else { return false }
            #endif
        }

        let temp = output.deletingLastPathComponent()
            .appendingPathComponent("\(output.deletingPathExtension().lastPathComponent)\(RecordingSegmentNaming.mergeTempSuffix)")
        try? FileManager.default.removeItem(at: temp)

        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            return false
        }

        do {
            try await export.export(to: temp, as: .m4a)
        } catch {
            try? FileManager.default.removeItem(at: temp)
            return false
        }

        try? FileManager.default.removeItem(at: output)
        do {
            try FileManager.default.moveItem(at: temp, to: output)
            Recording.protectFile(at: output)
            return true
        } catch {
            try? FileManager.default.removeItem(at: temp)
            return false
        }
    }
}
