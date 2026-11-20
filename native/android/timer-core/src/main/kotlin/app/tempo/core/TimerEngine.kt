package app.tempo.core

/**
 * Pure-Kotlin port of the TypeScript timer engine (artifacts/tempo/core/timer.ts in the Replit app).
 * No Android dependencies, so it is shared by the Wear OS app and the phone's native module and is
 * unit-tested on the JVM.
 *
 * Timing is derived from wall-clock timestamps (epoch ms), never from tick counters, so the result is
 * correct after the process is suspended, the screen is locked or the app is killed and restored
 * from a [TimerState] snapshot.
 */
enum class TimerType { TABATA, HIIT, EMOM, AMRAP, ROUNDS, CUSTOM }

enum class PhaseType { WARMUP, WORK, REST, SET_REST, COOLDOWN }

enum class TimerStatus { IDLE, RUNNING, PAUSED, COMPLETED, STOPPED }

data class WorkoutTemplate(
    val id: String,
    val name: String,
    val type: TimerType,
    val warmup: Int = 0,
    val work: Int,
    val rest: Int = 0,
    val rounds: Int = 1,
    val sets: Int = 1,
    val setRest: Int = 0,
    val cooldown: Int = 0,
)

data class Phase(
    val type: PhaseType,
    val durationSec: Int,
    val round: Int,
    val rounds: Int,
    val set: Int,
    val sets: Int,
    val offsetMs: Long,
) {
    val endMs: Long get() = offsetMs + durationSec * 1000L
}

data class TimerState(
    val status: TimerStatus = TimerStatus.IDLE,
    val startedAt: Long? = null,
    val pausedAt: Long? = null,
    val pausedMs: Long = 0,
    val offsetMs: Long = 0,
    val manualRounds: Int = 0,
)

data class TimerView(
    val status: TimerStatus,
    val phase: Phase?,
    val nextPhase: Phase?,
    val phaseIndex: Int,
    val remainingMs: Long,
    val elapsedMs: Long,
    val activeMs: Long,
    val totalMs: Long,
    val progress: Double,
    val phaseProgress: Double,
    val manualRounds: Int,
)

class InvalidTemplateException(message: String) : IllegalArgumentException(message)

object Presets {
    val all = listOf(
        WorkoutTemplate("tabata", "Tabata", TimerType.TABATA, work = 20, rest = 10, rounds = 8, setRest = 60),
        WorkoutTemplate("hiit", "HIIT", TimerType.HIIT, warmup = 30, work = 40, rest = 20, rounds = 10, setRest = 60, cooldown = 30),
        WorkoutTemplate("emom", "EMOM", TimerType.EMOM, work = 60, rounds = 12),
        WorkoutTemplate("amrap", "AMRAP", TimerType.AMRAP, work = 1200),
    )
}

const val MAX_DURATION_SEC = 86_400
const val MAX_COUNT = 100
const val MAX_NAME_LENGTH = 60
private const val BACK_RESTART_THRESHOLD_MS = 3_000L

fun validateTemplate(t: WorkoutTemplate) {
    if (t.name.isBlank()) throw InvalidTemplateException("nameRequired")
    if (t.name.length > MAX_NAME_LENGTH) throw InvalidTemplateException("nameTooLong")
    listOf(t.warmup, t.work, t.rest, t.setRest, t.cooldown).forEach {
        if (it < 0 || it > MAX_DURATION_SEC) throw InvalidTemplateException("invalidDuration")
    }
    if (t.work < 1) throw InvalidTemplateException("invalidDuration")
    listOf(t.rounds, t.sets).forEach {
        if (it < 1 || it > MAX_COUNT) throw InvalidTemplateException("invalidCount")
    }
}

fun compileWorkout(t: WorkoutTemplate): List<Phase> {
    validateTemplate(t)
    val phases = mutableListOf<Phase>()
    var offset = 0L
    val rounds = if (t.type == TimerType.AMRAP) 1 else t.rounds
    val sets = if (t.type == TimerType.AMRAP || t.type == TimerType.EMOM) 1 else t.sets
    fun add(type: PhaseType, duration: Int, round: Int, set: Int) {
        if (duration <= 0) return
        phases += Phase(type, duration, round, rounds, set, sets, offset)
        offset += duration * 1000L
    }
    add(PhaseType.WARMUP, t.warmup, 1, 1)
    when (t.type) {
        TimerType.AMRAP -> add(PhaseType.WORK, t.work, 1, 1)
        TimerType.EMOM -> for (r in 1..t.rounds) add(PhaseType.WORK, 60, r, 1)
        else -> for (s in 1..t.sets) {
            for (r in 1..t.rounds) {
                add(PhaseType.WORK, t.work, r, s)
                add(PhaseType.REST, t.rest, r, s)
            }
            if (s < t.sets) add(PhaseType.SET_REST, t.setRest, t.rounds, s)
        }
    }
    add(PhaseType.COOLDOWN, t.cooldown, rounds, sets)
    return phases
}

