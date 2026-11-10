@file:OptIn(UnstableApi::class)

package io.github.loopholetv.ui.player

import android.graphics.Color
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.loopholetv.R
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.player.AFR_SETTLE_MS
import io.github.loopholetv.player.DisplayModeSwitcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** Wait before asking again when the window has no display yet. */
private const val AFR_RETRY_MS = 250L

/** The PlayerView resize mode for [aspect]; forced ratios fill a box of that ratio (see [forcedRatio]). */
fun resizeModeFor(aspect: AspectMode): Int = when (aspect) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.RATIO_16_9, AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/**
 * The width/height ratio the picture is forced into, or null to follow the video. PlayerView
 * recomputes its own ratio from every video size change, so a forced ratio is applied by the
 * container around it rather than by `setAspectRatio`.
 */
fun forcedRatio(aspect: AspectMode): Float? = when (aspect) {
    AspectMode.RATIO_16_9 -> 16f / 9f
    AspectMode.RATIO_4_3 -> 4f / 3f
    else -> null
}

/** The subtitle caption style for [style]; no window colour, black edges, the default typeface. */
fun captionStyle(style: SubtitleStyle): CaptionStyleCompat = CaptionStyleCompat(
    style.colour.argb,
    style.background.argb,
    Color.TRANSPARENT,
    when (style.edge) {
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    },
    Color.BLACK,
    null,
)

/** Subtitle text height as a fraction of the view height for [size]. */
fun subtitleTextFraction(size: SubtitleSize): Float = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size.scale

/** Where the subtitle view lives inside the PlayerView. */
enum class SubtitleHost {
    /** The aspect-ratio frame, so subtitles follow the picture's rectangle. */
    CONTENT_FRAME,

    /** The screen-sized overlay frame. */
    OVERLAY,
}

/**
 * Zoom measures the content frame larger than the screen, so subtitles inside it would be cropped
 * and scaled up; they move to the screen-sized overlay instead. Every other mode keeps the frame
 * within the screen.
 */
fun subtitleHostFor(aspect: AspectMode): SubtitleHost =
    if (aspect == AspectMode.ZOOM) SubtitleHost.OVERLAY else SubtitleHost.CONTENT_FRAME

/**
 * Subtitles are hidden while the quick menu is open: its tiles and lists sit over the bottom of
 * the picture, where subtitles are drawn, and would otherwise show through them.
 */
fun subtitlesShown(quickMenuOpen: Boolean): Boolean = !quickMenuOpen

/** Draws subtitles in [style] in place of the stream's own colours and sizes. */
fun SubtitleView.applySubtitleStyle(style: SubtitleStyle) {
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setStyle(captionStyle(style))
    setFractionalTextSize(subtitleTextFraction(style.size))
}

/** Shows or hides this subtitle view without changing its layout. */
fun SubtitleView.showSubtitles(shown: Boolean) {
    val v = if (shown) View.VISIBLE else View.INVISIBLE
    if (visibility != v) visibility = v
}

/** True when the player is already showing a picture (ready, with a known video size). */
fun pictureShowing(playbackState: Int, videoSize: VideoSize): Boolean =
    playbackState == Player.STATE_READY && videoSize != VideoSize.UNKNOWN

/** Frame-rate matching runs only when the setting is on and the picture fills the screen (not in PiP). */
fun afrEnabled(autoFrameRate: Boolean, inPip: Boolean): Boolean = autoFrameRate && !inPip

/**
 * Whether the full-screen surface should count as attached (so tunneling may be requested):
 * not in PiP, where the window is small and shared with other apps, and not once disposed.
 */
fun surfaceAttachedFor(inPip: Boolean, disposed: Boolean): Boolean = !inPip && !disposed

/**
 * Decides when to request a display mode for the content frame rate. A rate becomes due once a
 * frame has been rendered and the rate has stayed the same, known and positive for [settleMs];
 * it is then reported once, and again only after it changes. [reset] starts over for new media.
 */
