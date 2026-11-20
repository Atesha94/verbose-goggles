import Foundation

/// Swift port of the TypeScript timer engine. Shared by the watchOS app and the iOS native module.
/// All times are epoch milliseconds; state is derived from timestamps, never from tick counters,
/// so it stays correct when the app is suspended, the wrist is lowered or the process is restored.

public enum TimerType: String, Codable, CaseIterable, Sendable { case tabata, hiit, emom, amrap, rounds, custom }
public enum PhaseType: String, Codable, Sendable { case warmup, work, rest, setRest, cooldown }
public enum TimerStatus: String, Codable, Sendable { case idle, running, paused, completed, stopped }

public struct WorkoutTemplate: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var type: TimerType
    public var warmup: Int
    public var work: Int
    public var rest: Int
    public var rounds: Int
    public var sets: Int
    public var setRest: Int
    public var cooldown: Int

    public init(id: String, name: String, type: TimerType, warmup: Int = 0, work: Int, rest: Int = 0,
                rounds: Int = 1, sets: Int = 1, setRest: Int = 0, cooldown: Int = 0) {
        self.id = id; self.name = name; self.type = type; self.warmup = warmup; self.work = work
        self.rest = rest; self.rounds = rounds; self.sets = sets; self.setRest = setRest; self.cooldown = cooldown
    }

    public static let presets: [WorkoutTemplate] = [
        .init(id: "tabata", name: "Tabata", type: .tabata, work: 20, rest: 10, rounds: 8, setRest: 60),
        .init(id: "hiit", name: "HIIT", type: .hiit, warmup: 30, work: 40, rest: 20, rounds: 10, setRest: 60, cooldown: 30),
        .init(id: "emom", name: "EMOM", type: .emom, work: 60, rounds: 12),
        .init(id: "amrap", name: "AMRAP", type: .amrap, work: 1200),
    ]
}

public struct Phase: Equatable, Sendable {
    public let type: PhaseType
    public let duration: Int
    public let round: Int
    public let rounds: Int
    public let set: Int
    public let sets: Int
    public let offsetMs: Int64
    public var endMs: Int64 { offsetMs + Int64(duration) * 1000 }
}

public struct TimerState: Codable, Equatable, Sendable {
    public var status: TimerStatus = .idle
    public var startedAt: Int64?
    public var pausedAt: Int64?
    public var pausedMs: Int64 = 0
    public var offsetMs: Int64 = 0
    public var manualRounds: Int = 0
    public init() {}
}

public struct TimerView: Equatable, Sendable {
    public let status: TimerStatus
    public let phase: Phase?
    public let nextPhase: Phase?
    public let phaseIndex: Int
    public let remainingMs: Int64
    public let elapsedMs: Int64
    public let activeMs: Int64
    public let totalMs: Int64
    public let progress: Double
    public let phaseProgress: Double
    public let manualRounds: Int
}

public enum TemplateError: Error, Equatable { case nameRequired, nameTooLong, invalidDuration, invalidCount }

public enum Limits {
    public static let maxDuration = 86_400
    public static let maxCount = 100
    public static let maxNameLength = 60
}

public func validate(_ t: WorkoutTemplate) throws {
    if t.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw TemplateError.nameRequired }
    if t.name.count > Limits.maxNameLength { throw TemplateError.nameTooLong }
    for d in [t.warmup, t.work, t.rest, t.setRest, t.cooldown] where d < 0 || d > Limits.maxDuration {
        throw TemplateError.invalidDuration
    }
    if t.work < 1 { throw TemplateError.invalidDuration }
    for c in [t.rounds, t.sets] where c < 1 || c > Limits.maxCount { throw TemplateError.invalidCount }
}

