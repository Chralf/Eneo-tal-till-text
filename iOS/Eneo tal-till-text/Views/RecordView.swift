import SwiftUI
import SwiftData

struct RecordView: View {
    var onFinished: ((Recording) -> Void)?

    @Environment(AudioRecorderService.self) private var recorder
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var permissionGranted = false
    @State private var showPermissionAlert = false
    @State private var errorPresentation: ErrorPresentation?
    @State private var activeRecording: Recording?
    @State private var showMaxDurationAlert = false
    @State private var showDiscardConfirmation = false
    @State private var showInfoSheet = false

    /// Keeps the timer legible at accessibility sizes without letting it push
    /// the controls off screen.
    @ScaledMetric(relativeTo: .largeTitle) private var timerSize: CGFloat = 64
    @ScaledMetric(relativeTo: .body) private var waveformHeight: CGFloat = 80

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Text(recorder.formattedTime)
                    .font(.system(size: timerSize, weight: .light, design: .monospaced))
                    .foregroundStyle(recorder.state == .recording ? .primary : .secondary)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .accessibilityLabel("Inspelad tid")
                    .accessibilityValue(recorder.formattedTime)

                WaveformView(
                    levels: recorder.audioLevels,
                    barColor: recorder.state == .recording ? Color.accentColor : Color(.tertiaryLabel)
                )
                .frame(height: waveformHeight)
                .padding(.horizontal)
                .accessibilityHidden(true)

                if recorder.state == .recording || recorder.state == .paused {
                    statusLabel
                }

