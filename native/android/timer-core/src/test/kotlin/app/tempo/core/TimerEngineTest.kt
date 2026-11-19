package app.tempo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerEngineTest {
    private val t0 = 1_700_000_000_000L
    private val tabata = Presets.all.first { it.type == TimerType.TABATA }

    @Test fun tabataCompilesTo16Phases() {
        val phases = compileWorkout(tabata)
        assertEquals(16, phases.size)
        assertEquals(240, totalDurationSec(tabata))
        assertEquals(PhaseType.WORK, phases[0].type)
        assertEquals(PhaseType.REST, phases[1].type)
        assertEquals(8, phases.last().round)
    }

    @Test fun emomIsOneMinuteBlocks() {
        val phases = compileWorkout(Presets.all.first { it.type == TimerType.EMOM })
        assertEquals(12, phases.size)
        assertTrue(phases.all { it.durationSec == 60 && it.sets == 1 })
    }

    @Test fun amrapIsSingleBlock() {
        val phases = compileWorkout(Presets.all.first { it.type == TimerType.AMRAP })
        assertEquals(1, phases.size)
        assertEquals(1200, phases[0].durationSec)
    }

    @Test fun customWithSetsWarmupAndCooldown() {
        val t = WorkoutTemplate("c", "Custom", TimerType.CUSTOM, warmup = 60, work = 30, rest = 15, rounds = 3, sets = 2, setRest = 90, cooldown = 120)
        val phases = compileWorkout(t)
        // warmup + 2×(3 work + 3 rest) + 1 setRest + cooldown
        assertEquals(1 + 12 + 1 + 1, phases.size)
        assertEquals(60 + 2 * 3 * 45 + 90 + 120, totalDurationSec(t))
        assertEquals(PhaseType.SET_REST, phases[7].type)
        assertEquals(2, phases[8].set)
    }

    @Test fun zeroDurationPhasesAreSkipped() {
        val t = WorkoutTemplate("r", "Rounds", TimerType.ROUNDS, work = 10, rest = 0, rounds = 3)
        assertTrue(compileWorkout(t).all { it.type == PhaseType.WORK })
    }

    @Test fun invalidTemplatesAreRejected() {
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(name = " ")) }
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(name = "x".repeat(61))) }
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(work = 0)) }
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(rest = -1)) }
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(rounds = 101)) }
        assertFailsWith<InvalidTemplateException> { compileWorkout(tabata.copy(warmup = MAX_DURATION_SEC + 1)) }
    }

    @Test fun idleBeforeStart() {
        val e = TimerEngine(tabata)
        val v = e.view(t0)
        assertEquals(TimerStatus.IDLE, v.status)
        assertEquals(0, v.elapsedMs)
    }

    @Test fun derivesPhaseFromWallClock() {
        val e = TimerEngine(tabata).apply { start(t0) }
        val v = e.view(t0 + 25_000)
        assertEquals(PhaseType.REST, v.phase?.type)
        assertEquals(5_000, v.remainingMs)
        assertEquals(1, v.phase?.round)
    }

    @Test fun pauseFreezesAndResumeContinues() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.pause(t0 + 5_000)
        assertEquals(15_000, e.view(t0 + 60_000).remainingMs)
        e.resume(t0 + 60_000)
        assertEquals(10_000, e.view(t0 + 65_000).remainingMs)
        assertEquals(TimerStatus.RUNNING, e.view(t0 + 65_000).status)
    }

    @Test fun skipJumpsToNextPhase() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.skip(t0 + 2_000)
        val v = e.view(t0 + 2_000)
        assertEquals(PhaseType.REST, v.phase?.type)
        assertEquals(10_000, v.remainingMs)
    }

    @Test fun skipWhilePausedStaysPaused() {
        val e = TimerEngine(tabata).apply { start(t0); pause(t0 + 1_000) }
        e.skip(t0 + 1_000)
        val v = e.view(t0 + 50_000)
        assertEquals(TimerStatus.PAUSED, v.status)
        assertEquals(1, v.phaseIndex)
        assertEquals(10_000, v.remainingMs)
    }

    @Test fun backRestartsCurrentPhaseAfterThreeSeconds() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.back(t0 + 25_000) // 5 s into the first rest
        val v = e.view(t0 + 25_000)
        assertEquals(1, v.phaseIndex)
        assertEquals(10_000, v.remainingMs)
    }

    @Test fun backGoesToPreviousPhaseWithinThreeSeconds() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.back(t0 + 21_000) // 1 s into the first rest
        val v = e.view(t0 + 21_000)
        assertEquals(0, v.phaseIndex)
        assertEquals(20_000, v.remainingMs)
    }

    @Test fun completesAtEnd() {
        val e = TimerEngine(tabata).apply { start(t0) }
        val v = e.view(t0 + 240_000)
        assertEquals(TimerStatus.COMPLETED, v.status)
        assertNull(v.phase)
        assertEquals(1.0, v.progress)
    }

    @Test fun skippingLastPhaseCompletes() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.skip(t0 + 235_000)
        assertEquals(TimerStatus.COMPLETED, e.view(t0 + 235_000).status)
    }

    @Test fun survivesLongBackgroundGapAndSnapshotRestore() {
        val e = TimerEngine(tabata).apply { start(t0) }
        val snapshot = e.state
        // Process killed; restored 2 minutes later from the persisted snapshot.
        val restored = TimerEngine(tabata, snapshot)
        val v = restored.view(t0 + 125_000)
        // 125 s = 4 full 30 s cycles + 5 s into the 5th work phase.
        assertEquals(8, v.phaseIndex)
        assertEquals(5, v.phase?.round)
        assertEquals(15_000, v.remainingMs)
    }

    @Test fun stopFreezesElapsed() {
        val e = TimerEngine(tabata).apply { start(t0) }
        e.stop(t0 + 30_000)
        val v = e.view(t0 + 999_000)
        assertEquals(TimerStatus.STOPPED, v.status)
        assertEquals(30_000, v.elapsedMs)
    }

    @Test fun amrapRoundTapsOnlyWhileRunning() {
        val e = TimerEngine(Presets.all.first { it.type == TimerType.AMRAP }).apply { start(t0) }
        e.tapRound(t0 + 1_000); e.tapRound(t0 + 2_000)
        e.pause(t0 + 3_000); e.tapRound(t0 + 4_000)
        assertEquals(2, e.view(t0 + 5_000).manualRounds)
    }
}