class AfrTrigger(private val settleMs: Long = AFR_SETTLE_MS) {
    private var rate: Float? = null
    private var rateSinceMs = 0L
    private var firstFrameMs: Long? = null
    private var reported = false

    /** Records the content frame rate; null, non-finite or non-positive means unknown. */
    fun onFrameRate(fps: Float?, nowMs: Long) {
        val known = fps?.takeIf { it.isFinite() && it > 0f }
        if (known == rate) return
        rate = known
        rateSinceMs = nowMs
        reported = false
    }

    /** Records that the current media has put a frame on screen. */
    fun onRenderedFirstFrame(nowMs: Long) {
        if (firstFrameMs == null) firstFrameMs = nowMs
    }

    /**
     * Starts over when matching turns on: [fps] is the current rate, and [pictureShowing] counts as
     * the first frame now (a picture handed over from another surface renders no new first frame).
     */
    fun start(fps: Float?, pictureShowing: Boolean, nowMs: Long) {
        reset()
        onFrameRate(fps, nowMs)
        if (pictureShowing) onRenderedFirstFrame(nowMs)
    }

    /** Starts over for new media, keeping [fps] until the new media reports its own rate. */
    fun onMediaChanged(fps: Float?, nowMs: Long) {
        reset()
        onFrameRate(fps, nowMs)
    }

    /** Reports the current rate again on the next [due], after a request that could not be made. */
    fun retry() {
        reported = false
    }

    fun reset() {
        rate = null
        rateSinceMs = 0L
        firstFrameMs = null
        reported = false
    }

    /** Milliseconds until [due] returns a rate (0 when already due), or null until another event. */
    fun msUntilDue(nowMs: Long): Long? {
        if (rate == null || reported) return null
        val shown = firstFrameMs ?: return null
        return (maxOf(shown, rateSinceMs) + settleMs - nowMs).coerceAtLeast(0L)
    }

    /** The rate to switch to now, or null when nothing is due. */
    fun due(nowMs: Long): Float? {
        val fps = rate ?: return null
        val shown = firstFrameMs ?: return null
        if (reported || nowMs - maxOf(shown, rateSinceMs) < settleMs) return null
        reported = true
        return fps
    }
}

/** Last values pushed to the PlayerView, so recomposition does not restyle it needlessly. */
private class AppliedSurface {
    var style: SubtitleStyle? = null
    var host = SubtitleHost.CONTENT_FRAME
}

/** Moves the subtitle view between the content frame and the overlay frame of this PlayerView. */
private fun PlayerView.hostSubtitles(host: SubtitleHost) {
    val subtitles = subtitleView ?: return
    val content = findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: return
    val overlay = overlayFrameLayout ?: return
    val target: ViewGroup = if (host == SubtitleHost.OVERLAY) overlay else content
    if (subtitles.parent === target) return
    (subtitles.parent as? ViewGroup)?.removeView(subtitles)
    val params = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    if (host == SubtitleHost.OVERLAY) {
        overlay.addView(subtitles, 0, params)
    } else {
        // Back above the video, shutter and artwork, below the buffering indicator, as inflated.
        val buffering = content.findViewById<View>(androidx.media3.ui.R.id.exo_buffering)
        val index = if (buffering?.parent === content) content.indexOfChild(buffering) else content.childCount
        content.addView(subtitles, index, params)
    }
}

/**
 * The full-screen video: [player] on a SurfaceView (the layout `fullscreen_player_view`), with the
 * picture shaped by [aspect] and subtitles drawn by the view's SubtitleView in [subtitleStyle],
 * which replaces the stream's own colours and sizes.
 *
 * With [autoFrameRate] on and not [inPip], the display is switched to a mode matching [frameRate]
 * a settle time after the picture appears. The mode is left as is on dispose; the activity
 * restores it when it stops.
 *
 * Subtitles are drawn only while [subtitlesShown].
 *
 * [onSurfaceAttached] is told whether this surface may be used for tunneled playback: true while
 * shown full screen, false in PiP and on dispose.
 */
