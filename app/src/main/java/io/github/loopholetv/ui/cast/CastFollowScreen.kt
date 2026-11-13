package io.github.loopholetv.ui.cast

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.player.rememberInPictureInPicture
import io.github.loopholetv.ui.player.VideoSurface
import io.github.loopholetv.ui.player.rememberCastLabelState
import io.github.loopholetv.ui.settings.focusSoon
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** What a key does while following a cast. */
internal enum class FollowKey {
    /** Leave the cast and go back to the guide. */
    LEAVE,

    /** Taken and ignored: a following TV doesn't control the cast. */
    CONSUME,

    /** Left to the system (volume). */
    PASS,
}

/**
 * Back on a remote, a keyboard (Escape) or a game controller (B). Android sends Back for the
 * last two only when the original key goes unhandled, so they are handled as Back here.
 */
internal val BACK_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)

internal val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

/** Only Back does anything while following; volume stays with the system; every other key is ignored. */
internal fun followKeyFor(keyCode: Int, down: Boolean): FollowKey = when (keyCode) {
    in BACK_KEYS -> if (down) FollowKey.LEAVE else FollowKey.CONSUME
    in VOLUME_KEYS -> FollowKey.PASS
    else -> FollowKey.CONSUME
}

private const val FOLLOW_LABEL_SHOW_MS = 10_000L

/** `From <name>`, with the name the casting TV announces now. */
internal fun followLabel(peer: CastPeer, peers: List<CastPeer>): String =
    "From ${peers.firstOrNull { it.id == peer.id }?.name ?: peer.name}"

/**
 * Full-screen video of the cast this TV follows, with `From <name>` in the top-right. The cast
 * plays through the shared player (the controller starts and stops it; this screen never plays
 * anything). Only Back is handled: it leaves the cast, keeps the guide from replaying its preview
 * and goes back through [onExit]. This screen never offers picture-in-picture.
 */
@Composable
fun CastFollowScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LoopholeApp).container }
    val cast = container.castController
    val playback = container.playback
    val state by cast.state.collectAsStateWithLifecycle()
    val peers by cast.peers.collectAsStateWithLifecycle()
    val tracks by playback.tracks.collectAsStateWithLifecycle()
    val settings by remember(container) {
        container.settingsStore.settings.map { it.playback }.catch { }
    }.collectAsStateWithLifecycle(PlaybackSettings())
    val inPip = rememberInPictureInPicture()

    // The label stays while the screen closes after the cast ended: the last followed TV is
    // remembered after each composition, never written during one.
    val current = state as? CastState.Following
    val lastFollowed = remember { mutableStateOf<CastState.Following?>(null) }
    SideEffect { if (current != null) lastFollowed.value = current }
    val followed = current ?: lastFollowed.value
    // Shown for 10 s after joining, and again on a channel change (the epoch) or another casting TV.
    val label = rememberCastLabelState(
        text = followed?.let { followLabel(it.peer, peers) },
        count = null,
        key = followed?.let { it.peer.castId to it.epoch },
        showMs = FOLLOW_LABEL_SHOW_MS,
    )

    var leaving by remember { mutableStateOf(false) }
    fun leave() {
        if (leaving) return
        leaving = true
        cast.leave()
        container.previewHold.hold()
        onExit()
    }
    // Back arrives through the key handler below; this covers any other source (e.g. a gesture).
    BackHandler(onBack = ::leave)

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(rootFocus) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                if (!down && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (followKeyFor(e.nativeKeyEvent.keyCode, down)) {
                    FollowKey.LEAVE -> { leave(); true }
                    FollowKey.CONSUME -> true
                    FollowKey.PASS -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        val player = playback.player
        VideoSurface(
            player = player,
            aspect = AspectMode.FIT,
            subtitleStyle = settings.subtitleStyle,
            autoFrameRate = settings.autoFrameRate,
            frameRate = decodedFrameRate(player) ?: tracks.frameRate,
            inPip = inPip,
            onSurfaceAttached = playback::setFullScreenSurface,
        )
        if (label != null && label.expanded && !inPip) {
            Text(
                label.text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The decoded video format's frame rate, when known (MPEG-TS tracks often carry none). */
@OptIn(UnstableApi::class)
private fun decodedFrameRate(player: Player): Float? =
    (player as? ExoPlayer)?.videoFormat?.frameRate?.takeIf { it > 0f }
