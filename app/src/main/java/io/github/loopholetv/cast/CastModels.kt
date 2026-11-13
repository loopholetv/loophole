package io.github.loopholetv.cast

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

enum class CastStatus { IDLE, WATCHING, CASTING }

/**
 * What a TV says about itself on the network.
 *
 * [castId], [token] and [castStartedAtMs] are set while [status] is [CastStatus.CASTING].
 * [castStartedAtMs] is the sender's wall-clock time (epoch ms) when the cast began, so casts from
 * different TVs can be compared; never a monotonic clock.
 *
 * [seq] is the sender's announce counter: it goes up whenever anything above changes, so a late,
 * older copy of an announce can be told apart from a newer one. It should keep growing across
 * restarts of the sender (for example by starting from the wall clock).
 *
 * [setup] is the version of the setup-copy protocol the TV speaks ([SETUP_VERSION]); TVs from
 * before it don't send the field, which reads as 0.
 */
data class CastAnnounce(
    val id: String,
    val name: String,
    val port: Int,
    val status: CastStatus,
    val accepts: Boolean,
    val castId: String?,
    val token: String?,
    val castStartedAtMs: Long?,
    val seq: Long = 0,
    val v: Int = 1,
    val setup: Int = 0,
) {
    override fun toString() =
        "CastAnnounce(id=$id, name=$name, port=$port, status=$status, accepts=$accepts, castId=$castId, " +
            "token=${if (token == null) "null" else "***"}, startedAt=$castStartedAtMs, seq=$seq, v=$v, setup=$setup)"

    companion object {
        /** The setup-copy protocol this build speaks. */
        const val SETUP_VERSION = 1
    }
}

data class CastPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val status: CastStatus,
    val accepts: Boolean,
    val castId: String?,
    val token: String?,
    val castStartedAtMs: Long?,
    val lastSeenMs: Long,
    /** See [CastAnnounce.setup]; 0 for a TV that can't copy setups. */
    val setup: Int = 0,
) {
    override fun toString() =
        "CastPeer(id=$id, name=$name, host=$host, port=$port, status=$status, accepts=$accepts, castId=$castId, " +
            "token=${if (token == null) "null" else "***"}, startedAt=$castStartedAtMs, lastSeenMs=$lastSeenMs, " +
            "setup=$setup)"
}

enum class StopReason { STOPPED, LEFT_APP, EXTERNAL_PLAYER }

sealed interface CastEvent {
    data class Playing(val castId: String, val epoch: Int, val path: String, val hls: Boolean) : CastEvent

    /**
     * The casting TV is playing nothing it can share (for example, paused live, or a stream that
     * ended). Followers keep showing their last frame. [reason] is null for an ordinary pause or end,
     * and [REASON_UNSUPPORTED] while the casting TV plays a format that can't be shared.
     */
    data class Idle(val castId: String, val reason: String? = null) : CastEvent {
        companion object {
            const val REASON_UNSUPPORTED = "unsupported"
        }
    }

    data class Stopped(val castId: String, val reason: StopReason) : CastEvent

    /** [positionMs] is the casting TV's position in a seekable archive, or null (for example, live). */
    data class Heartbeat(val castId: String, val epoch: Int, val followers: Int, val positionMs: Long? = null) : CastEvent
}

object CastJson {
    private const val VERSION = 1

    fun announce(a: CastAnnounce): String = write {
        name("id").value(a.id)
        name("name").value(a.name)
        name("port").value(a.port.toLong())
        name("status").value(a.status.name)
        name("accepts").value(a.accepts)
        name("castId").value(a.castId)
        name("token").value(a.token)
        name("startedAt").value(a.castStartedAtMs)
        name("seq").value(a.seq)
        name("v").value(a.v.toLong())
        name("setup").value(a.setup.toLong())
    }

