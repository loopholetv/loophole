package io.github.loopholetv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState

/** The screen is held on only while a stream is loading or playing, never when idle or errored. */
fun shouldKeepScreenOn(state: PlaybackState): Boolean =
    state is PlaybackState.Buffering || state is PlaybackState.Playing

/**
 * Keeps the screen on (no screensaver) while [playback] is buffering or playing; clears on dispose.
 * Call exactly once, at the app root: the flag lives on the activity's single ComposeView, so
 * per-screen callers would clear each other's setting during navigation.
 */
@Composable
fun KeepScreenOnWhilePlaying(playback: Playback) {
    val state by playback.state.collectAsState()
    val keepOn = shouldKeepScreenOn(state)
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
