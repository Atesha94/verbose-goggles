import Foundation
import TempoCore
import WatchConnectivity

/// Two-way phone ↔ watch sync over WatchConnectivity (local Bluetooth/Wi-Fi link, no cloud).
/// - Templates: phone → watch via applicationContext (latest state wins), watch edits go back the same way.
/// - Sessions: watch → phone via transferUserInfo (queued, delivered even if the phone app is closed).
/// - Commands: sendMessage when reachable (live remote control).
@MainActor
final class ConnectivityService: NSObject, ObservableObject {
    @Published private(set) var templates: [WorkoutTemplate] = WorkoutTemplate.presets
    var onCommand: ((SyncCommand) -> Void)?

    private let templatesKey = "tempo.templates.v1"

    override init() {
        super.init()
        if let data = UserDefaults.standard.data(forKey: templatesKey),
           let saved = try? JSONDecoder().decode([WorkoutTemplate].self, from: data) {
            templates = saved.filter { (try? validate($0)) != nil }
        }
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    func send(session: SessionSummary) {
        guard WCSession.default.activationState == .activated,
              let data = try? JSONEncoder().encode(SyncEnvelope(v: syncProtocolVersion, kind: .session, session: session)) else { return }
        WCSession.default.transferUserInfo(["payload": data])
    }

    private func apply(_ env: SyncEnvelope) {
        switch env.kind {
        case .templates:
            guard let t = env.templates, !t.isEmpty else { return }
            templates = t
            if let data = try? JSONEncoder().encode(t) { UserDefaults.standard.set(data, forKey: templatesKey) }
        case .command:
            if let c = env.command { onCommand?(c) }
        case .session:
            break // phone never sends sessions to the watch in v1
        }
    }

    nonisolated private func receive(_ dict: [String: Any]) {
        guard let data = dict["payload"] as? Data, let env = SyncDecoder.decode(data) else { return }
        Task { @MainActor in self.apply(env) }
    }
}

extension ConnectivityService: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if state == .activated { receive(session.receivedApplicationContext) }
    }
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext ctx: [String: Any]) { receive(ctx) }
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) { receive(message) }
    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any]) { receive(userInfo) }
}
