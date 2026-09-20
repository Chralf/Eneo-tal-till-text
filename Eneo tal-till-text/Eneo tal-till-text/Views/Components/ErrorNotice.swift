import SwiftUI

/// Shows a recoverable failure as symbol plus text, so the message survives
/// both low contrast and color blindness.
struct ErrorNotice: View {
    let presentation: ErrorPresentation
    var actionTitle: LocalizedStringResource?
    var action: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text(presentation.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
            } icon: {
                Image(systemName: presentation.systemImage)
                    .foregroundStyle(.red)
            }

            if let message = presentation.message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let action, let actionTitle {
                Button(action: action) {
                    Text(actionTitle)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.regular)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
    }
}

/// Compact variant for validation next to a single field.
struct FieldValidationMessage: View {
    let message: LocalizedStringKey

    var body: some View {
        Label {
            Text(message)
        } icon: {
            Image(systemName: "exclamationmark.circle.fill")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .symbolRenderingMode(.multicolor)
    }
}

#Preview("Fel med åtgärd") {
    List {
        Section {
            ErrorNotice(
                presentation: ErrorPresentation(
                    title: "Din inloggning har gått ut",
                    message: "Logga in i Eneo igen för att skicka inspelningen.",
                    systemImage: "person.crop.circle.badge.exclamationmark",
                    requiresSignIn: true
                ),
                actionTitle: "Logga in igen",
                action: {}
            )
        }
    }
}
