package io.github.loopholetv.ui.player

import android.view.KeyEvent
import io.github.loopholetv.data.db.ChannelDao
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.repo.ScopedChannels
import io.github.loopholetv.player.catchup.ProgrammeLookup
import io.github.loopholetv.player.catchup.ProgrammeSpan
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.common.isOkKey
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

/** A catch-up key on a channel without catch-up. */
const val NO_CATCHUP = "This channel has no catch-up."

/** The error card while a programme from the archive fails to play. */
const val ARCHIVE_FAILED = "Couldn't play this programme back."
const val ARCHIVE_FAILED_DETAIL = "The provider may not keep it any more."

/** How often the catch-up bar follows the position, only while it shows. */
const val CATCHUP_TICK_MS = 1_000L

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
    /** The overlay opened on the catch-up bar (focus on Play/Pause), not on the channel row. */
    val barFocused: Boolean = false,
)

/** What the key routing needs to know about the screen. */
data class PlayerKeyState(
    val overlayVisible: Boolean,
    val bannerVisible: Boolean,
    val numberEntry: Boolean,
    /** The live error card shows. */
    val error: Boolean,
    /** The channel has catch-up. */
    val hasCatchup: Boolean,
    /** Replaying the archive, or live paused on a catch-up channel. */
    val catchupActive: Boolean,
    /** The archive failed to play (its own error card shows). */
    val archiveFailed: Boolean,
    /** The catch-up seek bar shows (it can outlive the overlay while paused). */
    val barVisible: Boolean = false,
)

/** [PlayerKeyState] for this screen. */
internal fun PlayerUi.keyState(catchupActive: Boolean, archiveFailed: Boolean, barVisible: Boolean) = PlayerKeyState(
    overlayVisible = overlayVisible,
    bannerVisible = bannerVisible,
    numberEntry = numberEntry != null,
    error = error != null,
    hasCatchup = channel.catchup() != null,
    catchupActive = catchupActive,
    archiveFailed = archiveFailed,
    barVisible = barVisible,
)

/** Carries out the key routes that are not catch-up ones (see [PlayerCatchup.perform]). */
internal fun PlayerViewModel.performPlayerRoute(route: PlayerKeyRoute) {
    when (route) {
        is PlayerKeyRoute.Digit -> { digit(route.digit); onUserInput() }
        PlayerKeyRoute.CommitNumber -> commitNumber()
        PlayerKeyRoute.ShowOverlay -> showOverlay()
        PlayerKeyRoute.Retry -> retry()
        is PlayerKeyRoute.Action -> when (route.action) {
            PlayerAction.CHANNEL_UP -> channelUp()
            PlayerAction.CHANNEL_DOWN -> channelDown()
            PlayerAction.CHANNEL_LIST -> showOverlay()
            PlayerAction.GUIDE -> goToGuide()
            PlayerAction.QUICK_MENU -> openQuickMenu()
            PlayerAction.FAVOURITE -> toggleFavourite()
            PlayerAction.SUBTITLES -> openQuickPanel(QuickItem.SUBTITLES)
            PlayerAction.AUDIO_TRACK -> openQuickPanel(QuickItem.AUDIO)
            PlayerAction.SLEEP_TIMER -> openQuickPanel(QuickItem.SLEEP)
            PlayerAction.SEARCH -> openSearch()
            else -> Unit
        }
        else -> Unit
    }
}

/** The programme [nowAndNext] has airing at a shown instant, as a catch-up span. */
internal fun airingLookup(nowAndNext: suspend (ChannelEntity, Instant) -> List<ProgrammeEntity>) =
    ProgrammeLookup { c, t -> nowAndNext(c, t).firstOrNull { it.start <= t }?.let { ProgrammeSpan(it.title, it.start, it.stop) } }

/** What a key does on the player. */
sealed interface PlayerKeyRoute {
    /** Not handled here: the focused control (or nothing) gets it. */
    data object Pass : PlayerKeyRoute
    data class Digit(val digit: Int) : PlayerKeyRoute
    data object CommitNumber : PlayerKeyRoute
    data object ShowOverlay : PlayerKeyRoute

    /** OK while catch-up is active: the overlay with the catch-up bar, focus on Play/Pause. */
    data object ShowBar : PlayerKeyRoute

