import SwiftUI
import TempoCore

@main
struct TempoWatchApp: App {
    @StateObject private var workout = WorkoutManager()
    @StateObject private var sync = ConnectivityService()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if workout.view != nil {
                    WorkoutView()
                } else {
                    TemplateListView()
                }
            }
            .environmentObject(workout)
            .environmentObject(sync)
            .task {
                workout.onSessionFinished = { sync.send(session: $0) }
                sync.onCommand = { workout.handle(command: $0, templates: sync.templates) }
                workout.restoreIfNeeded(templates: sync.templates)
            }
        }
    }
}

struct TemplateListView: View {
    @EnvironmentObject var workout: WorkoutManager
    @EnvironmentObject var sync: ConnectivityService

    var body: some View {
        List(sync.templates) { t in
            Button {
                Task {
                    if !workout.healthAuthorized { await workout.requestAuthorization() }
                    workout.start(t)
                }
            } label: {
                VStack(alignment: .leading) {
                    Text(t.name).font(.headline)
                    Text(formatDuration((try? compile(t))?.reduce(0) { $0 + $1.duration } ?? 0))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityHint(Text("a11y.startWorkout"))
        }
        .navigationTitle("app.name")
    }
}

func formatDuration(_ seconds: Int) -> String {
    String(format: "%d:%02d", seconds / 60, seconds % 60)
}
