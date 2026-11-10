package io.github.loopholetv

import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.ui.nav.Routes

/**
 * Leaving the app (Home) opens picture-in-picture only when the user turned that on, the
 * full-screen player is the screen showing, a stream is loading or playing, and the device
 * supports it. The guide preview never goes into picture-in-picture.
 */
fun shouldEnterPipOnLeave(pipOnHome: Boolean, playerShowing: Boolean, state: PlaybackState, supported: Boolean): Boolean =
    pipOnHome && playerShowing && supported &&
        (state is PlaybackState.Playing || state is PlaybackState.Buffering)

/**
 * In picture-in-picture only the video shows: any destination above the player (the Settings
 * panel, the playlist form) is closed. [currentRoute] null means no destination yet.
 */
fun shouldCloseAbovePlayerForPip(inPip: Boolean, currentRoute: String?): Boolean =
    inPip && currentRoute != null && currentRoute != Routes.PLAYER

/**
 * When the sleep timer stops playback while the picture-in-picture window is open, the window is
 * closed too (the activity goes to the back and stops), so nothing stays on screen.
 */
fun shouldCloseWindowOnSleepExpiry(inPip: Boolean): Boolean = inPip

/** A stop for a configuration change keeps everything; any other stop releases the stream. */
fun shouldReleaseOnStop(changingConfigurations: Boolean): Boolean = !changingConfigurations
