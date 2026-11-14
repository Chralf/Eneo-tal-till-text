import SwiftUI

/// Shows whether the user is signed in to Eneo and whether Eneo answers, and
/// offers sign-in or sign-out. Used as a toolbar menu on the start screen and
/// as a single row in Settings.
struct AccountMenu: View {
    enum Style {
        case toolbar
        case row
    }

    let style: Style
    /// Opens Settings when no server address has been entered yet.
    var onOpenSettings: (() -> Void)?
    var onSignedIn: (() -> Void)?

    @Environment(EneoAuthService.self) private var authService
    @Environment(RecordingUploadService.self) private var uploadService
    @AppStorage(APISettings.baseURLStorageKey) private var storedBaseURLString = APISettings.defaultBaseURLString

    @State private var signInRequested = false
    @State private var showSignOutConfirmation = false

    private var serverHost: String? {
        APISettings.makeBaseURL(from: storedBaseURLString)?.host()
    }

    private var statusTitle: String {
        guard serverHost != nil else {
            return String(localized: "Inte kopplad till Eneo", comment: "Kontostatus när ingen serveradress finns")
        }
        guard authService.isAuthenticated else {
            return String(localized: "Utloggad", comment: "Kontostatus när användaren inte är inloggad")
        }
        if let email = authService.accountEmail {
            return String(localized: "Inloggad som \(email)", comment: "Kontostatus. Platshållaren är e-postadressen.")
        }
        return String(localized: "Inloggad", comment: "Kontostatus när e-postadressen är okänd")
    }

    /// Reachability is shown on its own line; being unreachable is normal
    /// outside the organisation's network and never hides the account state.
    private var statusSubtitle: String? {
        guard let serverHost else { return nil }
        if uploadService.isCheckingReachability, uploadService.serverReachability == .unknown {
            return String(localized: "Kontrollerar anslutning…", comment: "Kontomeny medan servern kontrolleras")
        }
        switch uploadService.serverReachability {
        case .reachable:
            return String(localized: "Ansluten till \(serverHost)", comment: "Kontomeny när Eneo svarar. Platshållaren är serverns värdnamn.")
        case .unreachable:
            return String(localized: "Eneo nås inte just nu", comment: "Kontomeny när Eneo inte svarar")
        case .unknown:
            return nil
        }
    }

    private var iconName: String {
        authService.isAuthenticated ? "person.crop.circle.badge.checkmark" : "person.crop.circle"
    }

    /// Grey whenever Eneo does not answer; the tint only while it can be used.
    private var iconStyle: AnyShapeStyle {
        authService.isAuthenticated && uploadService.serverReachability != .reachable
            ? AnyShapeStyle(.secondary)
            : AnyShapeStyle(.tint)
    }

    private var accessibilityValue: String {
        [statusTitle, statusSubtitle].compactMap { $0 }.joined(separator: ", ")
    }

    var body: some View {
        content
            .background {
                // Presentations live on a zero-size background: attached to
                // the row itself, a Form cell grows to fit their hosts.
                Color.clear
                    .eneoSignIn(isRequested: $signInRequested, onSignedIn: onSignedIn)
                    .confirmationDialog("Logga ut från Eneo?", isPresented: $showSignOutConfirmation, titleVisibility: .visible) {
                        Button("Logga ut", role: .destructive) {
                            authService.signOut()
                        }
                        Button("Avbryt", role: .cancel) {}
                    } message: {
                        Text("Dina inspelningar ligger kvar på telefonen.")
                    }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch style {
        case .toolbar:
            Menu {
                Section {
                    Text(statusTitle)
                    if let statusSubtitle {
                        Text(statusSubtitle)
                    }
                }
                menuAction
            } label: {
                Image(systemName: iconName)
                    .foregroundStyle(iconStyle)
            }
            .accessibilityLabel("Konto")
            .accessibilityValue(accessibilityValue)
        case .row:
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(statusTitle)
                    if let statusSubtitle {
                        Text(statusSubtitle)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if serverHost != nil {
                    accountButton
                        .buttonStyle(.borderless)
                }
            }
        }
    }

    @ViewBuilder
    private var menuAction: some View {
        if serverHost == nil {
            if let onOpenSettings {
                Button("Ange serveradress…", systemImage: "gearshape") {
                    onOpenSettings()
                }
            }
        } else {
            accountButton
        }
    }

    @ViewBuilder
    private var accountButton: some View {
        if authService.isAuthenticated {
            Button("Logga ut", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                showSignOutConfirmation = true
            }
        } else {
            Button("Logga in", systemImage: "person.crop.circle.badge.checkmark") {
                signInRequested = true
            }
        }
    }
}

extension View {
    /// Presents Eneo sign-in when `isRequested` becomes true. Checks first
    /// that Eneo answers, so the login page never fails to load off-network.
    func eneoSignIn(isRequested: Binding<Bool>, onSignedIn: (() -> Void)? = nil) -> some View {
        modifier(EneoSignInModifier(isRequested: isRequested, onSignedIn: onSignedIn))
    }
}

private struct EneoSignInModifier: ViewModifier {
    @Binding var isRequested: Bool
    let onSignedIn: (() -> Void)?

    @Environment(EneoAuthService.self) private var authService
    @Environment(RecordingUploadService.self) private var uploadService

    @State private var showConnectSheet = false
    @State private var showUnreachableAlert = false

    func body(content: Content) -> some View {
        content
            .onChange(of: isRequested) { _, requested in
                guard requested else { return }
                Task {
                    defer { isRequested = false }
                    do {
                        try await uploadService.probeServerReachability()
                    } catch {
                        showUnreachableAlert = true
                        return
                    }
                    // A stuck sign-in flag must not swallow the request.
                    if authService.isSigningIn {
                        authService.cancelSignIn()
                    }
                    showConnectSheet = true
                }
            }
            .sheet(isPresented: $showConnectSheet, onDismiss: {
                if authService.isAuthenticated {
                    onSignedIn?()
                }
            }) {
                EneoConnectView(onFinished: { _ in
                    showConnectSheet = false
                })
            }
            .alert("Eneo nås inte", isPresented: $showUnreachableAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Anslut till ett nät där Eneo är tillgängligt och försök igen.")
            }
    }
}
