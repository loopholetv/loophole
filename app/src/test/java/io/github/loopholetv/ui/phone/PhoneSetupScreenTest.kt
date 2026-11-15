package io.github.loopholetv.ui.phone

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.phone.PairResult
import io.github.loopholetv.phone.PairedPhone
import io.github.loopholetv.phone.PairingStore
import io.github.loopholetv.phone.PhoneAddress
import io.github.loopholetv.phone.PhoneJob
import io.github.loopholetv.phone.PhoneJobKind
import io.github.loopholetv.phone.PhoneJobPhase
import io.github.loopholetv.phone.PhonePairing
import io.github.loopholetv.ui.theme.LoopholeTheme
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class PhoneSetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeStore : PairingStore {
        val phones = MutableStateFlow<List<PairedPhone>>(emptyList())
        override suspend fun pairedPhones() = phones.value
        override val pairedCount = phones.map { it.size }
        override suspend fun savePairedPhones(list: List<PairedPhone>) { phones.value = list }
        override suspend fun phonePort(): Int? = null
        override suspend fun savePhonePort(port: Int) {}
    }

    private val pairing = PhonePairing(FakeStore(), clock = { 0L })
    private val address = MutableStateFlow<PhoneAddress?>(PhoneAddress("192.168.1.24", 8765))
    private val job = MutableStateFlow<PhoneJob?>(null)

    private fun show(onDone: () -> Unit = {}) {
        compose.setContent { LoopholeTheme { PhoneSetupScreen(onDone, address, pairing, job) } }
        compose.waitForIdle()
    }

    @Test fun showsAddressAndCode() {
        show()
        compose.onNodeWithText("Set up from your phone").assertExists()
        compose.onNodeWithText("Your phone or laptop must be on the same Wi‑Fi as this TV.").assertExists()
        compose.onNodeWithText("Scan the code with your phone's camera").assertExists()
        compose.onNodeWithText("or type this into a browser:").assertExists()
        compose.onNodeWithText("192.168.1.24:8765").assertExists()
        compose.onNodeWithText("Enter this code on the page").assertExists()
        val code = pairing.code.value
        assertNotNull(code)
        code!!.forEachIndexed { i, digit -> compose.onNodeWithTag(codeDigitTag(i), useUnmergedTree = true).assertTextEquals(digit.toString()) }
        compose.onNodeWithText("Waiting for a phone…").assertExists()
        compose.onNodeWithText("Done").assertIsFocused()
    }

    @Test fun noAddressShowsNetworkMessage() {
        address.value = null
        show()
        compose.onNodeWithText("Connect this TV to your home network to use the phone page.").assertExists()
        compose.onNodeWithText("Scan the code with your phone's camera").assertDoesNotExist()
        compose.onNodeWithText("Enter this code on the page").assertDoesNotExist()
        compose.onNodeWithText("Done").assertIsFocused()
    }

    @Test fun connectedUpdatesStatus() {
        show()
        val result = runBlocking { pairing.pair(pairing.code.value!!, InetAddress.getLoopbackAddress(), null) }
        assertTrue(result is PairResult.Paired)
        compose.waitForIdle()
        compose.onNodeWithText("Phone connected").assertExists()
        compose.onNodeWithText("Waiting for a phone…").assertDoesNotExist()
    }

    @Test fun jobProgressShows() {
        show()
        job.value = PhoneJob(1, PhoneJobKind.ADD, null, "Home", PhoneJobPhase.LoadingChannels(120))
        compose.waitForIdle()
        compose.onNodeWithText("Loading channels… 120").assertExists()
        job.value = job.value!!.copy(playlistId = 4, phase = PhoneJobPhase.LoadingGuide(50))
        compose.waitForIdle()
        compose.onNodeWithText("Loading TV guide… 50 programmes").assertExists()
        job.value = job.value!!.copy(phase = PhoneJobPhase.Failed("Couldn't load the playlist."))
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't load the playlist.").assertExists()
    }

    @Test fun aFailureFromBeforeTheScreenOpenedIsNotShown() {
        job.value = PhoneJob(1, PhoneJobKind.REFRESH, 4, "Home", PhoneJobPhase.Failed("Couldn't load the playlist."))
        show()
        compose.onNodeWithText("Couldn't load the playlist.").assertDoesNotExist()
        compose.onNodeWithText("Waiting for a phone…").assertExists()
    }

    @Test fun opensPairingOnEnterAndClosesOnLeave() {
        var showing by mutableStateOf(true)
        assertNull(pairing.code.value)
        compose.setContent { LoopholeTheme { if (showing) PhoneSetupScreen({}, address, pairing, job) } }
        compose.waitForIdle()
        assertNotNull(pairing.code.value)
        showing = false
        compose.waitForIdle()
        assertNull(pairing.code.value)
    }

    @Test fun doneAndBackClose() {
        var done = 0
        show(onDone = { done++ })
        compose.onNodeWithText("Done").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, done)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(2, done)
    }
}
