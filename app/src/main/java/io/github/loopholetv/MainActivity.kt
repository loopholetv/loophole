package io.github.loopholetv

import android.os.Bundle
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
import io.github.loopholetv.ui.nav.resolveStartPlayback
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.github.loopholetv.ui.nav.AppNav
import io.github.loopholetv.ui.nav.startDestination
import io.github.loopholetv.ui.theme.LoopholeTheme
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LoopholeApp
        val repo = app.container.playlistRepository

        // Decide the start screen from the DB before showing any NavHost (no flash of the wrong
        // screen), and kick off the once-per-process background refresh only if a playlist exists.
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

        setContent {
            LoopholeTheme {
                val settingsFlow = remember { app.container.settingsStore.settings.orDefaultsOnError() }
                val settings by settingsFlow.collectAsState(initial = AppSettings())
                val decided = hasPlaylist.value
                if (decided == null) {
                    Box(Modifier.fillMaxSize().background(Tokens.Background))
                } else {
                    AppNav(
                        rememberNavController(), startDestination(decided),
                        startPlayback = startSlot, confirmExit = settings.confirmExit,
                    )
                }
            }
        }
    }

    /** Leaving the app (Home, another app, screen off) stops the stream; screens restart it on return. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as LoopholeApp).container.playback.stop()
    }
}
