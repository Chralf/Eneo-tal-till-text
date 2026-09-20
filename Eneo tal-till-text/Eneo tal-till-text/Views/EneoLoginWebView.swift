import SwiftUI
import WebKit

struct EneoLoginWebView: UIViewRepresentable {
    let authorizationURL: URL
    let callbackHost: String
    let onCallback: (URL) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(callbackHost: callbackHost, onCallback: onCallback)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.load(URLRequest(url: authorizationURL))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let callbackHost: String
        private let onCallback: (URL) -> Void

        init(callbackHost: String, onCallback: @escaping (URL) -> Void) {
            self.callbackHost = callbackHost.lowercased()
            self.onCallback = onCallback
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }

            if Self.isLoginCallback(url, callbackHost: callbackHost) {
                decisionHandler(.cancel)
                onCallback(url)
                return
            }

            decisionHandler(.allow)
        }

        private static func isLoginCallback(_ url: URL, callbackHost: String) -> Bool {
            guard url.scheme?.lowercased() == "https",
                  url.host?.lowercased() == callbackHost,
                  isLoginCallbackPath(url.path),
                  let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                  components.queryItems?.contains(where: { $0.name == "code" && $0.value?.isEmpty == false }) == true,
                  components.queryItems?.contains(where: { $0.name == "state" && $0.value?.isEmpty == false }) == true
            else {
                return false
            }

            return true
        }

        private static func isLoginCallbackPath(_ path: String) -> Bool {
            let trimmed = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return trimmed == "login/callback"
        }
    }
}
