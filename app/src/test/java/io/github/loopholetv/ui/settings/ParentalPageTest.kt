package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.db.LockCounts
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.data.settings.UNLOCK_CHOICES
import io.github.loopholetv.ui.parental.PinPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalPageTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        counts: LockCounts = LockCounts(0, 0, 0, 0),
        removeAll: suspend () -> Unit = {},
        settings: MutableStateFlow<AppSettings> = MutableStateFlow(AppSettings()),
    ) = SettingsViewModel(
        playlists = flowOf(emptyList()),
        channelCounts = flowOf(emptyMap()),
        epgStatus = { flowOf(null) },
        refreshPlaylist = { _, _ -> error("unused") },
        deletePlaylist = {},
        refreshEpg = { _, _ -> error("unused") },
        stopPlayback = {},
        xtreamEpgUrl = { "" },
        settings = settings,
        updateSettings = { t -> settings.update(t) },
        lockCounts = flowOf(counts),
        removeAllLocks = removeAll,
    )

    @Test fun summaryTexts() {
        assertEquals("Nothing locked", lockSummary(LockCounts(0, 0, 0, 0)))
        assertEquals("2 groups, 5 channels, 1 film, 1 show", lockSummary(LockCounts(2, 5, 1, 1)))
        assertEquals("1 group", lockSummary(LockCounts(1, 0, 0, 0)))
        assertEquals("3 channels, 2 films", lockSummary(LockCounts(0, 3, 2, 0)))
        assertEquals("4 shows", lockSummary(LockCounts(0, 0, 0, 4)))
        assertEquals("1 group, 1 channel", lockSummary(LockCounts(1, 1, 0, 0)))
    }

    @Test fun unlockChoicesInOrder() {
        assertEquals(
            listOf("5 minutes", "15 minutes", "30 minutes", "1 hour", "Until I leave Loophole"),
            UNLOCK_CHOICES.map(::unlockText),
        )
    }

    @Test fun settingsLockTexts() {
        assertEquals(
            listOf("Off", "All of Settings", "Playlists and EPG"),
            listOf(SettingsLock.OFF, SettingsLock.ALL, SettingsLock.PLAYLISTS_EPG).map(::settingsLockText),
        )
    }

    @Test fun parentalSectionNoLongerDisabled() {
        assertFalse("Parental controls" in DISABLED_SECTIONS)
        assertEquals(SettingsPage.Parental, SettingsPage.fromKey("parental"))
        assertEquals("parental", SettingsPage.Parental.key)
    }

    @Test fun setPinRowRunsSet() {
        assertEquals(PinPurpose.SET, pinRowPurpose(hasPin = false))
        assertEquals(PinPurpose.CHANGE, pinRowPurpose(hasPin = true))
    }

    @Test fun turnOffConfirmsThenRemovesAll() = runTest {
        var removed = 0
        val vm = vm(counts = LockCounts(2, 5, 1, 1), removeAll = { removed++ })
        vm.requestTurnOff()
        assertTrue(vm.state.value.confirmTurnOff)
        assertEquals("2 groups, 5 channels, 1 film, 1 show", lockSummary(vm.state.value.lockCounts))
        assertEquals(0, removed)

        vm.cancelTurnOff()
        assertFalse(vm.state.value.confirmTurnOff)
        assertEquals(0, removed)

        vm.requestTurnOff()
        vm.confirmTurnOff()
        assertEquals(1, removed)
        assertFalse(vm.state.value.confirmTurnOff)
    }

    @Test fun unlockPeriodAndSettingsLockAreSaved() = runTest {
        val settings = MutableStateFlow(AppSettings(parental = ParentalSettings(pinHash = "h", pinSalt = "s")))
        val vm = vm(settings = settings)
        vm.setOption { it.copy(parental = it.parental.copy(unlockMinutes = 0, settingsLock = SettingsLock.ALL)) }
        assertEquals(0, settings.value.parental.unlockMinutes)
        assertEquals(SettingsLock.ALL, settings.value.parental.settingsLock)
        assertTrue(settings.value.parental.hasPin)
    }
}
