import SwiftUI

struct EneoAppPickerView: View {
    let apps: [EneoApp]
    let personalSpaceID: String?
    @Binding var selectedAppID: String
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    private var grouped: GroupedEneoApps {
        EneoAppGrouping.group(apps, personalSpaceID: personalSpaceID)
    }

    private var filtered: GroupedEneoApps {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return grouped }
        let matches: (EneoApp) -> Bool = { app in
            app.displayName.localizedCaseInsensitiveContains(query)
        }
        return GroupedEneoApps(
            personal: grouped.personal.filter(matches),
            shared: grouped.shared.filter(matches)
        )
    }

    var body: some View {
        List {
            if !filtered.personal.isEmpty {
                Section("Mina appar") {
                    appRows(filtered.personal, isPersonalSection: true)
                }
            }
            if !filtered.shared.isEmpty {
                Section("Delade appar") {
                    appRows(filtered.shared, isPersonalSection: false)
                }
            }
            if filtered.personal.isEmpty && filtered.shared.isEmpty {
                Text("Inga appar matchar sökningen.")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Eneo")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .searchable(text: $searchText, prompt: "Sök")
    }

    @ViewBuilder
    private func appRows(_ apps: [EneoApp], isPersonalSection: Bool) -> some View {
        ForEach(apps) { app in
            Button {
                selectedAppID = app.id
                dismiss()
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(app.pickerLabel(isPersonalSection: isPersonalSection))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    if app.id == selectedAppID {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                            .accessibilityHidden(true)
                    }
                }
            }
            .accessibilityAddTraits(app.id == selectedAppID ? [.isSelected] : [])
        }
    }
}

struct GroupedEneoApps {
    let personal: [EneoApp]
    let shared: [EneoApp]
}

enum EneoAppGrouping {
    static func group(_ apps: [EneoApp], personalSpaceID: String?) -> GroupedEneoApps {
        let personal: [EneoApp]
        if let personalSpaceID {
            personal = apps.filter { $0.spaceID == personalSpaceID }
                .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        } else {
            personal = []
        }
        let shared = apps.filter { personalSpaceID == nil || $0.spaceID != personalSpaceID }
            .sorted {
                let spaceComparison = $0.spaceName.localizedStandardCompare($1.spaceName)
                if spaceComparison != .orderedSame {
                    return spaceComparison == .orderedAscending
                }
                return $0.name.localizedStandardCompare($1.name) == .orderedAscending
            }
        return GroupedEneoApps(personal: personal, shared: shared)
    }
}

extension EneoApp {
    func pickerLabel(isPersonalSection: Bool) -> String {
        isPersonalSection ? name : displayName
    }
}
