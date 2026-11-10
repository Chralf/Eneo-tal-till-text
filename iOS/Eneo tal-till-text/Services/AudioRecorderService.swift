import AVFoundation
import Observation
#if os(iOS)
import UIKit
#endif

@Observable
@MainActor
final class AudioRecorderService {
    enum RecordingState {
        case idle
        case recording
        case paused
    }

    private static let autosaveIntervalSeconds: TimeInterval = 30

    var state: RecordingState = .idle
    var recordingTime: TimeInterval = 0
    var audioLevels: [Float] = []
    var wasInterrupted = false
    var reachedMaxDuration = false
    /// True from stop until the final audio file has been written. The UI
    /// must not start a new session or dismiss while this is set.
    var isFinalizing = false

    /// Called after each finalized segment with total session duration so far.
    var onSegmentFinalized: ((TimeInterval) -> Void)?

    private var audioRecorder: AVAudioRecorder?
    private var meterTimer: Timer?
    private var autosaveTimer: Timer?
    private var sessionDirectory: URL?
    private var sessionBaseName: String?
    private var sessionFinalURL: URL?
    private var nextPartIndex = 1
    private var completedParts: [URL] = []
    private var sessionStartedAt: Date?
    private var pausedAccumulated: TimeInterval = 0
    private var pauseStartedAt: Date?
    private var previousIdleTimerDisabled: Bool?
    @ObservationIgnored private var interruptionObservers: [NSObjectProtocol] = []
    private var shouldResumeAfterInterruption = false

