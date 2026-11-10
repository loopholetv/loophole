package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun playlist(
        id: Long,
        name: String = "List $id",
        type: PlaylistType = PlaylistType.M3U,
        url: String? = "http://example.com/list$id.m3u",
        epgUrls: String = "",
        xtream: XtreamCredentials? = null,
        enabled: Boolean = true,
        lastRefreshedAt: Instant? = null,
        lastError: String? = null,
    ) = PlaylistEntity(
        id = id,
        name = name,
        type = type,
        url = url,
        xtreamServer = xtream?.server,
        xtreamUser = xtream?.username,
        xtreamPass = xtream?.password,
        epgUrlsFromSource = epgUrls,
        manualEpgUrl = null,
        lastRefreshedAt = lastRefreshedAt,
        lastError = lastError,
        enabled = enabled,
    )

    private class Fakes(initial: List<PlaylistEntity>) {
        val playlists = MutableStateFlow(initial)
        val counts = MutableStateFlow(initial.associate { it.id to 3 })
        val statuses = MutableStateFlow<Map<Long, EpgStatusEntity?>>(emptyMap())
        var refreshPlaylist: suspend (Long, (Int) -> Unit) -> ImportResult = { id, _ -> ImportResult.Success(id, 3) }
        var refreshEpg: suspend (Long, (Int) -> Unit) -> EpgStatusEntity =
            { id, _ -> EpgStatusEntity(id, Instant.EPOCH, 0, ok = true, message = null) }
        val deleted = mutableListOf<Long>()
        val renamed = mutableListOf<Pair<Long, String>>()
        val enabledSet = mutableListOf<Pair<Long, Boolean>>()
        val uaSet = mutableListOf<Pair<Long, String?>>()
        val optionsSet = mutableListOf<Triple<Long, Int?, Boolean?>>()
        var refreshAll: suspend ((Long, Int) -> Unit) -> Map<Long, ImportResult> = { emptyMap() }
        var refreshAllCalls = 0
        val sources = MutableStateFlow<Map<Long, List<EpgSourceEntity>>>(emptyMap())
        val removedSources = mutableListOf<Long>()
        val useSourceSet = mutableListOf<Pair<Long, Boolean>>()
        val playing = MutableStateFlow<Long?>(null)
        private var nextSourceId = 100L
        var stops = 0
        /** Playback stops and playlist on/off writes, in call order. */
        val events = mutableListOf<String>()
        val playlistRefreshes = mutableListOf<Long>()
        val epgRefreshes = mutableListOf<Long>()
        val settings = MutableStateFlow(AppSettings())
        var clears = 0

        fun vm(pipSupported: Boolean = false) = SettingsViewModel(
            playlists = playlists,
            channelCounts = counts,
            epgStatus = { id -> statuses.map { it[id] } },
            refreshPlaylist = { id, p -> playlistRefreshes += id; refreshPlaylist(id, p) },
            deletePlaylist = { id -> deleted += id; playlists.update { l -> l.filterNot { it.id == id } } },
            refreshEpg = { id, p -> epgRefreshes += id; refreshEpg(id, p) },
            stopPlayback = { stops++; events += "stop" },
            xtreamEpgUrl = { c -> "${c.server}/xmltv.php?username=${c.username}&password=${c.password}" },
            zone = ZoneId.of("UTC"),
            locale = Locale.US,
            settings = settings,
            updateSettings = { t -> settings.update(t) },
            clearEpg = { clears++ },
            rename = { id, n -> renamed += id to n },
            setEnabled = { id, on -> enabledSet += id to on; events += "enabled:$id=$on" },
            setPlaylistUserAgent = { id, ua -> uaSet += id to ua },
            setUpdateOptions = { id, h, s -> optionsSet += Triple(id, h, s) },
            refreshAll = { p -> refreshAllCalls++; refreshAll(p) },
            epgSources = { id -> sources.map { it[id].orEmpty() } },
            addEpgSource = { id, url ->
                sources.update { m ->
                    val l = m[id].orEmpty()
                    m + (id to l + EpgSourceEntity(nextSourceId++, id, url, l.size))
                }
            },
            removeEpgSource = { sid ->
                removedSources += sid
                sources.update { m -> m.mapValues { (_, l) -> l.filterNot { it.id == sid } } }
            },
            setUseSourceEpg = { id, use -> useSourceSet += id to use; playlists.update { l -> l.map { if (it.id == id) it.copy(useSourceEpg = use) else it } } },
            playingPlaylistId = playing,
            pipSupported = pipSupported,
        )
    }

    @Test fun deletingLastPlaylistDeletesAndStopsPlayback() = runTest {
        val f = Fakes(listOf(playlist(1, name = "Home")))
        val vm = f.vm()
        vm.requestDelete(1)
        assertEquals("Home", vm.state.value.confirmDelete?.name)
        vm.confirmDelete()
        assertEquals(listOf(1L), f.deleted)
        assertNull(vm.state.value.confirmDelete)
        assertEquals(1, f.stops)
        assertTrue(vm.state.value.playlists.isEmpty())
    }

    @Test fun deletingOneOfTwoStaysInSettings() = runTest {
        val f = Fakes(listOf(playlist(1), playlist(2)))
        val vm = f.vm()
        vm.requestDelete(2)
        vm.confirmDelete()
        assertEquals(listOf(2L), f.deleted)
        assertEquals(listOf(1L), vm.state.value.playlists.map { it.id })
    }

    @Test fun cancelDeleteKeepsPlaylist() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.requestDelete(1)
        vm.cancelDelete()
        assertNull(vm.state.value.confirmDelete)
        assertTrue(f.deleted.isEmpty())
        assertEquals(0, f.stops)
    }

    @Test fun deleteStopsPlayback() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.requestDelete(1)
        vm.confirmDelete()
        assertEquals(1, f.stops)
    }

    @Test fun epgStatusTextFormatted() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.statuses.value = mapOf(1L to EpgStatusEntity(1, Instant.parse("2026-11-07T21:05:00Z"), 42, ok = true, message = null))
        val vm = f.vm()
        assertEquals(
            "EPG is successfully updated for 42 channels on 7/11/26 9:05 PM",
            vm.state.value.epg.single().statusText,
        )
        assertFalse(vm.state.value.epg.single().statusIsError)
    }

    // On Settings → EPG itself "No TV guide source. Add one in Settings → EPG." makes no
    // sense, and it stayed after a link was saved: with simply no source yet nothing is shown.
    @Test fun noSourceStatusIsNotShownOnTheEpgPage() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val msg = "No TV guide source. Add one in Settings → EPG."
        f.statuses.value = mapOf(1L to EpgStatusEntity(1, null, 0, ok = false, message = msg))
        val vm = f.vm()
        val epg = vm.state.value.epg.single()
        assertEquals(null, epg.statusText)
        assertFalse(epg.statusIsError)
    }

    @Test fun noSourceAfterAnEarlierSuccessShowsNothingInRedEither() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val msg = "No TV guide source. Add one in Settings → EPG."
        // fail() keeps the last success time; still no red line.
        f.statuses.value = mapOf(1L to EpgStatusEntity(1, Instant.parse("2026-11-07T21:05:00Z"), 3, ok = false, message = msg))
        val epg = f.vm().state.value.epg.single()
        assertEquals(null, epg.statusText)
        assertFalse(epg.statusIsError)
    }

    @Test fun realUpdateFailureIsStillShownInRed() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.statuses.value = mapOf(1L to EpgStatusEntity(1, null, 0, ok = false, message = "Couldn't reach the TV guide server."))
        val epg = f.vm().state.value.epg.single()
        assertEquals("Couldn't reach the TV guide server.", epg.statusText)
        assertTrue(epg.statusIsError)
    }

    @Test fun failedStatusShowsRedactedMessage() = runTest {
        val creds = XtreamCredentials("http://srv.example:8080", "alice77", "s3cretPass")
        val f = Fakes(listOf(playlist(1, type = PlaylistType.XTREAM, url = null, xtream = creds)))
        f.statuses.value = mapOf(
            1L to EpgStatusEntity(
                1, null, 0, ok = false,
                message = "Failed http://srv.example:8080/xmltv.php?username=alice77&password=s3cretPass",
            ),
        )
        val vm = f.vm()
        val epg = vm.state.value.epg.single()
        val text = epg.statusText.orEmpty()
        assertTrue(epg.statusIsError)
        assertTrue(text, text.startsWith("Failed http://srv.example:8080/xmltv.php"))
        assertFalse(text, "alice77" in text)
        assertFalse(text, "s3cretPass" in text)
    }

    @Test fun epgSourcesAreRedacted() = runTest {
        val creds = XtreamCredentials("http://srv.example:8080", "alice77", "s3cretPass")
        val f = Fakes(
            listOf(
                playlist(1, type = PlaylistType.XTREAM, url = null, xtream = creds),
                playlist(2, epgUrls = "http://epg.example/guide.xml.gz?user=carol99&pass=hunter22"),
            ),
        )
        f.sources.value = mapOf(1L to listOf(EpgSourceEntity(7, 1, "http://bob:pw123@guide.example/epg.xml?token=abc123xyz", 0)))
        val vm = f.vm()
        val epg = vm.state.value.epg
        val text = (epg.flatMap { it.ownGuide } + epg.flatMap { e -> e.sources.map { it.url } }).joinToString("\n")
        for (secret in listOf("alice77", "s3cretPass", "bob", "pw123", "abc123xyz", "carol99", "hunter22")) {
            assertFalse("$secret leaked in:\n$text", secret in text)
        }
        assertTrue(text, "http://srv.example:8080/xmltv.php" in text)
        assertTrue(text, "guide.example/epg.xml" in text)
        assertEquals(listOf(7L), epg[0].sources.map { it.id })
    }

    @Test fun updateTriggersRefreshWithProgress() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val gate = CompletableDeferred<ImportResult>()
        var report: (Int) -> Unit = {}
        f.refreshPlaylist = { _, p -> report = p; gate.await() }
        val vm = f.vm()
        vm.updatePlaylist(1)
        assertEquals(listOf(1L), f.playlistRefreshes)
        assertEquals(PlaylistOp.Updating(0), vm.state.value.playlists.single().op)
        report(250)
        assertEquals(PlaylistOp.Updating(250), vm.state.value.playlists.single().op)
        assertEquals("Updating… 250 channels", vm.state.value.playlists.single().statusText)
        vm.updatePlaylist(1) // ignored while running
        assertEquals(1, f.playlistRefreshes.size)
        gate.complete(ImportResult.Success(1, 300))
        assertEquals(PlaylistOp.Updated(300), vm.state.value.playlists.single().op)
    }

    @Test fun updateFailureShowsMessage() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.refreshPlaylist = { _, _ ->
            ImportResult.Failure(ImportError.AuthFailed, "Server refused access (HTTP 403)")
        }
        val vm = f.vm()
        vm.updatePlaylist(1)
        val text = (vm.state.value.playlists.single().op as PlaylistOp.Failed).message
        assertEquals("The server rejected the username or password.", text)
        assertTrue(vm.state.value.playlists.single().op is PlaylistOp.Failed)
    }

    @Test fun updateFailureNeverShowsRawMessage() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.refreshPlaylist = { _, _ ->
            ImportResult.Failure(ImportError.Http(403), "Server returned HTTP 403 for http://h/u/johnsmith/letmein/m3u")
        }
        val vm = f.vm()
        vm.updatePlaylist(1)
        val text = (vm.state.value.playlists.single().op as PlaylistOp.Failed).message
        assertEquals("The server returned an error (403).", text)
        assertFalse("johnsmith" in text || "letmein" in text)
    }

    @Test fun epgSuccessWithPartialFailureAppendsMessage() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.statuses.value = mapOf(
            1L to EpgStatusEntity(1, Instant.parse("2026-11-07T21:05:00Z"), 42, ok = true, message = "(1 of 2 guide sources failed)"),
        )
        val vm = f.vm()
        assertEquals(
            "EPG is successfully updated for 42 channels on 7/11/26 9:05 PM (1 of 2 guide sources failed)",
            vm.state.value.epg.single().statusText,
        )
        assertFalse(vm.state.value.epg.single().statusIsError)
    }

    @Test fun updateEpgShowsProgressThenStoredStatus() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val gate = CompletableDeferred<EpgStatusEntity>()
        f.refreshEpg = { _, _ -> gate.await() }
        val vm = f.vm()
        vm.updateEpg(1)
        assertEquals(listOf(1L), f.epgRefreshes)
        assertTrue(vm.state.value.epg.single().updating)
        assertEquals("Updating guide…", vm.state.value.epg.single().statusText)
        val done = EpgStatusEntity(1, Instant.parse("2026-01-02T03:04:00Z"), 7, ok = true, message = null)
        f.statuses.value = mapOf(1L to done)
        gate.complete(done)
        assertFalse(vm.state.value.epg.single().updating)
        assertEquals("EPG is successfully updated for 7 channels on 2/1/26 3:04 AM", vm.state.value.epg.single().statusText)
    }

    @Test fun channelCountShown() = runTest {
        val f = Fakes(listOf(playlist(1, name = "Home")))
        f.counts.value = mapOf(1L to 1234)
        val vm = f.vm()
        val row = vm.state.value.playlists.single()
        assertEquals("Home", row.name)
        assertTrue(row.secondaryText, row.secondaryText.startsWith("1,234 channels · "))
    }

    @Test fun optionsReflectStore() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        assertEquals(AppSettings(), vm.state.value.options)
        f.settings.value = AppSettings(epgUpdateHours = 6)
        assertEquals(6, vm.state.value.options.epgUpdateHours)
    }

    @Test fun setOptionWritesThroughStore() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setOption { it.copy(confirmExit = false) }
        assertFalse(f.settings.value.confirmExit)
        assertFalse(vm.state.value.options.confirmExit)
    }

    @Test fun intervalAndDaysText() {
        assertEquals("Off", intervalText(0))
        assertEquals("Every 12 hours", intervalText(12))
        assertEquals("None", pastDaysText(0))
        assertEquals("1 day", pastDaysText(1))
        assertEquals("5 days", pastDaysText(5))
        assertEquals("On", onOff(true))
        assertEquals("Off", onOff(false))
    }

    @Test fun clearEpgNeedsConfirmation() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.requestClearEpg()
        assertTrue(vm.state.value.confirmClearEpg)
        assertEquals(0, f.clears)
        vm.confirmClearEpg()
        assertEquals(1, f.clears)
        assertFalse(vm.state.value.confirmClearEpg)
        assertTrue(vm.state.value.epgCleared)
    }

    @Test fun cancelClearEpgDoesNothing() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.requestClearEpg()
        vm.cancelClearEpg()
        assertFalse(vm.state.value.confirmClearEpg)
        assertFalse(vm.state.value.epgCleared)
        assertEquals(0, f.clears)
    }

    @Test fun playlistUpdateChainsEpgWhenOptionOn() = runTest {
        // refreshPlaylist is RefreshCoordinator.refreshPlaylist(chainEpg = false): the VM always
        // updates the guide after a manual update (no recent-skip) and shows its progress.
        val f = Fakes(listOf(playlist(1)))
        val gate = CompletableDeferred<EpgStatusEntity>()
        f.refreshEpg = { _, _ -> gate.await() }
        val vm = f.vm()
        vm.updatePlaylist(1)
        assertEquals(listOf(1L), f.playlistRefreshes)
        assertEquals(listOf(1L), f.epgRefreshes)
        assertTrue(vm.state.value.epg.single().updating)
        assertEquals("Updating guide…", vm.state.value.epg.single().statusText)
        gate.complete(EpgStatusEntity(1, Instant.EPOCH, 0, ok = true, message = null))
    }

    @Test fun playlistUpdateDoesNotChainEpgWhenOff() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.settings.value = AppSettings(epgUpdateOnPlaylistChange = false)
        val vm = f.vm()
        vm.updatePlaylist(1)
        assertTrue(f.epgRefreshes.isEmpty())
    }

    @Test fun turnedOffPlaylistUpdateDoesNotChainEpg() = runTest {
        // A manual update of a turned-off playlist works, but its guide isn't updated.
        val f = Fakes(listOf(playlist(1).copy(enabled = false)))
        val vm = f.vm()
        vm.updatePlaylist(1)
        assertEquals(listOf(1L), f.playlistRefreshes)
        assertTrue(f.epgRefreshes.isEmpty())
    }

    @Test fun failedPlaylistUpdateDoesNotChainEpg() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.refreshPlaylist = { _, _ -> ImportResult.Failure(ImportError.Unexpected, "") }
        val vm = f.vm()
        vm.updatePlaylist(1)
        assertTrue(f.epgRefreshes.isEmpty())
    }

    // --- Playing playlist, update all and interval choices ---

    @Test fun disablingPlayingPlaylistStopsPlayback() = runTest {
        val f = Fakes(listOf(playlist(1), playlist(2)))
        f.playing.value = 2
        val vm = f.vm()
        vm.setEnabled(2, false)
        assertEquals(1, f.stops)
        assertEquals(listOf(2L to false), f.enabledSet)
        // Playback stops before the playlist is turned off.
        assertEquals(listOf("stop", "enabled:2=false"), f.events)
    }

    @Test fun disablingOtherPlaylistKeepsPlayback() = runTest {
        val f = Fakes(listOf(playlist(1), playlist(2)))
        f.playing.value = 1
        val vm = f.vm()
        vm.setEnabled(2, false)
        vm.setEnabled(1, true)
        assertEquals(0, f.stops)
        assertEquals(listOf(2L to false, 1L to true), f.enabledSet)
    }

    @Test fun updateAllCallsCoordinatorAndShowsProgress() = runTest {
        val f = Fakes(listOf(playlist(1, name = "Home"), playlist(2, name = "Sport")))
        val gate = CompletableDeferred<Unit>()
        var report: (Long, Int) -> Unit = { _, _ -> }
        f.refreshAll = { p ->
            report = p
            gate.await()
            mapOf(1L to ImportResult.Success(1, 1360), 2L to ImportResult.Failure(ImportError.AuthFailed, "raw http://x/u/p"))
        }
        val vm = f.vm()
        vm.updateAll()
        assertEquals(1, f.refreshAllCalls)
        assertTrue(vm.state.value.updatingAll)
        report(1, 250)
        assertEquals("Updating Home… 250 channels", vm.state.value.updateAllText)
        report(2, 1360)
        assertEquals("Updating Sport… 1,360 channels", vm.state.value.updateAllText)
        vm.updateAll() // ignored while running
        assertEquals(1, f.refreshAllCalls)
        gate.complete(Unit)
        assertFalse(vm.state.value.updatingAll)
        assertNull(vm.state.value.updateAllText)
        val rows = vm.state.value.playlists
        assertEquals(PlaylistOp.Updated(1360), rows[0].op)
        assertEquals(PlaylistOp.Failed("The server rejected the username or password."), rows[1].op)
        // The coordinator chains the EPG itself; the VM must not add a second refresh.
        assertTrue(f.epgRefreshes.isEmpty())
    }

    @Test fun intervalChoicesStartWithSameAsDefault() {
        val choices = playlistIntervalChoices(AppSettings())
        assertEquals("Same as default (Every 24 hours)", choices.first().second)
        assertNull(choices.first().first)
        assertEquals(listOf<Int?>(null) + AppSettings.INTERVAL_HOURS, choices.map { it.first })
        assertEquals("Off", choices[1].second)
        val onStart = playlistOnStartChoices(AppSettings(playlistUpdateOnStart = false))
        assertEquals(listOf("Same as default (Off)", "On", "Off"), onStart.map { it.second })
        assertEquals(listOf(null, true, false), onStart.map { it.first })
    }

    @Test fun pickingSameAsDefaultStoresNull() = runTest {
        val p = playlist(1).copy(updateHours = 6, updateOnStart = false)
        val f = Fakes(listOf(p))
        val vm = f.vm()
        vm.setPlaylistInterval(1, null)
        assertEquals(Triple(1L, null, false), f.optionsSet.last())
        vm.setPlaylistOnStart(1, null)
        // Each pick keeps the other option's stored value (the fake doesn't write back).
        assertEquals(Triple(1L, 6, null), f.optionsSet.last())
        vm.setPlaylistInterval(1, 0)
        assertEquals(Triple(1L, 0, false), f.optionsSet.last())
    }

    @Test fun playlistUserAgentBlankStoredAsNull() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setPlaylistUserAgent(1, "  VLC/3.0  ")
        vm.setPlaylistUserAgent(1, "   ")
        assertEquals(listOf(1L to "VLC/3.0", 1L to null), f.uaSet)
        assertEquals("Default", userAgentText(null))
        assertEquals("VLC/3.0", userAgentText("VLC/3.0"))
    }

    @Test fun nonAsciiUserAgentIsNotSaved() = runTest {
        // The dialog's Save is disabled for it (UserAgents.isAcceptable); the VM refuses it too.
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setPlaylistUserAgent(1, "Café/1.0")
        vm.setGeneralUserAgent("Café/1.0")
        assertTrue(f.uaSet.isEmpty())
        assertNull(f.settings.value.userAgent)
    }

    @Test fun generalUserAgentBlankClears() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setGeneralUserAgent(" Kodi/20 ")
        assertEquals("Kodi/20", f.settings.value.userAgent)
        vm.setGeneralUserAgent(" ")
        assertNull(f.settings.value.userAgent)
        assertEquals("Default (Loophole/1.2.3)", generalUserAgentText(null, "1.2.3"))
        assertEquals("Kodi/20", generalUserAgentText("Kodi/20", "1.2.3"))
    }

    @Test fun udpProxyInvalidShowsHintAndIsNotSaved() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.settings.value = AppSettings(udpProxy = "10.0.0.1:4022")
        val vm = f.vm()
        vm.setUdpProxy("not a proxy")
        assertEquals("Use the form 192.168.1.1:4022", vm.state.value.udpProxyError)
        assertEquals("10.0.0.1:4022", f.settings.value.udpProxy)
        vm.setUdpProxy("")
        assertNull(f.settings.value.udpProxy)
        assertNull(vm.state.value.udpProxyError)
    }

    @Test fun udpProxyNormalisedOnSave() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setUdpProxy("http://192.168.1.1:4022/")
        assertEquals("192.168.1.1:4022", f.settings.value.udpProxy)
        assertNull(vm.state.value.udpProxyError)
    }

    @Test fun epgSourcesListedAddAndRemove() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.sources.value = mapOf(1L to listOf(EpgSourceEntity(5, 1, "http://example.test/migrated.xml", 0)))
        val vm = f.vm()
        assertEquals(listOf("http://example.test/migrated.xml"), vm.state.value.epg.single().sources.map { it.url })
        vm.addEpgSource(1, "  http://example.test/two.xml ")
        vm.addEpgSource(1, "   ") // blank never deletes and never adds
        assertEquals(
            listOf("http://example.test/migrated.xml", "http://example.test/two.xml"),
            vm.state.value.epg.single().sources.map { it.url },
        )
        vm.requestRemoveEpgSource(5)
        vm.confirmRemoveEpgSource()
        assertEquals(listOf(5L), f.removedSources)
        assertEquals(listOf("http://example.test/two.xml"), vm.state.value.epg.single().sources.map { it.url })
    }

    @Test fun turningOffOwnGuide() = runTest {
        val f = Fakes(
            listOf(
                playlist(1, epgUrls = "http://example.test/own.xml"),
                playlist(2, type = PlaylistType.M3U_FILE, url = "/sdcard/Download/a.m3u"),
                playlist(3, type = PlaylistType.M3U_FILE, url = "/sdcard/Download/b.m3u", epgUrls = "http://example.test/b.xml"),
            ),
        )
        val vm = f.vm()
        val epg = vm.state.value.epg
        assertEquals(listOf(true, false, true), epg.map { it.showOwnGuide })
        assertTrue(epg[0].useOwnGuide)
        assertEquals(listOf("http://example.test/own.xml"), epg[0].ownGuide)
        vm.setUseOwnGuide(1, false)
        assertEquals(listOf(1L to false), f.useSourceSet)
        assertFalse(vm.state.value.epg[0].useOwnGuide)
    }

    @Test fun renameBlankIgnored() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.rename(1, "   ")
        vm.rename(1, "  Living room ")
        assertEquals(listOf(1L to "Living room"), f.renamed)
    }

    @Test fun rowSecondaryText() = runTest {
        val f = Fakes(
            listOf(
                playlist(1, lastRefreshedAt = Instant.parse("2026-11-09T14:05:00Z")),
                playlist(2, enabled = false),
                playlist(3, lastRefreshedAt = Instant.parse("2026-11-09T14:05:00Z"), lastError = "Server returned HTTP 500 for http://h/u/p"),
                playlist(4, lastError = "Couldn't open the file. Using the copy from 9 Nov."),
            ),
        )
        f.counts.value = mapOf(1L to 1360, 2L to 1, 3L to 0, 4L to 12)
        val vm = f.vm()
        val rows = vm.state.value.playlists
        assertEquals("1,360 channels · On · Updated 9 Nov 14:05", rows[0].secondaryText)
        assertEquals("1 channel · Off · Not updated yet", rows[1].secondaryText)
        // A failed edit or update (e.g. a type change) is never shown as a success; raw text never shows.
        assertEquals("0 channels · On · Last update failed", rows[2].secondaryText)
        assertEquals("12 channels · On · Couldn't open the file. Using the copy from 9 Nov.", rows[3].secondaryText)
    }

    @Test fun rowSecondaryShowsSessionProgressAndFailure() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.counts.value = mapOf(1L to 3)
        val gate = CompletableDeferred<ImportResult>()
        var report: (Int) -> Unit = {}
        f.refreshPlaylist = { _, p -> report = p; gate.await() }
        val vm = f.vm()
        vm.updatePlaylist(1)
        report(2500)
        assertEquals("3 channels · On · Updating… 2,500 channels", vm.state.value.playlists.single().secondaryText)
        gate.complete(ImportResult.Failure(ImportError.Unreachable, "raw"))
        assertEquals(
            "3 channels · On · Couldn't reach the server. Check the address and your internet connection.",
            vm.state.value.playlists.single().secondaryText,
        )
    }

    @Test fun pagesSurviveSaveAndRestore() {
        val pages = listOf(
            SettingsPage.Sections, SettingsPage.General, SettingsPage.Playlists, SettingsPage.Playlist(7),
            SettingsPage.Editor(42), SettingsPage.Epg, SettingsPage.Playback, SettingsPage.About,
        )
        for (p in pages) assertEquals(p, SettingsPage.fromKey(p.key))
        assertEquals("playlist:7", SettingsPage.Playlist(7).key)
        assertEquals(SettingsPage.Sections, SettingsPage.fromKey("nonsense"))
        assertEquals(SettingsPage.Sections, SettingsPage.fromKey("playlist:x"))
    }

    // --- Guide link removal and portal error copy ---

    @Test fun removingGuideLinkAsksFirst() = runTest {
        val f = Fakes(listOf(playlist(1)))
        f.sources.value = mapOf(1L to listOf(EpgSourceEntity(5, 1, "http://example.test/migrated.xml", 0)))
        val vm = f.vm()
        vm.requestRemoveEpgSource(5)
        assertEquals(EpgSourceUi(5, "http://example.test/migrated.xml"), vm.state.value.confirmRemoveSource)
        assertTrue(f.removedSources.isEmpty())
        vm.cancelRemoveEpgSource()
        assertNull(vm.state.value.confirmRemoveSource)
        assertTrue(f.removedSources.isEmpty())
        vm.confirmRemoveEpgSource() // nothing pending: ignored
        assertTrue(f.removedSources.isEmpty())
        vm.requestRemoveEpgSource(5)
        vm.confirmRemoveEpgSource()
        assertEquals(listOf(5L), f.removedSources)
        assertNull(vm.state.value.confirmRemoveSource)
        assertTrue(vm.state.value.epg.single().sources.isEmpty())
    }

    @Test fun unknownSourceIdDoesNotAsk() = runTest {
        val vm = Fakes(listOf(playlist(1))).vm()
        vm.requestRemoveEpgSource(99)
        assertNull(vm.state.value.confirmRemoveSource)
    }

    @Test fun portalFailureShowsPortalCopy() = runTest {
        val f = Fakes(listOf(playlist(1, type = PlaylistType.STALKER), playlist(2)))
        f.refreshPlaylist = { id, _ ->
            if (id == 1L) ImportResult.Failure(ImportError.AuthFailed, "The portal refused this MAC address or login.")
            else ImportResult.Failure(ImportError.AuthFailed, "raw http://h/u/p")
        }
        val vm = f.vm()
        vm.updatePlaylist(1)
        vm.updatePlaylist(2)
        val rows = vm.state.value.playlists
        assertEquals(PlaylistOp.Failed("The portal refused this MAC address or login."), rows[0].op)
        assertEquals(PlaylistOp.Failed("The server rejected the username or password."), rows[1].op)
    }

    @Test fun storedPortalErrorShownAsIs() = runTest {
        val f = Fakes(listOf(playlist(1, type = PlaylistType.STALKER, lastError = "Couldn't reach the portal.")))
        f.counts.value = mapOf(1L to 2)
        val row = f.vm().state.value.playlists.single()
        assertEquals("2 channels · On · Couldn't reach the portal.", row.secondaryText)
        assertTrue(row.statusIsError)
    }

    @Test fun statusIsErrorForStoredAndSessionFailuresOnly() = runTest {
        val f = Fakes(
            listOf(
                playlist(1, lastRefreshedAt = Instant.parse("2026-11-09T14:05:00Z")),
                playlist(2, lastError = "Server returned HTTP 500"),
                playlist(3, lastError = "Couldn't open the file. Using the copy from 9 Nov."),
                playlist(4),
            ),
        )
        f.refreshPlaylist = { _, _ -> ImportResult.Failure(ImportError.Unreachable, "") }
        val vm = f.vm()
        assertEquals(listOf(false, true, false, false), vm.state.value.playlists.map { it.statusIsError })
        vm.updatePlaylist(4)
        assertTrue(vm.state.value.playlists[3].statusIsError)
    }

    @Test fun updateAllThatThrowsRestoresRows() = runTest {
        val f = Fakes(listOf(playlist(1, lastRefreshedAt = Instant.parse("2026-11-09T14:05:00Z"))))
        f.refreshAll = { p -> p(1, 40); error("boom http://h/u/p") }
        val vm = f.vm()
        vm.updateAll()
        val ui = vm.state.value
        assertFalse(ui.updatingAll)
        assertNull(ui.updateAllText)
        assertEquals(PlaylistOp.Idle, ui.playlists.single().op)
        assertTrue(ui.playlists.single().statusText, ui.playlists.single().statusText.startsWith("Updated "))
    }

    @Test fun udpProxyErrorClearedOnRequest() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setUdpProxy("not a proxy")
        assertEquals(UDP_PROXY_INVALID, vm.state.value.udpProxyError)
        vm.clearUdpProxyError()
        assertNull(vm.state.value.udpProxyError)
    }

    // --- Playback page ---

    @Test fun playbackSectionEnabledAndKeyed() {
        assertEquals(SettingsPage.Playback, SettingsPage.fromKey("playback"))
        assertEquals("playback", SettingsPage.Playback.key)
        assertFalse("Playback" in DISABLED_SECTIONS)
    }

    @Test fun pickingBufferWritesPreset() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setPlayback { it.copy(bufferPreset = BufferPreset.LARGE) }
        assertEquals(BufferPreset.LARGE, f.settings.value.playback.bufferPreset)
        assertEquals(BufferPreset.LARGE, vm.state.value.options.playback.bufferPreset)
        // Other settings are untouched.
        assertEquals(AppSettings().copy(playback = PlaybackSettings(bufferPreset = BufferPreset.LARGE)), f.settings.value)
    }

    @Test fun subtitleLanguageOffStoresNull() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        val choices = subtitleLanguageChoices()
        vm.setPlayback { it.copy(subtitleLanguage = choices.first { c -> c.second == "German" }.first) }
        assertEquals("de", f.settings.value.playback.subtitleLanguage)
        vm.setPlayback { it.copy(subtitleLanguage = choices.first().first) }
        assertNull(f.settings.value.playback.subtitleLanguage)
    }

    @Test fun togglesWriteThrough() = runTest {
        val f = Fakes(listOf(playlist(1)))
        val vm = f.vm()
        vm.setPlayback { it.copy(autoFrameRate = !it.autoFrameRate) }
        vm.setPlayback { it.copy(preferSurround = !it.preferSurround) }
        vm.setPlayback { it.copy(decodeDtsToPcm = !it.decodeDtsToPcm) }
        vm.setPlayback { it.copy(tunneling = !it.tunneling) }
        vm.setPlayback { it.copy(pipOnHome = !it.pipOnHome) }
        val p = f.settings.value.playback
        assertTrue(p.autoFrameRate)
        assertFalse(p.preferSurround)
        assertTrue(p.decodeDtsToPcm)
        assertTrue(p.tunneling)
        assertTrue(p.pipOnHome)
        vm.setPlayback { it.copy(tunneling = !it.tunneling) }
        assertFalse(f.settings.value.playback.tunneling)
    }

    @Test fun pipSupportComesFromTheDevice() {
        val f = Fakes(listOf(playlist(1)))
        assertFalse(f.vm().pipSupported)
        assertTrue(f.vm(pipSupported = true).pipSupported)
    }

    @Test fun choiceTextFallsBackForUnknownValues() {
        val intervals = playlistIntervalChoices(AppSettings())
        assertEquals("Every 7 hours", choiceText(intervals, 7) { intervalText(it ?: 0) })
        assertEquals("Off", choiceText(intervals, 0) { intervalText(it ?: 0) })
    }
}
