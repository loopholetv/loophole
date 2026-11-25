package io.github.loopholetv.ui.settings

import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.CastInvite
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.cast.TvAction
import io.github.loopholetv.ui.cast.TvsViewModel
import io.github.loopholetv.ui.onboarding.AddPlaylistViewModel
import io.github.loopholetv.ui.onboarding.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class SettingsLockTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val everyPage = listOf(
        SettingsPage.Sections, SettingsPage.General, SettingsPage.Playlists, SettingsPage.Playlist(1),
        SettingsPage.Editor(1), SettingsPage.Epg, SettingsPage.Playback, SettingsPage.Remote,
        SettingsPage.Phone, SettingsPage.Recordings, SettingsPage.Details,
        SettingsPage.RemoteButtons(BindingContext.GUIDE), SettingsPage.Parental, SettingsPage.About,
    )
    private val playlistsAndEpg = setOf(
        SettingsPage.Playlists, SettingsPage.Playlist(1), SettingsPage.Editor(1), SettingsPage.Epg,
    )

    @Test fun needsPinTable() {
        for (page in everyPage) {
            for (lock in SettingsLock.entries) {
                assertFalse("$page $lock without a PIN", needsPin(page, lock, hasPin = false))
                val expected = when {
                    page == SettingsPage.Parental -> true
                    lock == SettingsLock.ALL -> true
                    lock == SettingsLock.PLAYLISTS_EPG -> page in playlistsAndEpg
                    else -> false
                }
                assertEquals("$page $lock", expected, needsPin(page, lock, hasPin = true))
            }
        }
    }

    @Test fun parentalPageAlwaysAsksWhenPinSet() {
        assertTrue(needsPin(SettingsPage.Parental, SettingsLock.OFF, hasPin = true))
        assertFalse(needsPin(SettingsPage.Parental, SettingsLock.OFF, hasPin = false))
    }

    @Test fun openingSettingsUnderAllAsks() {
        assertTrue(needsPin(SettingsPage.Sections, SettingsLock.ALL, hasPin = true))
        assertFalse(needsPin(SettingsPage.Sections, SettingsLock.PLAYLISTS_EPG, hasPin = true))
    }

    // --- Sending a setup, and Copy from another TV ---

    private class FakeCast : CastView {
        override val state = MutableStateFlow<CastState>(CastState.Off)
        override val peers = MutableStateFlow<List<CastPeer>>(emptyList())
        override val invite = MutableStateFlow<CastInvite?>(null)
        override val messages = MutableSharedFlow<String>()
        override fun startCasting() {}
        override fun stopCasting(reason: StopReason) {}
        override fun join(peerId: String) {}
        override fun leave() {}
        override fun ignoreInvite() {}
    }

    private object FakeSetup : SetupView {
        override val state = MutableStateFlow<SetupState>(SetupState.Idle)
        override fun sendTo(peerId: String) {}
        override fun copyFrom(peerId: String) {}
        override fun enterCode(code: String) {}
        override fun cancel() {}
    }

    private fun tvs(lock: SettingsLock, pin: Boolean): TvsViewModel {
        val parental = if (pin) ParentalSettings(pinHash = "h", pinSalt = "s", settingsLock = lock) else ParentalSettings(settingsLock = lock)
        return TvsViewModel(FakeCast(), flowOf(false), FakeSetup, flowOf(parental))
    }

    @Test fun aPinGuardsTvsSendWhateverTheSettingsLockSays() {
        assertTrue(tvs(SettingsLock.ALL, pin = true).needsPin(TvAction.SEND_TO))
        assertFalse(tvs(SettingsLock.ALL, pin = true).needsPin(TvAction.JOIN))
        assertTrue(tvs(SettingsLock.PLAYLISTS_EPG, pin = true).needsPin(TvAction.SEND_TO))
        assertTrue(tvs(SettingsLock.OFF, pin = true).needsPin(TvAction.SEND_TO))
        assertFalse(tvs(SettingsLock.ALL, pin = false).needsPin(TvAction.SEND_TO))
        assertTrue(allSettingsGuardsOutside(ParentalSettings(pinHash = "h", pinSalt = "s", settingsLock = SettingsLock.ALL)))
        assertFalse(allSettingsGuardsOutside(ParentalSettings(pinHash = "h", pinSalt = "s", settingsLock = SettingsLock.PLAYLISTS_EPG)))
    }

    // --- Playlists and locks ---

    @Test fun deleteAndLoginChangeAskPinWhenPinSet() = runTest {
        var asked = 0
        assertTrue(askWhenPinSet({ true }) { asked++; true })
        assertEquals(1, asked)
        // Cancelled: the action is refused.
        assertFalse(askWhenPinSet({ true }) { asked++; false })
        assertEquals(2, asked)
    }

    @Test fun disableNeedsNoPin() = runTest {
        // No PIN: nothing is asked.
        var asked = 0
        assertTrue(askWhenPinSet({ false }) { asked++; true })
        assertEquals(0, asked)
        // Disabling (and adding) never goes through the question: the view model acts at once.
        val disabled = mutableListOf<Pair<Long, Boolean>>()
        val vm = SettingsViewModel(
            playlists = flowOf(emptyList()), channelCounts = flowOf(emptyMap()), epgStatus = { flowOf(null) },
            refreshPlaylist = { _, _ -> error("unused") }, deletePlaylist = {},
            refreshEpg = { _, _ -> error("unused") }, stopPlayback = {}, xtreamEpgUrl = { "" },
            setEnabled = { id, on -> disabled += id to on },
        )
        vm.setEnabled(3, false)
        assertEquals(listOf(3L to false), disabled)
    }

    private fun saved(type: PlaylistType, url: String? = null, server: String? = null, user: String? = null, pass: String? = null, login: String? = null) =
        PlaylistEntity(
            id = 5, name = "Mine", type = type, url = url, xtreamServer = server, xtreamUser = user, xtreamPass = pass,
            epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null, portalLogin = login,
        )

    private fun form(p: PlaylistEntity?) = AddPlaylistViewModel(
        addM3u = { _, _, _ -> error("unused") },
        addXtream = { _, _, _ -> error("unused") },
        refreshEpg = { _, _ -> },
        loadPlaylist = { p },
        editId = p?.id,
    )

    @Test fun savingAnUnchangedSourceChangesNothing() = runTest {
        assertFalse(form(saved(PlaylistType.M3U, url = "http://a/x.m3u")).changesSource())
        assertFalse(form(saved(PlaylistType.XTREAM, server = "http://s", user = "u", pass = "p")).changesSource())
    }

    @Test fun changingUrlOrLoginIsASourceChange() = runTest {
        val m3u = form(saved(PlaylistType.M3U, url = "http://a/x.m3u"))
        m3u.setUrl("http://b/x.m3u")
        assertTrue(m3u.changesSource())

        val xtream = form(saved(PlaylistType.XTREAM, server = "http://s", user = "u", pass = "p"))
        xtream.setUser("v")
        assertTrue(xtream.changesSource())
        xtream.setUser("u")
        assertFalse(xtream.changesSource())
        xtream.setPass("q")
        assertTrue(xtream.changesSource())

        val kind = form(saved(PlaylistType.M3U, url = "http://a/x.m3u"))
        kind.setMode(Mode.XTREAM)
        assertTrue(kind.changesSource())
    }

    @Test fun portalFieldsAreSourceChanges() = runTest {
        val portal = form(saved(PlaylistType.STALKER, url = "http://p/c", login = "me"))
        assertFalse(portal.changesSource())
        portal.setPortalLogin("you")
        assertTrue(portal.changesSource())
    }

    @Test fun repickingALocalFileIsASourceChange() = runTest {
        val file = form(saved(PlaylistType.M3U_FILE, url = "content://tree/a.m3u"))
        assertFalse(file.changesSource())
        file.setFile("content://tree/b.m3u")
        assertTrue(file.changesSource())
        file.setFile("content://tree/a.m3u")
        assertFalse(file.changesSource())
    }

    @Test fun aNameOnlyEditIsNotASourceChange() = runTest {
        val m3u = form(saved(PlaylistType.M3U, url = "http://a/x.m3u"))
        m3u.setName("Another name")
        assertFalse(m3u.changesSource())
    }
}