    private let recorderSettings: [String: Any] = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 48_000.0,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 128_000,
        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
    ]

    var currentRecordingURL: URL? { sessionFinalURL }

    var formattedTime: String {
        DurationFormatting.elapsed(recordingTime)
    }

    var isNearMaxDuration: Bool {
        recordingTime >= Recording.maxRecordingDuration - 60
    }

    init() {
        setupInterruptionObservers()
    }

    deinit {
        for observer in interruptionObservers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// Current permission without prompting, for re-checks after the user
    /// returns from the Settings app.
    var hasRecordPermission: Bool {
        #if os(iOS)
        return AVAudioApplication.shared.recordPermission == .granted
        #else
        return true
        #endif
    }

    func requestPermission() async -> Bool {
        #if os(iOS)
        return await AVAudioApplication.requestRecordPermission()
        #else
        return true
        #endif
    }

    /// Starts a session. Returns the eventual final file URL (`recording_<epoch>.m4a`).
    func startRecording() throws -> URL {
        guard !isFinalizing else {
            throw AudioRecorderError.failedToStart
        }
        let recordingsDir = try Recording.ensureRecordingsDirectory()
        let baseName = "recording_\(Int(Date().timeIntervalSince1970))"
        let finalURL = RecordingSegmentNaming.finalURL(directory: recordingsDir, baseName: baseName)

        resetSessionState()
        sessionDirectory = recordingsDir
        sessionBaseName = baseName
        sessionFinalURL = finalURL
        nextPartIndex = 1
        sessionStartedAt = Date()

        try activateSession()
        try startNextSegment()
        preventDeviceSleep()
        state = .recording
        recordingTime = 0
        audioLevels = []
        wasInterrupted = false
        reachedMaxDuration = false
        shouldResumeAfterInterruption = false

        startMetering()
        startAutosaveTimer()
        return finalURL
    }

    func pauseRecording() {
        guard state == .recording else { return }
        stopAutosaveTimer()
        stopMetering()
        finalizeCurrentSegment(addToCompleted: true)
        pauseStartedAt = Date()
        recordingTime = totalElapsedSeconds()
        state = .paused
        onSegmentFinalized?(recordingTime)
    }

    func resumeRecording() {
        guard state == .paused else { return }
        if let pauseStartedAt {
            pausedAccumulated += Date().timeIntervalSince(pauseStartedAt)
        }
        pauseStartedAt = nil
        #if os(iOS)
        try? activateSession()
        #endif
        do {
            try startNextSegment()
        } catch {
            // Still paused: keep counting paused time so the clock stays right.
            pauseStartedAt = Date()
            return
        }
        preventDeviceSleep()
        state = .recording
        wasInterrupted = false
        startMetering()
        startAutosaveTimer()
    }

    func stopRecording() async -> (url: URL, duration: TimeInterval)? {
        // A second stop while the first merge is still running must not
        // re-run the merge or clobber the session that is being finalized.
        guard !isFinalizing else { return nil }

        stopAutosaveTimer()
        stopMetering()

        if state == .recording || audioRecorder != nil {
            finalizeCurrentSegment(addToCompleted: true)
        }

        let duration = min(totalElapsedSeconds(), Recording.maxRecordingDuration)
        recordingTime = duration

        let finalURL = sessionFinalURL
        let parts = completedParts
        state = .idle
        audioRecorder = nil
        audioLevels = []
        shouldResumeAfterInterruption = false
        wasInterrupted = false
        sessionFinalURL = nil

        allowDeviceSleep()
        deactivateSession()

        guard let finalURL else {
            clearSessionLists()
            recordingTime = 0
            onSegmentFinalized = nil
            return nil
        }

        isFinalizing = true
        defer { isFinalizing = false }

        let concatOK = await M4aSegmentConcatenator.concatenate(parts: parts, output: finalURL)
        if concatOK {
            for part in parts where part.standardizedFileURL != finalURL.standardizedFileURL {
                try? FileManager.default.removeItem(at: part)
            }
        } else if parts.count == 1, FileManager.default.fileExists(atPath: parts[0].path) {
            try? FileManager.default.removeItem(at: finalURL)
            do {
                try FileManager.default.copyItem(at: parts[0], to: finalURL)
                if parts[0].standardizedFileURL != finalURL.standardizedFileURL {
                    try? FileManager.default.removeItem(at: parts[0])
                }
            } catch {
                // Keep the autosaved part so launch recovery can retry.
            }
        }

        clearSessionLists()
        let finalDuration = duration
        recordingTime = 0
        onSegmentFinalized = nil
        return (finalURL, finalDuration)
    }

    func cancelRecording(deleteFile: Bool = true) {
        stopAutosaveTimer()
        stopMetering()

        if let recorder = audioRecorder {
            recorder.stop()
        }

        if deleteFile {
            for part in completedParts {
                try? FileManager.default.removeItem(at: part)
            }
            if let open = openPartURL() {
                try? FileManager.default.removeItem(at: open)
            }
            if let finalURL = sessionFinalURL {
                try? FileManager.default.removeItem(at: finalURL)
            }
        }

        state = .idle
        audioRecorder = nil
        recordingTime = 0
        audioLevels = []
        shouldResumeAfterInterruption = false
        wasInterrupted = false
        reachedMaxDuration = false
        onSegmentFinalized = nil

        clearSessionLists()
        sessionFinalURL = nil
        sessionDirectory = nil
        sessionBaseName = nil

        allowDeviceSleep()
        deactivateSession()
    }

    private func startNextSegment() throws {
        guard let directory = sessionDirectory, let baseName = sessionBaseName else {
            throw AudioRecorderError.failedToStart
        }
        let partURL = RecordingSegmentNaming.partURL(directory: directory, baseName: baseName, index: nextPartIndex)
        nextPartIndex += 1
        try? FileManager.default.removeItem(at: partURL)

        let recorder = try AVAudioRecorder(url: partURL, settings: recorderSettings)
        recorder.isMeteringEnabled = true
        guard recorder.record() else {
            throw AudioRecorderError.failedToStart
        }
        audioRecorder = recorder
        Recording.protectFile(at: partURL)
    }

    private func finalizeCurrentSegment(addToCompleted: Bool) {
        let partURL = openPartURL()
        audioRecorder?.stop()
        if let partURL {
            Recording.protectFile(at: partURL)
            let size = (try? partURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if addToCompleted, size > 1024 {
                completedParts.append(partURL)
            } else {
                try? FileManager.default.removeItem(at: partURL)
            }
        }
        audioRecorder = nil
    }

    private func rotateSegment() {
        guard state == .recording else { return }
        finalizeCurrentSegment(addToCompleted: true)
        recordingTime = totalElapsedSeconds()
        onSegmentFinalized?(recordingTime)

        if recordingTime >= Recording.maxRecordingDuration {
            stopRecordingKeepingTime()
            return
        }

        do {
            try startNextSegment()
        } catch {
            // Leave session paused-like if we cannot open the next part.
            // Mark when the pause began so the clock stops with it.
            state = .paused
            pauseStartedAt = Date()
            wasInterrupted = true
            stopAutosaveTimer()
            stopMetering()
        }
    }

    private func openPartURL() -> URL? {
        guard let directory = sessionDirectory, let baseName = sessionBaseName else { return nil }
        let index = nextPartIndex - 1
        guard index >= 1 else { return nil }
        return RecordingSegmentNaming.partURL(directory: directory, baseName: baseName, index: index)
    }

    private func totalElapsedSeconds() -> TimeInterval {
        guard let sessionStartedAt else { return 0 }
        let now = Date()
        var paused = pausedAccumulated
        if state == .paused, let pauseStartedAt {
            paused += now.timeIntervalSince(pauseStartedAt)
        }
        return max(0, now.timeIntervalSince(sessionStartedAt) - paused)
    }

    private func preventDeviceSleep() {
        #if os(iOS)
        if previousIdleTimerDisabled == nil {
            previousIdleTimerDisabled = UIApplication.shared.isIdleTimerDisabled
        }
        UIApplication.shared.isIdleTimerDisabled = true
        #endif
    }

    private func allowDeviceSleep() {
        #if os(iOS)
        if let previousIdleTimerDisabled {
            UIApplication.shared.isIdleTimerDisabled = previousIdleTimerDisabled
            self.previousIdleTimerDisabled = nil
        }
        #endif
    }

    private func activateSession() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .default,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setActive(true, options: [])
        #endif
    }

    private func deactivateSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    private func startMetering() {
        stopMetering()
        let t = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.updateMeters()
            }
        }
        RunLoop.main.add(t, forMode: .common)
        meterTimer = t
    }

    private func stopMetering() {
        meterTimer?.invalidate()
        meterTimer = nil
    }

    private func startAutosaveTimer() {
        stopAutosaveTimer()
        let t = Timer(timeInterval: Self.autosaveIntervalSeconds, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.rotateSegment()
            }
        }
        RunLoop.main.add(t, forMode: .common)
        autosaveTimer = t
    }

    private func stopAutosaveTimer() {
        autosaveTimer?.invalidate()
        autosaveTimer = nil
    }

    private func updateMeters() {
        guard let recorder = audioRecorder else { return }

        if state == .recording {
            recordingTime = totalElapsedSeconds()

            if recordingTime >= Recording.maxRecordingDuration {
                stopRecordingKeepingTime()
                return
            }

            guard recorder.isRecording else { return }
            recorder.updateMeters()
            let power = recorder.averagePower(forChannel: 0)
            let normalized = max(0, min(1, (power + 50) / 50))
            audioLevels.append(normalized)
            if audioLevels.count > 150 {
                audioLevels.removeFirst()
            }
        }
    }

    /// Stops at max duration: finalizes segments and concatenates asynchronously.
    private func stopRecordingKeepingTime() {
        stopAutosaveTimer()
        stopMetering()
        if state == .recording || audioRecorder != nil {
            finalizeCurrentSegment(addToCompleted: true)
        }
        let duration = min(totalElapsedSeconds(), Recording.maxRecordingDuration)
        recordingTime = duration

        let finalURL = sessionFinalURL
        let parts = completedParts
        state = .idle
        audioRecorder = nil
        audioLevels = []
        shouldResumeAfterInterruption = false
        sessionFinalURL = nil
        allowDeviceSleep()
        deactivateSession()

        guard let finalURL else {
            clearSessionLists()
            reachedMaxDuration = true
            return
        }

        isFinalizing = true
        Task { @MainActor in
            defer { isFinalizing = false }
            let concatOK = await M4aSegmentConcatenator.concatenate(parts: parts, output: finalURL)
            if concatOK {
                for part in parts where part.standardizedFileURL != finalURL.standardizedFileURL {
                    try? FileManager.default.removeItem(at: part)
                }
            }
            self.clearSessionLists()
            self.onSegmentFinalized = nil
            // Keep recordingTime until RecordView reads it for max-duration finalize.
            self.recordingTime = duration
            // Only now is the merged file on disk (or definitively not coming),
            // so only now is it safe for RecordView to finalize the recording.
            self.reachedMaxDuration = true
        }
    }

    private func resetSessionState() {
        completedParts = []
        pausedAccumulated = 0
        pauseStartedAt = nil
        sessionStartedAt = nil
    }

    private func clearSessionLists() {
        completedParts = []
        nextPartIndex = 1
        sessionDirectory = nil
        sessionBaseName = nil
        pausedAccumulated = 0
        pauseStartedAt = nil
        sessionStartedAt = nil
    }

    private func setupInterruptionObservers() {
        #if os(iOS)
        let center = NotificationCenter.default

        let interruption = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            Task { @MainActor [weak self] in
                self?.handleInterruption(typeValue: typeValue, optionsValue: optionsValue)
            }
        }

        let routeChange = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            Task { @MainActor [weak self] in
                self?.handleRouteChange(reasonValue: reasonValue)
            }
        }

        interruptionObservers = [interruption, routeChange]
        #endif
    }

    #if os(iOS)
    private func handleInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard state == .recording || state == .paused else { return }
        guard let typeValue,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        else { return }

        switch type {
        case .began:
            if state == .recording {
                shouldResumeAfterInterruption = true
                pauseRecording()
                wasInterrupted = true
            }
        case .ended:
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue ?? 0)
            if options.contains(.shouldResume), shouldResumeAfterInterruption {
                resumeRecording()
            }
            shouldResumeAfterInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChange(reasonValue: UInt?) {
        guard state == .recording || state == .paused else { return }
        guard let reasonValue,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        else { return }

        if reason == .oldDeviceUnavailable {
            if state == .recording, audioRecorder?.isRecording == false {
                try? activateSession()
                audioRecorder?.record()
            }
        }
    }
    #endif
}

enum AudioRecorderError: PresentableError {
    case failedToStart

    var errorDescription: String? {
        switch self {
        case .failedToStart:
            return String(localized: "Kunde inte starta inspelningen", comment: "Rubrik när mikrofoninspelning inte startar")
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .failedToStart:
            return String(localized: "Kontrollera att ingen annan app använder mikrofonen och försök igen.", comment: "Åtgärd när inspelningen inte startar")
        }
    }

    var systemImage: String { "mic.slash.fill" }
}
