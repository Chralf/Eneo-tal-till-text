import SwiftUI
import SwiftData
import AVFoundation

struct RecordingDetailView: View {
    @Bindable var recording: Recording
    /// Starts converting as soon as the view appears, for a recording that
    /// was just finished with "convert after recording" turned on.
    var autoStartUpload = false
    var onAutoStartHandled: (() -> Void)?

    @Environment(RecordingUploadService.self) private var uploadService
    @Environment(EneoAuthService.self) private var authService
    @Environment(ConversionCoordinator.self) private var coordinator
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var isPlaying = false
    @State private var audioPlayer: AVAudioPlayer?
    @State private var playbackPosition: TimeInterval = 0
    @State private var playbackDuration: TimeInterval = 0
    @State private var isScrubbing = false
    @State private var playbackTimer: Timer?
    @State private var showDeleteConfirmation = false
    @AppStorage(APISettings.appIDStorageKey) private var defaultAppID = APISettings.defaultAppID
    @AppStorage(APISettings.baseURLStorageKey) private var storedBaseURLString = APISettings.defaultBaseURLString

    @State private var selectedAppID = ""
    @State private var availableApps: [EneoApp] = []
    @State private var personalSpaceID: String?
    @State private var isLoadingApps = false
    @State private var noticeAlert: NoticeAlert?
    @State private var showConnectSheet = false
    @State private var pendingUploadAfterLogin = false
    @State private var pendingOpenAppPickerAfterLogin = false
    /// Set when Convert was tapped with no app chosen; the upload starts as
    /// soon as the picker returns a choice.
    @State private var pendingUploadAfterAppPick = false
    @State private var showAppPicker = false
    @State private var isCheckingServer = false
    @State private var isInfoExpanded = false
    @State private var isPlaybackExpanded = false
    @State private var didCopyResult = false
    @State private var isRecoveringAudio = false
    @State private var audioRecoveryFailed = false
    @State private var isDeleted = false
    /// Plain-text version of the result for sharing. Cached because deriving
    /// it parses the whole markdown document, and the body is re-evaluated
    /// several times a second during playback.
    @State private var sharePlainText = ""
    private var displayedUploadError: ErrorPresentation? {
        coordinator.lastErrors[recording.id] ?? persistedUploadError
    }

    /// Outside the organisation's network Convert queues the recording, and
    /// the button says so up front.
    private var isEneoUnreachable: Bool {
        uploadService.serverReachability == .unreachable
    }

    private var uploadButtonTitle: LocalizedStringKey {
        if isEneoUnreachable {
            return "Omvandla när Eneo nås"
        }
        return recording.hasTranscription ? "Omvandla igen" : "Omvandla till text"
    }

    private var hasServerAddress: Bool {
        APISettings.makeBaseURL(from: storedBaseURLString) != nil
    }

    /// The in-memory presentation is richer, but a failure from a previous
    /// launch survives only as persisted text.
    private var persistedUploadError: ErrorPresentation? {
        guard let stored = recording.uploadError?.trimmingCharacters(in: .whitespacesAndNewlines),
              !stored.isEmpty else {
            return nil
        }

        let lines = stored.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
        return ErrorPresentation(
            title: String(lines.first ?? ""),
            message: lines.count > 1 ? String(lines[1]) : nil
        )
    }

