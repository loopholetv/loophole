package io.github.loopholetv.ui.guide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.nav.Routes
import io.github.loopholetv.ui.nav.copyFromDestination
import io.github.loopholetv.ui.nav.openCopyFrom
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class GuideEmptyStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) {}
        override fun enterCode(code: String) {}
        override fun cancel() {}
    }

    @Test fun theCopyButtonGoesWithTheNoPlaylistHintOnly() {
        assertTrue(showsCopyFromAnotherTv(emptyHintFor(false, ChannelScope.All, listEmpty = true, noPlaylists = true)))
        assertFalse(showsCopyFromAnotherTv(emptyHintFor(true, ChannelScope.All, listEmpty = true)))
        assertFalse(showsCopyFromAnotherTv(emptyHintFor(false, ChannelScope.Favourites, listEmpty = true)))
        assertFalse(showsCopyFromAnotherTv(emptyHintFor(false, ChannelScope.All, listEmpty = false)))
    }

    @Test fun anotherEmptyListShowsItsHintWithoutTheButton() {
        val focus = FocusRequester()
        compose.setContent {
            LoopholeTheme { GuideEmptyState(ALL_PLAYLISTS_OFF, onCopyFromAnotherTv = {}, buttonFocus = focus) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(ALL_PLAYLISTS_OFF).assertIsDisplayed()
        compose.onNodeWithText("Copy from another TV").assertDoesNotExist()
    }

    @Test fun theButtonTakesFocusWhenTheEmptyStateShowsLateButOnlyOnce() {
        // The list loads empty before the playlists are known: the plain empty list shows first.
        val hint = androidx.compose.runtime.mutableStateOf(ALL_PLAYLISTS_OFF)
        val focus = FocusRequester()
        val elsewhere = FocusRequester()
        val tick = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            LoopholeTheme {
                androidx.compose.foundation.layout.Column {
                    io.github.loopholetv.ui.onboarding.PillButton("Elsewhere", selected = false, modifier = androidx.compose.ui.Modifier.focusRequester(elsewhere)) {}
                    // Read here, so changing it recomposes the screen.
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(tick.intValue.dp))
                    GuideEmptyState(hint.value, onCopyFromAnotherTv = {}, buttonFocus = focus)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { elsewhere.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("Elsewhere").assertIsFocused()

        hint.value = NO_PLAYLIST_HINT
        compose.waitForIdle()
        compose.onNodeWithText("Copy from another TV").assertIsFocused()

        // Once the user moves on, a recomposition doesn't take focus back.
        compose.runOnUiThread { elsewhere.requestFocus() }
        compose.waitForIdle()
        tick.intValue++
        compose.waitForIdle()
        compose.onNodeWithText("Elsewhere").assertIsFocused()
    }

    @Test fun okOnTheButtonOpensTheTvPickerAndBackReturnsToTheGuide() {
        lateinit var nav: NavHostController
        val focus = FocusRequester()
        compose.setContent {
            LoopholeTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.GUIDE) {
                    composable(Routes.GUIDE) {
                        GuideEmptyState(NO_PLAYLIST_HINT, onCopyFromAnotherTv = { nav.openCopyFrom() }, buttonFocus = focus)
                        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                    copyFromDestination(MutableStateFlow<List<CastPeer>>(emptyList()), FakeSetup())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(NO_PLAYLIST_HINT).assertIsDisplayed()
        compose.onNodeWithText("Copy from another TV").assertIsFocused()

        compose.onNodeWithText("Copy from another TV").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(Routes.SETUP_COPY_FROM, nav.currentDestination?.route)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("back to the guide, not onboarding", Routes.GUIDE, nav.currentDestination?.route)
        compose.onNodeWithText("Copy from another TV").assertIsDisplayed()
    }
}
