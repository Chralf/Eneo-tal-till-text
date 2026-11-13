import Foundation
import Observation

@Observable
@MainActor
final class EneoAuthService {
    var isAuthenticated = AuthTokenStore.hasAccessToken
    /// E-mail of the signed-in account, when the server reported one.
    var accountEmail: String? = AuthTokenStore.loadAccountEmail()
    var isSigningIn = false
    var statusText: String?
    var errorPresentation: ErrorPresentation?

    private let urlSession: URLSession

    init(urlSession: URLSession = .shared) {
        self.urlSession = urlSession
    }

    /// A stored token says nothing about whether it still works, so the session
    /// is re-checked against the server rather than inferred from the Keychain.
    func refreshAuthenticationState() async {
        // A check racing a sign-in in progress could clear the token it is
        // about to verify.
        guard !isSigningIn else { return }
        guard AuthTokenStore.hasAccessToken else {
            isAuthenticated = false
            accountEmail = nil
            return
        }

        do {
            try await verifySession()
            isAuthenticated = true
        } catch EneoAuthError.verificationFailed(let statusCode) where statusCode == 401 || statusCode == 403 {
            // An expired token, not a sign-out: keep the SSO session.
            handleExpiredSession()
        } catch EneoAuthError.missingAccessToken {
            isAuthenticated = false
        } catch {
            // Being offline is not the same as being signed out; keep the session.
            isAuthenticated = true
        }
    }

    /// Called when a request is rejected mid-task so the UI can offer a new sign-in.
    func handleExpiredSession() {
        AuthTokenStore.clearAccessToken()
        AuthTokenStore.clearPendingState()
        AuthTokenStore.clearAccountEmail()
        isAuthenticated = false
        accountEmail = nil
        // The IdP cookies are deliberately kept: the next sign-in should pass
        // through the remembered SSO session instead of asking for a password.
    }

    func beginSignIn() async throws -> URL {
        guard !isSigningIn else {
            throw EneoAuthError.signInAlreadyInProgress
        }

        isSigningIn = true
        errorPresentation = nil
        statusText = String(localized: "Öppnar Eneo-inloggning...", comment: "Status medan inloggningssidan hämtas")

        do {
            let initiateResponse = try await initiateAuthentication()
            AuthTokenStore.savePendingState(initiateResponse.state)
            statusText = String(localized: "Väntar på inloggning...", comment: "Status medan användaren loggar in")
            return initiateResponse.authorizationURL
        } catch {
            isSigningIn = false
            statusText = nil
            errorPresentation = ErrorPresentation(error)
            throw error
        }
    }

    func completeSignIn(callbackURL: URL) async {
        do {
            let callback = try parseCallback(callbackURL)
            guard callback.state == AuthTokenStore.loadPendingState() else {
                throw EneoAuthError.stateMismatch
            }

            statusText = String(localized: "Slutför inloggning...", comment: "Status medan koden växlas mot token")
            let token = try await exchangeCodeForToken(code: callback.code, state: callback.state)
            AuthTokenStore.saveAccessToken(token)
            AuthTokenStore.clearPendingState()

            statusText = String(localized: "Verifierar inloggning...", comment: "Status medan sessionen verifieras")
            try await verifySession()

            isAuthenticated = true
            statusText = nil
            errorPresentation = nil
        } catch {
            // A stored token is not a verified session. Drop it so upload cannot
            // proceed as if login succeeded.
            AuthTokenStore.clearAccessToken()
            AuthTokenStore.clearPendingState()
            AuthTokenStore.clearAccountEmail()
            isAuthenticated = false
            accountEmail = nil
            statusText = nil
            errorPresentation = ErrorPresentation(error)
        }

        isSigningIn = false
    }

    func cancelSignIn() {
        AuthTokenStore.clearPendingState()
        isSigningIn = false
        statusText = nil
    }

    /// Explicit sign-out. Unlike an expired session this also forgets the
    /// identity provider's cookies, so the next sign-in asks for credentials.
    func signOut() {
        AuthTokenStore.clearAccessToken()
        AuthTokenStore.clearPendingState()
        AuthTokenStore.clearAccountEmail()
        isAuthenticated = false
        accountEmail = nil
        isSigningIn = false
        statusText = nil
        errorPresentation = nil
        Task {
            await LoginWebSession.clear()
        }
    }

