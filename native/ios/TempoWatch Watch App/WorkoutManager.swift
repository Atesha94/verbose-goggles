import Foundation
import HealthKit
import TempoCore
import WatchKit

/// Runs the interval timer standalone on Apple Watch, with an HKWorkoutSession for live heart rate,
/// background runtime and saving the finished HKWorkout to HealthKit.
@MainActor
final class WorkoutManager: NSObject, ObservableObject {
    @Published private(set) var view: TimerView?
    @Published private(set) var heartRate: Int?
    @Published private(set) var activeCalories: Double = 0
    @Published private(set) var healthAuthorized = false
    @Published var lastError: String?

    private(set) var engine: TimerEngine?
    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var ticker: Timer?
    private var lastPhaseIndex = -1
    private var lastSpokenSecond = -1
    private var hrSamples: [(Int64, Int)] = []
    private let snapshotKey = "tempo.activeWorkout.v1"
    var onSessionFinished: ((SessionSummary) -> Void)?

    // Minimum permissions: write workouts + active energy + heart rate; read heart rate.
    private let shareTypes: Set<HKSampleType> = [
        HKObjectType.workoutType(),
        HKQuantityType(.activeEnergyBurned),
        HKQuantityType(.heartRate),
    ]
    private let readTypes: Set<HKObjectType> = [HKQuantityType(.heartRate), HKQuantityType(.activeEnergyBurned)]

    func requestAuthorization() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        do {
            try await store.requestAuthorization(toShare: shareTypes, read: readTypes)
            healthAuthorized = store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized
        } catch {
            // Denied or unavailable: the timer still works, we just do not save to Health.
            healthAuthorized = false
        }
    }

    func start(_ template: WorkoutTemplate) {
        do {
            let e = try TimerEngine(template: template)
            e.start()
            engine = e
            hrSamples.removeAll()
            lastPhaseIndex = -1
            persist()
            startHealthSession()
            startTicker()
        } catch {
            lastError = String(localized: "error.invalidTemplate")
        }
    }

    /// Restores a workout after the app was terminated (timestamps make this exact).
    func restoreIfNeeded(templates: [WorkoutTemplate]) {
        guard engine == nil,
              let data = UserDefaults.standard.data(forKey: snapshotKey),
              let snap = try? JSONDecoder().decode(ActiveSnapshot.self, from: data),
              let e = try? TimerEngine(template: snap.template, snapshot: snap.state) else { return }
        engine = e
        if e.view().status == .completed || e.view().status == .stopped { finish(); return }
        startTicker()
    }

    func pauseResume() {
        guard let e = engine else { return }
        if e.state.status == .running { e.pause(); session?.pause() } else { e.resume(); session?.resume() }
        persist(); refresh()
    }

    func skip() { engine?.skip(); persist(); refresh() }
    func back() { engine?.back(); persist(); refresh() }
    func tapRound() { engine?.tapRound(); persist(); refresh() }
    func stop() { engine?.stop(); finish() }

    /// Commands arriving from the phone (start/pause/skip). Template already validated by TempoCore.
    func handle(command: SyncCommand, templates: [WorkoutTemplate]) {
        switch command.action {
        case .start: if let t = templates.first(where: { $0.id == command.templateId }) { start(t) }
        case .pause: if engine?.state.status == .running { pauseResume() }
        case .resume: if engine?.state.status == .paused { pauseResume() }
        case .skip: skip()
        case .back: back()
        case .stop: stop()
        }
    }

    // MARK: - Ticking (UI refresh only; time itself comes from timestamps)

    private func startTicker() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        refresh()
    }

    private func refresh() {
        guard let e = engine else { return }
        let v = e.view()
        view = v
        if v.phaseIndex != lastPhaseIndex {
            if lastPhaseIndex >= 0 { WKInterfaceDevice.current().play(v.phase?.type == .work ? .start : .stop) }
            lastPhaseIndex = v.phaseIndex
        }
        let sec = Int((v.remainingMs + 999) / 1000)
        if v.status == .running, (1...3).contains(sec), sec != lastSpokenSecond {
            WKInterfaceDevice.current().play(.click)
            lastSpokenSecond = sec
        }
        if v.status == .completed { finish() }
    }

    // MARK: - HealthKit session

    private func startHealthSession() {
        guard healthAuthorized else { return }
        let config = HKWorkoutConfiguration()
        config.activityType = .highIntensityIntervalTraining
        config.locationType = .indoor
        do {
            let s = try HKWorkoutSession(healthStore: store, configuration: config)
            let b = s.associatedWorkoutBuilder()
            b.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: config)
            b.delegate = self
            session = s; builder = b
            let date = Date()
            s.startActivity(with: date)
            b.beginCollection(withStart: date) { _, _ in }
        } catch {
            lastError = String(localized: "error.healthSession")
        }
    }

    private func finish() {
        ticker?.invalidate(); ticker = nil
        guard let e = engine else { return }
        let v = e.view()
        let summary = SessionSummary(
            id: UUID().uuidString, templateId: e.template.id, name: e.template.name, type: e.template.type,
            startedAt: e.state.startedAt ?? nowMs(), completedAt: nowMs(), duration: Int(v.activeMs / 1000),
            rounds: e.template.type == .amrap ? v.manualRounds : (v.phase?.round ?? e.template.rounds),
            averageHr: hrSamples.isEmpty ? nil : hrSamples.map(\.1).reduce(0, +) / hrSamples.count,
            maxHr: hrSamples.map(\.1).max(), calories: activeCalories, source: "watch")
        UserDefaults.standard.removeObject(forKey: snapshotKey)
        engine = nil; view = nil
        session?.end()
        if let b = builder {
            b.endCollection(withEnd: Date()) { _, _ in
                b.finishWorkout { _, _ in }
            }
        }
        session = nil; builder = nil
        WKInterfaceDevice.current().play(.success)
        onSessionFinished?(summary)
    }

    private func persist() {
        guard let e = engine, let data = try? JSONEncoder().encode(ActiveSnapshot(template: e.template, state: e.state)) else { return }
        UserDefaults.standard.set(data, forKey: snapshotKey)
    }
}

extension WorkoutManager: HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        let hrType = HKQuantityType(.heartRate)
        let energyType = HKQuantityType(.activeEnergyBurned)
        let bpm = workoutBuilder.statistics(for: hrType)?.mostRecentQuantity()?
            .doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
        let kcal = workoutBuilder.statistics(for: energyType)?.sumQuantity()?.doubleValue(for: .kilocalorie())
        Task { @MainActor in
            if let bpm, HeartRate.validRange.contains(Int(bpm)) {
                self.heartRate = Int(bpm)
                self.hrSamples.append((nowMs(), Int(bpm)))
            }
            if let kcal { self.activeCalories = kcal }
        }
    }
}

struct ActiveSnapshot: Codable { let template: WorkoutTemplate; let state: TimerState }
