package app.tempo.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.tempo.core.HeartRate
import app.tempo.core.TimerEngine
import app.tempo.core.TimerState
import app.tempo.core.TimerStatus
import app.tempo.core.TimerType
import app.tempo.core.TimerView
import app.tempo.core.WorkoutTemplate
import app.tempo.wear.sync.DataLayerSync
import app.tempo.wear.sync.WireSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service that owns the running workout so it keeps going with the screen off.
 * Timing comes from [TimerEngine] timestamps; the 200 ms loop only refreshes UI/haptics.
 * Uses Health Services ExerciseClient for live heart rate and calories (no raw sensor access).
 */
class WorkoutService : LifecycleService() {
    data class UiState(val view: TimerView? = null, val heartRate: Int? = null, val calories: Double = 0.0, val template: WorkoutTemplate? = null)

    companion object {
        private val _ui = MutableStateFlow(UiState())
        val ui: StateFlow<UiState> = _ui
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 42
        const val EXTRA_TEMPLATE_ID = "templateId"

        fun start(ctx: Context, templateId: String) =
            ctx.startForegroundService(Intent(ctx, WorkoutService::class.java).putExtra(EXTRA_TEMPLATE_ID, templateId))

        var instance: WorkoutService? = null
            private set
    }

    private var engine: TimerEngine? = null
    private var loop: Job? = null
    private var lastPhase = -1
    private val hr = mutableListOf<Int>()
    private var exerciseActive = false
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        val restored = restoreSnapshot()
        if (!restored) {
            val id = intent?.getStringExtra(EXTRA_TEMPLATE_ID)
            val template = DataLayerSync.templates.value.firstOrNull { it.id == id } ?: run { stopSelf(); return START_NOT_STICKY }
            engine = TimerEngine(template).also { it.start() }
            hr.clear()
            persist()
        }
        startExercise()
        startLoop()
        return START_STICKY
    }

    fun pauseResume() {
        val e = engine ?: return
        lifecycleScope.launch {
            if (e.state.status == TimerStatus.RUNNING) { e.pause(); if (exerciseActive) runCatching { exerciseClient.pauseExerciseAsync().await() } }
            else { e.resume(); if (exerciseActive) runCatching { exerciseClient.resumeExerciseAsync().await() } }
            persist(); publish()
        }
    }
    fun skip() { engine?.skip(); persist(); publish() }
    fun back() { engine?.back(); persist(); publish() }
    fun tapRound() { engine?.tapRound(); persist(); publish() }
    fun stopWorkout() { engine?.stop(); finish() }

    private fun startLoop() {
        loop?.cancel()
        loop = lifecycleScope.launch {
            while (isActive) {
                publish()
                if (engine?.view()?.status == TimerStatus.COMPLETED) { finish(); break }
                delay(200)
            }
        }
    }

    private fun publish() {
        val e = engine ?: return
        val v = e.view()
        if (v.phaseIndex != lastPhase) { if (lastPhase >= 0) buzz(v.phase?.type?.name == "WORK"); lastPhase = v.phaseIndex }
        _ui.value = _ui.value.copy(view = v, template = e.template)
    }

    private fun buzz(long: Boolean) {
        val vib = getSystemService(Vibrator::class.java) ?: return
        vib.vibrate(VibrationEffect.createOneShot(if (long) 400 else 150, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun startExercise() {
        if (checkSelfPermission(android.Manifest.permission.BODY_SENSORS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            runCatching {
                val caps = exerciseClient.getCapabilitiesAsync().await()
                val supported = caps.getExerciseTypeCapabilities(ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING).supportedDataTypes
                val types = setOf(DataType.HEART_RATE_BPM, DataType.CALORIES_TOTAL).intersect(supported)
                exerciseClient.setUpdateCallback(callback)
                exerciseClient.startExerciseAsync(
                    ExerciseConfig.builder(ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING).setDataTypes(types).build()
                ).await()
                exerciseActive = true
            } // On failure (no sensor, permission revoked) the timer keeps running without HR.
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt()
                ?.takeIf(HeartRate::isValid)?.let { bpm -> hr += bpm; _ui.value = _ui.value.copy(heartRate = bpm) }
            update.latestMetrics.getData(DataType.CALORIES_TOTAL)?.total?.let { _ui.value = _ui.value.copy(calories = it) }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private fun finish() {
        loop?.cancel()
        val e = engine ?: return stopSelf()
        val v = e.view()
        val session = WireSession(
            id = UUID.randomUUID().toString(), templateId = e.template.id, name = e.template.name,
            type = e.template.type.name.lowercase(), startedAt = e.state.startedAt ?: System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(), duration = (v.activeMs / 1000).toInt(),
            rounds = if (e.template.type == TimerType.AMRAP) v.manualRounds else (v.phase?.round ?: e.template.rounds),
            averageHr = hr.takeIf { it.isNotEmpty() }?.average()?.toInt(), maxHr = hr.maxOrNull(),
            calories = _ui.value.calories,
        )
        engine = null
        getSharedPreferences("tempo_active", MODE_PRIVATE).edit().clear().apply()
        _ui.value = UiState()
        lifecycleScope.launch {
            if (exerciseActive) runCatching { exerciseClient.endExerciseAsync().await() }
            runCatching { DataLayerSync.sendSession(this@WorkoutService, session) }
            ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Snapshot persistence: the workout survives the process being killed by the system.
    private fun persist() {
        val e = engine ?: return
        val s = e.state
        getSharedPreferences("tempo_active", MODE_PRIVATE).edit()
            .putString("templateId", e.template.id).putString("status", s.status.name)
            .putLong("startedAt", s.startedAt ?: -1).putLong("pausedAt", s.pausedAt ?: -1)
            .putLong("pausedMs", s.pausedMs).putLong("offsetMs", s.offsetMs).putInt("manualRounds", s.manualRounds)
            .apply()
    }

    private fun restoreSnapshot(): Boolean {
        if (engine != null) return true
        val p = getSharedPreferences("tempo_active", MODE_PRIVATE)
        val id = p.getString("templateId", null) ?: return false
        val t = DataLayerSync.templates.value.firstOrNull { it.id == id } ?: return false
        val status = runCatching { TimerStatus.valueOf(p.getString("status", "")!!) }.getOrNull() ?: return false
        val state = TimerState(status, p.getLong("startedAt", -1).takeIf { it > 0 }, p.getLong("pausedAt", -1).takeIf { it > 0 },
            p.getLong("pausedMs", 0), p.getLong("offsetMs", 0), p.getInt("manualRounds", 0))
        engine = TimerEngine(t, state)
        return true
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_WORKOUT)
        OngoingActivity.Builder(this, NOTIF_ID, builder)
            .setStaticIcon(android.R.drawable.ic_media_play).setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.ongoing_status)).build())
            .build().apply(this)
        ServiceCompat.startForeground(this, NOTIF_ID, builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }
}
