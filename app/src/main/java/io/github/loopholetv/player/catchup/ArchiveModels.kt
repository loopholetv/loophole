package io.github.loopholetv.player.catchup

import io.github.loopholetv.data.db.ChannelEntity
import java.time.Instant

/** The guide-shown start and end of a programme to replay. */
data class ArchiveRequest(val from: Instant, val until: Instant)

sealed interface PlayMode {
    data object Live : PlayMode

    /** [durationMs] is null while unknown; [ended] once the archived stream played to its end. */
    data class Archive(
        val channelId: Long,
        val request: ArchiveRequest,
        val seekable: Boolean = false,
        val durationMs: Long? = null,
        val ended: Boolean = false,
        /** True when the loaded archive is an HLS stream, which seeks freely; otherwise it is progressive. */
        val hls: Boolean = false,
    ) : PlayMode

    /** Following another TV's cast on the home network: no channel, and nothing goes through the resolver. */
    data object Cast : PlayMode
}

/** A guide programme in shown times; a null [title] means "No information". */
data class ProgrammeSpan(val title: String?, val start: Instant, val stop: Instant)

fun interface ProgrammeLookup {
    suspend fun at(channel: ChannelEntity, t: Instant): ProgrammeSpan?
}

/** What the catch-up overlay shows; [live] is true only while a paused live stream waits to resume. */
data class CatchupUi(
    val channelId: Long,
    val programme: ProgrammeSpan,
    val position: Instant,
    val now: Instant,
    val pendingTarget: Instant?,
    val paused: Boolean,
    val live: Boolean,
    val failed: Boolean,
)
