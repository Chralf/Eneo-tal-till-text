import SwiftUI

/// Sign-in presented at the moment it is needed. Opens the Eneo login page
/// immediately — no intermediate explanation screen.
struct EneoConnectView: View {
    @Environment(EneoAuthService.self) private var authService
    @Environment(\.dismiss) private var dismiss

    /// Called with true only once the session has been verified.
    let onFinished: (Bool) -> Void

    @State private var authorizationRequest: AuthorizationRequest?
    @State private var didReceiveCallback = false
    @State private var didStartSignIn = false
    @State private var noticeAlert: ConnectNoticeAlert?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if authService.isSigningIn || authorizationRequest != nil {
                    ProgressView()
                    Text(authService.statusText ?? String(localized: "Öppnar inloggning...", comment: "Status medan inloggningssidan hämtas"))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
            .navigationTitle("Logga in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Avbryt") {
                        cancel()
                    }
                }
            }
        }
        .interactiveDismissDisabled(authService.isSigningIn)
        .task {
            guard !didStartSignIn else { return }
            didStartSignIn = true
            beginSignIn()
        }
        .sheet(item: $authorizationRequest, onDismiss: {
            if authService.isSigningIn, !didReceiveCallback {
                authService.cancelSignIn()
                onFinished(false)
            }
        }) { request in
            loginSheet(for: request)
        }
        .alert(item: $noticeAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: alert.message.map(Text.init),
                dismissButton: .default(Text("OK")) {
                    onFinished(false)
                }
            )
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
                            onFinished(true)
                        } else if let presentation = authService.errorPresentation {
                            noticeAlert = ConnectNoticeAlert(presentation)
                        } else {
                            onFinished(false)
                        }
                    }
                },
                onCancel: {
                    authorizationRequest = nil
                    authService.cancelSignIn()
                    onFinished(false)
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
                        onFinished(false)
                    }
                }
            }
        }
    }

    private func beginSignIn() {
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
                let presentation = authService.errorPresentation ?? ErrorPresentation(error)
                noticeAlert = ConnectNoticeAlert(presentation)
            }
        }
    }

    private func cancel() {
        if authService.isSigningIn, !didReceiveCallback {
            authService.cancelSignIn()
        }
        onFinished(false)
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

struct AuthorizationRequest: Identifiable, Hashable {
    let id = UUID()
    let authorizationURL: URL
    let callbackHost: String
}