    var body: some View {
        List {
            titleSection
            infoSection
            missingFileSection
            playerSection
            appSelectionSection
            uploadErrorSection
            resultSection
            deleteSection
        }
        .navigationTitle(recording.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .privacySensitive()
        #endif
        .safeAreaInset(edge: .bottom) {
            uploadBottomBar
        }
        .confirmationDialog("Radera inspelning?", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
            Button("Radera", role: .destructive) {
                deleteRecording()
            }
            Button("Avbryt", role: .cancel) {}
        } message: {
            Text("Inspelningen och ljudfilen raderas permanent.")
        }
        .onAppear {
            RecordingPersistence.openRecordingIDs.insert(recording.id)
            loadPlaybackDuration()
            prepareAppSelection()
        }
        .task {
            await recoverMissingAudioIfPossible()
            if recording.transcriptionState == .queued {
                // The network may have changed while the app stayed open.
                await uploadService.refreshServerReachability()
                await coordinator.processQueue(context: modelContext)
            }
            if autoStartUpload {
                onAutoStartHandled?()
                if recording.audioFileExists, !recording.hasTranscription, !uploadService.isUploading {
                    uploadRecording()
                }
            }
        }
        .task(id: recording.transcriptionText) {
            let text = recording.transcriptionText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            sharePlainText = text.isEmpty ? "" : MarkdownExport.from(markdown: text).plain
        }
        .onChange(of: authService.isAuthenticated) { _, isAuthenticated in
            if !isAuthenticated {
                availableApps = []
                personalSpaceID = nil
                isLoadingApps = false
            }
        }
        .onDisappear {
            // Pushing the app picker also fires onDisappear, but the
            // recording is still on screen underneath it.
            if !showAppPicker {
                RecordingPersistence.openRecordingIDs.remove(recording.id)
            }
            if !isDeleted {
                saveCurrentTitle()
            }
            stopPlayback()
        }
        .alert(item: $noticeAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: alert.message.map(Text.init),
                dismissButton: .default(Text("OK")) {
                    clearNotice()
                }
            )
        }
        .sheet(isPresented: $showConnectSheet, onDismiss: {
            // Swiping the sheet away is also a way of declining to sign in.
            if authService.isAuthenticated {
                if pendingUploadAfterLogin {
                    pendingUploadAfterLogin = false
                    pendingOpenAppPickerAfterLogin = false
                    uploadRecording()
                } else if pendingOpenAppPickerAfterLogin {
                    pendingOpenAppPickerAfterLogin = false
                    openAppPickerAfterAuth()
                }
            } else {
                pendingUploadAfterLogin = false
                pendingOpenAppPickerAfterLogin = false
            }
        }) {
            EneoConnectView(
                onFinished: { _ in
                    showConnectSheet = false
                }
            )
        }
        .navigationDestination(isPresented: $showAppPicker) {
            EneoAppPickerView(
                apps: availableApps,
                personalSpaceID: personalSpaceID,
                selectedAppID: selectedAppBinding
            )
                .task {
                    if availableApps.isEmpty {
                        loadApps()
                    }
                }
        }
    }

    @ViewBuilder
    private var titleSection: some View {
        Section {
            TextField("Titel", text: $recording.title)
                .submitLabel(.done)
                .onSubmit {
                    saveCurrentTitle()
                }
        }
    }

