package io.github.loopholetv.ui.vod

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.cast.CastInvite
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.ui.theme.LoopholeTheme
import kotlinx.coroutines.flow.MutableSharedFlow
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
class VodStarterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val peer = CastPeer(
        id = "b", name = "Bedroom", host = "192.0.2.20", port = 8000, status = CastStatus.CASTING,
        accepts = true, castId = "c", token = "t", castStartedAtMs = 1L, lastSeenMs = 0, setup = 1,
    )

    /** Records every call, in order, so tests can check stop-then-navigate. */
    private class FakeCast(initial: CastState) : CastView {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(initial)
        override val peers = MutableStateFlow<List<CastPeer>>(emptyList())
        override val invite = MutableStateFlow<CastInvite?>(null)
        override val messages = MutableSharedFlow<String>()
        override fun startCasting() {}
        override fun stopCasting(reason: StopReason) { calls += "stop:$reason" }
        override fun join(peerId: String) {}
        override fun leave() { calls += "leave" }
        override fun ignoreInvite() {}
    }

    private val ref = VodRef(VodKind.MOVIE, 1L, "k")

    /** Focus opens on "Cancel"; Left reaches "Continue". */
    private fun press(label: String) {
        if (label == "Continue") {
            compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
            compose.onNodeWithText("Continue").assertIsFocused()
        }
        compose.onNodeWithText(label).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun launch(cast: FakeCast): VodStarter {
        var starter: VodStarter? = null
        compose.setContent {
            LoopholeTheme {
                starter = rememberVodStarter(cast) { r, ms -> cast.calls += "go:$r@$ms" }
            }
        }
        compose.waitForIdle()
        return starter!!
    }

    @Test
    fun gateMapsEachCastState() {
        assertEquals(VodStartGate.GO, vodStartGate(CastState.Off))
        assertEquals(VodStartGate.ASK_STOP_CAST, vodStartGate(CastState.Casting(2)))
        assertEquals(VodStartGate.ASK_LEAVE_CAST, vodStartGate(CastState.Following(peer)))
    }

    @Test
    fun offNavigatesAtOnceWithoutAsking() {
        val cast = FakeCast(CastState.Off)
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 5L) }
        compose.waitForIdle()
        assertEquals(listOf("go:$ref@5"), cast.calls)
    }

    @Test
    fun castingAsksThenStopsTheCastBeforeNavigating() {
        val cast = FakeCast(CastState.Casting(1))
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 7L) }
        compose.onNodeWithText("Playing this will stop casting to the other TVs. Continue?").assertExists()
        assertEquals(emptyList<String>(), cast.calls)
        press("Continue")
        compose.waitForIdle()
        assertEquals(listOf("stop:EXTERNAL_PLAYER", "go:$ref@7"), cast.calls)
        compose.onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test
    fun followingAsksThenLeavesBeforeNavigating() {
        val cast = FakeCast(CastState.Following(peer))
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 0L) }
        compose.onNodeWithText("Playing this will leave the cast. Continue?").assertExists()
        press("Continue")
        compose.waitForIdle()
        assertEquals(listOf("leave", "go:$ref@0"), cast.calls)
    }

    @Test
    fun castEndingWhileAskedNavigatesWithoutStopping() {
        val cast = FakeCast(CastState.Casting(1))
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 3L) }
        cast.state.value = CastState.Off
        press("Continue")
        compose.waitForIdle()
        assertEquals(listOf("go:$ref@3"), cast.calls)
    }

    @Test
    fun stateChangingToAnotherAskRePromptsInsteadOfActing() {
        val cast = FakeCast(CastState.Casting(1))
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 3L) }
        cast.state.value = CastState.Following(peer)
        press("Continue")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), cast.calls)
        compose.onNodeWithText("Playing this will leave the cast. Continue?").assertExists()
        // The dialog stays up, so focus is still on "Continue".
        compose.onNodeWithText("Continue").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf("leave", "go:$ref@3"), cast.calls)
    }

    @Test
    fun cancelClosesTheDialogAndDoesNothingElse() {
        val cast = FakeCast(CastState.Casting(1))
        val starter = launch(cast)
        compose.runOnIdle { starter.start(ref, 0L) }
        press("Cancel")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), cast.calls)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }
}
