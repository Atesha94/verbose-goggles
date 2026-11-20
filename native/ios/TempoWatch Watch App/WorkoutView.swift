import SwiftUI
import TempoCore

/// Glanceable workout screen: phase colour fills the background, giant countdown, round X/Y, HR.
struct WorkoutView: View {
    @EnvironmentObject var workout: WorkoutManager

    var body: some View {
        if let v = workout.view {
            let phase = v.phase
            VStack(spacing: 2) {
                Text(LocalizedStringKey("phase.\(phase?.type.rawValue ?? "done")"))
                    .font(.system(.title3, design: .rounded).weight(.heavy))
                    .textCase(.uppercase)
                Text(formatDuration(Int((v.remainingMs + 999) / 1000)))
                    .font(.system(size: 56, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .accessibilityLabel(Text("a11y.timeLeft \(Int((v.remainingMs + 999) / 1000))"))
                HStack {
                    if let p = phase {
                        Text("round \(p.round) \(p.rounds)")
                        if p.sets > 1 { Text("set \(p.set) \(p.sets)") }
                    }
                    Spacer()
                    if let hr = workout.heartRate {
                        Label("\(hr)", systemImage: "heart.fill").accessibilityLabel(Text("a11y.heartRate \(hr)"))
                    }
                }
                .font(.system(.body, design: .rounded).weight(.semibold))
                HStack(spacing: 8) {
                    controlButton("backward.fill", label: "a11y.back") { workout.back() }
                    controlButton(v.status == .running ? "pause.fill" : "play.fill",
                                  label: v.status == .running ? "a11y.pause" : "a11y.resume") { workout.pauseResume() }
                    controlButton("forward.fill", label: "a11y.skip") { workout.skip() }
                }
                if workout.engine?.template.type == .amrap {
                    Button("amrap.tap \(v.manualRounds)") { workout.tapRound() }
                }
            }
            .padding(.horizontal, 4)
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(color(for: phase?.type).ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .destructive) { workout.stop() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(Text("a11y.stop"))
                }
            }
        }
    }

    private func controlButton(_ icon: String, label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.title3) }
            .buttonStyle(.borderedProminent)
            .tint(.black.opacity(0.75))
            .foregroundStyle(.white)
            .accessibilityLabel(Text(label))
    }

    private func color(for type: PhaseType?) -> Color {
        switch type {
        case .work: Color(red: 1.0, green: 0.36, blue: 0.24)
        case .rest, .setRest: Color(red: 0.30, green: 0.85, blue: 0.55)
        case .warmup: Color(red: 1.0, green: 0.82, blue: 0.25)
        case .cooldown: Color(red: 0.40, green: 0.70, blue: 1.0)
        case nil: .white
        }
    }
}
