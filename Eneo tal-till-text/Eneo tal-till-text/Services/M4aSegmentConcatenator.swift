import Foundation
import AVFoundation

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
    static func concatenate(parts: [URL], output: URL) async -> Bool {
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

        // Never publish a partial final file as if all autosaved segments had
        // been preserved. The caller will keep the parts for recovery.
        guard insertAt.seconds > 0, !skippedPart else { return false }

        let temp = output.deletingLastPathComponent()
            .appendingPathComponent("\(output.deletingPathExtension().lastPathComponent)_concat_tmp.m4a")
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
