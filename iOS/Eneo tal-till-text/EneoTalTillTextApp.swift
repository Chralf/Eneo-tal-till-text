import SwiftUI
import SwiftData
import AVFoundation

@main
struct EneoTalTillTextApp: App {
    @State private var audioRecorderService = AudioRecorderService()
    @State private var recordingUploadService = RecordingUploadService()
    @State private var eneoAuthService = EneoAuthService()

    @State private var importResult: ImportResult?
    @Environment(\.scenePhase) private var scenePhase

    let modelContainer: ModelContainer?
    let startupError: String?

    init() {
        TranscriptionNotificationPresenter.shared.install()
        do {
            modelContainer = try ModelContainer(for: Recording.self)
            startupError = nil
        } catch {
            do {
                let fallbackConfiguration = ModelConfiguration(isStoredInMemoryOnly: true)
                modelContainer = try ModelContainer(for: Recording.self, configurations: fallbackConfiguration)
                startupError = String(localized: "Appens lokala databas kunde inte öppnas. Tillfällig lagring används tills appen startas om.", comment: "Startfel med reservlagring")
            } catch {
                modelContainer = nil
                startupError = String(localized: "Appens lokala databas kunde inte öppnas.", comment: "Startfel utan reservlagring")
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            if let modelContainer {
                ContentView()
                    .environment(audioRecorderService)
                    .environment(recordingUploadService)
                    .environment(eneoAuthService)
                    .modelContainer(modelContainer)
                    .onOpenURL { url in
                        handleIncomingURL(url)
                    }
                    .task {
                        RecordingPersistence.recoverOnLaunch(context: modelContainer.mainContext)
                    }
                    .onChange(of: scenePhase) { _, phase in
                        // Retention must also apply when the app is merely
                        // brought back from the background, not only at launch.
                        if phase == .active {
                            RecordingPersistence.purgeExpiredRecordings(context: modelContainer.mainContext)
                        }
                    }
                    .alert(item: $importResult) { result in
                        Alert(
                            title: Text(result.title),
                            message: Text(result.message),
                            dismissButton: .default(Text("OK"))
                        )
                    }
            } else {
                StartupErrorView(message: startupError ?? String(localized: "Appen kunde inte starta.", comment: "Allmänt startfel"))
            }
        }
    }

    struct ImportResult: Identifiable {
        let id = UUID()
        let title: String
        let message: String
    }

    private struct StartupErrorView: View {
        let message: String

        var body: some View {
            ContentUnavailableView {
                Label("Kunde inte starta", systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Text("Starta om appen. Om felet kvarstår kan appens lokala data behöva rensas.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
        }
    }

    @MainActor
    private func handleIncomingURL(_ url: URL) {
        guard let modelContainer else { return }
        // Files handed over via "Copy to" land in the app's own Documents/Inbox
        // and are not security scoped, so a false return here is normal. Only
        // the copy below can tell whether the file is actually readable.
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let supportedExtensions = ["m4a", "wav", "mp3", "caf", "aac", "flac"]
        guard supportedExtensions.contains(url.pathExtension.lowercased()) else {
            importResult = ImportResult(
                title: String(localized: "Filformatet stöds inte", comment: "Rubrik vid okänt filformat"),
                message: String(localized: "Du kan importera ljudfiler av typen m4a, wav, mp3, caf, aac och flac.", comment: "Lista över filformat som stöds")
            )
            return
        }

        do {
            let recordingsDir = try Recording.ensureRecordingsDirectory()

            let fileName = Self.importFileName(for: url.lastPathComponent)
            let destination = recordingsDir.appendingPathComponent(fileName)
            try FileManager.default.copyItem(at: url, to: destination)
            guard Self.looksLikeSupportedAudio(at: destination) else {
                try? FileManager.default.removeItem(at: destination)
                importResult = ImportResult(
                    title: String(localized: "Filformatet stöds inte", comment: "Rubrik vid okänt filformat"),
                    message: String(localized: "Filen ser inte ut att vara ett stött ljudformat. Du kan importera m4a, wav, mp3, caf, aac och flac.", comment: "Magic-byte-validering misslyckades")
                )
                return
            }
            Recording.protectFile(at: destination)

            let recording = Recording(
                title: url.deletingPathExtension().lastPathComponent,
                audioFileName: fileName,
                isImported: true
            )
            modelContainer.mainContext.insert(recording)
            try modelContainer.mainContext.save()
            updateDuration(for: recording, fileURL: destination)
            Self.removeInboxCopyIfNeeded(url)

            let recordingTitle = recording.title
            importResult = ImportResult(
                title: String(localized: "Ljudfilen är importerad", comment: "Rubrik när importen lyckades"),
                message: String(localized: "\(recordingTitle) finns nu i listan och kan omvandlas till text.", comment: "Text när importen lyckades. Platshållaren är filens namn.")
            )
        } catch {
            importResult = ImportResult(
                title: String(localized: "Filen kunde inte importeras", comment: "Rubrik när importen misslyckades"),
                message: String(localized: "Kontrollera att det finns ledigt utrymme på telefonen och försök igen.", comment: "Åtgärd när importen misslyckades")
            )
        }
    }

    /// iOS leaves the handed-over copy in Documents/Inbox; it would otherwise
    /// sit there unprotected and unpurged for as long as the app is installed.
    private static func removeInboxCopyIfNeeded(_ url: URL) {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let inbox = documents.appendingPathComponent("Inbox", isDirectory: true).standardizedFileURL.path
        guard url.standardizedFileURL.path.hasPrefix(inbox + "/") else { return }
        try? FileManager.default.removeItem(at: url)
    }

    @MainActor
    private func updateDuration(for recording: Recording, fileURL: URL) {
        guard let modelContainer else { return }
        Task { @MainActor in
            let asset = AVURLAsset(url: fileURL)
            if let duration = try? await asset.load(.duration) {
                let seconds = duration.seconds
                if seconds.isFinite, seconds > 0 {
                    recording.duration = seconds
                    try? modelContainer.mainContext.save()
                }
            }
        }
    }

    static func importFileName(for originalFileName: String, id: UUID = UUID()) -> String {
        "shared_\(id.uuidString)_\(safeFileName(from: originalFileName))"
    }

    static func safeFileName(from fileName: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._- "))
        let sanitized = String(fileName.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? Character(scalar) : "_"
        })
        return sanitized.isEmpty ? "imported-audio" : sanitized
    }

    /// Sniff common audio container headers after copy.
    private static func looksLikeSupportedAudio(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        let data = handle.readData(ofLength: 12)
        guard data.count >= 4 else { return false }
        let bytes = [UInt8](data)

        func ascii(_ offset: Int, _ expected: String) -> Bool {
            let expectedBytes = Array(expected.utf8)
            guard offset + expectedBytes.count <= bytes.count else { return false }
            return expectedBytes.enumerated().allSatisfy { bytes[offset + $0.offset] == $0.element }
        }

        if bytes.count >= 8 && ascii(4, "ftyp") { return true }
        if ascii(0, "RIFF") && bytes.count >= 12 && ascii(8, "WAVE") { return true }
        if ascii(0, "fLaC") { return true }
        if ascii(0, "caff") { return true }
        if ascii(0, "ID3") { return true }
        if bytes[0] == 0xFF && (bytes[1] & 0xE0) == 0xE0 { return true }
        if bytes[0] == 0xFF && (bytes[1] & 0xF6) == 0xF0 { return true }
        return false
    }
}
