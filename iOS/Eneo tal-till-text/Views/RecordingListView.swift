import SwiftUI
import SwiftData

struct RecordingListView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(RecordingUploadService.self) private var uploadService
    @Environment(EneoAuthService.self) private var authService
    @Environment(ConversionCoordinator.self) private var coordinator
    @AppStorage(APISettings.baseURLStorageKey) private var storedBaseURLString = APISettings.defaultBaseURLString
    @Query(sort: \Recording.dateCreated, order: .reverse) private var recordings: [Recording]

    @State private var showRecordSheet = false
    @State private var showSettingsSheet = false
    @State private var showInfoSheet = false
    @State private var navigationPath = NavigationPath()
    @State private var pendingRecording: Recording?
    @State private var recordingToDelete: Recording?
    @State private var searchText = ""
    @State private var signInRequested = false

    private var waitingCount: Int {
        recordings.filter { $0.transcriptionState == .queued }.count
    }

    /// Waiting recordings blocked only by a missing sign-in. Off-network
    /// there is nothing to do, so nothing is shown then.
    private var showsWaitingSignInRow: Bool {
        waitingCount > 0
            && !authService.isAuthenticated
            && uploadService.serverReachability == .reachable
    }

    private func processQueue() {
        Task {
            await coordinator.processQueue(context: modelContext)
        }
    }
    /// The just-finished recording that should start converting when its
    /// detail view opens (only when the setting is on).
    @State private var autoConvertRecordingID: UUID?
    @AppStorage(APISettings.autoConvertAfterRecordingStorageKey) private var autoConvertAfterRecording = false

    private var filteredRecordings: [Recording] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return recordings }
        return recordings.filter { recording in
            recording.title.localizedStandardContains(query)
                || (recording.summaryText?.localizedStandardContains(query) ?? false)
                || (recording.transcriptionText?.localizedStandardContains(query) ?? false)
        }
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            Group {
                if recordings.isEmpty {
                    emptyState
                } else {
                    recordingsList
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Eneo tal-till-text")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showInfoSheet = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .accessibilityLabel("Information")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    AccountMenu(
                        style: .toolbar,
                        onOpenSettings: { showSettingsSheet = true },
                        onSignedIn: processQueue
                    )
                    Button {
                        showSettingsSheet = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Inställningar")
                }
            }
            .safeAreaInset(edge: .bottom) {
                recordBottomBar
            }
            .sheet(isPresented: $showRecordSheet, onDismiss: {
                if let recording = pendingRecording {
                    pendingRecording = nil
                    autoConvertRecordingID = autoConvertAfterRecording ? recording.id : nil
                    navigationPath.append(recording)
                }
            }) {
                RecordView(onFinished: { recording in
                    pendingRecording = recording
                })
            }
            .sheet(isPresented: $showSettingsSheet) {
                SettingsView()
            }
            .sheet(isPresented: $showInfoSheet) {
                InfoView()
            }
            .navigationDestination(for: Recording.self) { recording in
                RecordingDetailView(
                    recording: recording,
                    autoStartUpload: recording.id == autoConvertRecordingID,
                    onAutoStartHandled: { autoConvertRecordingID = nil }
                )
            }
            .eneoSignIn(isRequested: $signInRequested, onSignedIn: processQueue)
            .confirmationDialog(
                "Radera inspelning?",
                isPresented: Binding(
                    get: { recordingToDelete != nil },
                    set: { if !$0 { recordingToDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: recordingToDelete
            ) { recording in
                Button("Radera", role: .destructive) {
                    deleteRecording(recording)
                    recordingToDelete = nil
                }
                Button("Avbryt", role: .cancel) {
                    recordingToDelete = nil
                }
            } message: { _ in
                Text("Inspelningen och ljudfilen raderas permanent.")
            }
        }
    }

    private var recordBottomBar: some View {
        VStack(spacing: 0) {
            Divider()
            Button {
                showRecordSheet = true
            } label: {
                Label("Spela in", systemImage: "mic.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 8)
        }
        .background(.bar)
    }

    @ViewBuilder
    private var emptyState: some View {
        ContentUnavailableView {
            Label("Inga inspelningar", systemImage: "waveform")
        } description: {
            Text("Spela in ljud för att komma igång.")
        }
    }

    @ViewBuilder
    private var recordingsList: some View {
        List {
            if showsWaitingSignInRow {
                waitingSignInRow
            }
            ForEach(filteredRecordings) { recording in
                Button {
                    navigationPath.append(recording)
                } label: {
                    RecordingRow(recording: recording)
                }
                .buttonStyle(RecordingCardButtonStyle())
                .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        recordingToDelete = recording
                    } label: {
                        Label("Radera", systemImage: "trash")
                    }
                }
                .accessibilityHint("Öppnar inspelningen")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .searchable(text: $searchText, prompt: "Sök bland inspelningar")
        .overlay {
            if filteredRecordings.isEmpty {
                ContentUnavailableView.search(text: searchText)
            }
        }
    }

    private var waitingSignInRow: some View {
        HStack(spacing: 12) {
            Label("Väntar på omvandling: \(waitingCount)", systemImage: "clock")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Logga in") {
                signInRequested = true
            }
            .buttonStyle(.borderless)
            .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }

    private func deleteRecording(_ recording: Recording) {
        if uploadService.isUploading(recording) {
            uploadService.cancelInFlightUpload()
        }
        try? FileManager.default.removeItem(at: recording.audioFileURL)
        RecordingPersistence.deleteSessionPartFiles(forFinalFileName: recording.audioFileName)
        modelContext.delete(recording)
        try? modelContext.save()
    }
}

struct RecordingRow: View {
    let recording: Recording

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(recording.title)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)

            if let summary = recording.summaryText?.trimmingCharacters(in: .whitespacesAndNewlines), !summary.isEmpty {
                Text(summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            HStack(spacing: 6) {
                Text(recording.formattedDate)
                Text("·")
                    .accessibilityHidden(true)
                Text(recording.formattedDuration)
                statusSymbols
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabelText)
    }

    @ViewBuilder
    private var statusSymbols: some View {
        HStack(spacing: 5) {
            if recording.isImported {
                Image(systemName: "square.and.arrow.down")
            }

            switch recording.transcriptionState {
            case .none:
                EmptyView()
            case .queued:
                Image(systemName: "clock")
            case .processing:
                ProgressView()
                    .controlSize(.mini)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            case .completed:
                Image(systemName: "text.page")
            }
        }
        .imageScale(.small)
        .accessibilityHidden(true)
    }

    private var accessibilityLabelText: String {
        var parts = [recording.title, recording.formattedDate, recording.formattedDuration]

        if recording.isImported {
            parts.append(String(localized: "Importerad", comment: "VoiceOver när inspelningen är importerad"))
        }

        switch recording.transcriptionState {
        case .none:
            break
        case .queued:
            parts.append(String(localized: "Väntar på omvandling", comment: "VoiceOver när inspelningen väntar på att Eneo ska nås"))
        case .processing:
            parts.append(String(localized: "Omvandling pågår", comment: "VoiceOver när textomvandling pågår"))
        case .failed:
            parts.append(String(localized: "Omvandling misslyckades", comment: "VoiceOver när textomvandling misslyckades"))
        case .completed:
            parts.append(String(localized: "Text klar", comment: "VoiceOver när transkriptionen är klar"))
        }

        if let summary = recording.summaryText?.trimmingCharacters(in: .whitespacesAndNewlines), !summary.isEmpty {
            parts.append(summary)
        }

        return parts.joined(separator: ", ")
    }
}

private struct RecordingCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(.easeInOut(duration: 0.12), value: configuration.isPressed)
    }
}