    func verifySession() async throws {
        let token = AuthTokenStore.loadAccessToken().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            throw EneoAuthError.missingAccessToken
        }

        let url = try Self.makeURL(path: "/api/v1/users/me/")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await urlSession.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw EneoAuthError.invalidResponse
        }
        guard 200..<300 ~= httpResponse.statusCode else {
            throw EneoAuthError.verificationFailed(statusCode: httpResponse.statusCode)
        }

        if let email = Self.parseAccountEmail(from: data) {
            accountEmail = email
            AuthTokenStore.saveAccountEmail(email)
        }
    }

    /// Eneo's user endpoint is not pinned to one schema, so accept the most
    /// likely identity fields in order of usefulness.
    nonisolated static func parseAccountEmail(from data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        for key in ["email", "username", "name"] {
            if let value = object[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    return trimmed
                }
            }
        }
        return nil
    }

    private func initiateAuthentication() async throws -> InitiateAuthResponse {
        let url = try Self.makeURL(path: "/api/v1/auth/initiate")
        let (data, response) = try await urlSession.data(from: url)
        try Self.validate(response: response, data: data, operation: "Initiera inloggning", url: url)
        let initiateResponse = try JSONDecoder().decode(InitiateAuthResponse.self, from: data)
        try Self.validateAuthorizationURL(initiateResponse.authorizationURL)
        return initiateResponse
    }

    /// Eneo's initiate response typically points at an external identity
    /// provider, not the Eneo host. Require HTTPS so the in-app WebView cannot
    /// be pointed at a custom scheme.
    private static func validateAuthorizationURL(_ url: URL) throws {
        guard url.scheme?.lowercased() == "https", url.host() != nil else {
            throw EneoAuthError.untrustedAuthorizationURL
        }
    }

    private func parseCallback(_ url: URL) throws -> AuthCallback {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw EneoAuthError.invalidCallback
        }

        let queryItems = components.queryItems ?? []
        let code = queryItems.first { $0.name == "code" }?.value
        let state = queryItems.first { $0.name == "state" }?.value

        guard let code, !code.isEmpty, let state, !state.isEmpty else {
            throw EneoAuthError.invalidCallback
        }

        return AuthCallback(code: code, state: state)
    }

    private func exchangeCodeForToken(code: String, state: String) async throws -> String {
        let url = try Self.makeURL(path: "/api/v1/auth/callback")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(CallbackRequest(code: code, state: state))

        let (data, response) = try await urlSession.data(for: request)
        try Self.validate(response: response, data: data, operation: "Slutför inloggning", url: url)
        let tokenResponse = try JSONDecoder().decode(AccessTokenResponse.self, from: data)
        return tokenResponse.accessToken
    }

    private static func makeURL(path: String) throws -> URL {
        let baseURL = try APISettings.loadBaseURL()
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw EneoAuthError.invalidURL
        }

        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let appendedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let fullPath = [basePath, appendedPath]
            .filter { !$0.isEmpty }
            .joined(separator: "/")
        let trailingSlash = path.hasSuffix("/") ? "/" : ""
        components.path = "/\(fullPath)\(trailingSlash)"

        guard let url = components.url else {
            throw EneoAuthError.invalidURL
        }
        return url
    }

    private static func validate(response: URLResponse, data: Data, operation: String, url: URL) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw EneoAuthError.invalidResponse
        }
        guard 200..<300 ~= httpResponse.statusCode else {
            let message = String(data: data, encoding: .utf8)
            throw EneoAuthError.serverError(operation: operation, statusCode: httpResponse.statusCode, url: url.absoluteString, message: message)
        }
    }
}

private struct InitiateAuthResponse: Decodable {
    let authorizationURL: URL
    let state: String

    enum CodingKeys: String, CodingKey {
        case authorizationURL = "authorization_url"
        case state
    }
}

private struct CallbackRequest: Encodable {
    let code: String
    let state: String
}

