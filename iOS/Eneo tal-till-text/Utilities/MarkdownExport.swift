import Foundation
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

enum MarkdownExport {
    struct Payload {
        let html: String
        let plain: String
    }

    static func from(markdown: String) -> Payload {
        let attributed = attributedString(from: markdown)
        let plain = plainText(from: attributed, fallback: markdown)
        let html = htmlString(from: attributed, plainFallback: plain)
        return Payload(html: html, plain: plain)
    }

    #if canImport(UIKit)
    static func copyToPasteboard(markdown: String) {
        let payload = from(markdown: markdown)
        var item: [String: Any] = [
            UTType.utf8PlainText.identifier: payload.plain,
        ]
        if let htmlData = payload.html.data(using: .utf8) {
            item[UTType.html.identifier] = htmlData
        }
        UIPasteboard.general.items = [item]
    }
    #endif

    private static func attributedString(from markdown: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .full,
            failurePolicy: .returnPartiallyParsedIfPossible
        )
        if let parsed = try? AttributedString(markdown: markdown, options: options) {
            return parsed
        }
        return AttributedString(markdown)
    }

    private static func plainText(from attributed: AttributedString, fallback: String) -> String {
        let value = String(attributed.characters)
        return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : value
    }

    private static func htmlString(from attributed: AttributedString, plainFallback: String) -> String {
        let ns = NSAttributedString(attributed)
        let range = NSRange(location: 0, length: ns.length)
        guard ns.length > 0,
              let data = try? ns.data(
                from: range,
                documentAttributes: [
                    .documentType: NSAttributedString.DocumentType.html,
                    .characterEncoding: String.Encoding.utf8.rawValue,
                ]
              ),
              let html = String(data: data, encoding: .utf8)
        else {
            let escaped = plainFallback
                .replacingOccurrences(of: "&", with: "&amp;")
                .replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;")
            return "<!DOCTYPE html><html><body><p>\(escaped)</p></body></html>"
        }
        return html
    }
}
