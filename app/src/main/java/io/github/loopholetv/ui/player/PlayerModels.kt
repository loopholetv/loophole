package io.github.loopholetv.ui.player

import android.view.KeyEvent
import io.github.loopholetv.data.db.ChannelDao
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.repo.ScopedChannels
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.manage.ChannelEdit
import java.time.Instant

const val OVERLAY_TIMEOUT_MS = 5_000L
const val NUMBER_ENTRY_IDLE_MS = 1_500L
const val MAX_NUMBER_DIGITS = 4
const val MESSAGE_MS = 2_000L

/** The link for a channel set to an external player could not be resolved. */
const val EXTERNAL_RESOLVE_FAILED = "Couldn't open this channel in the other player."

/** A channel's aspect ratio could not be saved; the saved one stays. */
const val ASPECT_SAVE_FAILED = "Couldn't save the aspect ratio."

/** The channel's external player is no longer installed. */
const val EXTERNAL_LAUNCH_FAILED = "That player isn't installed any more. Playing here instead."

/**
 * Zaps are coalesced: each press moves the target channel at once (its info shows immediately),
 * but play() runs only once no zap key has come for this long, so holding Down (key repeats)
 * doesn't open and drop a provider connection per channel passed.
 */
const val ZAP_SETTLE_MS = 400L

/** Channel tiles loaded either side of the current channel in the row. */
const val ROW_EACH_SIDE = 15

/** The channel lookups the player needs ([ChannelDao] + [ScopedChannels] in the app, fakes in tests). */
interface PlayerChannels {
    suspend fun byId(id: Long): ChannelEntity?
    suspend fun byNumber(n: Int): ChannelEntity?
    suspend fun neighbour(current: ChannelEntity, scope: ChannelScope, direction: Int): ChannelEntity?
    suspend fun around(current: ChannelEntity, scope: ChannelScope, each: Int): List<ChannelEntity>
    suspend fun contains(channel: ChannelEntity, scope: ChannelScope): Boolean

    /** What the banner calls [scope] (a renamed group's custom name). */
    suspend fun label(scope: ChannelScope): String = scope.label()
}

internal class DaoPlayerChannels(private val dao: ChannelDao, private val scoped: ScopedChannels) : PlayerChannels {
    override suspend fun byId(id: Long) = dao.byId(id)
    override suspend fun byNumber(n: Int) = dao.byNumber(n)
    override suspend fun neighbour(current: ChannelEntity, scope: ChannelScope, direction: Int) =
        scoped.neighbour(current, scope, direction)
    override suspend fun around(current: ChannelEntity, scope: ChannelScope, each: Int) = scoped.around(current, scope, each)
    override suspend fun contains(channel: ChannelEntity, scope: ChannelScope) = scoped.contains(channel, scope)
    override suspend fun label(scope: ChannelScope) = scoped.label(scope)
}

data class PlayerUi(
    val channel: ChannelEntity,
    val now: ProgrammeEntity?,
    val next: ProgrammeEntity?,
    val tags: StreamTags,
    val overlayVisible: Boolean,
    val bannerVisible: Boolean = false,
    /** Digits typed so far (shown large, top-right); null when not entering a number. */
    val numberEntry: String?,
    /** A short notice such as "No channel 99"; null when none. */
    val message: String?,
    /** [PlaybackState.Error.message] for [channel]; null when it isn't failing. */
    val error: String?,
    /** Date and time for the top-right corner. */
    val clock: String,
    val groupLabel: String = ChannelScope.All.label(),
    /** Loading [channel] (or still showing the previous one while a zap settles). */
    val buffering: Boolean = false,
    /** When [clock] was taken; progress and minutes left are computed against it. */
    val at: Instant = Instant.EPOCH,
    /** [channel] is a favourite (★ in the info bar). */
    val favourite: Boolean = false,
)

/** A [ChannelEdit] picked from the player's channel options for [channel]. */
data class ChannelEditing(val channel: ChannelEntity, val edit: ChannelEdit)

/** What the footer row lists after its two label tiles. */
enum class RowMode { CHANNELS, HISTORY }

/**
 * The footer row: channel tiles around the current channel ([RowMode.CHANNELS]) or the recently
 * watched channels, newest first ([RowMode.HISTORY]); [titles] holds the programme airing on each.
 */
data class ChannelRowUi(
    val channels: List<ChannelEntity>,
    val currentIndex: Int,
    val titles: Map<Long, String>,
    val mode: RowMode = RowMode.CHANNELS,
) {
    val currentId: Long? get() = channels.getOrNull(currentIndex)?.id

    companion object {
        val EMPTY = ChannelRowUi(emptyList(), -1, emptyMap())
    }
}

/** 0-9 for the digit keys (top row and numpad), else null. */
internal fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/**
 * The error card after a rename: playback errors read "<name>: <reason>" with the name shown when
 * the stream failed, so [oldName] becomes [newName]; any other text stays as it is.
 */
internal fun relabelError(error: String?, oldName: String, newName: String): String? =
    if (error != null && oldName != newName && error.startsWith("$oldName: ")) newName + error.substring(oldName.length) else error

/** [PlayerUi] with [s]'s tags, error and loading state for its channel. */
internal fun PlayerUi.withPlayback(s: PlaybackState): PlayerUi {
    val id = channel.id
    return copy(
        tags = (s as? PlaybackState.Playing)?.takeIf { it.channelId == id }?.tags ?: StreamTags.NONE,
        error = (s as? PlaybackState.Error)?.takeIf { it.channelId == id }?.message,
        buffering = when (s) {
            is PlaybackState.Buffering -> true
            is PlaybackState.Playing -> s.channelId != id
            is PlaybackState.Error -> s.channelId != id
            PlaybackState.Idle -> false
        },
    )
}