    /**
     * Null on garbage, another version, a missing or out-of-range field, or a [CastStatus.CASTING]
     * announce without its castId, token and start time.
     */
    fun announce(json: String): CastAnnounce? = parse(json) { f ->
        if (f.long("v") != VERSION.toLong()) return@parse null
        val a = CastAnnounce(
            id = f.string("id") ?: return@parse null,
            name = f.string("name") ?: return@parse null,
            port = f.long("port")?.takeIf { it in 1..65_535 }?.toInt() ?: return@parse null,
            status = f.string("status")?.let { s -> CastStatus.entries.firstOrNull { it.name == s } }
                ?: return@parse null,
            accepts = f.bool("accepts") ?: return@parse null,
            castId = f.string("castId"),
            token = f.string("token"),
            castStartedAtMs = f.long("startedAt"),
            seq = f.long("seq")?.takeIf { it >= 0 } ?: return@parse null,
            setup = f.int("setup") ?: 0,
        )
        val castComplete = a.castId != null && a.token != null && a.castStartedAtMs != null
        if (a.status == CastStatus.CASTING && !castComplete) null else a
    }

    /** One line, no newlines. */
    fun event(e: CastEvent): String = when (e) {
        is CastEvent.Playing -> write {
            name("e").value("playing")
            name("castId").value(e.castId)
            name("epoch").value(e.epoch.toLong())
            name("path").value(e.path)
            name("hls").value(e.hls)
        }
        is CastEvent.Idle -> write {
            name("e").value("idle")
            name("castId").value(e.castId)
            name("reason").value(e.reason)
        }
        is CastEvent.Stopped -> write {
            name("e").value("stopped")
            name("castId").value(e.castId)
            name("reason").value(e.reason.name)
        }
        is CastEvent.Heartbeat -> write {
            name("e").value("heartbeat")
            name("castId").value(e.castId)
            name("epoch").value(e.epoch.toLong())
            name("followers").value(e.followers.toLong())
            name("positionMs").value(e.positionMs)
        }
    }

    /** Null on garbage or an unknown kind. */
    fun event(line: String): CastEvent? = parse(line) { f ->
        val castId = f.string("castId") ?: return@parse null
        when (f.string("e")) {
            "playing" -> CastEvent.Playing(
                castId,
                f.int("epoch") ?: return@parse null,
                f.string("path") ?: return@parse null,
                f.bool("hls") ?: return@parse null,
            )
            "idle" -> CastEvent.Idle(castId, f.string("reason"))
            "stopped" -> CastEvent.Stopped(
                castId,
                f.string("reason")?.let { s -> StopReason.entries.firstOrNull { it.name == s } }
                    ?: return@parse null,
            )
            "heartbeat" -> CastEvent.Heartbeat(
                castId,
                f.int("epoch") ?: return@parse null,
                f.int("followers") ?: return@parse null,
                f.long("positionMs")?.takeIf { it >= 0 },
            )
            else -> null
        }
    }

    private fun write(body: JsonWriter.() -> Unit): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { w ->
            w.serializeNulls = true
            w.beginObject()
            w.body()
            w.endObject()
        }
        return buffer.readUtf8()
    }

    private fun <T> parse(json: String, build: (Fields) -> T?): T? = try {
        JsonReader.of(Buffer().writeUtf8(json)).use { r ->
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return null
            val values = HashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                values[key] = when (r.peek()) {
                    JsonReader.Token.STRING -> r.nextString()
                    JsonReader.Token.NUMBER -> exactNumber(r.nextString())
                    JsonReader.Token.BOOLEAN -> r.nextBoolean()
                    else -> { r.skipValue(); null }
                }
            }
            r.endObject()
            build(Fields(values))
        }
    } catch (_: Exception) {
        null
    }

    /** An integral value as a Long (exact when it fits), otherwise a Double or null. */
    private fun exactNumber(text: String): Any? {
        text.toLongOrNull()?.let { return it }
        val d = text.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d % 1.0 == 0.0 && d >= Long.MIN_VALUE && d < Long.MAX_VALUE) d.toLong() else d
    }

    private class Fields(private val values: Map<String, Any?>) {
        fun string(key: String): String? = values[key] as? String
        fun long(key: String): Long? = values[key] as? Long
        fun int(key: String): Int? = long(key)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        fun bool(key: String): Boolean? = values[key] as? Boolean
    }
}
