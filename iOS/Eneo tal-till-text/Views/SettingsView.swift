import SwiftUI
import SwiftData

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @Environment(RecordingUploadService.self) private var uploadService
    @Environment(EneoAuthService.self) private var authService

    @AppStorage(APISettings.baseURLStorageKey) private var storedBaseURLString = APISettings.defaultBaseURLString
    @AppStorage(APISettings.appIDStorageKey) private var appID = APISettings.defaultAppID
    @AppStorage(RecordingRetention.storageKey) private var retentionDays = RecordingRetention.defaultDays
    @AppStorage(APISettings.autoConvertAfterRecordingStorageKey) private var autoConvertAfterRecording = false

    @State private var draftBaseURLString = ""
    @State private var committedBaseURLString = ""
    @State private var isTestingCommunication = false
    @State private var isCheckingServer = false
    @State private var isLoadingApps = false
    @State private var availableApps: [EneoApp] = []
    @State private var personalSpaceID: String?
    @State private var noticeAlert: SettingsNoticeAlert?
    @State private var showAppPicker = false
    @State private var pendingRetentionDays: Int?
    @State private var retentionWarningCount = 0
    @State private var showRetentionWarning = false

    private var isBaseURLValid: Bool {
        APISettings.makeBaseURL(from: draftBaseURLString) != nil
    }

    private var hasDefaultApp: Bool {
        !appID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var defaultAppDisplayName: String {
        guard hasDefaultApp else {
            return String(localized: "Välj app", comment: "Visas i Inställningar när ingen förvald Eneo-app är vald")
        }
        if let app = availableApps.first(where: { $0.id == appID }) {
            return app.displayName
        }
        return APISettings.storedDefaultAppDisplayName()
            ?? String(localized: "Förvald app", comment: "Visas tills namnet på förvald Eneo-app har hämtats")
    }

    /// Keeps the stored default consistent with what the server offers: a
    /// vanished app is cleared, a sole app is taken, otherwise the user picks.
    private func reconcileDefaultApp(with apps: [EneoApp]) {
        if apps.contains(where: { $0.id == appID }) { return }
        appID = apps.count == 1 ? apps[0].id : ""
    }

    private var appIDBinding: Binding<String> {
        Binding(
            get: { appID },
            set: { newValue in
                appID = newValue
                persistSelectedDefaultApp()
            }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                accountSection
                serverSection
                appSection
                autoConvertSection
                retentionSection
                diagnosticsSection
                aboutSection
                disclaimerSection
            }
            .navigationTitle("Inställningar")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Klar") {
                        commitServerChange()
                        dismiss()
                    }
                }
            }
            .onDisappear {
                commitServerChange()
            }
            .task {
                let stored = storedBaseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
                let initial = stored.isEmpty ? APISettings.defaultBaseURLString : stored
                draftBaseURLString = APISettings.displayBaseURLString(initial)
                committedBaseURLString = APISettings.normalizedBaseURLString(initial)
                await authService.refreshAuthenticationState()
                await refreshServerReachability()
            }
            .onChange(of: authService.isAuthenticated) { _, isAuthenticated in
                if !isAuthenticated {
                    availableApps = []
                    personalSpaceID = nil
                    isLoadingApps = false
                    showAppPicker = false
                }
            }
            .alert(item: $noticeAlert) { alert in
                Alert(
                    title: Text(alert.title),
                    message: alert.message.map(Text.init),
                    dismissButton: .default(Text("OK"))
                )
            }
            .navigationDestination(isPresented: $showAppPicker) {
                EneoAppPickerView(
                    apps: availableApps,
                    personalSpaceID: personalSpaceID,
                    selectedAppID: appIDBinding
                )
                    .onAppear {
                        if availableApps.isEmpty {
                            loadApps(openPickerWhenDone: false)
                        }
                    }
            }
            .alert("Ändra gallring?", isPresented: $showRetentionWarning) {
                Button("Avbryt", role: .cancel) {
                    pendingRetentionDays = nil
                }
                Button("Ändra") {
                    if let pendingRetentionDays {
                        retentionDays = pendingRetentionDays
                    }
                    pendingRetentionDays = nil
                }
            } message: {
                Text("\(retentionWarningCount) inspelningar gallras lokalt nästa gång appen öppnas. Material på er Eneo-server påverkas inte.")
            }
        }
    }

    @ViewBuilder
    private var retentionSection: some View {
        Section {
            Picker("Automatisk gallring", selection: retentionSelectionBinding) {
                ForEach(RecordingRetention.selectableDays, id: \.self) { days in
                    Text(RecordingRetention.label(forDays: days)).tag(days)
                }
            }
        } header: {
            Text("Gallring")
        } footer: {
            Text("Raderar automatiskt ljud och text i appen efter valt antal dagar (högst 30). Material på er Eneo-server påverkas inte.")
        }
    }

    private var retentionSelectionBinding: Binding<Int> {
        Binding(
            get: { RecordingRetention.clamped(retentionDays) },
            set: { newValue in
                requestRetentionChange(to: newValue)
            }
        )
    }

    private func requestRetentionChange(to newValue: Int) {
        let clamped = RecordingRetention.clamped(newValue)
        guard clamped != retentionDays else { return }
        let currentlyExpired = countWouldExpire(withAppDefaultDays: retentionDays)
        let wouldExpire = countWouldExpire(withAppDefaultDays: clamped)
        if wouldExpire > currentlyExpired {
            pendingRetentionDays = clamped
            retentionWarningCount = wouldExpire
            showRetentionWarning = true
            return
        }
        retentionDays = clamped
    }

    private func countWouldExpire(withAppDefaultDays days: Int) -> Int {
        guard let recordings = try? modelContext.fetch(FetchDescriptor<Recording>()) else { return 0 }
        let now = Date()
        return recordings.reduce(0) { partial, recording in
            if recording.isTranscribing || recording.uploadStatus == "processing" {
                return partial
            }
            let expired = RecordingRetention.isExpired(
                dateCreated: recording.dateCreated,
                retentionDays: days,
                now: now
            )
            return partial + (expired ? 1 : 0)
        }
    }

    @ViewBuilder
    private var serverSection: some View {
        Section {
            TextField("Serveradress", text: $draftBaseURLString, prompt: Text(verbatim: "eneo.dinorganisation.se"))
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                #endif
                .autocorrectionDisabled()
                .onSubmit(commitServerChange)

            if !isBaseURLValid {
                FieldValidationMessage(message: "Ange en giltig serveradress")
            }
        } header: {
            Text("Server")
        } footer: {
            Text("Adressen till din organisations Eneo.")
        }
    }

    /// One row: account state, reachability and the sign-in/out button.
    private var accountSection: some View {
        Section {
            AccountMenu(style: .row, onSignedIn: {
                loadApps(openPickerWhenDone: false)
            })
        }
    }

    @ViewBuilder
    private var appSection: some View {
        if uploadService.serverReachability == .reachable, authService.isAuthenticated {
            Section {
                Button {
                    beginSelectDefaultApp()
                } label: {
                    HStack(alignment: .center, spacing: 12) {
                        Text(defaultAppDisplayName)
                            .foregroundStyle(hasDefaultApp ? AnyShapeStyle(.primary) : AnyShapeStyle(.tint))
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
                .disabled(isCheckingServer || isLoadingApps || !isBaseURLValid)
                .accessibilityHint("Välj förvald Eneo-app. Kräver nätverk.")
            } header: {
                Text("Omvandla till text")
            } footer: {
                Text("Den förvalda appen används för nya omvandlingar. Du kan välja en annan app på en enskild inspelning.")
            }
        }
    }

    private var autoConvertSection: some View {
        Section {
            Toggle("Omvandla direkt efter inspelning", isOn: $autoConvertAfterRecording)
        } footer: {
            Text("När du stoppar en inspelning skickas den till Eneo direkt, utan att du behöver trycka på Omvandla till text.")
        }
    }

    @ViewBuilder
    private var diagnosticsSection: some View {
        Section {
            Button {
                testCommunication()
            } label: {
                if isTestingCommunication {
                    HStack {
                        ProgressView()
                        Text("Testar kommunikation...")
                    }
                } else {
                    Label("Testa kommunikation till Eneo", systemImage: "network")
                }
            }
            .disabled(isTestingCommunication || !isBaseURLValid)
        } header: {
            Text("Felsökning")
        } footer: {
            Text("Testar först att servern går att nå, sedan inloggning och appar om du är inloggad. Filuppladdning testas när du omvandlar en inspelning.")
        }
    }

    @ViewBuilder
    private var aboutSection: some View {
        Section {
            Text("Appen är gratis och fortsätter vara det.")
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundStyle(.primary)
                .padding(.vertical, 2)

            if let emailURL = URL(string: "mailto:christian@iteca.se?subject=Eneo%20tal-till-text") {
                Link(destination: emailURL) {
                    Label("christian@iteca.se", systemImage: "envelope")
                }
            }
            if let websiteURL = URL(string: "https://iteca.se/eneotaltilltext") {
                Link(destination: websiteURL) {
                    Label("iteca.se/eneotaltilltext", systemImage: "safari")
                }
            }
        }
    }

    @ViewBuilder
    private var disclaimerSection: some View {
        Section {
            Text("Appen tillhandahålls ”i befintligt skick” och ”som tillgänglig” utan garantier av något slag. Christian Alfredsson tar inget ansvar för transkriberingens riktighet, förlust av data, Eneo-serverns tillgänglighet eller skador som uppstår genom din användning av appen. Du använder appen helt på egen risk.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Persist only when leaving the field or the screen, and sign out in the
    /// same step. Binding the field to AppStorage would send the old token to
    /// a newly typed host before logout ran.
    private func commitServerChange() {
        let normalized = APISettings.normalizedBaseURLString(draftBaseURLString)
        guard APISettings.makeBaseURL(from: normalized) != nil else { return }

        draftBaseURLString = APISettings.displayBaseURLString(normalized)
        storedBaseURLString = normalized

        let hostChanged = normalized != committedBaseURLString
        if hostChanged {
            // App IDs belong to one Eneo installation, so the default app
            // from the previous server would only fail on the new one.
            if !committedBaseURLString.isEmpty {
                appID = ""
                UserDefaults.standard.removeObject(forKey: APISettings.defaultAppNameStorageKey)
                UserDefaults.standard.removeObject(forKey: APISettings.defaultAppSpaceNameStorageKey)
                resetServerBoundUploadState()
            }
            committedBaseURLString = normalized
            authService.signOut()
            resetConnectionState()
        }

        Task {
            await refreshServerReachability()
        }
    }

    /// File, run and app IDs saved on recordings point at the previous
    /// server; resuming with them there would fail before a fresh upload.
    private func resetServerBoundUploadState() {
        guard let recordings = try? modelContext.fetch(FetchDescriptor<Recording>()) else { return }
        for recording in recordings where !uploadService.isUploading(recording) {
            recording.uploadFileID = nil
            recording.uploadServerID = nil
            recording.uploadAppID = nil
            recording.uploadAppName = nil
            recording.uploadSpaceName = nil
        }
        try? modelContext.save()
    }

    private func resetConnectionState() {
        availableApps = []
        isLoadingApps = false
        isCheckingServer = false
        showAppPicker = false
    }

    private func presentNotice(_ presentation: ErrorPresentation) {
        noticeAlert = SettingsNoticeAlert(presentation)
    }

    private func presentNotice(title: String, message: String) {
        noticeAlert = SettingsNoticeAlert(title: title, message: message)
    }

    @MainActor
    private func refreshServerReachability() async {
        await uploadService.refreshServerReachability()
    }

    private func beginSelectDefaultApp() {
        guard !isCheckingServer, !isLoadingApps else { return }
        Task {
            guard await ensureServerReachable() else { return }
            openAppPickerAfterReachable()
        }
    }

    private func openAppPickerAfterReachable() {
        showAppPicker = true
        loadApps(openPickerWhenDone: false)
    }

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

    private func testCommunication() {
        isTestingCommunication = true

        Task {
            do {
                try await uploadService.probeServerReachability()
            } catch {
                presentNotice(ErrorPresentation(error))
                isTestingCommunication = false
                return
            }

            guard authService.isAuthenticated else {
                presentNotice(
                    title: String(localized: "Kommunikation OK", comment: "Rubrik när servertest lyckas"),
                    message: String(localized: "Servern svarar.", comment: "Servertest utan inloggning")
                )
                isTestingCommunication = false
                return
            }

            do {
                try await authService.verifySession()
            } catch {
                let presentation = ErrorPresentation(error)
                if presentation.requiresSignIn {
                    authService.handleExpiredSession()
                }
                presentNotice(presentation)
                isTestingCommunication = false
                return
            }

            do {
                personalSpaceID = await uploadService.fetchPersonalSpaceId()
                let apps = try await uploadService.fetchApps()
                availableApps = apps
                reconcileDefaultApp(with: apps)
                persistSelectedDefaultApp()
                presentNotice(
                    title: String(localized: "Kommunikation OK", comment: "Rubrik när alla kommunikationstester lyckas"),
                    message: String(localized: "Server, inloggning och appar fungerar. \(apps.count) appar kan hämtas.", comment: "Sammanfattning av lyckat kommunikationstest")
                )
            } catch {
                let presentation = ErrorPresentation(error)
                if presentation.requiresSignIn {
                    authService.handleExpiredSession()
                }
                presentNotice(presentation)
            }

            isTestingCommunication = false
        }
    }

    private func loadApps(openPickerWhenDone: Bool) {
        guard !isLoadingApps else { return }
        guard authService.isAuthenticated, isBaseURLValid else { return }
        isLoadingApps = true

        Task {
            do {
                personalSpaceID = await uploadService.fetchPersonalSpaceId()
                let apps = try await uploadService.fetchApps()
                availableApps = apps
                reconcileDefaultApp(with: apps)
                persistSelectedDefaultApp()
                if openPickerWhenDone {
                    showAppPicker = true
                }
            } catch {
                let presentation = ErrorPresentation(error)
                if presentation.requiresSignIn {
                    authService.handleExpiredSession()
                }
                showAppPicker = false
                presentNotice(presentation)
            }
            isLoadingApps = false
        }
    }

    private func persistSelectedDefaultApp() {
        guard let app = availableApps.first(where: { $0.id == appID }) else { return }
        APISettings.persistDefaultAppDisplayName(from: app)
    }
}


private struct SettingsNoticeAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String?

    init(_ presentation: ErrorPresentation) {
        title = presentation.title
        message = presentation.message
    }

    init(title: String, message: String?) {
        self.title = title
        self.message = message
    }
}
