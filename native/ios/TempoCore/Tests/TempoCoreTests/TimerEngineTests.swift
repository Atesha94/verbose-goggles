import XCTest
@testable import TempoCore

final class TimerEngineTests: XCTestCase {
    let t0: Int64 = 1_700_000_000_000
    let tabata = WorkoutTemplate.presets[0]

    func testTabataCompiles() throws {
        let p = try compile(tabata)
        XCTAssertEqual(p.count, 16)
        XCTAssertEqual(p.reduce(0) { $0 + $1.duration }, 240)
    }

    func testCustomWithSets() throws {
        let t = WorkoutTemplate(id: "c", name: "C", type: .custom, warmup: 60, work: 30, rest: 15, rounds: 3, sets: 2, setRest: 90, cooldown: 120)
        let p = try compile(t)
        XCTAssertEqual(p.count, 15)
        XCTAssertEqual(p[7].type, .setRest)
    }

    func testRejectsInvalid() {
        var t = tabata; t.work = 0
        XCTAssertThrowsError(try compile(t))
        t = tabata; t.name = String(repeating: "x", count: 61)
        XCTAssertThrowsError(try compile(t))
        t = tabata; t.rounds = 0
        XCTAssertThrowsError(try compile(t))
    }

    func testWallClockDerivation() throws {
        let e = try TimerEngine(template: tabata); e.start(t0)
        let v = e.view(t0 + 25_000)
        XCTAssertEqual(v.phase?.type, .rest)
        XCTAssertEqual(v.remainingMs, 5_000)
    }

    func testPauseResume() throws {
        let e = try TimerEngine(template: tabata); e.start(t0)
        e.pause(t0 + 5_000)
        XCTAssertEqual(e.view(t0 + 60_000).remainingMs, 15_000)
        e.resume(t0 + 60_000)
        XCTAssertEqual(e.view(t0 + 65_000).remainingMs, 10_000)
    }

    func testBackBehaviour() throws {
        let e = try TimerEngine(template: tabata); e.start(t0)
        e.back(t0 + 25_000)
        XCTAssertEqual(e.view(t0 + 25_000).phaseIndex, 1)
        let f = try TimerEngine(template: tabata); f.start(t0)
        f.back(t0 + 21_000)
        XCTAssertEqual(f.view(t0 + 21_000).phaseIndex, 0)
    }

    func testSnapshotRestore() throws {
        let e = try TimerEngine(template: tabata); e.start(t0)
        let data = try JSONEncoder().encode(e.state)
        let restored = try TimerEngine(template: tabata, snapshot: JSONDecoder().decode(TimerState.self, from: data))
        let v = restored.view(t0 + 125_000)
        XCTAssertEqual(v.phaseIndex, 8)
        XCTAssertEqual(v.remainingMs, 15_000)
    }

    func testCompletes() throws {
        let e = try TimerEngine(template: tabata); e.start(t0)
        XCTAssertEqual(e.view(t0 + 240_000).status, .completed)
    }

    func testBleParser() {
        XCTAssertEqual(HeartRate.parseBleMeasurement(Data([0x00, 72])), 72)
        XCTAssertEqual(HeartRate.parseBleMeasurement(Data([0x01, 0xB4, 0x00])), 180)
        XCTAssertNil(HeartRate.parseBleMeasurement(Data([0x01, 0x50])))
        XCTAssertNil(HeartRate.parseBleMeasurement(Data()))
        XCTAssertNil(HeartRate.parseBleMeasurement(Data([0x00, 5])))
    }
}
