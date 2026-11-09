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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.github.loopholetv.data.db.channelScopeFromArg
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.orDefaultsOnError
import androidx.compose.runtime.remember
import io.github.loopholetv.ui.nav.StartSlot
import io.github.loopholetv.ui.nav.StartPlayback
import io.github.loopholetv.ui.nav.resolveStartPlayback
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.github.loopholetv.ui.nav.AppNav
import io.github.loopholetv.ui.nav.startDestination
import io.github.loopholetv.ui.splash.FirstInProcess
import io.github.loopholetv.ui.splash.SplashKeyGate
import io.github.loopholetv.ui.splash.SplashKeyResult
import io.github.loopholetv.ui.splash.SplashScreen
import io.github.loopholetv.ui.splash.SplashTimeline
import io.github.loopholetv.ui.splash.nextScreen
import io.github.loopholetv.ui.splash.shouldShowSplash
import io.github.loopholetv.ui.theme.LoopholeTheme
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether an activity has been created yet in this process (cold start detection). */
private val firstActivityInProcess = FirstInProcess()

class MainActivity : ComponentActivity() {
    private val splash = SplashTimeline()
    private val splashKeys = SplashKeyGate()
    private var splashShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LoopholeApp
        val repo = app.container.playlistRepository

        // Cold start only; a configuration change recreates the activity without it.
        splashShown = shouldShowSplash(savedInstanceState != null, firstActivityInProcess.take())
        val splashVisible = mutableStateOf(splashShown)

        // Decide the start screen from the DB before showing any NavHost (no flash of the wrong
        // screen), and kick off the once-per-process background refresh only if a playlist exists.
        // This runs behind the splash, which waits for it (up to its cap).
        val hasPlaylist = mutableStateOf<Boolean?>(null)
        // One-shot (StartSlot): taken by the first guide composition, so a later return to the guide
        // (e.g. all playlists deleted -> onboarding -> new playlist) can't replay it.
        var startSlot = StartSlot(null)
        val fresh = savedInstanceState == null
        lifecycleScope.launch {
            val exists = repo.playlists().first().isNotEmpty()
            // Resolve before the refresh starts so it cannot remove the channel in between;
            // never on recreation.
            var start: StartPlayback? = null
            if (exists && fresh) {
                val c = app.container
                val s = c.settingsStore.current()
                start = resolveStartPlayback(
                    s.lastChannelOnStart, c.history::latest, channelScopeFromArg(s.lastGuideScope), c.scopedChannels::contains,
                    c.scopedChannels::resolve,
                )
                startSlot = StartSlot(start)
            }
            if (exists) app.startupRefresher.start()
            hasPlaylist.value = exists
            if (nextScreen(exists, start) != null) splash.markReady(SystemClock.uptimeMillis())
        }

        setContent {
            LoopholeTheme {
                val settingsFlow = remember { app.container.settingsStore.settings.orDefaultsOnError() }
                val settings by settingsFlow.collectAsState(initial = AppSettings())
                val decided = hasPlaylist.value
                Box(Modifier.fillMaxSize()) {
                    if (decided == null) {
                        Box(Modifier.fillMaxSize().background(Tokens.Background))
                    } else {
                        AppNav(
                            rememberNavController(), startDestination(decided),
                            startPlayback = startSlot, confirmExit = settings.confirmExit,
                        )
                    }
                    // Drawn over the next screen, which composes underneath and is revealed by the fade.
                    if (splashVisible.value) {
                        SplashScreen(splash, onDone = { splashVisible.value = false })
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
            val now = SystemClock.uptimeMillis()
            val active = splashShown && splash.isActive(now)
            when (splashKeys.onKey(action == KeyEvent.ACTION_DOWN, event.keyCode, active)) {
                SplashKeyResult.PASS -> Unit
                SplashKeyResult.SKIP -> {
                    splash.skip(now)
                    return true
                }
                SplashKeyResult.CONSUME -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Leaving the app (Home, another app, screen off) stops the stream; screens restart it on return. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as LoopholeApp).container.playback.stop()
    }
}
