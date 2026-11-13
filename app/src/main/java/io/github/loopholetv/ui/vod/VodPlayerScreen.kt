package io.github.loopholetv.ui.vod

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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.rememberInPictureInPicture
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.guide.tryFocus
import io.github.loopholetv.ui.player.VideoSurface
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

/** How long the bar stays up after a key press while the film plays. */
private const val BAR_SHOWN_MS = 4_000L

/**
 * Full-screen playback of a film or episode. Left/Right seek (held keys speed up and the seek
 * happens once the key is released), OK pauses and resumes, holding OK or Menu opens audio,
 * subtitle and aspect options, Back leaves. The bar shows while paused, while seeking and for a
 * moment after any key. In picture-in-picture only the video shows.
 */
@Composable
fun VodPlayerScreen(ref: VodRef, startMs: Long, onExit: () -> Unit) {
    val vm: VodPlayerViewModel = viewModel(factory = vodPlayerViewModelFactory(ref, startMs))
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LoopholeApp).container }
    val playback = container.playback
    val settings = remember { container.settingsSnapshot.current.playback }
    val error by vm.error.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    val tracks by playback.tracks.collectAsStateWithLifecycle()
    // The bar changes several times a second: only the bar reads it, so the screen never recomposes for it.
    val bar = vm.bar.collectAsStateWithLifecycle()
    val inPip = rememberInPictureInPicture()

    // Read when the user presses Home, to open picture-in-picture over a film that is on screen.
    DisposableEffect(Unit) {
        container.pipGate.playerShowing = true
        onDispose { container.pipGate.playerShowing = false }
    }
    LaunchedEffect(vm) { vm.events.collect { onExit() } }
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
    var optionsOpen by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // Key codes whose key-down was taken here, so their key-up is swallowed too.
    val consumed = remember { mutableSetOf<Int>() }

    LaunchedEffect(error != null, optionsOpen) {
        if (optionsOpen) return@LaunchedEffect
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
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            (code == KeyEvent.KEYCODE_MEDIA_PLAY) == paused
                        ) vm.onPlayPause()
                        inputs++
                        true
                    }
                    down && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) -> {
                        inputs++
                        false
                    }
                    else -> false
                }
            }
            .okPress(
                onClick = { if (error == null) { vm.onPlayPause(); inputs++ } },
                onLongClick = { if (error == null) optionsOpen = true },
            )
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(
            player = playback.player,
            aspect = aspect,
            subtitleStyle = settings.subtitleStyle,
            autoFrameRate = settings.autoFrameRate,
            frameRate = tracks.frameRate,
            inPip = inPip,
            onSurfaceAttached = playback::setFullScreenSurface,
            subtitlesShown = !optionsOpen,
        )
        if (inPip) return@Box
        if (state is PlaybackState.Buffering && error == null) {
            CircularProgressIndicator(
                color = Tokens.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
        BarHost(bar, recentInput, Modifier.align(Alignment.BottomCenter))
        error?.let { ErrorCard(it, onRetry = vm::retry, focus = retryFocus, modifier = Modifier.align(Alignment.Center)) }
        if (optionsOpen) {
            val options = remember { vm.options() }
            ChannelOptionsDialog(
                title = bar.value?.title.orEmpty(),
                options = options.map { o -> ChannelOption(o.label) { optionsOpen = false; o.onClick() } },
                onDismiss = { optionsOpen = false },
            )
        }
    }
}

private val PLAY_PAUSE_KEYS = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
)

/** Reads [bar] here so each tick recomposes only the bar. Shown for [recentInput], while paused and while a seek waits. */
@Composable
private fun BarHost(bar: State<VodBarUi?>, recentInput: Boolean, modifier: Modifier) {
    val ui = bar.value ?: return
    if (!recentInput && !ui.paused && ui.pendingLabel == null) return
    VodBar(
        ui,
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp),
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, focus: FocusRequester, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .width(520.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(28.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
    }
}
