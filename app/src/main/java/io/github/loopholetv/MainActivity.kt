package io.github.loopholetv

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.github.loopholetv.data.db.channelScopeFromArg
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.orDefaultsOnError
import io.github.loopholetv.player.DisplayModeSwitcher
import io.github.loopholetv.player.enterPictureInPicture
import androidx.compose.runtime.remember
import io.github.loopholetv.ui.nav.StartSlot
import io.github.loopholetv.ui.nav.resolveStartPlayback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.github.loopholetv.ui.nav.AppNav
import io.github.loopholetv.ui.nav.startDestination
import io.github.loopholetv.ui.splash.FirstInProcess
import io.github.loopholetv.ui.splash.SplashKeyResult
import io.github.loopholetv.ui.splash.SplashScreen
import io.github.loopholetv.ui.splash.SplashSession
import io.github.loopholetv.ui.splash.shouldShowSplash
import io.github.loopholetv.ui.theme.LoopholeTheme
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether an activity has been created yet in this process (cold start detection). */
private val firstActivityInProcess = FirstInProcess()

class MainActivity : ComponentActivity() {
    private var splash: SplashSession? = null

    /** This activity's window display mode (frame-rate matching); one per activity, never shared. */
    private val displayModes by lazy { DisplayModeSwitcher(window) }

    private val container get() = (application as LoopholeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LoopholeApp
        val repo = app.container.playlistRepository

        // Cold start only; a configuration change recreates the activity without it. While the
        // splash shows, a start channel opened behind it does not connect (the session's gate).
        val session = SplashSession(app.container.startGate)
        val firstInProcess = firstActivityInProcess.take()
        session.begin(shouldShowSplash(savedInstanceState != null, firstInProcess))
        splash = session
        // The update check runs once per process, after the splash (it waits on the start gate).
        if (firstInProcess) app.container.updater.onColdStart()

        // Decide the start screen from the DB before showing any NavHost (no flash of the wrong
        // screen), and kick off the once-per-process background refresh only if a playlist exists.
        // This runs behind the splash, independent of composition.
        val hasPlaylist = mutableStateOf<Boolean?>(null)
        // One-shot (StartSlot): taken by the first guide composition, so a later return to the guide
        // (e.g. all playlists deleted -> onboarding -> new playlist) can't replay it.
        var startSlot = StartSlot(null)
        val fresh = savedInstanceState == null
        lifecycleScope.launch {
            val exists = repo.playlists().first().isNotEmpty()
            // Resolve before the refresh starts so it cannot remove the channel in between;
            // never on recreation.
            if (exists && fresh) {
                val c = app.container
                val s = c.settingsStore.current()
                startSlot = StartSlot(
                    resolveStartPlayback(
                        s.lastChannelOnStart, c.history::latest, channelScopeFromArg(s.lastGuideScope), c.scopedChannels::contains,
                        c.scopedChannels::resolve,
                    ),
                )
            }
            if (exists) app.startupRefresher.start()
            hasPlaylist.value = exists
        }

        // A picture-in-picture window must not hold the TV at the stream's frame rate while
        // another app is full screen behind it; full screen asks again once it settles.
        addOnPictureInPictureModeChangedListener { if (it.isInPictureInPictureMode) displayModes.restore() }
        // The sleep timer has stopped playback: a picture-in-picture window would stay open, black.
        lifecycleScope.launch {
            app.container.sleepTimer.expired.collect {
                if (shouldCloseWindowOnSleepExpiry(isInPictureInPictureMode)) moveTaskToBack(true)
            }
        }

        setContent {
            LoopholeTheme {
                val settingsFlow = remember { app.container.settingsStore.settings.orDefaultsOnError() }
                val settings by settingsFlow.collectAsState(initial = AppSettings())
                val decided = hasPlaylist.value
                Box(Modifier.fillMaxSize()) {
                    // The next screen composes under the splash (at launch, or once the logo has
                    // settled) and is drawn only once the splash starts to fade.
                    Box(
                        Modifier.fillMaxSize()
                            .drawWithContent { if (session.drawContentUnder) drawContent() }
                            .background(Tokens.Background),
                    ) {
                        if (decided != null && session.contentAllowed) {
                            LaunchedEffect(Unit) { session.onContentComposed(SystemClock.uptimeMillis()) }
                            AppNav(
                                rememberNavController(), startDestination(decided),
                                startPlayback = startSlot, confirmExit = settings.confirmExit,
                            )
                        }
                    }
                    if (session.visible) {
                        // The opaque splash covers the window: skip the window background fill.
                        DisposableEffect(Unit) {
                            window.setBackgroundDrawable(null)
                            onDispose { window.setBackgroundDrawableResource(R.color.loophole_background) }
                        }
                        SplashScreen(session)
                    }
                }
            }
        }
    }

    /** While the splash shows, a remote key skips it; that key never reaches the screen below. */
    // Activity.dispatchKeyEvent is public platform API; lint flags it only through the
    // androidx.core ComponentActivity override.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            val session = splash
            val result = session?.onKey(
                action == KeyEvent.ACTION_DOWN, event.keyCode, event.repeatCount, SystemClock.uptimeMillis(),
            )
            if (result != null && result != SplashKeyResult.PASS) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Other TVs can find this one, and it them, only while Loophole is on screen. */
    override fun onStart() {
        super.onStart()
        container.castController.onForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // Back from the install-permission screen or the system installer.
        container.updater.onResumed()
    }

    /** Home while the full-screen player plays opens picture-in-picture, when that is turned on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = container
        val enter = shouldEnterPipOnLeave(
            pipOnHome = c.settingsSnapshot.current.playback.pipOnHome,
            playerShowing = c.pipGate.playerShowing,
            state = c.playback.state.value,
            supported = c.pipSupported,
        )
        if (enter) enterPictureInPicture(this)
    }

    /**
     * Leaving the app (Home, another app, screen off, or closing the picture-in-picture window)
     * ends this TV's cast or leaves the one it follows, stops the stream and the sleep timer and
     * gives the display back its own mode; screens restart playback on return. Picture-in-picture
     * itself only pauses the activity.
     */
    override fun onStop() {
        super.onStop()
        if (shouldReleaseOnStop(isChangingConfigurations)) {
            val c = container
            // First, so the other TVs hear "stopped casting" rather than seeing the stream stop.
            c.castController.onForeground(false)
            c.playback.stop()
            c.sleepTimer.cancel()
            displayModes.restore()
        }
    }
}
