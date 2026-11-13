package io.github.loopholetv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState

/**
 * A screen with no stream behind it (Search, Movies, Series, a film or show page) stops whatever
 * still plays when it shows: a guide preview it was opened over, or a player opened from it. Back
 * to the guide resumes its preview (GuideViewModel.onScreenStarted, Idle branch).
 */
internal fun stopsPlaybackOnResume(state: PlaybackState): Boolean = state !is PlaybackState.Idle

/** Stops [playback] each time the screen resumes, when [stopsPlaybackOnResume] says so. */
@Composable
internal fun StopPlaybackOnResume(playback: Playback = rememberAppPlayback()) {
    LifecycleResumeEffect(playback) {
        if (stopsPlaybackOnResume(playback.state.value)) playback.stop()
        onPauseOrDispose { }
    }
}

/** The app's single player. */
@Composable
internal fun rememberAppPlayback(): Playback {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as LoopholeApp).container.playback }
}