                if recorder.isNearMaxDuration && recorder.state != .idle {
                    Label("Maximal inspelningstid (2 timmar) närmar sig", systemImage: "exclamationmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()

                if recorder.isFinalizing {
                    finalizingLabel
                } else {
                    controlButtons
                }

                ZStack {
                    if recorder.state == .idle && !recorder.isFinalizing {
                        idleTips
                            .transition(.asymmetric(
                                insertion: .opacity.combined(with: .move(edge: .bottom)),
                                removal: .opacity.combined(with: .move(edge: .bottom))
                            ))
                    }
                }
                .animation(.easeInOut(duration: 0.28), value: recorder.state == .idle && !recorder.isFinalizing)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)

                Spacer()
            }
            .navigationTitle("Spela in")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if recorder.state != .idle {
                        Button("Avbryt") {
                            showDiscardConfirmation = true
                        }
                    } else if !recorder.isFinalizing {
                        Button("Stäng") {
                            dismiss()
                        }
                    }
                }
            }
            .interactiveDismissDisabled(recorder.state != .idle || recorder.isFinalizing)
            .confirmationDialog("Kasta inspelningen?", isPresented: $showDiscardConfirmation, titleVisibility: .visible) {
                Button("Kasta inspelningen", role: .destructive) {
                    cancelActiveRecording()
                }
                Button("Fortsätt spela in", role: .cancel) {}
            } message: {
                Text("Ljudet du har spelat in raderas och går inte att få tillbaka.")
            }
            .task {
                permissionGranted = await recorder.requestPermission()
                if !permissionGranted {
                    showPermissionAlert = true
                }
            }
            .onChange(of: scenePhase) { _, phase in
                // The user may have enabled microphone access in Settings and
                // come straight back; the record button must follow suit.
                guard phase == .active, !permissionGranted else { return }
                permissionGranted = recorder.hasRecordPermission
            }
            .onChange(of: recorder.reachedMaxDuration) { _, reached in
                if reached {
                    finalizeRecording(fromMaxDuration: true)
                }
            }
            .alert("Mikrofonåtkomst krävs", isPresented: $showPermissionAlert) {
                #if os(iOS)
                Button("Öppna Inställningar") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                #endif
                Button("Avbryt", role: .cancel) {
                    dismiss()
                }
            } message: {
                Text("Appen behöver åtkomst till mikrofonen för att spela in ljud. Aktivera mikrofonåtkomst i Inställningar.")
            }
            .alert("Maximal längd nådd", isPresented: $showMaxDurationAlert) {
                Button("OK") {
                    dismiss()
                }
            } message: {
                Text("Inspelningen stoppades automatiskt efter 2 timmar. Ljudfilen är sparad.")
            }
            .alert(
                errorPresentation?.title ?? "",
                isPresented: Binding(
                    get: { errorPresentation != nil },
                    set: { if !$0 { errorPresentation = nil } }
                )
            ) {
                Button("OK", role: .cancel) {
                    errorPresentation = nil
                }
            } message: {
                if let message = errorPresentation?.message {
                    Text(message)
                }
            }
            .sheet(isPresented: $showInfoSheet) {
                InfoView()
            }
        }
    }

    /// Shown only before recording starts. Opens the same Information sheet as the list.
    private var idleTips: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                showInfoSheet = true
            } label: {
                Label {
                    Text("Läs viktig \(Text("Information").underline()) innan du spelar in.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } icon: {
                    Image(systemName: "info.circle")
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint("Öppnar viktig information om inspelning")

            Label(
                "Låt appen vara öppen medan du spelar in. Gör du något annat på telefonen kan inspelningen stoppas.",
                systemImage: "iphone"
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
        .padding(.horizontal, 32)
    }

    /// Shown while autosaved segments are merged into the final file. The
    /// controls are hidden so a new session cannot start on top of the merge.
    private var finalizingLabel: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text("Sparar inspelningen...")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(height: 72)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var statusLabel: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(recorder.state == .recording ? .red : .orange)
                .frame(width: 10, height: 10)

            Text(statusText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var statusText: String {
        if recorder.wasInterrupted {
            return "Pausad (avbrott)"
        }
        return recorder.state == .recording ? "Spelar in..." : "Pausad"
    }

    @ViewBuilder
    private var controlButtons: some View {
        HStack(spacing: 40) {
            switch recorder.state {
            case .idle:
                Button {
                    startRecording()
                } label: {
                    ZStack {
                        Circle()
                            .fill(.red)
                            .frame(width: 72, height: 72)
                        Circle()
                            .fill(.red)
                            .frame(width: 64, height: 64)
                            .overlay {
                                Circle()
                                    .stroke(.white, lineWidth: 3)
                            }
                    }
                }
                .disabled(!permissionGranted)
                .accessibilityLabel("Starta inspelning")

            case .recording:
                Button {
                    pauseAndPersistDuration()
                } label: {
                    Image(systemName: "pause.circle.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.orange)
                }
                .accessibilityLabel("Pausa inspelning")

                Button {
                    finalizeRecording(fromMaxDuration: false)
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 72))
                        .foregroundStyle(.red)
                }
                .accessibilityLabel("Stoppa och spara inspelning")

            case .paused:
                Button {
                    recorder.resumeRecording()
                } label: {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.green)
                }
                .accessibilityLabel("Återuppta inspelning")

                Button {
                    finalizeRecording(fromMaxDuration: false)
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 72))
                        .foregroundStyle(.red)
                }
                .accessibilityLabel("Stoppa och spara inspelning")
            }
        }
    }

    private func startRecording() {
        do {
            recorder.onSegmentFinalized = { duration in
                if let activeRecording = self.activeRecording {
                    activeRecording.duration = duration
                    try? self.modelContext.save()
                }
            }
            let fileURL = try recorder.startRecording()
            let fileName = fileURL.lastPathComponent
            let timestamp = Date().formatted(date: .numeric, time: .shortened)
            let title = String(
                localized: "Inspelning \(timestamp)",
                comment: "Förvald titel på en ny inspelning. Platshållaren är datum och tid."
            )

            let recording = Recording(
                title: title,
                audioFileName: fileName,
                duration: 0
            )
            modelContext.insert(recording)
            try modelContext.save()
            activeRecording = recording
            errorPresentation = nil
        } catch {
            recorder.onSegmentFinalized = nil
            recorder.cancelRecording(deleteFile: true)
            errorPresentation = ErrorPresentation(
                title: String(localized: "Kunde inte starta inspelningen", comment: "Rubrik när inspelningen inte kan starta"),
                message: String(localized: "Kontrollera att ingen annan app använder mikrofonen och försök igen.", comment: "Åtgärd när inspelningen inte kan starta"),
                systemImage: "mic.slash.fill",
                technicalDetails: error.localizedDescription
            )
        }
    }

    private func pauseAndPersistDuration() {
        recorder.pauseRecording()
        if let activeRecording {
            activeRecording.duration = recorder.recordingTime
            try? modelContext.save()
        }
    }

    private func cancelActiveRecording() {
        recorder.onSegmentFinalized = nil
        recorder.cancelRecording(deleteFile: true)
        if let activeRecording {
            modelContext.delete(activeRecording)
            try? modelContext.save()
            self.activeRecording = nil
        }
        dismiss()
    }

    private func finalizeRecording(fromMaxDuration: Bool) {
        // A second tap on stop while the merge is running must not finalize twice.
        guard fromMaxDuration || !recorder.isFinalizing else { return }
        Task { @MainActor in
            let duration: TimeInterval
            if fromMaxDuration && recorder.state == .idle {
                duration = recorder.recordingTime > 0
                    ? recorder.recordingTime
                    : Recording.maxRecordingDuration
                recorder.recordingTime = 0
            } else if let result = await recorder.stopRecording() {
                duration = result.duration
            } else {
                duration = activeRecording?.duration ?? 0
            }
            recorder.onSegmentFinalized = nil

            guard let activeRecording else {
                errorPresentation = ErrorPresentation(
                    title: String(localized: "Inspelningen kunde inte sparas i listan", comment: "Rubrik när metadata saknas"),
                    message: String(localized: "Ljudfilen finns kvar på telefonen och dyker upp i listan nästa gång du startar appen.", comment: "Förklaring när metadata saknas"),
                    systemImage: "exclamationmark.triangle.fill"
                )
                return
            }

            activeRecording.duration = duration
            try? modelContext.save()

            let saved = activeRecording
            self.activeRecording = nil
            onFinished?(saved)

            if fromMaxDuration {
                showMaxDurationAlert = true
            } else {
                dismiss()
            }
        }
    }
}
