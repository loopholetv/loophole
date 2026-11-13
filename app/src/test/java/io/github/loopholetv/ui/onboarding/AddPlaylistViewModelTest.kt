package io.github.loopholetv.ui.onboarding

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.repo.errorCopy
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.xtream.XtreamCredentials
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import io.github.loopholetv.data.repo.RefreshCoordinator
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class AddPlaylistViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fakes(
        var m3u: suspend (String, String, (Int) -> Unit) -> ImportResult = { _, _, _ -> ImportResult.Success(1, 3) },
        var xtream: suspend (String, XtreamCredentials, (Int) -> Unit) -> ImportResult = { _, _, _ -> ImportResult.Success(1, 3) },
        var epg: suspend (Long, (Int) -> Unit) -> Unit = { _, _ -> },
        var file: suspend (String, String, (Int) -> Unit) -> ImportResult = { _, _, _ -> ImportResult.Success(1, 3) },
        var portal: suspend (String, PortalConfig, (Int) -> Unit) -> ImportResult = { _, _, _ -> ImportResult.Success(1, 3) },
        var update: suspend (Long, SourceEdit, (Int) -> Unit) -> ImportResult = { id, _, _ -> ImportResult.Success(id, 3) },
        var stored: PlaylistEntity? = null,
    ) {
        val m3uCalls = mutableListOf<Pair<String, String>>()
        val xtreamCalls = mutableListOf<Pair<String, XtreamCredentials>>()
        val fileCalls = mutableListOf<Pair<String, String>>()
        val portalCalls = mutableListOf<Pair<String, PortalConfig>>()
        val updateCalls = mutableListOf<Pair<Long, SourceEdit>>()
        var epgCalls = 0
        fun vm(editId: Long? = null, appScope: CoroutineScope? = null) = AddPlaylistViewModel(
            addM3u = { n, u, p -> m3uCalls += n to u; m3u(n, u, p) },
            addXtream = { n, c, p -> xtreamCalls += n to c; xtream(n, c, p) },
            refreshEpg = { id, p -> epgCalls++; epg(id, p) },
            addLocalFile = { n, l, p -> fileCalls += n to l; file(n, l, p) },
            addStalker = { n, c, p -> portalCalls += n to c; portal(n, c, p) },
            updateSource = { id, e, p -> updateCalls += id to e; update(id, e, p) },
            loadPlaylist = { id -> stored?.takeIf { it.id == id } },
            fileDisplayName = { it.substringAfterLast('/') },
            editId = editId,
            appScope = appScope,
        )

        /** The VM inside a real store, so `store.clear()` clears it the way leaving the screen does. */
        fun storedVm(store: ViewModelStore, editId: Long?, appScope: CoroutineScope): AddPlaylistViewModel =
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm(editId, appScope) as T
            })[AddPlaylistViewModel::class.java]
    }

    private fun entity(id: Long, type: PlaylistType) = PlaylistEntity(
        id = id, name = "Mine", type = type, url = null, xtreamServer = null, xtreamUser = null, xtreamPass = null,
        epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
    )

    @Test fun submitM3uSuccessReachesDone() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setUrl("http://example.com/list.m3u")
        vm.submit()
        assertEquals(Phase.Done, vm.state.value.phase)
        assertEquals(1, f.epgCalls)
        assertEquals("http://example.com/list.m3u", f.m3uCalls.single().second)
    }

    @Test fun submitXtreamUsesCredentials() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.XTREAM)
        vm.setServer("http://srv.example:8080")
        vm.setUser("bob")
        vm.setPass("pw")
        vm.setName("Mine")
        vm.submit()
        assertEquals(Phase.Done, vm.state.value.phase)
        val (name, creds) = f.xtreamCalls.single()
        assertEquals("Mine", name)
        assertEquals(XtreamCredentials("http://srv.example:8080", "bob", "pw"), creds)
        assertTrue(f.m3uCalls.isEmpty())
    }

    @Test fun blankUrlNotSubmitted() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setUrl("   ")
        vm.submit()
        assertEquals(Phase.Editing, vm.state.value.phase)
        assertTrue(f.m3uCalls.isEmpty())
        vm.setMode(Mode.XTREAM)
        vm.setServer("http://s"); vm.setUser("u")
        vm.submit()
        assertTrue(f.xtreamCalls.isEmpty())
        assertEquals(Phase.Editing, vm.state.value.phase)
    }

    @Test fun authFailureShowsExactCopy() = runTest {
        val f = Fakes(m3u = { _, _, _ -> ImportResult.Failure(ImportError.AuthFailed, "x") })
        val vm = f.vm()
        vm.setUrl("http://a.b/c"); vm.setName("n")
        vm.submit()
        assertEquals(Phase.Failed("The server rejected the username or password."), vm.state.value.phase)
        assertEquals("http://a.b/c", vm.state.value.url)
        assertEquals(0, f.epgCalls)
    }

    @Test fun otherErrorCopy() {
        assertEquals("Couldn't reach the server. Check the address and your internet connection.", errorCopy(ImportError.Unreachable))
        assertEquals("The playlist loaded but contained no channels.", errorCopy(ImportError.EmptyPlaylist))
        assertEquals("That address didn't return a playlist.", errorCopy(ImportError.NotAPlaylist))
        assertEquals("The server returned an error (503).", errorCopy(ImportError.Http(503)))
    }

    @Test fun unexpectedErrorShowsGenericCopy() = runTest {
        val f = Fakes(m3u = { _, _, _ -> ImportResult.Failure(ImportError.Unexpected, "boom") })
        val vm = f.vm()
        vm.setUrl("http://a.b/c")
        vm.submit()
        assertEquals(Phase.Failed("Something went wrong while loading the playlist."), vm.state.value.phase)
    }

    @Test fun epgFailureStillReachesDone() = runTest {
        val f = Fakes(epg = { _, _ -> error("epg down") })
        val vm = f.vm()
        vm.setUrl("http://a.b/c")
        vm.submit()
        assertEquals(Phase.Done, vm.state.value.phase)
    }

    @Test fun nameDefaultsToHost() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setUrl("http://iptv.example.org:8000/get.php?x=1")
        vm.submit()
        assertEquals("iptv.example.org", f.m3uCalls.single().first)

        val g = Fakes()
        val vx = g.vm()
        vx.setMode(Mode.XTREAM)
        vx.setServer("srv.example.net:80"); vx.setUser("u"); vx.setPass("p")
        vx.submit()
        assertEquals("srv.example.net", g.xtreamCalls.single().first)

        val h = Fakes()
        val vh = h.vm()
        vh.setUrl("not a uri %%")
        vh.submit()
        assertEquals("Playlist", h.m3uCalls.single().first)
    }

    @Test fun progressShowsChannelCountThenGuideProgramCount() = runTest {
        val phases = mutableListOf<Phase>()
        lateinit var vm: AddPlaylistViewModel
        val f = Fakes(
            m3u = { _, _, p -> p(42); phases += vm.state.value.phase; ImportResult.Success(1, 42) },
            epg = { _, p ->
                phases += vm.state.value.phase
                p(1500)
                phases += vm.state.value.phase
            },
        )
        vm = f.vm()
        vm.setUrl("http://a.b/c")
        vm.submit()
        assertEquals(listOf(Phase.LoadingChannels(42), Phase.LoadingGuide(0), Phase.LoadingGuide(1500)), phases)
        assertEquals(Phase.Done, vm.state.value.phase)
    }

    @Test fun guideProgressTextShowsProgrammes() {
        assertEquals("Loading TV guide…", guideProgressText(0))
        assertEquals("Loading TV guide… 1500 programmes", guideProgressText(1500))
    }

    @Test fun schemeLessAddressesGetHttp() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setUrl("iptv.example.org/list.m3u")
        vm.submit()
        assertEquals("http://iptv.example.org/list.m3u", f.m3uCalls.single().second)

        val g = Fakes()
        val vx = g.vm()
        vx.setMode(Mode.XTREAM)
        vx.setServer(" srv.example.net:8080 "); vx.setUser("u"); vx.setPass("p")
        vx.submit()
        assertEquals("http://srv.example.net:8080", g.xtreamCalls.single().second.server)

        val h = Fakes()
        val vh = h.vm()
        vh.setUrl("HTTPS://secure.example/l.m3u")
        vh.submit()
        assertEquals("HTTPS://secure.example/l.m3u", h.m3uCalls.single().second)
    }

    @Test fun doubleSubmitIgnoredWhileLoading() = runTest {
        val gate = CompletableDeferred<ImportResult>()
        val f = Fakes(m3u = { _, _, _ -> gate.await() })
        val vm = f.vm()
        vm.setUrl("http://a.b/c")
        vm.submit()
        vm.submit()
        assertEquals(1, f.m3uCalls.size)
        gate.complete(ImportResult.Success(1, 1))
        assertEquals(Phase.Done, vm.state.value.phase)
    }

    // --- Local file and MAC portal; edit mode ---

    private fun portalVm(f: Fakes, mac: String = "00:1A:79:00:00:01") = f.vm().apply {
        setMode(Mode.PORTAL)
        setPortalUrl("http://portal.example.test")
        setMac(mac)
    }

    @Test fun modesInOrderWithLabels() {
        assertEquals(listOf(Mode.M3U, Mode.FILE, Mode.XTREAM, Mode.PORTAL), Mode.entries.toList())
        assertEquals(listOf("M3U link", "Local file", "Xtream Codes", "MAC portal"), Mode.entries.map { it.label })
    }

    @Test fun fileModeRequiresChosenFile() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.FILE)
        vm.submit()
        assertEquals(Phase.Editing, vm.state.value.phase)
        assertTrue(f.fileCalls.isEmpty())
        vm.setFile("/storage/emulated/0/Download/list.m3u")
        assertEquals("list.m3u", vm.state.value.fileName)
        vm.submit()
        assertEquals(Phase.Done, vm.state.value.phase)
        assertEquals("/storage/emulated/0/Download/list.m3u", f.fileCalls.single().second)
        assertEquals(1, f.epgCalls)
        assertTrue(f.m3uCalls.isEmpty())
    }

    @Test fun fileModeDefaultNameFromFile() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.FILE)
        vm.setFile("/sdcard/Download/my list.m3u")
        vm.submit()
        assertEquals("my list", f.fileCalls.single().first)

        val g = Fakes()
        val vg = g.vm()
        vg.setMode(Mode.FILE)
        vg.setFile("/sdcard/Download/my list.m3u")
        vg.setName("  Kitchen  ")
        vg.submit()
        assertEquals("Kitchen", g.fileCalls.single().first)
        assertEquals("noext", fileBaseName("noext"))
        assertEquals(".m3u", fileBaseName(".m3u"))
        assertEquals("Playlist", fileBaseName(" "))
    }

    @Test fun fileModeCantOpenShowsFileCopy() = runTest {
        val f = Fakes(file = { _, _, _ -> ImportResult.Failure(ImportError.Unreachable, "Couldn't open the file.") })
        val vm = f.vm()
        vm.setMode(Mode.FILE)
        vm.setFile("/x/a.m3u")
        vm.submit()
        assertEquals(Phase.Failed("Couldn't open the file."), vm.state.value.phase)
    }

    @Test fun portalModeNormalisesMac() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.PORTAL)
        assertEquals("00:1A:79:", vm.state.value.mac)
        vm.setPortalUrl(" http://portal.example.test/c/ ")
        vm.setMac("00-1a-79-ab-cd-ef")
        vm.submit()
        val (name, cfg) = f.portalCalls.single()
        assertEquals("portal.example.test", name)
        assertEquals("http://portal.example.test/c/", cfg.portalUrl)
        assertEquals("00:1A:79:AB:CD:EF", cfg.mac)
        assertEquals(Phase.Done, vm.state.value.phase)
        assertEquals(1, f.epgCalls)
    }

    @Test fun invalidMacBlocksSubmit() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.PORTAL)
        vm.setPortalUrl("http://portal.example.test")
        vm.submit() // the pre-filled "00:1A:79:" alone is not a MAC
        assertEquals(INVALID_MAC, vm.state.value.macError)
        assertEquals(Phase.Editing, vm.state.value.phase)
        assertTrue(f.portalCalls.isEmpty())
        vm.setMac("00:1A:79:12:34")
        assertNull("typing clears the message", vm.state.value.macError)
        vm.submit()
        assertEquals(INVALID_MAC, vm.state.value.macError)
        assertTrue(f.portalCalls.isEmpty())
        assertEquals("Enter the MAC address as 12 letters or digits, e.g. 00:1A:79:12:34:56.", INVALID_MAC)
    }

    @Test fun nonInfomirMacWarnsButSubmits() = runTest {
        val f = Fakes()
        val vm = portalVm(f)
        assertNull(vm.state.value.macWarning)
        vm.setMac("AA:BB:CC:00:00:01")
        assertEquals("This MAC doesn't start with 00:1A:79. Some portals only accept those.", vm.state.value.macWarning)
        vm.submit()
        assertEquals("AA:BB:CC:00:00:01", f.portalCalls.single().second.mac)
        assertNull(vm.state.value.macError)
        assertEquals(Phase.Done, vm.state.value.phase)
    }

    @Test fun portalOptionalFieldsBlankSentAsNull() = runTest {
        val f = Fakes()
        val vm = portalVm(f)
        vm.toggleMoreOptions()
        assertTrue(vm.state.value.moreOptions)
        vm.setPortalLogin("  ")
        vm.setPortalPass("")
        vm.setSerial(" ")
        vm.setDeviceId("\t")
        vm.setDeviceId2("")
        vm.setSignature("")
        vm.submit()
        val cfg = f.portalCalls.single().second
        assertEquals(PortalConfig("http://portal.example.test", "00:1A:79:00:00:01"), cfg)
        assertNull(cfg.login); assertNull(cfg.password); assertNull(cfg.serial)
        assertNull(cfg.deviceId); assertNull(cfg.deviceId2); assertNull(cfg.signature)

        val g = Fakes()
        val vg = portalVm(g)
        vg.setPortalLogin(" bob "); vg.setPortalPass(" pw "); vg.setSerial(" SN1 ")
        vg.setDeviceId("D1"); vg.setDeviceId2("D2"); vg.setSignature("SIG")
        vg.submit()
        assertEquals(
            PortalConfig("http://portal.example.test", "00:1A:79:00:00:01", "bob", " pw ", "SN1", "D1", "D2", "SIG"),
            g.portalCalls.single().second,
        )
    }

    @Test fun portalNeverAutoFillsIdentity() {
        val ui = AddPlaylistUi()
        assertEquals("", ui.serial); assertEquals("", ui.deviceId); assertEquals("", ui.deviceId2); assertEquals("", ui.signature)
        assertFalse(ui.moreOptions)
    }

    @Test fun editModePrefillsAndCallsUpdateSource() = runTest {
        val f = Fakes(stored = entity(7, PlaylistType.M3U).copy(url = "http://example.test/old.m3u"))
        val vm = f.vm(editId = 7)
        val ui = vm.state.value
        assertTrue(ui.editing)
        assertEquals(Mode.M3U, ui.mode)
        assertEquals("http://example.test/old.m3u", ui.url)
        vm.setUrl("example.test/new.m3u")
        vm.submit()
        assertEquals(7L to SourceEdit.M3uUrl("http://example.test/new.m3u"), f.updateCalls.single())
        assertTrue(f.m3uCalls.isEmpty())
        assertEquals("the coordinator chains the EPG itself", 0, f.epgCalls)
        assertEquals(Phase.Done, vm.state.value.phase)

        // Each stored type pre-selects its mode and fields; the type can be changed.
        val file = Fakes(stored = entity(3, PlaylistType.M3U_FILE).copy(url = "/sdcard/Download/a b.m3u")).vm(3).state.value
        assertEquals(Mode.FILE, file.mode)
        assertEquals("/sdcard/Download/a b.m3u", file.fileLocation)
        assertEquals("a b.m3u", file.fileName)
        val portal = Fakes(
            stored = entity(4, PlaylistType.STALKER).copy(url = "http://portal.example.test", mac = "00:1A:79:00:00:01", portalSerial = "SN"),
        )
        val pv = portal.vm(4)
        assertEquals(Mode.PORTAL, pv.state.value.mode)
        assertEquals("http://portal.example.test", pv.state.value.portalUrl)
        assertEquals("00:1A:79:00:00:01", pv.state.value.mac)
        assertEquals("SN", pv.state.value.serial)
        assertTrue("typed extras are shown", pv.state.value.moreOptions)
        pv.setMode(Mode.FILE)
        pv.setFile("/x/new.m3u")
        pv.submit()
        assertEquals(4L to SourceEdit.LocalFile("/x/new.m3u"), portal.updateCalls.single())
        assertTrue(portal.fileCalls.isEmpty())
    }

    @Test fun editModeKeepsPasswordWhenUntouched() = runTest {
        val f = Fakes(
            stored = entity(5, PlaylistType.XTREAM).copy(xtreamServer = "http://srv.example.test", xtreamUser = "bob", xtreamPass = "secret"),
        )
        val vm = f.vm(editId = 5)
        assertEquals(Mode.XTREAM, vm.state.value.mode)
        vm.setUser("alice")
        vm.submit()
        val edit = f.updateCalls.single().second as SourceEdit.Xtream
        assertEquals(XtreamCredentials("http://srv.example.test", "alice", "secret"), edit.creds)

        val g = Fakes(
            stored = entity(6, PlaylistType.STALKER).copy(
                url = "http://portal.example.test", mac = "00:1A:79:00:00:01", portalLogin = "l", portalPassword = "pp",
            ),
        )
        val vg = g.vm(editId = 6)
        vg.setMac("00:1A:79:00:00:02")
        vg.submit()
        val cfg = (g.updateCalls.single().second as SourceEdit.Portal).cfg
        assertEquals(PortalConfig("http://portal.example.test", "00:1A:79:00:00:02", login = "l", password = "pp"), cfg)
    }

    @Test fun anEditStoppedByACopiedSetupShowsNoError() = runTest {
        val f = Fakes(
            stored = entity(8, PlaylistType.M3U).copy(url = "http://example.test/a.m3u"),
            update = { _, _, _ -> ImportResult.Stopped },
        )
        val vm = f.vm(editId = 8)
        vm.setUrl("http://example.test/b.m3u")
        vm.submit()
        assertEquals(Phase.Done, vm.state.value.phase)
    }

    @Test fun editModeFailureShowsCopyAndStaysOnForm() = runTest {
        val f = Fakes(
            stored = entity(8, PlaylistType.M3U).copy(url = "http://example.test/a.m3u"),
            update = { _, _, _ -> ImportResult.Failure(ImportError.AuthFailed, "x") },
        )
        val vm = f.vm(8)
        vm.submit()
        assertEquals(Phase.Failed(errorCopy(ImportError.AuthFailed)), vm.state.value.phase)
        assertEquals("http://example.test/a.m3u", vm.state.value.url)
        // A playlist that no longer exists can't be edited.
        val gone = Fakes().vm(99)
        assertEquals(Phase.Failed(errorCopy(ImportError.Unexpected)), gone.state.value.phase)
    }

    @Test fun portalErrorsUseFixedCopy() = runTest {
        val fixed = listOf(
            ImportError.AuthFailed to "The portal refused this MAC address or login.",
            ImportError.Unreachable to "Couldn't reach the portal.",
            ImportError.NotAPlaylist to "No MAC portal was found at this address.",
            ImportError.Unexpected to "The portal sent an answer Loophole doesn't understand.",
        )
        for ((error, copy) in fixed) {
            val vm = portalVm(Fakes(portal = { _, _, _ -> ImportResult.Failure(error, copy) }))
            vm.submit()
            assertEquals(Phase.Failed(copy), vm.state.value.phase)
        }
        // The form shares Settings' list (data/repo), so the two can't drift apart.
        assertEquals(fixed.map { it.second }.toSet(), io.github.loopholetv.data.repo.PORTAL_ERROR_COPY)
        // Anything else never shows the raw message: the general copy for its error.
        val vg = portalVm(Fakes(portal = { _, _, _ -> ImportResult.Failure(ImportError.Unexpected, "boom 00:1A:79:00:00:01") }))
        vg.submit()
        assertEquals(Phase.Failed(errorCopy(ImportError.Unexpected)), vg.state.value.phase)
    }

    // --- Edit and add survive the ViewModel being cleared ---

    private fun TestScope.appScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test fun clearingViewModelMidEditStillCompletesEdit() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val f = Fakes(
            stored = entity(7, PlaylistType.M3U).copy(url = "http://example.test/old.m3u"),
            update = { id, _, _ -> gate.await(); finished = true; ImportResult.Success(id, 3) },
        )
        val app = appScope()
        val store = ViewModelStore()
        val vm = f.storedVm(store, 7, app)
        vm.setUrl("http://example.test/new.m3u")
        vm.submit()
        store.clear() // Back: the route is popped while the edit runs
        gate.complete(Unit)
        assertTrue("the edit ran to the end", finished)
        assertEquals(1, f.updateCalls.size)
        app.cancel()
    }

    @Test fun failingEditAfterViewModelClearedStillRecordsLastError() = runTest {
        // Through the real coordinator (its lock and `guarded` rethrow cancellation); the
        // updateSource function stands in for PlaylistRepository, which stores lastError.
        val gate = CompletableDeferred<Unit>()
        val lastError = mutableMapOf<Long, String>()
        val coordinator = RefreshCoordinator(
            playlists = { emptyList() },
            epgStatus = { null },
            refreshPlaylistFn = { id, _ -> ImportResult.Success(id, 0) },
            refreshEpgFn = { _, _ -> error("no EPG chain after a failure") },
            settings = { AppSettings() },
            updateSourceFn = { id, _, _ ->
                gate.await()
                ImportResult.Failure(ImportError.AuthFailed, "Server refused access").also { lastError[id] = it.message }
            },
        )
        val f = Fakes(
            stored = entity(7, PlaylistType.M3U).copy(url = "http://example.test/old.m3u"),
            update = { id, e, p -> coordinator.updateSource(id, e, p) },
        )
        val app = appScope()
        val store = ViewModelStore()
        f.storedVm(store, 7, app).submit()
        store.clear()
        gate.complete(Unit)
        assertEquals("Server refused access", lastError[7])
        app.cancel()
    }

    @Test fun clearingViewModelMidAddFinishesTheAdd() = runTest {
        // A cancelled add would roll back; it now finishes (or fails cleanly), so no half-made row.
        val gate = CompletableDeferred<Unit>()
        var added = false
        val f = Fakes(m3u = { _, _, _ -> gate.await(); added = true; ImportResult.Success(1, 3) })
        val app = appScope()
        val store = ViewModelStore()
        val vm = f.storedVm(store, null, app)
        vm.setUrl("http://example.test/a.m3u")
        vm.submit()
        store.clear()
        gate.complete(Unit)
        assertTrue(added)
        assertEquals("the guide still loads for the new playlist", 1, f.epgCalls)
        app.cancel()
    }

    @Test fun submitIgnoredWhileEditedPlaylistLoads() = runTest {
        val gate = CompletableDeferred<PlaylistEntity?>()
        val f = Fakes()
        val vm = AddPlaylistViewModel(
            addM3u = { _, _, _ -> error("not an add") },
            addXtream = { _, _, _ -> error("not an add") },
            refreshEpg = { _, _ -> },
            updateSource = { id, e, _ -> f.updateCalls += id to e; ImportResult.Success(id, 1) },
            loadPlaylist = { gate.await() },
            editId = 3,
        )
        vm.setUrl("http://example.test/x.m3u")
        vm.submit()
        assertTrue(f.updateCalls.isEmpty())
        assertFalse(vm.state.value.loaded)
        gate.complete(entity(3, PlaylistType.M3U).copy(url = "http://example.test/a.m3u"))
        assertEquals("http://example.test/a.m3u", vm.state.value.url)
    }

    @Test fun blankPortalAddressNotSubmitted() = runTest {
        val f = Fakes()
        val vm = f.vm()
        vm.setMode(Mode.PORTAL)
        vm.setMac("00:1A:79:00:00:01")
        vm.submit()
        assertTrue(f.portalCalls.isEmpty())
        assertEquals(Phase.Editing, vm.state.value.phase)
    }

    @Test fun fileSubmitWaitsForTheFileName() = runTest {
        val name = CompletableDeferred<String>()
        val f = Fakes()
        val vm = AddPlaylistViewModel(
            addM3u = { _, _, _ -> error("no") },
            addXtream = { _, _, _ -> error("no") },
            refreshEpg = { _, _ -> },
            addLocalFile = { n, l, _ -> f.fileCalls += n to l; ImportResult.Success(1, 1) },
            fileDisplayName = { name.await() },
        )
        vm.setMode(Mode.FILE)
        vm.setFile("content://com.example.docs/document/msf%3A1234")
        vm.submit()
        assertTrue("not named after the URI segment", f.fileCalls.isEmpty())
        name.complete("my list.m3u")
        vm.submit()
        assertEquals("my list", f.fileCalls.single().first)
    }

    @Test fun changingKindClearsMacErrorAndEachBlockedSubmitCounts() = runTest {
        val vm = Fakes().vm()
        vm.setMode(Mode.PORTAL)
        vm.setPortalUrl("http://portal.example.test")
        vm.submit()
        val first = vm.state.value.macErrorShown
        vm.submit()
        assertEquals("a repeated blocked submit refocuses the MAC field", first + 1, vm.state.value.macErrorShown)
        vm.setMode(Mode.M3U)
        assertNull(vm.state.value.macError)
    }

    @Test fun uiToStringHasNoSecrets() {
        val ui = AddPlaylistUi(
            url = "http://u:p@example.test/l.m3u", user = "bob", pass = "secret", mac = "00:1A:79:00:00:01",
            portalPass = "pp", serial = "SN1", deviceId = "D1", deviceId2 = "D2", signature = "SIG", fileLocation = "/x/y.m3u",
        )
        val s = ui.toString()
        for (secret in listOf("secret", "bob", "00:1A:79:00:00:01", "pp", "SN1", "D1", "D2", "SIG", "example.test", "/x/y.m3u")) {
            assertFalse("leaks $secret", secret in s)
        }
    }
}