public func compile(_ t: WorkoutTemplate) throws -> [Phase] {
    try validate(t)
    var phases: [Phase] = []
    var offset: Int64 = 0
    let rounds = t.type == .amrap ? 1 : t.rounds
    let sets = (t.type == .amrap || t.type == .emom) ? 1 : t.sets
    func add(_ type: PhaseType, _ duration: Int, _ round: Int, _ set: Int) {
        guard duration > 0 else { return }
        phases.append(Phase(type: type, duration: duration, round: round, rounds: rounds, set: set, sets: sets, offsetMs: offset))
        offset += Int64(duration) * 1000
    }
    add(.warmup, t.warmup, 1, 1)
    switch t.type {
    case .amrap: add(.work, t.work, 1, 1)
    case .emom: for r in 1...t.rounds { add(.work, 60, r, 1) }
    default:
        for s in 1...t.sets {
            for r in 1...t.rounds { add(.work, t.work, r, s); add(.rest, t.rest, r, s) }
            if s < t.sets { add(.setRest, t.setRest, t.rounds, s) }
        }
    }
    add(.cooldown, t.cooldown, rounds, sets)
    return phases
}

public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

public final class TimerEngine {
    public let template: WorkoutTemplate
    public let phases: [Phase]
    public private(set) var state: TimerState
    private let totalMs: Int64
    private static let backRestartThresholdMs: Int64 = 3_000

    public init(template: WorkoutTemplate, snapshot: TimerState? = nil) throws {
        self.template = template
        self.phases = try compile(template)
        self.totalMs = phases.reduce(0) { $0 + Int64($1.duration) * 1000 }
        self.state = snapshot ?? TimerState()
    }

    public func view(_ now: Int64 = nowMs()) -> TimerView {
        let clock = state.pausedAt ?? now
        let active: Int64 = state.startedAt.map { max(0, clock - $0 - state.pausedMs) } ?? 0
        let elapsed = min(totalMs, max(0, active + state.offsetMs))
        let complete = elapsed >= totalMs && state.status != .idle && state.status != .stopped
        let idx = complete ? phases.count : (phases.firstIndex { elapsed < $0.endMs } ?? phases.count)
        let phase = complete || idx >= phases.count ? nil : phases[idx]
        return TimerView(
            status: complete ? .completed : state.status,
            phase: phase,
            nextPhase: idx + 1 < phases.count ? phases[idx + 1] : nil,
            phaseIndex: idx,
            remainingMs: phase.map { max(0, $0.endMs - elapsed) } ?? 0,
            elapsedMs: elapsed, activeMs: active, totalMs: totalMs,
            progress: totalMs == 0 ? 0 : Double(elapsed) / Double(totalMs),
            phaseProgress: phase.map { min(1, Double(elapsed - $0.offsetMs) / Double($0.duration * 1000)) } ?? 1,
            manualRounds: state.manualRounds)
    }

    public func start(_ now: Int64 = nowMs()) {
        state = TimerState(); state.status = .running; state.startedAt = now
    }

    public func pause(_ now: Int64 = nowMs()) {
        guard view(now).status == .running else { return }
        state.status = .paused; state.pausedAt = now
    }

    public func resume(_ now: Int64 = nowMs()) {
        guard state.status == .paused else { return }
        state.pausedMs += max(0, now - (state.pausedAt ?? now))
        state.pausedAt = nil; state.status = .running
    }

    public func skip(_ now: Int64 = nowMs()) {
        let v = view(now); seek(v.nextPhase?.offsetMs ?? v.totalMs, now)
    }

    public func back(_ now: Int64 = nowMs()) {
        let v = view(now)
        guard let current = v.phase else { return }
        let into = v.elapsedMs - current.offsetMs
        let target = (into > Self.backRestartThresholdMs || v.phaseIndex == 0) ? current.offsetMs : phases[v.phaseIndex - 1].offsetMs
        seek(target, now)
    }

    public func stop(_ now: Int64 = nowMs()) {
        state.pausedAt = state.pausedAt ?? now; state.status = .stopped
    }

    public func tapRound(_ now: Int64 = nowMs()) {
        let v = view(now)
        if template.type == .amrap, v.status == .running, v.phase?.type == .work { state.manualRounds += 1 }
    }

    private func seek(_ target: Int64, _ now: Int64) {
        let v = view(now)
        guard v.status == .running || v.status == .paused else { return }
        state.offsetMs = target - v.activeMs
    }
}
