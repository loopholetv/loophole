package io.github.loopholetv.ui.cast

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.cast.CastInvite
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.StopReason
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
class TvsPanelFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeCast : CastView {
        override val state = MutableStateFlow<CastState>(CastState.Off)
        override val peers = MutableStateFlow<List<CastPeer>>(emptyList())
        override val invite = MutableStateFlow<CastInvite?>(null)
        override val messages = MutableSharedFlow<String>()
        val joined = mutableListOf<String>()
        override fun startCasting() {}
        override fun stopCasting(reason: StopReason) {}
        override fun join(peerId: String) { joined += peerId }
        override fun leave() {}
        override fun ignoreInvite() {}
    }

    private fun peer(id: String, name: String, status: CastStatus = CastStatus.CASTING) = CastPeer(
        id = id, name = name, host = "10.0.0.20", port = 8000, status = status, accepts = true,
        castId = "c-$id", token = "t-$id", castStartedAtMs = 1L, lastSeenMs = 0, setup = 1,
    )

    @Test
    fun aTvAppearingAboveKeepsFocusOnTheSameTv() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("b", "Bedroom"), peer("k", "Kitchen"))
        val vm = TvsViewModel(cast)
        compose.setContent { LoopholeTheme { TvsPanel(onClose = {}, vm = vm) } }
        compose.waitForIdle()
        compose.onNodeWithText("This TV").assertIsFocused()
        compose.onNodeWithText("This TV").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Bedroom").assertIsFocused()

        // A TV sorting before Bedroom appears.
        cast.peers.value = listOf(peer("a", "Attic"), peer("b", "Bedroom"), peer("k", "Kitchen"))
        compose.waitForIdle()

        compose.onNodeWithText("Bedroom").assertIsFocused()
        compose.onNodeWithText("Bedroom").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        // The TV's menu opens on Join cast, so OK then OK joins.
        compose.onNodeWithText("Join cast").assertIsFocused()
        compose.onNodeWithText("Join cast").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals("OK acts on the TV the user focused", listOf("b"), cast.joined)
    }

    @Test
    fun backClosesTheMenuOntoTheSameTv() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("b", "Bedroom"), peer("k", "Kitchen"))
        val vm = TvsViewModel(cast)
        compose.setContent { LoopholeTheme { TvsPanel(onClose = {}, vm = vm) } }
        compose.waitForIdle()
        compose.onNodeWithText("This TV").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Bedroom").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Kitchen").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Send my setup to this TV").assertExists()

        // The remote's Back, as the system delivers it.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Send my setup to this TV").assertDoesNotExist()
        compose.onNodeWithText("Kitchen").assertIsFocused()
    }

    @Test
    fun focusMovesToTheFirstActionWhenJoinGoes() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("b", "Bedroom"))
        val vm = TvsViewModel(cast)
        compose.setContent { LoopholeTheme { TvsPanel(onClose = {}, vm = vm) } }
        compose.waitForIdle()
        compose.onNodeWithText("This TV").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Bedroom").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Join cast").assertIsFocused()

        // Bedroom stops casting while its menu is open: Join goes, and focus doesn't go with it.
        cast.peers.value = listOf(peer("b", "Bedroom", CastStatus.IDLE))
        compose.waitForIdle()

        compose.onNodeWithText("Join cast").assertDoesNotExist()
        compose.onNodeWithText("Copy this TV's setup").assertIsFocused()
    }
}
