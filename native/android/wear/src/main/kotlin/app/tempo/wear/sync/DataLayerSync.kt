package app.tempo.wear.sync

import android.content.Context
import app.tempo.core.Presets
import app.tempo.core.WorkoutTemplate
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Phone ↔ watch sync over the Wearable Data Layer. Google Play services only delivers Data Layer
 * traffic between apps signed with the same key and sharing the same applicationId, which gives us
 * peer authentication for free; payloads are still validated in [SyncCodec.decode].
 */
object DataLayerSync {
    private const val PREFS = "tempo_sync"
    private const val KEY_TEMPLATES = "templates_v1"

    private val _templates = MutableStateFlow(Presets.all)
    val templates: StateFlow<List<WorkoutTemplate>> = _templates
    private val _commands = MutableSharedFlow<WireCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<WireCommand> = _commands

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEMPLATES, null) ?: return
        SyncCodec.decode(raw.toByteArray())?.templates?.mapNotNull { it.toCore() }?.takeIf { it.isNotEmpty() }?.let { _templates.value = it }
    }

    internal fun onTemplates(context: Context, bytes: ByteArray) {
        val list = SyncCodec.decode(bytes)?.templates?.mapNotNull { it.toCore() } ?: return
        if (list.isEmpty()) return
        _templates.value = list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEMPLATES, bytes.decodeToString()).apply()
    }

    internal fun onCommand(bytes: ByteArray) {
        SyncCodec.decode(bytes)?.command?.let { _commands.tryEmit(it) }
    }

    /** Queued, durable delivery of a finished session to the phone (survives disconnects). */
    suspend fun sendSession(context: Context, session: WireSession) {
        val req = PutDataMapRequest.create("$PATH_SESSION/${session.id}").apply {
            dataMap.putByteArray("payload", SyncCodec.encode(SyncEnvelope(SYNC_PROTOCOL_VERSION, "session", session = session)))
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}

class TempoListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH_TEMPLATES }.forEach {
            DataMapItem.fromDataItem(it.dataItem).dataMap.getByteArray("payload")?.let { b -> DataLayerSync.onTemplates(this, b) }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_COMMAND) DataLayerSync.onCommand(event.data)
    }
}
