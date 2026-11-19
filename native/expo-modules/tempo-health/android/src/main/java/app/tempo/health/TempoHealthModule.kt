package app.tempo.health

import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneOffset

/**
 * Android adapter: Health Connect (primary; Samsung Health, Fitbit, Garmin etc. sync through it)
 * plus the Wearable Data Layer for the Wear OS companion. Google Fit APIs are deprecated and not used.
 */
class TempoHealthModule : Module(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var permissionLauncher: ActivityResultLauncher<Set<String>>? = null
    private var pendingPermission: Promise? = null

    // Minimum set: write exercise session + calories + HR; read HR + resting HR.
    private val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    )

    private val context get() = appContext.reactContext ?: throw CodedException("NoContext", "React context unavailable", null)
    private fun client() = HealthConnectClient.getOrCreate(context)

    override fun definition() = ModuleDefinition {
        Name("TempoHealth")
        Events("onWatchMessage")

        OnCreate { Wearable.getDataClient(context).addListener(this@TempoHealthModule) }
        OnDestroy { runCatching { Wearable.getDataClient(context).removeListener(this@TempoHealthModule) } }

        OnActivityEntersForeground {
            val activity = appContext.currentActivity as? androidx.activity.ComponentActivity ?: return@OnActivityEntersForeground
            if (permissionLauncher == null) {
                permissionLauncher = activity.activityResultRegistry.register(
                    "tempo-health-permissions", PermissionController.createRequestPermissionResultContract()
                ) { granted -> pendingPermission?.resolve(granted.containsAll(permissions)); pendingPermission = null }
            }
        }

        AsyncFunction("isAvailable") {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }

        AsyncFunction("requestPermissions") { promise: Promise ->
            val launcher = permissionLauncher
            if (launcher == null || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                promise.resolve(false); return@AsyncFunction
            }
            pendingPermission = promise
            launcher.launch(permissions)
        }

        AsyncFunction("getPermissionStatus") { promise: Promise ->
            scope.launch {
                runCatching { client().permissionController.getGrantedPermissions() }
                    .onSuccess { promise.resolve(if (it.containsAll(permissions)) "granted" else if (it.isEmpty()) "notDetermined" else "partial") }
                    .onFailure { promise.resolve("unavailable") }
            }
        }

        AsyncFunction("saveWorkout") { w: WorkoutRecord, promise: Promise ->
            scope.launch {
                try {
                    w.validate()
                    val start = Instant.ofEpochMilli(w.startedAt.toLong())
                    val end = Instant.ofEpochMilli(w.completedAt.toLong())
                    val offset = ZoneOffset.systemDefault().rules.getOffset(start)
                    // clientRecordId makes the write idempotent: retrying a queued sync never duplicates.
                    val meta = { suffix: String -> Metadata.manualEntry(clientRecordId = "tempo-${w.id}-$suffix") }
                    val records = buildList {
                        add(ExerciseSessionRecord(start, offset, end, offset, meta("session"),
                            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, title = w.name.take(60)))
                        if (w.calories > 0) add(ActiveCaloriesBurnedRecord(start, offset, end, offset, Energy.kilocalories(w.calories), meta("kcal")))
                        val hr = w.hrSamples.asSequence().filter { it.bpm in 25..250 }
                            .map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.timestamp.toLong()), it.bpm.toLong()) }
                            .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }.take(20_000).toList()
                        if (hr.isNotEmpty()) add(HeartRateRecord(start, offset, end, offset, hr, meta("hr")))
                    }
                    val ids = client().insertRecords(records).recordIdsList
                    promise.resolve(ids.firstOrNull() ?: "")
                } catch (e: SecurityException) {
                    promise.reject(CodedException("PermissionDenied", "Health Connect permission denied", e))
                } catch (e: Exception) {
                    promise.reject(CodedException("SaveFailed", e.message ?: "save failed", e))
                }
            }
        }

        AsyncFunction("readHeartRate") { fromMs: Double, toMs: Double, promise: Promise ->
            scope.launch {
                runCatching {
                    client().readRecords(ReadRecordsRequest(HeartRateRecord::class,
                        TimeRangeFilter.between(Instant.ofEpochMilli(fromMs.toLong()), Instant.ofEpochMilli(toMs.toLong())), pageSize = 1000))
                        .records.flatMap { r -> r.samples.map { mapOf("timestamp" to it.time.toEpochMilli().toDouble(), "bpm" to it.beatsPerMinute.toDouble()) } }
                }.onSuccess(promise::resolve).onFailure { promise.resolve(emptyList<Map<String, Double>>()) }
            }
        }

        AsyncFunction("readRestingHeartRate") { promise: Promise ->
            scope.launch {
                runCatching {
                    val now = Instant.now()
                    client().readRecords(ReadRecordsRequest(RestingHeartRateRecord::class,
                        TimeRangeFilter.between(now.minusSeconds(7 * 86_400), now), ascendingOrder = false, pageSize = 1))
                        .records.firstOrNull()?.beatsPerMinute?.toDouble()
                }.onSuccess(promise::resolve).onFailure { promise.resolve(null) }
            }
        }

        AsyncFunction("sendToWatch") { payload: String, mode: String ->
            val bytes = payload.toByteArray()
            if (bytes.size > 512 * 1024) return@AsyncFunction
            scope.launch {
                runCatching {
                    if (mode == "message") {
                        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/tempo/command", bytes).await() }
                    } else {
                        val req = PutDataMapRequest.create("/tempo/templates").apply { dataMap.putByteArray("payload", bytes) }
                            .asPutDataRequest().setUrgent()
                        Wearable.getDataClient(context).putDataItem(req).await()
                    }
                }
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith("/tempo/session/") == true }.forEach { ev ->
            val bytes = DataMapItem.fromDataItem(ev.dataItem).dataMap.getByteArray("payload") ?: return@forEach
            if (bytes.size <= 512 * 1024) sendEvent("onWatchMessage", mapOf("payload" to bytes.decodeToString()))
            // Remove the item once delivered so health data does not linger in the Data Layer.
            scope.launch { runCatching { Wearable.getDataClient(context).deleteDataItems(ev.dataItem.uri).await() } }
        }
    }
}

class HrSample : Record {
    @Field var timestamp: Double = 0.0
    @Field var bpm: Int = 0
}

class WorkoutRecord : Record {
    @Field var id: String = ""
    @Field var name: String = ""
    @Field var startedAt: Double = 0.0
    @Field var completedAt: Double = 0.0
    @Field var calories: Double = 0.0
    @Field var hrSamples: List<HrSample> = emptyList()

    fun validate() {
        val now = System.currentTimeMillis()
        require(id.isNotBlank() && id.length <= 64) { "invalid id" }
        require(startedAt > 0 && completedAt > startedAt && completedAt <= now + 60_000) { "invalid time range" }
        require(completedAt - startedAt <= 86_400_000) { "workout too long" }
        require(calories in 0.0..20_000.0) { "invalid calories" }
    }
}