@Composable
fun VideoSurface(
    player: Player,
    aspect: AspectMode,
    subtitleStyle: SubtitleStyle,
    autoFrameRate: Boolean,
    frameRate: Float?,
    inPip: Boolean,
    onSurfaceAttached: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitlesShown: Boolean = true,
) {
    val applied = remember { AppliedSurface() }
    val ratio = forcedRatio(aspect)
    // No background of its own: the host screen is already black, so pillarbox bars cost no extra fill.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.fullscreen_player_view, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    subtitleView?.setApplyEmbeddedStyles(false)
                    subtitleView?.setApplyEmbeddedFontSizes(false)
                    // The guide preview may still hold the player during the transition; the player
                    // moves to this surface, and the preview's later unbind leaves it alone.
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = resizeModeFor(aspect)
                val host = subtitleHostFor(aspect)
                if (applied.host != host) {
                    view.hostSubtitles(host)
                    applied.host = host
                }
                if (applied.style != subtitleStyle) {
                    view.subtitleView?.applySubtitleStyle(subtitleStyle)
                    applied.style = subtitleStyle
                }
                view.subtitleView?.showSubtitles(subtitlesShown)
            },
            onRelease = { it.player = null },
            modifier = if (ratio != null) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxSize(),
        )
    }

    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    DisposableEffect(inPip) {
        latestOnSurfaceAttached(surfaceAttachedFor(inPip, disposed = false))
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnSurfaceAttached(surfaceAttachedFor(inPip = false, disposed = true)) }
    }

    FrameRateMatching(player, afrEnabled(autoFrameRate, inPip), frameRate)
}

/** Drives [AfrTrigger] from the player and [frameRate] while [enabled], switching the display when due. */
@Composable
private fun FrameRateMatching(player: Player, enabled: Boolean, frameRate: Float?) {
    val activity = LocalActivity.current
    val switcher = remember(activity) { activity?.window?.let(::DisplayModeSwitcher) }
    val window = activity?.window
    val trigger = remember(player) { AfrTrigger() }
    // Bumped on every trigger event, so the waiting loop below recomputes its deadline.
    val events = remember(trigger) { MutableStateFlow(0) }
    val latestFrameRate by rememberUpdatedState(frameRate)
    val active = enabled && switcher != null

    DisposableEffect(player, trigger, active) {
        if (!active) return@DisposableEffect onDispose { }
        // A picture handed over from the preview, or already playing when the setting turns on,
        // renders no new first frame, so it counts as shown now.
        trigger.start(latestFrameRate, pictureShowing(player.playbackState, player.videoSize), SystemClock.elapsedRealtime())
        events.value++
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                trigger.onRenderedFirstFrame(SystemClock.elapsedRealtime())
                events.value++
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                trigger.onMediaChanged(latestFrameRate, SystemClock.elapsedRealtime())
                events.value++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(trigger, active, frameRate) {
        if (!active) return@LaunchedEffect
        trigger.onFrameRate(frameRate, SystemClock.elapsedRealtime())
        events.value++
    }

    LaunchedEffect(trigger, active, switcher) {
        if (!active || switcher == null) return@LaunchedEffect
        // Sleeps until the next deadline; with nothing pending it waits for the next event.
        events.collectLatest {
            while (true) {
                val wait = trigger.msUntilDue(SystemClock.elapsedRealtime()) ?: break
                delay(wait)
                val fps = trigger.due(SystemClock.elapsedRealtime()) ?: continue
                if (!switcher.matchFrameRate(fps) && window?.decorView?.display == null) {
                    // The window is not on a display yet: ask again shortly.
                    trigger.retry()
                    delay(AFR_RETRY_MS)
                }
            }
        }
    }
}