    @ViewBuilder
    private var missingFileSection: some View {
        if isRecoveringAudio {
            Section {
                Label {
                    Text(String(localized: "Återställer ljudfilen…", comment: "Visas medan sparade ljuddelar slås ihop till en fil"))
                } icon: {
                    ProgressView()
                }
            }
        } else if !recording.audioFileExists {
            Section {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(localized: "Ljudfilen saknas", comment: "Rubrik när ljudfilen inte finns på enheten"))
                            .font(.headline)
                        Text(missingFileMessage)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } icon: {
                    Image(systemName: "waveform.slash")
                        .foregroundStyle(.secondary)
                }

                if audioRecoveryFailed {
                    Button(String(localized: "Försök igen", comment: "Knapp för att försöka återställa ljudfilen igen")) {
                        Task { await recoverMissingAudioIfPossible() }
                    }
                }
            }
        }
    }

    private var missingFileMessage: String {
        if audioRecoveryFailed {
            return String(localized: "Ljudet finns sparat i delar på telefonen men kunde inte slås ihop. Lås upp telefonen, håll appen öppen och försök igen.", comment: "Förklaring när sparade ljuddelar inte kunde slås ihop")
        }
        return String(localized: "Filen kan ha raderats utanför appen. Du kan inte spela upp eller omvandla den här inspelningen.", comment: "Förklaring när ljudfilen saknas")
    }

    /// Merges leftover autosaved segments when the final file was never written,
    /// e.g. because the app was suspended while merging after stop.
    private func recoverMissingAudioIfPossible() async {
        guard !isRecoveringAudio,
              !recording.audioFileExists,
              !RecordingPersistence.pendingPartFiles(for: recording).isEmpty
        else { return }

        isRecoveringAudio = true
        let recovered = await RecordingPersistence.recoverAudioFile(for: recording, context: modelContext)
        isRecoveringAudio = false
        audioRecoveryFailed = !recovered
        if recovered {
            loadPlaybackDuration()
        }
    }

    @ViewBuilder
    private var infoSection: some View {
        Section {
            DisclosureGroup(isExpanded: $isInfoExpanded) {
                LabeledContent("Datum", value: recording.formattedDate)
                LabeledContent("Längd", value: recording.formattedDuration)
                if recording.isImported {
                    LabeledContent("Källa", value: "Importerad")
                }
                LabeledContent("Fil", value: recording.audioFileName)
                if let uploadedAt = recording.uploadedAt {
                    LabeledContent("Uppladdad", value: uploadedAt.formatted(date: .abbreviated, time: .shortened))
                }
            } label: {
                Text("Information")
            }
        }
    }

    @ViewBuilder
    private var playerSection: some View {
        Section {
            DisclosureGroup(isExpanded: $isPlaybackExpanded) {
                Button {
                    togglePlayback()
                } label: {
                    Label(
                        isPlaying ? "Pausa" : "Spela upp",
                        systemImage: isPlaying ? "pause.fill" : "play.fill"
                    )
                }
                .disabled(!recording.audioFileExists)

                VStack(spacing: 8) {
                    Slider(
                        value: $playbackPosition,
                        in: 0...max(playbackDuration, 1),
                        onEditingChanged: { editing in
                            isScrubbing = editing
                            if !editing {
                                seekPlayback(to: playbackPosition)
                            }
                        }
                    )
                    .disabled(!recording.audioFileExists)

                    HStack {
                        Text(formatPlaybackTime(playbackPosition))
                        Spacer()
                        Text(formatPlaybackTime(playbackDuration))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            } label: {
                Text("Uppspelning")
            }
        }
    }

    /// Progress and the primary action share one location so status is always
    /// found in the same place.
    private var uploadBottomBar: some View {
        VStack(spacing: 0) {
            Divider()

            VStack(spacing: 12) {
                if uploadService.isUploading(recording) {
                    HStack(spacing: 12) {
                        ProgressView()

                        Text(uploadService.statusText ?? recording.uploadProgressText ?? String(localized: "Arbetar...", comment: "Reservstatus under uppladdning"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        Button("Avbryt") {
                            cancelUpload()
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.vertical, 8)
                } else if recording.transcriptionState == .queued {
                    HStack(spacing: 12) {
                        Image(systemName: "clock")
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)

                        Text("Väntar på Eneo")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        Button("Avbryt") {
                            coordinator.cancelWaiting(recording, context: modelContext)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityLabel("Sluta vänta på omvandling")
                    }
                    .padding(.vertical, 8)
                } else {
                    if !hasServerAddress {
                        Text("Lägg till serveradress under Inställningar.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }

                    Button {
                        uploadRecording()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: isEneoUnreachable ? "clock" : "arrow.up.circle.fill")
                            Text(uploadButtonTitle)
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!recording.audioFileExists || uploadService.isUploading)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 8)
        }
        .background(.bar)
    }

    private var hasSelectedApp: Bool {
        !normalizedSelectedAppID().isEmpty
    }

    @ViewBuilder
    private var appSelectionSection: some View {
        Section {
            Button {
                beginSelectApp()
            } label: {
                HStack(alignment: .center, spacing: 12) {
                    Text(selectedAppDisplayName)
                        .foregroundStyle(hasSelectedApp ? AnyShapeStyle(.primary) : AnyShapeStyle(.tint))
                        .multilineTextAlignment(.leading)
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    if isCheckingServer || isLoadingApps {
                        ProgressView()
                            .accessibilityLabel("Kontrollerar anslutning")
                    } else {
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            .accessibilityHint("Välj Eneo-app. Kräver nätverk och inloggning.")
        } footer: {
            if !hasSelectedApp {
                Text("Välj vilken Eneo-app som ska omvandla inspelningen. Du kan också välja en förvald app under Inställningar.")
            }
        }
    }

    private var selectedAppDisplayName: String {
        if !hasSelectedApp {
            return String(localized: "Välj Eneo-app", comment: "Visas i appraden när ingen Eneo-app är vald")
        }
        if let app = availableApps.first(where: { $0.id == selectedAppID }) {
            return app.displayName
        }
        if let name = recording.uploadAppName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            if let space = recording.uploadSpaceName?.trimmingCharacters(in: .whitespacesAndNewlines), !space.isEmpty {
                return "[\(space)] \(name)"
            }
            return name
        }
        if selectedAppID == defaultAppID, let stored = APISettings.storedDefaultAppDisplayName() {
            return stored
        }
        return String(localized: "Förvald app", comment: "Visas tills namnet på förvald Eneo-app har hämtats")
    }

    private var selectedAppBinding: Binding<String> {
        Binding(
            get: { selectedAppID },
            set: { newValue in
                selectedAppID = newValue
                saveSelectedApp()
                if pendingUploadAfterAppPick, !newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    pendingUploadAfterAppPick = false
                    uploadRecording()
                }
            }
        )
    }

    @ViewBuilder
    private var deleteSection: some View {
        Section {
            Button(role: .destructive) {
                showDeleteConfirmation = true
            } label: {
                Text("Radera")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .listRowInsets(EdgeInsets(top: 4, leading: 20, bottom: 12, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    /// A failed conversion stays visible, with its remedy, until the next
    /// attempt starts. An alert would vanish and take the explanation with it.
    @ViewBuilder
    private var uploadErrorSection: some View {
        if let error = displayedUploadError, !uploadService.isUploading(recording) {
            Section {
                ErrorNotice(
                    presentation: error,
                    actionTitle: error.requiresSignIn
                        ? LocalizedStringResource("Logga in igen", comment: "Knapp i felsektionen när inloggningen gått ut")
                        : LocalizedStringResource("Försök igen", comment: "Knapp i felsektionen för att omvandla på nytt"),
                    action: {
                        if error.requiresSignIn {
                            beginLogin(thenUpload: true, thenOpenApps: false)
                        } else {
                            uploadRecording()
                        }
                    }
                )

                if let details = error.technicalDetails, !details.isEmpty {
                    DisclosureGroup {
                        Text(details)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } label: {
                        Text("Teknisk information")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var resultSection: some View {
        if let resultText = recording.transcriptionText?.trimmingCharacters(in: .whitespacesAndNewlines), !resultText.isEmpty {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Resultat")
                            .font(.headline)
                        Spacer()
                        Button {
                            MarkdownExport.copyToPasteboard(markdown: resultText)
                            didCopyResult.toggle()
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .imageScale(.large)
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Kopiera resultat")
                        ShareLink(item: sharePlainText.isEmpty ? resultText : sharePlainText, subject: Text(recording.title)) {
                            Image(systemName: "square.and.arrow.up")
                                .imageScale(.large)
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Dela resultat")
                    }

                    MarkdownResultView(markdown: resultText)
                        .equatable()
                }
            }
            .sensoryFeedback(.success, trigger: didCopyResult)
        }
    }

    private func togglePlayback() {
        if isPlaying {
            stopPlayback()
            return
        }

        guard recording.audioFileExists else {
            presentNotice(ErrorPresentation(RecordingUploadError.audioFileMissing))
            return
        }

        do {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback)
            try session.setActive(true)
            #endif

            let player: AVAudioPlayer
            if let audioPlayer {
                player = audioPlayer
            } else {
                player = try AVAudioPlayer(contentsOf: recording.audioFileURL)
            }
            player.delegate = PlaybackDelegate.shared
            PlaybackDelegate.shared.onFinish = {
                Task { @MainActor in
                    stopPlayback(resetPosition: true)
                }
            }
            player.currentTime = min(playbackPosition, player.duration)
            audioPlayer = player
            playbackDuration = player.duration
            player.play()
            isPlaying = true
            startPlaybackTimer()
        } catch {
            stopPlayback(resetPosition: false)
            presentNotice(
                ErrorPresentation(
                    title: String(localized: "Kunde inte spela upp", comment: "Rubrik när uppspelning misslyckas"),
                    message: String(localized: "Ljudfilen går inte att öppna. Den kan vara skadad.", comment: "Förklaring när uppspelning misslyckas"),
                    systemImage: "speaker.slash.fill"
                )
            )
        }
    }

    private func loadPlaybackDuration() {
        guard recording.audioFileExists else { return }
        if playbackDuration <= 0 {
            playbackDuration = recording.duration
        }
    }

    private func startPlaybackTimer() {
        stopPlaybackTimer()
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak player = audioPlayer] _ in
            guard let player, !isScrubbing else { return }
            playbackPosition = player.currentTime
            playbackDuration = player.duration
        }
        RunLoop.main.add(timer, forMode: .common)
        playbackTimer = timer
    }

    private func stopPlaybackTimer() {
        playbackTimer?.invalidate()
        playbackTimer = nil
    }

    private func stopPlayback(resetPosition: Bool = false) {
        let wasPlaying = audioPlayer != nil
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        stopPlaybackTimer()
        if resetPosition {
            playbackPosition = 0
        }
        #if os(iOS)
        // Hand audio back so music or podcasts paused by playback can resume.
        if wasPlaying {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        #endif
    }

    private func seekPlayback(to position: TimeInterval) {
        let clampedPosition = min(max(position, 0), max(playbackDuration, 0))
        playbackPosition = clampedPosition
        audioPlayer?.currentTime = clampedPosition
    }

    private func formatPlaybackTime(_ time: TimeInterval) -> String {
        DurationFormatting.elapsed(time)
    }

    private func saveCurrentTitle() {
        let trimmedTitle = recording.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedTitle.isEmpty {
            // SwiftData autosaves the bound field, so an emptied title would
            // otherwise persist and leave a blank row in the list.
            let timestamp = recording.dateCreated.formatted(date: .numeric, time: .shortened)
            recording.title = String(
                localized: "Inspelning \(timestamp)",
                comment: "Förvald titel på en ny inspelning. Platshållaren är datum och tid."
            )
        } else {
            recording.title = trimmedTitle
        }
        try? modelContext.save()
    }

    private func prepareAppSelection() {
        selectedAppID = recording.uploadAppID?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? recording.uploadAppID ?? defaultAppID
            : defaultAppID
        // Do not contact the server here — recording management is offline-first.
    }

    private func loadApps() {
        guard !isLoadingApps else { return }
        guard authService.isAuthenticated else {
            isLoadingApps = false
            availableApps = []
            return
        }
        isLoadingApps = true

        Task {
            do {
                personalSpaceID = await uploadService.fetchPersonalSpaceId()
                let apps = try await uploadService.fetchApps()
                availableApps = apps
                if !apps.contains(where: { $0.id == selectedAppID }) {
                    // Fall back to the default app, or to the only app there
                    // is. Otherwise leave the choice to the user rather than
                    // silently picking the first one in the list.
                    selectedAppID = apps.first(where: { $0.id == defaultAppID })?.id
                        ?? (apps.count == 1 ? apps[0].id : "")
                }
                if let defaultApp = apps.first(where: { $0.id == defaultAppID }) {
                    APISettings.persistDefaultAppDisplayName(from: defaultApp)
                }
                saveSelectedApp()
                if pendingUploadAfterAppPick, hasSelectedApp {
                    // The only available app was taken automatically; no need
                    // to make the user confirm it in the picker.
                    pendingUploadAfterAppPick = false
                    showAppPicker = false
                    uploadRecording()
                }
            } catch {
                let presentation = ErrorPresentation(error)
                if presentation.requiresSignIn {
                    authService.handleExpiredSession()
                }
                presentNotice(presentation)
            }
            isLoadingApps = false
        }
    }

    private func saveSelectedApp() {
        applySelectedAppMetadata()
        try? modelContext.save()
    }

    private func applySelectedAppMetadata() {
        let appID = normalizedSelectedAppID()
        recording.uploadAppID = appID

        guard let app = availableApps.first(where: { $0.id == appID }) else { return }
        recording.uploadAppName = app.name
        recording.uploadSpaceName = app.spaceName
        APISettings.persistDefaultAppDisplayName(from: app)
    }

    private func normalizedSelectedAppID() -> String {
        let trimmed = selectedAppID.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? defaultAppID : trimmed
    }

    private func uploadRecording() {
        guard !uploadService.isUploading else { return }
        guard !isCheckingServer else { return }
        guard hasServerAddress else {
            presentNotice(ErrorPresentation(RecordingUploadError.missingServerURL))
            return
        }

        Task {
            // Outside the organisation's network the recording waits and is
            // converted the next time the app opens where Eneo answers.
            guard await isServerReachableNow() else {
                coordinator.enqueue(recording, context: modelContext)
                return
            }

            guard authService.isAuthenticated else {
                beginLogin(thenUpload: true, thenOpenApps: false)
                return
            }

            guard hasSelectedApp else {
                // Nothing to convert with yet: let the user pick an app and
                // continue the upload from there.
                pendingUploadAfterAppPick = true
                openAppPickerAfterAuth()
                return
            }

            startUploadNow()
        }
    }

    @MainActor
    private func startUploadNow() {
        clearNotice()
        guard coordinator.convert(recording, appID: normalizedSelectedAppID(), context: modelContext) != nil else { return }
        applySelectedAppMetadata()
        try? modelContext.save()
    }

    private func beginSelectApp() {
        guard !isCheckingServer else { return }
        pendingUploadAfterAppPick = false
        Task {
            guard await ensureServerReachable() else { return }
            if authService.isAuthenticated {
                openAppPickerAfterAuth()
            } else {
                beginLogin(thenUpload: false, thenOpenApps: true)
            }
        }
    }

    private func openAppPickerAfterAuth() {
        loadApps()
        showAppPicker = true
    }

    @MainActor
    private func isServerReachableNow() async -> Bool {
        isCheckingServer = true
        defer { isCheckingServer = false }
        do {
            try await uploadService.probeServerReachability()
            return true
        } catch {
            return false
        }
    }

    /// Returns false when the host cannot be reached; surfaces network guidance.
    @MainActor
    private func ensureServerReachable() async -> Bool {
        isCheckingServer = true
        defer { isCheckingServer = false }
        do {
            try await uploadService.probeServerReachability()
            return true
        } catch {
            presentNotice(ErrorPresentation(error))
            return false
        }
    }

    private func cancelUpload() {
        uploadService.cancelInFlightUpload()
    }

    private func beginLogin(thenUpload: Bool, thenOpenApps: Bool = false) {
        // A stuck sign-in flag must not swallow Convert / Logga in.
        if authService.isSigningIn {
            authService.cancelSignIn()
        }

        pendingUploadAfterLogin = thenUpload
        pendingOpenAppPickerAfterLogin = thenOpenApps
        clearNotice()
        clearUploadError()
        recording.uploadStatus = nil
        recording.uploadProgressText = nil
        try? modelContext.save()

        showConnectSheet = true
    }

    private func presentNotice(_ presentation: ErrorPresentation) {
        noticeAlert = NoticeAlert(presentation)
    }

    /// Dismisses a transient alert only. The upload error is cleared
    /// separately, when a new attempt begins.
    private func clearNotice() {
        noticeAlert = nil
    }

    private func clearUploadError() {
        coordinator.clearError(for: recording, context: modelContext)
    }

    private func deleteRecording() {
        stopPlayback()
        // An upload left running would write its result into the deleted
        // model and post a notification for a recording that is gone.
        if uploadService.isUploading(recording) {
            uploadService.cancelInFlightUpload()
        }
        isDeleted = true
        try? FileManager.default.removeItem(at: recording.audioFileURL)
        RecordingPersistence.deleteSessionPartFiles(forFinalFileName: recording.audioFileName)
        modelContext.delete(recording)
        try? modelContext.save()
        dismiss()
    }
}

private struct NoticeAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String?

    init(_ presentation: ErrorPresentation) {
        title = presentation.title
        message = presentation.message
    }
}

/// Lightweight delegate so playback end resets UI state.
private final class PlaybackDelegate: NSObject, AVAudioPlayerDelegate {
    static let shared = PlaybackDelegate()
    var onFinish: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
    }
}
