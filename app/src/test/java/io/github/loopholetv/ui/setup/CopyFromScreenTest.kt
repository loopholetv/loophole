package io.github.loopholetv.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.nav.Routes
import io.github.loopholetv.ui.nav.copyFromDestination
import io.github.loopholetv.ui.nav.openCopyFrom
import io.github.loopholetv.ui.onboarding.AddPlaylistScreen
import io.github.loopholetv.ui.onboarding.AddPlaylistViewModel
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class CopyFromScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        val copied = mutableListOf<String>()
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) { copied += peerId }
        override fun enterCode(code: String) {}
        override fun cancel() {}
    }

    private fun peer(id: String, name: String, setup: Int = 1) = CastPeer(
        id = id, name = name, host = "10.0.0.20", port = 8000, status = CastStatus.IDLE, accepts = true,
        castId = null, token = null, castStartedAtMs = null, lastSeenMs = 0, setup = setup,
    )

    private fun formVm() = AddPlaylistViewModel(
        addM3u = { _, _, _ -> ImportResult.Success(1, 1) },
        addXtream = { _, _, _ -> ImportResult.Success(1, 1) },
        refreshEpg = { _, _ -> },
    )

    @Test
    fun onboardingCopyOpensPicker() {
        val peers = MutableStateFlow<List<CastPeer>>(emptyList())
        val setup = FakeSetup()
        val vm = formVm()
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LoopholeTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.ONBOARDING) {
                    composable(Routes.ONBOARDING) {
                        AddPlaylistScreen(onDone = {}, onSkip = {}, onCopyFromAnotherTv = { nav.openCopyFrom() }, vm = vm)
                    }
                    copyFromDestination(peers, setup)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Copy from another TV").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(Routes.SETUP_COPY_FROM, nav.currentDestination?.route)
        compose.onNodeWithText("Looking for TVs…").assertIsDisplayed()

        // Nobody answers for a while.
        compose.mainClock.advanceTimeBy(SEARCH_MS)
        compose.onNodeWithText("No other Loophole TVs found on this network.").assertIsDisplayed()

        // TVs turn up, by name; an older one can't be picked.
        peers.value = listOf(peer("k", "Kitchen"), peer("d", "Den", setup = 0))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Den is on an older version of Loophole.").assertIsDisplayed()
        compose.onNodeWithText("Den").assertIsFocused()
        compose.onNodeWithText("Den").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Kitchen").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("k"), setup.copied)

        // Back returns to the first-start screen.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(Routes.ONBOARDING, nav.currentDestination?.route)
    }

    @Test
    fun focusMovesToTheFirstTvWhenTheFocusedOneLeaves() {
        val peers = mutableStateOf(listOf(peer("b", "Bedroom"), peer("k", "Kitchen")))
        compose.setContent { LoopholeTheme { CopyFromScreen(peers.value, onPick = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Bedroom").assertIsFocused()
        compose.onNodeWithText("Bedroom").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Kitchen").assertIsFocused()

        peers.value = listOf(peer("b", "Bedroom"), peer("l", "Living room"))
        compose.waitForIdle()

        compose.onNodeWithText("Bedroom").assertIsFocused()
    }
}