private struct AccessTokenResponse: Decodable {
    let accessToken: String

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
    }
}

private struct AuthCallback {
    let code: String
    let state: String
}

enum EneoAuthError: PresentableError {
    case invalidURL
    case invalidResponse
    case missingAccessToken
    case invalidCallback
    case stateMismatch
    case signInAlreadyInProgress
    case verificationFailed(statusCode: Int)
    case untrustedAuthorizationURL
    case serverError(operation: String, statusCode: Int, url: String, message: String?)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return String(localized: "Serveradressen är ogiltig", comment: "Rubrik när Eneo-adressen inte går att tolka")
        case .invalidResponse:
            return String(localized: "Oväntat svar från Eneo", comment: "Rubrik när inloggningssvaret inte är giltig HTTP")
        case .missingAccessToken:
            return String(localized: "Du är inte inloggad", comment: "Rubrik när token saknas")
        case .invalidCallback:
            return String(localized: "Inloggningen kunde inte slutföras", comment: "Rubrik när callbacken saknar kod")
        case .stateMismatch:
            return String(localized: "Inloggningen kunde inte verifieras", comment: "Rubrik vid state mismatch")
        case .signInAlreadyInProgress:
            return String(localized: "Inloggning pågår redan", comment: "Rubrik när inloggning redan är igång")
        case .verificationFailed:
            return String(localized: "Inloggningen kunde inte bekräftas", comment: "Rubrik när sessionen inte kunde verifieras")
        case .untrustedAuthorizationURL:
            return String(localized: "Inloggningen kunde inte startas", comment: "Rubrik när inloggningsadressen saknar https")
        case .serverError:
            return String(localized: "Eneo kunde inte logga in dig", comment: "Rubrik vid serverfel under inloggning")
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .invalidURL:
            return String(localized: "Kontrollera serveradressen under Inställningar.", comment: "Åtgärd vid ogiltig adress")
        case .invalidResponse:
            return String(localized: "Försök igen. Kvarstår felet kan servern vara nere.", comment: "Åtgärd vid ogiltigt svar")
        case .missingAccessToken:
            return String(localized: "Logga in i Eneo för att skicka inspelningen.", comment: "Åtgärd när token saknas")
        case .invalidCallback, .stateMismatch:
            return String(localized: "Försök logga in igen.", comment: "Åtgärd vid trasig inloggning")
        case .signInAlreadyInProgress:
            return String(localized: "Vänta tills den pågående inloggningen är klar.", comment: "Åtgärd när inloggning redan pågår")
        case .verificationFailed:
            return String(localized: "Kontrollera med din organisation att ditt konto har tillgång till Eneo.", comment: "Åtgärd när verifieringen nekas")
        case .untrustedAuthorizationURL:
            return String(localized: "Försök igen. Kvarstår felet kan du kontrollera serveradressen under Inställningar.", comment: "Åtgärd när inloggningsadressen inte är https")
        case .serverError(_, _, _, let message):
            if let message, !message.isEmpty {
                return message
            }
            return String(localized: "Försök igen om en stund.", comment: "Åtgärd vid serverfel utan meddelande")
        }
    }

    var systemImage: String {
        switch self {
        case .invalidURL, .untrustedAuthorizationURL:
            return "gearshape.exclamationmark"
        case .missingAccessToken, .verificationFailed, .stateMismatch, .invalidCallback:
            return "person.crop.circle.badge.exclamationmark"
        default:
            return "exclamationmark.triangle.fill"
        }
    }

    var requiresSignIn: Bool {
        switch self {
        case .missingAccessToken, .verificationFailed, .stateMismatch, .invalidCallback:
            return true
        default:
            return false
        }
    }

    var technicalDetails: String? {
        switch self {
        case .verificationFailed(let statusCode):
            return "Sessionsverifiering: status \(statusCode)"
        case .serverError(let operation, let statusCode, let url, let message):
            var details = "\(operation): status \(statusCode)\nURL: \(url)"
            if let message, !message.isEmpty {
                details += "\nSvar: \(message)"
            }
            return details
        default:
            return nil
        }
    }
}