    /** One press of a seek key; [direction] is negative for back. */
    data class Seek(val direction: Int) : PlayerKeyRoute
    data object RewindLive : PlayerKeyRoute
    data object TogglePause : PlayerKeyRoute
    data object GoLive : PlayerKeyRoute
    data object WatchFromStart : PlayerKeyRoute
    data object NoCatchup : PlayerKeyRoute
    data object Retry : PlayerKeyRoute
    data object RetryArchive : PlayerKeyRoute

    /** A bound button whose action does not depend on catch-up (channel keys, menus, favourite…). */
    data class Action(val action: PlayerAction) : PlayerKeyRoute
}

/**
 * Key-down routing for the player. Digits always enter a number and OK commits one being typed.
 * Bound buttons ([RemoteSettings.bindings]) act everywhere. With the overlay up the D-pad belongs
 * to it. On bare video D-pad Up opens the overlay on the channel row and Down opens the quick
 * menu; neither changes channel. While a number is typed or the seek bar shows, both open the
 * overlay. Left and Right seek while catch-up is active ([RemoteSettings.leftRightSeek]), and
 * Left on live goes back in time only when [RemoteSettings.leftRewindsLive] is on, so a stray
 * press never drops the picture.
 */
fun routePlayerKey(s: PlayerKeyState, keyCode: Int, remote: RemoteSettings): PlayerKeyRoute {
    digitOf(keyCode)?.let { return PlayerKeyRoute.Digit(it) }
    val ok = isOkKey(keyCode)
    if (ok && s.numberEntry) return PlayerKeyRoute.CommitNumber
    remote.bindings.player(keyCode)?.takeIf { it != PlayerAction.NONE }?.let { return boundRoute(s, it, remote) }
    if (s.overlayVisible) return PlayerKeyRoute.Pass
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> PlayerKeyRoute.ShowOverlay
        // While a number is typed or the seek bar shows, Down keeps showing the overlay.
        KeyEvent.KEYCODE_DPAD_DOWN ->
            if (s.numberEntry || s.barVisible) PlayerKeyRoute.ShowOverlay else PlayerKeyRoute.Action(PlayerAction.QUICK_MENU)
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            val back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            when {
                // The archive's error card: Left/Right move between Try again and Watch live.
                s.archiveFailed -> PlayerKeyRoute.Pass
                s.catchupActive -> if (remote.leftRightSeek) PlayerKeyRoute.Seek(if (back) -1 else 1) else PlayerKeyRoute.Pass
                back && remote.leftRewindsLive && s.hasCatchup -> PlayerKeyRoute.RewindLive
                else -> PlayerKeyRoute.Pass
            }
        }
        else -> when {
            !ok -> PlayerKeyRoute.Pass
            // Bare video with an error card: OK is its Try again (the screen takes OK before the button).
            s.archiveFailed && !s.bannerVisible -> PlayerKeyRoute.RetryArchive
            s.error && !s.bannerVisible && !s.catchupActive -> PlayerKeyRoute.Retry
            s.catchupActive -> PlayerKeyRoute.ShowBar
            else -> PlayerKeyRoute.ShowOverlay
        }
    }
}

private fun boundRoute(s: PlayerKeyState, action: PlayerAction, remote: RemoteSettings): PlayerKeyRoute = when (action) {
    PlayerAction.REWIND -> when {
        s.catchupActive -> if (remote.mediaKeysSeek) PlayerKeyRoute.Seek(-1) else PlayerKeyRoute.Pass
        !remote.rwRewindsLive -> PlayerKeyRoute.Pass
        s.hasCatchup -> PlayerKeyRoute.RewindLive
        else -> PlayerKeyRoute.NoCatchup
    }
    PlayerAction.FAST_FORWARD ->
        if (s.catchupActive && remote.mediaKeysSeek) PlayerKeyRoute.Seek(1) else PlayerKeyRoute.Pass
    // Play/Pause is not a seek: it always pauses or plays, whatever the seek keys are set to.
    PlayerAction.PLAY_PAUSE -> PlayerKeyRoute.TogglePause
    PlayerAction.LIVE -> if (s.catchupActive) PlayerKeyRoute.GoLive else PlayerKeyRoute.Pass
    PlayerAction.WATCH_FROM_START -> if (s.hasCatchup) PlayerKeyRoute.WatchFromStart else PlayerKeyRoute.NoCatchup
    else -> PlayerKeyRoute.Action(action)
}

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
