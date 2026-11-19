package app.tempo.wear.sync

import app.tempo.core.TimerType
import app.tempo.core.WorkoutTemplate
import app.tempo.core.validateTemplate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format shared with the phone and with watchOS (native/SYNC_PROTOCOL.md). */
const val SYNC_PROTOCOL_VERSION = 1
const val PATH_TEMPLATES = "/tempo/templates"
const val PATH_SESSION = "/tempo/session"
const val PATH_COMMAND = "/tempo/command"
private const val MAX_PAYLOAD_BYTES = 512 * 1024
private const val MAX_TEMPLATES = 200

@Serializable
data class WireTemplate(
    val id: String, val name: String, val type: String,
    val warmup: Int = 0, val work: Int, val rest: Int = 0, val rounds: Int = 1,
    val sets: Int = 1, val setRest: Int = 0, val cooldown: Int = 0,
) {
    fun toCore(): WorkoutTemplate? = runCatching {
        WorkoutTemplate(id, name, TimerType.valueOf(type.uppercase()), warmup, work, rest, rounds, sets, setRest, cooldown)
            .also(::validateTemplate)
    }.getOrNull()

    companion object {
        fun from(t: WorkoutTemplate) = WireTemplate(t.id, t.name, t.type.name.lowercase(), t.warmup, t.work, t.rest, t.rounds, t.sets, t.setRest, t.cooldown)
    }
}

@Serializable
data class WireSession(
    val id: String, val templateId: String, val name: String, val type: String,
    val startedAt: Long, val completedAt: Long, val duration: Int, val rounds: Int,
    val averageHr: Int? = null, val maxHr: Int? = null, val calories: Double, val source: String = "wear",
)

@Serializable
data class WireCommand(val action: String, val templateId: String? = null)

@Serializable
data class SyncEnvelope(
    val v: Int,
    val kind: String,
    val templates: List<WireTemplate>? = null,
    val session: WireSession? = null,
    val command: WireCommand? = null,
)

object SyncCodec {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(env: SyncEnvelope): ByteArray = json.encodeToString(SyncEnvelope.serializer(), env).toByteArray()

    /** Treats every incoming payload as untrusted: size-capped, version-checked, templates validated. */
    fun decode(bytes: ByteArray): SyncEnvelope? {
        if (bytes.size > MAX_PAYLOAD_BYTES) return null
        val env = runCatching { json.decodeFromString(SyncEnvelope.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        if (env.v != SYNC_PROTOCOL_VERSION) return null
        return env.copy(templates = env.templates?.take(MAX_TEMPLATES)?.filter { it.toCore() != null })
    }
}
