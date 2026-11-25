package io.github.loopholetv.ui.recordings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.github.loopholetv.data.db.isChannelLocked
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.guide.tryFocus
import io.github.loopholetv.ui.player.VideoSurface
import io.github.loopholetv.ui.theme.Tokens
import io.github.loopholetv.ui.vod.VOD_TICK_MS
import io.github.loopholetv.ui.vod.VodBar
import io.github.loopholetv.ui.vod.VodBarUi
import kotlinx.coroutines.delay

private const val BAR_SHOWN_MS = 4_000L

/** The badge on a recording that is still being written. */
const val REC_BADGE = "● REC"

/**
 * Full-screen playback of a recording. Left/Right seek (applied once the key is released), OK pauses and
 * resumes, Back leaves. [peerId] names another TV that holds the recording; null is this TV's own.
 * [onOpenLive] is called when a viewer who arrived from the live channel is still at the end as the
 * recording finishes.
 */
@Composable
fun RecordingPlayerScreen(
    id: Long,
    peerId: String?,
    live: Boolean,
    onExit: () -> Unit,
    onOpenLive: (channelId: Long) -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LoopholeApp).container }
    val peer = remember(peerId) { peerId?.let { p -> container.castController.peers.value.firstOrNull { it.id == p } } }
    if (peerId != null && peer == null) {
        Unavailable(onExit)
        return
    }
    val vm: RecordingPlayerViewModel = viewModel(factory = recordingPlayerViewModelFactory(id, peer, live))
    val playback = container.playback
    val settings = remember { container.settingsSnapshot.current.playback }
    val error by vm.error.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    val tracks by playback.tracks.collectAsStateWithLifecycle()
    val bar = vm.bar.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                RecordingPlayerEvent.Exit -> onExit()
                is RecordingPlayerEvent.OpenLive -> onOpenLive(e.channelId)
            }
        }
    }
    LaunchedEffect(vm) {
        while (true) {
            vm.tick()
            delay(VOD_TICK_MS)
        }
    }
    // Leaving the app stops the stream (MainActivity); coming back continues where it was.
    LifecycleStartEffect(vm) {
        vm.onScreenStarted()
        onStopOrDispose { vm.onScreenStopped() }
    }
    BackHandler(onBack = vm::onBack)

    var inputs by remember { mutableIntStateOf(0) }
    var recentInput by remember { mutableStateOf(true) }
    LaunchedEffect(inputs) {
        recentInput = true
        delay(BAR_SHOWN_MS)
        recentInput = false
    }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val consumed = remember { mutableSetOf<Int>() }
    LaunchedEffect(error != null) {
        repeat(10) {
            if ((if (error != null) retryFocus else rootFocus).tryFocus()) return@LaunchedEffect
            delay(30)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                if (!down && !up) return@onPreviewKeyEvent false
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (down) consumed += code else if (consumed.remove(code)) vm.onBack()
                    return@onPreviewKeyEvent true
                }
                val direction = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
                    else -> 0
                }
                when {
                    direction != 0 && error == null -> {
                        if (down) {
                            vm.onSeekKey(direction, e.nativeKeyEvent.repeatCount)
                            inputs++
                        } else {
                            vm.onSeekRelease()
                        }
                        true
                    }
                    down && code in PLAY_PAUSE_KEYS && error == null -> {
                        val paused = playback.paused.value
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (code == KeyEvent.KEYCODE_MEDIA_PLAY) == paused) {
                            vm.onPlayPause()
                        }
                        inputs++
                        true
                    }
                    else -> false
                }
            }
            .okPress(
                onClick = {
                    if (error == null) {
                        vm.onPlayPause()
                        inputs++
                    }
                },
                onLongClick = null,
            )
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(
            player = playback.player,
            aspect = AspectMode.FIT,
            subtitleStyle = settings.subtitleStyle,
            autoFrameRate = settings.autoFrameRate,
            frameRate = tracks.frameRate,
            inPip = false,
            onSurfaceAttached = playback::setFullScreenSurface,
        )
        if (state is PlaybackState.Buffering && error == null) {
            CircularProgressIndicator(
                color = Tokens.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
        if (recording) {
            Text(
                REC_BADGE,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.Error,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }
        BarHost(bar, recentInput, Modifier.align(Alignment.BottomCenter))
        error?.let { Card(it, "Try again", vm::retry, retryFocus, Modifier.align(Alignment.Center)) }
    }
}

private val PLAY_PAUSE_KEYS = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
)

private fun barVisible(ui: VodBarUi?, recentInput: Boolean): Boolean =
    ui != null && (recentInput || ui.paused || ui.pendingLabel != null)

/** Reads [bar] here so each tick recomposes only the bar. */
@Composable
private fun BarHost(bar: State<VodBarUi?>, recentInput: Boolean, modifier: Modifier) {
    val ui = bar.value?.takeIf { barVisible(it, recentInput) } ?: return
    VodBar(
        ui,
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp),
    )
}

/** The recording's other TV is not in the list any more: nothing to play, Back leaves. */
@Composable
private fun Unavailable(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            CANT_PLAY_RECORDING,
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun Card(message: String, action: String, onAction: () -> Unit, focus: FocusRequester, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .width(520.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(28.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.focusRequester(focus)) { Text(action) }
    }
}

private fun recordingPlayerViewModelFactory(id: Long, peer: CastPeer?, live: Boolean): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
            RecordingPlayerViewModel(
                id = id,
                peer = peer,
                live = live,
                db = c.db,
                client = c.recordingsClient,
                playback = c.playback,
                clock = java.time.Instant::now,
                localUrl = c.recordingsRoute::localFileUrl,
                channelLocked = { playlistId, key, name ->
                    !c.parentalLock.isUnlocked && when {
                        playlistId != null && key != null -> c.db.lockDao().isChannelLocked(playlistId, key)
                        else -> c.db.lockDao().channelNameLocked(name)
                    }
                },
            )
        }
    }
