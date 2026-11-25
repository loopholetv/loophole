package io.github.loopholetv.ui.settings

import androidx.compose.foundation.text.BasicText as Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsLockRestoreTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredGuardedPageAsksForThePinBeforeShowing() {
        val asked = mutableListOf<SettingsPage>()
        val shown = mutableListOf<SettingsPage>()
        val answer = mutableStateOf(true)
        val tester = StateRestorationTester(compose)
        var hasPin = true
        tester.setContent {
            val pageState = rememberSettingsPage(null)
            PinGate(
                page = pageState.value,
                needed = { needsPin(it, io.github.loopholetv.data.settings.SettingsLock.OFF, hasPin) },
                ask = { asked += pageState.value; answer.value },
                onDenied = {},
            ) {
                shown += pageState.value
                Text("panel ${pageState.value.key}")
            }
            // The page the user had open when the screen was saved.
            if (pageState.value == SettingsPage.Sections && asked.isEmpty()) pageState.value = SettingsPage.Parental
        }
        compose.waitForIdle()
        // Opened on Sections: the first start needed no PIN; the panel moved to Parental afterwards.
        assertEquals(emptyList<SettingsPage>(), asked)

        shown.clear()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf<SettingsPage>(SettingsPage.Parental), asked)
        compose.onNodeWithText("panel parental").assertExists()
    }

    @Test fun cancelledPinKeepsRestoredPageHidden() {
        var denied = 0
        val shown = mutableListOf<SettingsPage>()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val pageState = rememberSettingsPage("parental")
            PinGate(
                page = pageState.value,
                needed = { needsPin(it, io.github.loopholetv.data.settings.SettingsLock.OFF, true) },
                ask = { false },
                onDenied = { denied++ },
            ) { shown += pageState.value }
        }
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(emptyList<SettingsPage>(), shown)
        assertEquals(2, denied)
    }
}
