import ExpoModulesCore
import HealthKit
import WatchConnectivity

/// iOS adapter: HealthKit (write HKWorkout + active energy + heart rate, read heart rate / resting HR)
/// and WatchConnectivity (sync templates/sessions with the watchOS app). Everything stays on-device.
public class TempoHealthModule: Module {
    private let store = HKHealthStore()
    private lazy var watch = WatchBridge { [weak self] payload in self?.sendEvent("onWatchMessage", ["payload": payload]) }

    private var writeTypes: Set<HKSampleType> {
        [HKObjectType.workoutType(), HKQuantityType(.activeEnergyBurned), HKQuantityType(.heartRate)]
    }
    private var readTypes: Set<HKObjectType> {
        [HKQuantityType(.heartRate), HKQuantityType(.restingHeartRate)]
    }

    public func definition() -> ModuleDefinition {
        Name("TempoHealth")
        Events("onWatchMessage")

        OnCreate { _ = self.watch }

        AsyncFunction("isAvailable") { HKHealthStore.isHealthDataAvailable() }

        AsyncFunction("requestPermissions") { () async throws -> Bool in
            guard HKHealthStore.isHealthDataAvailable() else { return false }
            try await self.store.requestAuthorization(toShare: self.writeTypes, read: self.readTypes)
            // HealthKit does not reveal read permission status (privacy); write status is reported.
            return self.store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized
        }

        AsyncFunction("getPermissionStatus") { () -> String in
            switch self.store.authorizationStatus(for: HKObjectType.workoutType()) {
            case .sharingAuthorized: return "granted"
            case .sharingDenied: return "denied"
            default: return "notDetermined"
            }
        }

        /// workout: { startedAt, completedAt (epoch ms), calories, hrSamples: [{timestamp, bpm}], name }
        AsyncFunction("saveWorkout") { (w: WorkoutRecord) async throws -> String in
            try w.validate()
            let start = Date(timeIntervalSince1970: w.startedAt / 1000)
            let end = Date(timeIntervalSince1970: w.completedAt / 1000)
            let config = HKWorkoutConfiguration()
            config.activityType = .highIntensityIntervalTraining
            config.locationType = .indoor
            let builder = HKWorkoutBuilder(healthStore: self.store, configuration: config, device: .local())
            try await builder.beginCollection(at: start)
            var samples: [HKSample] = []
            if w.calories > 0 {
                samples.append(HKQuantitySample(type: HKQuantityType(.activeEnergyBurned),
                                                quantity: HKQuantity(unit: .kilocalorie(), doubleValue: w.calories),
                                                start: start, end: end))
            }
            let bpmUnit = HKUnit.count().unitDivided(by: .minute())
            for s in w.hrSamples.prefix(20_000) where (25...250).contains(s.bpm) {
                let d = Date(timeIntervalSince1970: s.timestamp / 1000)
                guard d >= start && d <= end else { continue }
                samples.append(HKQuantitySample(type: HKQuantityType(.heartRate),
                                                quantity: HKQuantity(unit: bpmUnit, doubleValue: Double(s.bpm)), start: d, end: d))
            }
            if !samples.isEmpty { try await builder.addSamples(samples) }
            try await builder.addMetadata([HKMetadataKeyWorkoutBrandName: "Ronda", "TempoSessionId": w.id])
            try await builder.endCollection(at: end)
            let workout = try await builder.finishWorkout()
            return workout?.uuid.uuidString ?? ""
        }

        AsyncFunction("readHeartRate") { (fromMs: Double, toMs: Double) async throws -> [[String: Double]] in
            try await self.query(.heartRate, from: fromMs, to: toMs, limit: 5_000)
        }

        AsyncFunction("readRestingHeartRate") { () async throws -> Double? in
            let now = Date().timeIntervalSince1970 * 1000
            return try await self.query(.restingHeartRate, from: now - 7 * 86_400_000, to: now, limit: 1, newestFirst: true).first?["bpm"]
        }

        AsyncFunction("sendToWatch") { (payload: String, mode: String) in
            self.watch.send(payload: payload, mode: mode)
        }
    }

    private func query(_ id: HKQuantityTypeIdentifier, from: Double, to: Double, limit: Int, newestFirst: Bool = false) async throws -> [[String: Double]] {
        let predicate = HKQuery.predicateForSamples(withStart: Date(timeIntervalSince1970: from / 1000), end: Date(timeIntervalSince1970: to / 1000))
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(id), predicate: predicate)],
            sortDescriptors: [SortDescriptor(\.startDate, order: newestFirst ? .reverse : .forward)], limit: limit)
        let unit = HKUnit.count().unitDivided(by: .minute())
        return try await descriptor.result(for: store).map {
            ["timestamp": $0.startDate.timeIntervalSince1970 * 1000, "bpm": $0.quantity.doubleValue(for: unit)]
        }
    }
}

struct HrSample: Record {
    @Field var timestamp: Double = 0
    @Field var bpm: Int = 0
}

struct WorkoutRecord: Record {
    @Field var id: String = ""
    @Field var name: String = ""
    @Field var startedAt: Double = 0
    @Field var completedAt: Double = 0
    @Field var calories: Double = 0
    @Field var hrSamples: [HrSample] = []

    func validate() throws {
        let now = Date().timeIntervalSince1970 * 1000
        guard startedAt > 0, completedAt > startedAt, completedAt <= now + 60_000,
              completedAt - startedAt <= 86_400_000, calories >= 0, calories < 20_000 else {
            throw Exception(name: "InvalidWorkout", description: "Workout record failed validation")
        }
    }
}

/// WatchConnectivity bridge. Payloads are opaque JSON strings validated on both ends (SYNC_PROTOCOL.md).
final class WatchBridge: NSObject, WCSessionDelegate {
    private let onMessage: (String) -> Void
    init(onMessage: @escaping (String) -> Void) {
        self.onMessage = onMessage
        super.init()
        if WCSession.isSupported() { WCSession.default.delegate = self; WCSession.default.activate() }
    }

    func send(payload: String, mode: String) {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated,
              WCSession.default.isPaired, WCSession.default.isWatchAppInstalled,
              let data = payload.data(using: .utf8), data.count < 512 * 1024 else { return }
        let msg: [String: Any] = ["payload": data]
        switch mode {
        case "message" where WCSession.default.isReachable: WCSession.default.sendMessage(msg, replyHandler: nil)
        case "context": try? WCSession.default.updateApplicationContext(msg)
        default: WCSession.default.transferUserInfo(msg)
        }
    }

    private func deliver(_ dict: [String: Any]) {
        guard let d = dict["payload"] as? Data, d.count < 512 * 1024, let s = String(data: d, encoding: .utf8) else { return }
        onMessage(s)
    }

    func session(_ s: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {}
    func sessionDidBecomeInactive(_ s: WCSession) {}
    func sessionDidDeactivate(_ s: WCSession) { WCSession.default.activate() }
    func session(_ s: WCSession, didReceiveMessage m: [String: Any]) { deliver(m) }
    func session(_ s: WCSession, didReceiveUserInfo u: [String: Any]) { deliver(u) }
    func session(_ s: WCSession, didReceiveApplicationContext c: [String: Any]) { deliver(c) }
}