fun totalDurationSec(t: WorkoutTemplate): Int = compileWorkout(t).sumOf { it.durationSec }

class TimerEngine(val template: WorkoutTemplate, snapshot: TimerState? = null) {
    val phases: List<Phase> = compileWorkout(template)
    private val totalMs: Long = phases.sumOf { it.durationSec * 1000L }
    var state: TimerState = snapshot ?: TimerState()
        private set

    fun view(now: Long = System.currentTimeMillis()): TimerView {
        val clock = state.pausedAt ?: now
        val started = state.startedAt
        val activeMs = if (started == null) 0L else maxOf(0L, clock - started - state.pausedMs)
        val elapsed = (activeMs + state.offsetMs).coerceIn(0L, totalMs)
        val complete = elapsed >= totalMs && state.status != TimerStatus.IDLE && state.status != TimerStatus.STOPPED
        val idx = if (complete) phases.size else phases.indexOfFirst { elapsed < it.endMs }.let { if (it < 0) phases.size else it }
        val phase = if (complete) null else phases.getOrNull(idx)
        return TimerView(
            status = if (complete) TimerStatus.COMPLETED else state.status,
            phase = phase,
            nextPhase = phases.getOrNull(idx + 1),
            phaseIndex = idx,
            remainingMs = phase?.let { maxOf(0L, it.endMs - elapsed) } ?: 0L,
            elapsedMs = elapsed,
            activeMs = activeMs,
            totalMs = totalMs,
            progress = if (totalMs == 0L) 0.0 else elapsed.toDouble() / totalMs,
            phaseProgress = phase?.let { minOf(1.0, (elapsed - it.offsetMs).toDouble() / (it.durationSec * 1000.0)) } ?: 1.0,
            manualRounds = state.manualRounds,
        )
    }

    fun start(now: Long = System.currentTimeMillis()) {
        state = TimerState(status = TimerStatus.RUNNING, startedAt = now)
    }

    fun pause(now: Long = System.currentTimeMillis()) {
        if (view(now).status == TimerStatus.RUNNING) state = state.copy(status = TimerStatus.PAUSED, pausedAt = now)
    }

    fun resume(now: Long = System.currentTimeMillis()) {
        if (state.status != TimerStatus.PAUSED) return
        val pausedFor = maxOf(0L, now - (state.pausedAt ?: now))
        state = state.copy(status = TimerStatus.RUNNING, pausedAt = null, pausedMs = state.pausedMs + pausedFor)
    }

    fun skip(now: Long = System.currentTimeMillis()) {
        val v = view(now)
        seek(v.nextPhase?.offsetMs ?: v.totalMs, now)
    }

    /** Restarts the current phase, or goes to the previous one if pressed within the first 3 seconds. */
    fun back(now: Long = System.currentTimeMillis()) {
        val v = view(now)
        val current = v.phase ?: return
        val intoPhase = v.elapsedMs - current.offsetMs
        val target = if (intoPhase > BACK_RESTART_THRESHOLD_MS || v.phaseIndex == 0) current.offsetMs
        else phases[v.phaseIndex - 1].offsetMs
        seek(target, now)
    }

    fun stop(now: Long = System.currentTimeMillis()) {
        state = state.copy(status = TimerStatus.STOPPED, pausedAt = state.pausedAt ?: now)
    }

    fun tapRound(now: Long = System.currentTimeMillis()) {
        val v = view(now)
        if (template.type == TimerType.AMRAP && v.status == TimerStatus.RUNNING && v.phase?.type == PhaseType.WORK) {
            state = state.copy(manualRounds = state.manualRounds + 1)
        }
    }

    private fun seek(targetMs: Long, now: Long) {
        val v = view(now)
        if (v.status != TimerStatus.RUNNING && v.status != TimerStatus.PAUSED) return
        state = state.copy(offsetMs = targetMs - v.activeMs)
    }
}
