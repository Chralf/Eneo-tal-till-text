import Foundation

/// An error that knows how it should be shown: a symbol so the message reads
/// without color perception, a recovery suggestion, and support-facing detail.
protocol PresentableError: LocalizedError {
    var systemImage: String { get }
    /// URLs, status codes and raw server responses. Deliberately untranslated —
    /// this text is for troubleshooting, not for reading.
    var technicalDetails: String? { get }
    /// True when signing in again is what actually resolves the failure.
    var requiresSignIn: Bool { get }
}

extension PresentableError {
    var systemImage: String { "exclamationmark.triangle.fill" }
    var technicalDetails: String? { nil }
    var requiresSignIn: Bool { false }
}

/// Everything the UI needs to render a recoverable failure.
struct ErrorPresentation: Equatable {
    var title: String
    var message: String?
    var systemImage: String
    var technicalDetails: String?
    var requiresSignIn: Bool

    init(
        title: String,
        message: String? = nil,
        systemImage: String = "exclamationmark.triangle.fill",
        technicalDetails: String? = nil,
        requiresSignIn: Bool = false
    ) {
        self.title = title
        self.message = message
        self.systemImage = systemImage
        self.technicalDetails = technicalDetails
        self.requiresSignIn = requiresSignIn
    }
}

extension ErrorPresentation {
    init(_ error: Error) {
        if let presentable = error as? any PresentableError {
            self.init(
                title: presentable.errorDescription ?? Self.genericTitle,
                message: presentable.recoverySuggestion,
                systemImage: presentable.systemImage,
                technicalDetails: presentable.technicalDetails,
                requiresSignIn: presentable.requiresSignIn
            )
            return
        }

        if let urlError = error as? URLError {
            self.init(urlError)
            return
        }

        self.init(title: Self.genericTitle, message: error.localizedDescription)
    }

    private init(_ urlError: URLError) {
        let details = "URLError \(urlError.errorCode): \(urlError.localizedDescription)"

        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed:
            self.init(
                title: String(localized: "Ingen internetanslutning", comment: "Rubrik när enheten saknar nätverk"),
                message: String(localized: "Kontrollera nätverksanslutningen och försök igen.", comment: "Åtgärd vid saknat nätverk"),
                systemImage: "wifi.exclamationmark",
                technicalDetails: details
            )
        case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .secureConnectionFailed:
            self.init(
                title: String(localized: "Kunde inte nå Eneo-servern", comment: "Rubrik när servern inte svarar"),
                message: String(localized: "Kontrollera att du är på ett nätverk som når Eneo-servern. Se också att serveradressen i Inställningar stämmer.", comment: "Åtgärd när servern inte går att nå"),
                systemImage: "network.slash",
                technicalDetails: details
            )
        case .timedOut:
            self.init(
                title: String(localized: "Tiden gick ut", comment: "Rubrik vid timeout"),
                message: String(localized: "Servern svarade inte i tid. Kontrollera att du är på ett nätverk som når Eneo-servern och försök igen.", comment: "Åtgärd vid timeout med nätverksråd"),
                systemImage: "clock.badge.exclamationmark",
                technicalDetails: details
            )
        case .cancelled:
            self.init(
                title: String(localized: "Avbruten", comment: "Rubrik när användaren avbröt"),
                message: nil,
                systemImage: "xmark.circle",
                technicalDetails: nil
            )
        default:
            self.init(
                title: Self.genericTitle,
                message: urlError.localizedDescription,
                systemImage: "network.slash",
                technicalDetails: details
            )
        }
    }

    static var genericTitle: String {
        String(localized: "Något gick fel", comment: "Rubrik för fel utan känd orsak")
    }
}
