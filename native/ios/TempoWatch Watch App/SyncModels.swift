import Foundation
import TempoCore

/// Wire format shared with the phone (see native/SYNC_PROTOCOL.md). Version-tagged so that old
/// watch builds ignore messages they do not understand instead of crashing.
let syncProtocolVersion = 1
let maxTemplatesPerSync = 200

struct SyncEnvelope: Codable {
    let v: Int
    let kind: Kind
    var templates: [WorkoutTemplate]?
    var session: SessionSummary?
    var command: SyncCommand?

    enum Kind: String, Codable { case templates, session, command }
}

struct SyncCommand: Codable {
    enum Action: String, Codable { case start, pause, resume, skip, back, stop }
    let action: Action
    var templateId: String?
}

struct SessionSummary: Codable, Identifiable {
    let id: String
    let templateId: String
    let name: String
    let type: TimerType
    let startedAt: Int64
    let completedAt: Int64
    let duration: Int
    let rounds: Int
    let averageHr: Int?
    let maxHr: Int?
    let calories: Double
    let source: String
}

enum SyncDecoder {
    /// Decodes and validates an incoming payload. Anything malformed, too large or with an invalid
    /// template is dropped. Data from the paired phone is still treated as untrusted input.
    static func decode(_ data: Data) -> SyncEnvelope? {
        guard data.count < 512 * 1024,
              var env = try? JSONDecoder().decode(SyncEnvelope.self, from: data),
              env.v == syncProtocolVersion else { return nil }
        if let t = env.templates {
            env.templates = Array(t.prefix(maxTemplatesPerSync)).filter { (try? validate($0)) != nil }
        }
        return env
    }
}
