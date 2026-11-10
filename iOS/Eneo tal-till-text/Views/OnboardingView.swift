import SwiftUI

private enum OnboardingStep: Int, CaseIterable {
    case welcome = 0
    case serverURL = 1
    case verifyServer = 2
    case login = 3
    case selectApp = 4
}

private enum VerifyPhase {
    case idle
    case running
    case success
    case failure(String?)
}

private enum LoginPhase {
    case prompt
    case signingIn
    case signedIn
}

struct OnboardingView: View {
    @Environment(RecordingUploadService.self) private var uploadService
    @Environment(EneoAuthService.self) private var authService

    let onComplete: () -> Void

    @AppStorage(APISettings.appIDStorageKey) private var selectedAppID = APISettings.defaultAppID

    @State private var step: OnboardingStep = .welcome
    @State private var serverURLDraft = ""
    @State private var verifiedHost: String?
    @State private var verifyPhase: VerifyPhase = .idle
    @State private var loginPhase: LoginPhase = .prompt
    @State private var authorizationRequest: AuthorizationRequest?
    @State private var didReceiveCallback = false
    @State private var availableApps: [EneoApp] = []
    @State private var personalSpaceID: String?
    @State private var isLoadingApps = false
    @State private var appLoadError: ErrorPresentation?
    @State private var loginNoticeAlert: ConnectNoticeAlert?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                stepIndicator
                    .padding(.top, 12)
                    .padding(.bottom, 24)

                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        stepContent
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                }

                actionBar
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
            }
            .background(Color(.systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if step.rawValue > OnboardingStep.welcome.rawValue && step != .verifyServer {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Tillbaka") {
                            goBack()
                        }
                    }
                }
            }
        }
        .interactiveDismissDisabled(true)
        .sheet(item: $authorizationRequest, onDismiss: {
            if authService.isSigningIn, !didReceiveCallback {
                authService.cancelSignIn()
                loginPhase = .prompt
            }
        }) { request in
            loginSheet(for: request)
        }
        .alert(item: $loginNoticeAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: alert.message.map(Text.init),
                dismissButton: .default(Text("OK")) {
                    loginPhase = .prompt
                }
            )
        }
        .task(id: step) {
            if step == .selectApp, availableApps.isEmpty, !isLoadingApps {
                await loadApps()
            }
        }
        .onAppear {
            // An onboarding abandoned after the server step starts over, but
            // the address already entered should not have to be typed again.
            if serverURLDraft.isEmpty {
                serverURLDraft = OnboardingStore.storedServerURLString
            }
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case .welcome:
            welcomeContent
        case .serverURL:
            serverURLContent
        case .verifyServer:
            verifyContent
        case .login:
            loginContent
        case .selectApp:
            selectAppContent
        }
    }

    private var welcomeContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)

            Text("Välkommen")
                .font(.largeTitle.bold())

            Text("Eneo tal-till-text")
                .font(.title3.weight(.semibold))

            Text("Spela in samtal och omvandla ljud till text med din organisations Eneo.")
                .foregroundStyle(.secondary)

            Label("Inspelningar sparas lokalt på din enhet.", systemImage: "iphone")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
    }

    private var serverURLContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Anslut till Eneo")
                .font(.title.bold())

            Text("Ange adressen till din organisations Eneo-server.")
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 8) {
                Text("Serveradress")
                    .font(.subheadline.weight(.semibold))
                TextField("https://eneo.dinorganisation.se", text: $serverURLDraft)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
            }

            Text("Exempel: https://eneo.dinorganisation.se")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
    }

    @ViewBuilder
    private var verifyContent: some View {
        switch verifyPhase {
        case .idle:
            EmptyView()
        case .running:
            VStack(alignment: .leading, spacing: 16) {
                Text("Kontrollerar servern")
                    .font(.title.bold())
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Kontrollerar servern…")
                        .foregroundStyle(.secondary)
                }
            }
        case .success:
            VStack(alignment: .leading, spacing: 16) {
                Label("Servern är verifierad", systemImage: "checkmark.seal.fill")
                    .font(.title2.bold())
                    .foregroundStyle(.green)
                if let host = verifiedHost {
                    Text(host)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                }
            }
        case .failure(let message):
            VStack(alignment: .leading, spacing: 16) {
                Label("Kunde inte ansluta till Eneo", systemImage: "exclamationmark.triangle.fill")
                    .font(.title2.bold())
                    .foregroundStyle(.orange)
                Text(message ?? "Kontrollera serveradressen och nätverksanslutningen.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var loginContent: some View {
        switch loginPhase {
        case .prompt, .signingIn:
            VStack(alignment: .leading, spacing: 16) {
                Text("Logga in i Eneo")
                    .font(.title.bold())
                Text("Logga in med det konto du använder hos din organisation.")
                    .foregroundStyle(.secondary)
                Text("Inloggningen öppnas säkert via organisationens inloggningstjänst.")
                    .font(.subheadline)
                    .foregroundStyle(.tertiary)

                if loginPhase == .signingIn {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(authService.statusText ?? "Öppnar inloggning...")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 8)
                }
            }
        case .signedIn:
            VStack(alignment: .leading, spacing: 16) {
                Label("Du är inloggad", systemImage: "person.crop.circle.badge.checkmark")
                    .font(.title2.bold())
                    .foregroundStyle(.green)
                Text("Du kan nu välja vilken Eneo-app som ska användas för transkriberingar.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var selectAppContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Välj transkriberingsapp")
                .font(.title.bold())
            Text("Välj vilken Eneo-app som normalt ska användas för nya transkriberingar. Du kan byta app för en enskild inspelning senare.")
                .foregroundStyle(.secondary)

            if isLoadingApps {
                HStack {
                    ProgressView()
                    Text("Hämtar appar...")
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8)
            } else if let appLoadError {
                Text(appLoadError.message ?? appLoadError.title)
                    .foregroundStyle(.secondary)
                Text("Du kan slutföra ändå och välja app senare under Inställningar.")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            } else {
                appSections
            }
        }
    }

    @ViewBuilder
    private var appSections: some View {
        let grouped = EneoAppGrouping.group(availableApps, personalSpaceID: personalSpaceID)

        if !grouped.personal.isEmpty {
            appSection(title: "Mina appar", apps: grouped.personal, isPersonalSection: true)
        }
        if !grouped.shared.isEmpty {
            appSection(title: "Delade appar", apps: grouped.shared, isPersonalSection: false)
        }
        if grouped.personal.isEmpty && grouped.shared.isEmpty {
            Text("Inga appar hittades. Kontrollera att ditt konto har tillgång till minst en app för tal till text.")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func appSection(title: String, apps: [EneoApp], isPersonalSection: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 8)

            VStack(spacing: 0) {
                ForEach(apps) { app in
                    Button {
                        selectedAppID = app.id
                        APISettings.persistDefaultAppDisplayName(from: app)
                    } label: {
                        HStack {
                            Text(app.pickerLabel(isPersonalSection: isPersonalSection))
                                .foregroundStyle(.primary)
                            Spacer()
                            if app.id == selectedAppID {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                    }
                    if app.id != apps.last?.id {
                        Divider().padding(.leading, 16)
                    }
                }
            }
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var stepIndicator: some View {
        HStack(spacing: 8) {
            ForEach(OnboardingStep.allCases, id: \.rawValue) { item in
                Capsule()
                    .fill(item.rawValue <= step.rawValue ? Color.accentColor : Color(.tertiarySystemFill))
                    .frame(height: 4)
            }
        }
        .padding(.horizontal, 24)
        .accessibilityLabel("Steg \(step.rawValue + 1) av \(OnboardingStep.allCases.count)")
    }

    @ViewBuilder
    private var actionBar: some View {
        switch step {
        case .welcome:
            primaryButton("Kom igång") { step = .serverURL }
        case .serverURL:
            primaryButton("Verifiera server", disabled: !isServerURLValid) {
                step = .verifyServer
                startVerification()
            }
        case .verifyServer:
            verifyActions
        case .login:
            loginActions
        case .selectApp:
            // With a load error the user may finish anyway and pick an app
            // later; otherwise a choice is required.
            primaryButton("Slutför", disabled: isLoadingApps || (selectedAppID.isEmpty && appLoadError == nil)) {
                OnboardingStore.markCompleted()
                onComplete()
            }
        }
    }

    @ViewBuilder
    private var verifyActions: some View {
        switch verifyPhase {
        case .idle, .running:
            EmptyView()
        case .success:
            primaryButton("Fortsätt") {
                loginPhase = authService.isAuthenticated ? .signedIn : .prompt
                step = .login
            }
        case .failure:
            VStack(spacing: 12) {
                primaryButton("Försök igen") { startVerification() }
                Button("Ändra serveradress") {
                    verifyPhase = .idle
                    step = .serverURL
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private var loginActions: some View {
        switch loginPhase {
        case .prompt:
            primaryButton("Logga in med Eneo") { beginLogin() }
        case .signingIn:
            EmptyView()
        case .signedIn:
            primaryButton("Fortsätt") { step = .selectApp }
        }
    }

    private var isServerURLValid: Bool {
        APISettings.makeBaseURL(from: serverURLDraft) != nil
    }

    private func primaryButton(_ title: String, disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
        }
        .buttonStyle(.borderedProminent)
        .disabled(disabled)
    }

    private func goBack() {
        switch step {
        case .serverURL:
            step = .welcome
        case .verifyServer:
            verifyPhase = .idle
            step = .serverURL
        case .login:
            verifyPhase = .idle
            step = .verifyServer
            startVerification()
        case .selectApp:
            step = .login
        default:
            break
        }
    }

    private func startVerification() {
        verifyPhase = .running
        Task {
            let normalized = APISettings.normalizedBaseURLString(serverURLDraft)
            guard APISettings.makeBaseURL(from: normalized) != nil else {
                verifyPhase = .failure("Kontrollera serveradressen och nätverksanslutningen.")
                return
            }
            serverURLDraft = normalized
            OnboardingStore.saveServerURL(normalized)
            do {
                try await uploadService.probeServerReachability()
                verifiedHost = try? APISettings.loadBaseURL().host()
                verifyPhase = .success
            } catch {
                let presentation = ErrorPresentation(error)
                verifyPhase = .failure(presentation.message ?? presentation.title)
            }
        }
    }

    private func beginLogin() {
        loginPhase = .signingIn
        didReceiveCallback = false
        authService.errorPresentation = nil
        Task {
            do {
                let authorizationURL = try await authService.beginSignIn()
                let baseURL = try APISettings.loadBaseURL()
                guard let callbackHost = baseURL.host() else {
                    throw EneoAuthError.invalidURL
                }
                authorizationRequest = AuthorizationRequest(
                    authorizationURL: authorizationURL,
                    callbackHost: callbackHost
                )
            } catch {
                if authService.isSigningIn {
                    authService.cancelSignIn()
                }
                loginNoticeAlert = ConnectNoticeAlert(authService.errorPresentation ?? ErrorPresentation(error))
                loginPhase = .prompt
            }
        }
    }

    private func loginSheet(for request: AuthorizationRequest) -> some View {
        NavigationStack {
            EneoLoginWebView(
                authorizationURL: request.authorizationURL,
                callbackHost: request.callbackHost,
                onCallback: { callbackURL in
                    didReceiveCallback = true
                    authorizationRequest = nil
                    Task {
                        await authService.completeSignIn(callbackURL: callbackURL)
                        didReceiveCallback = false
                        if authService.isAuthenticated {
                            loginPhase = .signedIn
                        } else if let presentation = authService.errorPresentation {
                            loginNoticeAlert = ConnectNoticeAlert(presentation)
                            loginPhase = .prompt
                        } else {
                            loginPhase = .prompt
                        }
                    }
                },
                onCancel: {
                    authorizationRequest = nil
                    authService.cancelSignIn()
                    loginPhase = .prompt
                }
            )
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Logga in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Avbryt") {
                        authorizationRequest = nil
                        authService.cancelSignIn()
                        loginPhase = .prompt
                    }
                }
            }
        }
    }

    private func loadApps() async {
        isLoadingApps = true
        appLoadError = nil
        personalSpaceID = await uploadService.fetchPersonalSpaceId()
        do {
            let apps = try await uploadService.fetchApps()
            availableApps = apps
            if apps.contains(where: { $0.id == selectedAppID }) == false {
                // Preselect only when there is nothing to choose between.
                selectedAppID = apps.count == 1 ? apps[0].id : ""
            }
            if let app = apps.first(where: { $0.id == selectedAppID }) {
                APISettings.persistDefaultAppDisplayName(from: app)
            }
        } catch {
            appLoadError = ErrorPresentation(error)
        }
        isLoadingApps = false
    }
}

private struct ConnectNoticeAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String?

    init(_ presentation: ErrorPresentation) {
        title = presentation.title
        message = presentation.message
    }
}
