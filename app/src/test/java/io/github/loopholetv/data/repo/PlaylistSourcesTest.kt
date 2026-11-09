package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.stalker.FakePortal
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.xmltv.XmltvChannel
import io.github.loopholetv.data.xmltv.XmltvProgramme
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Local file and MAC portal playlists, source edits with the key carry-over, user agents and delete. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var portalServer: MockWebServer
    private lateinit var copiesDir: File
    private lateinit var repo: PlaylistRepository
    private val http = OkHttpClient()
    private val portal = FakePortal()
    private val fileClock = Instant.parse("2026-11-09T12:00:00Z")
    private var generalUa = "General/1"
    private var clock = Instant.parse("2026-11-09T09:00:00Z")

    /** Every M3U/Xtream request: path to User-Agent. */
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = HashMap<String, String>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val key = request.requestUrl!!.let { u -> u.queryParameter("action") ?: u.encodedPath }
                    val body = synchronized(bodies) { bodies[key] } ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(body)
                }
            }
            it.start()
        }
        portalServer = MockWebServer().also { it.dispatcher = portal; it.start() }
        copiesDir = tmp.newFolder("sources")
        repo = newRepo()
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
        runCatching { portalServer.shutdown() } // one test shuts it down early
    }

    private fun newRepo(): PlaylistRepository {
        val client = StalkerClient(http)
        val sessions = StalkerSessions(client, db.playlistDao()::byId) { id, e, t -> db.playlistDao().setPortalSession(id, e, t) }
        return PlaylistRepository(
            db, http, XtreamClient(http),
            now = { clock },
            local = LocalM3uOpener(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, copiesDir, clock = { fileClock }),
            stalker = client,
            sessions = sessions,
            downloadUa = { p -> p?.userAgent ?: generalUa },
        )
    }

    // --- helpers ---

    private fun serve(path: String, body: String) = synchronized(bodies) { bodies[path] = body }

    private fun m3u(vararg channels: Pair<String, String>, group: String = "G") = buildString {
        append("#EXTM3U\n")
        channels.forEach { (name, url) -> append("#EXTINF:-1 group-title=\"$group\",$name\n$url\n") }
    }

    private fun successOf(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("import failed: ${result.error} ${result.message}")
    }

    private suspend fun playlist(id: Long): PlaylistEntity = db.playlistDao().byId(id)!!
    private suspend fun channels(id: Long): List<ChannelEntity> = db.channelDao().channelsOf(id)

    private val portalBase get() = "http://${portalServer.hostName}:${portalServer.port}"
    private val portalCfg get() = PortalConfig("$portalBase/stalker_portal/c/", "00:1a:79:00:00:01", login = " ")

    private fun localFile(name: String, vararg channels: Pair<String, String>): File =
        File(tmp.root, name).apply { writeText(m3u(*channels)) }

    // --- local file ---

    @Test fun addLocalFileImportsAndKeepsCopy() = runTest {
        val file = localFile("list.m3u", "One" to "http://s.example.test/1.ts", "Two" to "http://s.example.test/2.ts")
        val r = successOf(repo.addLocalFile("Mine", " ${file.absolutePath} ") {})

        val p = playlist(r.playlistId)
        assertEquals(PlaylistType.M3U_FILE, p.type)
        assertEquals(file.absolutePath, p.url)
        assertEquals(2, r.channelCount)
        assertEquals(listOf("One", "Two"), channels(r.playlistId).map { it.name })
        val copy = File(copiesDir, "${r.playlistId}.m3u")
        assertTrue(copy.isFile)
        assertEquals(file.readText(), copy.readText())
    }

    @Test fun refreshLocalFileMissingKeepsChannelsAndShowsCopyNotice() = runTest {
        val file = localFile("list.m3u", "One" to "http://s.example.test/1.ts")
        val id = successOf(repo.addLocalFile("Mine", file.absolutePath) {}).playlistId
        val before = channels(id)

        val refreshedAt = playlist(id).lastRefreshedAt

        file.delete() // USB stick removed
        clock = clock.plusSeconds(3600)
        assertEquals(ImportResult.Success(id, 1), repo.refresh(id) {})
        // Read from the copy: not a real update, so the next scheduled one still tries the file.
        assertEquals(refreshedAt, playlist(id).lastRefreshedAt)
        assertEquals(before.map { it.key }, channels(id).map { it.key })
        assertEquals("Couldn't open the file. Using the copy from 9 Nov.", playlist(id).lastError)

        // Back again: the file is read and the notice goes.
        localFile("list.m3u", "One" to "http://s.example.test/1.ts", "New" to "http://s.example.test/9.ts")
        assertEquals(ImportResult.Success(id, 2), repo.refresh(id) {})
        assertNull(playlist(id).lastError)
        assertEquals(clock, playlist(id).lastRefreshedAt)
    }

    @Test fun localFileErrorsNeverContainThePath() = runTest {
        val secret = File(tmp.newFolder("tok-9f8e7d6c"), "list.m3u")
        val r = repo.addLocalFile("Mine", secret.absolutePath) {} as ImportResult.Failure
        assertEquals(ImportError.Unreachable, r.error)
        assertFalse(r.message, r.message.contains("tok-9f8e7d6c"))
        assertTrue(repo.playlists().first().isEmpty())
    }

    // --- MAC portal ---

    @Test fun addStalkerStoresEndpointAndToken() = runTest {
        val r = successOf(repo.addStalker("Portal", portalCfg) {})
        val p = playlist(r.playlistId)
        assertEquals(PlaylistType.STALKER, p.type)
        assertEquals("$portalBase/stalker_portal/server/load.php", p.portalEndpoint)
        assertEquals("TOKEN-PLACEHOLDER-1", p.portalToken)
        assertEquals("00:1A:79:00:00:01", p.mac) // normalised
        assertEquals("$portalBase/stalker_portal/c/", p.url)
        assertNull(p.portalLogin) // blank = not typed
        assertEquals(4, r.channelCount)
    }

    @Test fun stalkerRefreshReusesSavedToken() = runTest {
        val id = successOf(repo.addStalker("Portal", portalCfg) {}).playlistId
        portal.requests.clear()

        // A fresh process: a new repository and session cache, the saved endpoint and token.
        val r = newRepo().refresh(id) {}
        assertEquals(ImportResult.Success(id, 4), r)
        val handshake = portal.ofAction("handshake").single()
        assertEquals("TOKEN-PLACEHOLDER-1", handshake.requestUrl!!.queryParameter("token"))
        assertEquals(0, portal.requests.count { it.requestUrl!!.encodedPath != portal.path }) // no probing
        assertEquals(0, portal.count("create_link"))
    }

    @Test fun stalkerChannelsKeyedByIdWithNeedLinkFlag() = runTest {
        val id = successOf(repo.addStalker("Portal", portalCfg) {}).playlistId
        val byKey = channels(id).associateBy { it.key }
        assertEquals(setOf("101", "102", "103", "105"), byKey.keys)
        val news = byKey.getValue("101")
        assertEquals("News One", news.name)
        assertEquals(1, news.number)
        assertEquals("News", news.groupTitle)
        assertEquals("news.one.test", news.tvgId)
        assertEquals("ffmpeg http://localhost/ch/101_", news.streamUrl)
        assertTrue(news.needsLink)
        assertFalse(byKey.getValue("102").needsLink)
        assertEquals("stalker-102", byKey.getValue("102").tvgId)
        assertTrue(byKey.getValue("105").needsLink) // load balancing
        assertEquals("", playlist(id).epgUrlsFromSource)

        // A refresh that renames a channel keeps its row (keyed by the portal id).
        val oldId = news.id
        portal.allChannelsJson = portal.allChannelsJson!!.replace("News One", "News 1")
        repo.refresh(id) {}
        assertEquals(oldId, channels(id).single { it.key == "101" }.id)
        assertEquals("News 1", channels(id).single { it.key == "101" }.name)
    }

    @Test fun stalkerErrorsNeverContainSecrets() = runTest {
        val cfg = portalCfg.copy(login = "user1", password = "pass1", serial = "SER123456")
        val id = successOf(repo.addStalker("Portal", cfg) {}).playlistId
        val secrets = listOf(
            "00:1A:79:00:00:01", "00:1a:79:00:00:01", "001A79000001", "TOKEN-PLACEHOLDER", "user1", "pass1",
            "SER123456", portalServer.hostName, "${portalServer.port}",
        )
        fun check(r: ImportResult) {
            val f = r as ImportResult.Failure
            for (text in listOf(f.message)) for (s in secrets) assertFalse("$s in $text", text.contains(s))
        }

        portal.accessDenied = true
        val denied = repo.refresh(id) {}
        assertEquals(ImportError.AuthFailed, (denied as ImportResult.Failure).error)
        check(denied)
        assertEquals(denied.message, playlist(id).lastError)
        portal.accessDenied = false

        portal.htmlPaths += portal.path
        newRepo().let { fresh ->
            val notPortal = fresh.refresh(id) {}
            assertEquals(ImportError.NotAPlaylist, (notPortal as ImportResult.Failure).error)
            check(notPortal)
        }
        portal.htmlPaths.clear()

        portalServer.shutdown()
        val down = newRepo().refresh(id) {}
        assertEquals(ImportError.Unreachable, (down as ImportResult.Failure).error)
        check(down)
        for (s in secrets) assertFalse(s, playlist(id).lastError.orEmpty().contains(s))
        assertEquals(4, channels(id).size) // old channels stay
    }

    @Test fun portalGuideUsesStoredChannelIdsAndNames() = runTest {
        val id = successOf(repo.addStalker("Portal", portalCfg) {}).playlistId
        val client = StalkerClient(http)
        val sessions = StalkerSessions(client, db.playlistDao()::byId) { pid, e, t -> db.playlistDao().setPortalSession(pid, e, t) }
        val out = mutableListOf<Any>()
        readPortalGuide(playlist(id), db.maintenanceDao(), client, sessions) { out += it }
        val guideChannels = out.filterIsInstance<XmltvChannel>()
        assertEquals(listOf("news.one.test", "stalker-102"), guideChannels.map { it.id })
        assertEquals(listOf("News One"), guideChannels.first().displayNames)
        assertEquals(setOf("news.one.test", "stalker-102"), out.filterIsInstance<XmltvProgramme>().map { it.channelId }.toSet())
        assertEquals("72", portal.ofAction("get_epg_info").single().requestUrl!!.queryParameter("period"))
    }

    // --- source edits ---

    private fun loginList(user: String) = m3u(
        "One" to "${server.url("/live/$user/1.ts")}",
        "Two" to "${server.url("/live/$user/2.ts")}",
        "Dup" to "${server.url("/live/$user/3.ts")}",
        "Dup" to "${server.url("/live/$user/4.ts")}",
    )

    @Test fun changingLoginCarriesFavouritesAndEdits() = runTest {
        serve("/u1.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/u1.m3u").toString()) {}).playlistId
        val one = channels(id).single { it.name == "One" }
        val two = channels(id).single { it.name == "Two" }
        FavouritesRepository(db).toggle(one)
        HistoryRepository(db).record(one)
        val edits = ChannelEdits(db, ChannelOverlay(db))
        edits.rename(ChannelRef(id, two.key), "Second")
        edits.setHidden(ChannelRef(id, channels(id).first { it.name == "Dup" }.key), true)
        // The automatic guide match of "One", with a programme on now.
        db.channelDao().setEpgChannelId(one.id, "one.epg")
        db.programmeDao().insertAll(listOf(programme("one.epg")))

        serve("/u2.m3u", loginList("u2/p2"))
        val r = repo.updateSource(id, SourceEdit.M3uUrl(server.url("/u2.m3u").toString())) {}
        assertEquals(ImportResult.Success(id, 4), r)

        val after = channels(id)
        val newOne = after.single { it.name == "One" }
        assertTrue(newOne.key.contains("/u2/p2/"))
        assertTrue(FavouritesRepository(db).isFavourite(newOne))
        assertEquals(newOne.key, HistoryRepository(db).latest()?.key)
        assertEquals("Second", after.single { it.name == "Two" }.displayName)
        // The guide match moves too: its programmes show at once, with no EPG refresh.
        assertEquals("one.epg", newOne.epgChannelId)
        assertEquals("one.epg", newOne.guideEpgId)
        assertEquals(1, db.programmeDao().nowAndNext(newOne.guideEpgId!!, Instant.parse("2026-11-09T10:30:00Z")).size)
        assertNull(after.single { it.name == "Two" }.guideEpgId)
        // The two "Dup" channels can't be told apart: the hidden flag stays with the old key.
        assertTrue(after.filter { it.name == "Dup" }.all { it.visible })
        val p = playlist(id)
        assertFalse(p.rekeyPending)
        assertEquals(server.url("/u2.m3u").toString(), p.url)
    }

    @Test fun carryOverOnlyAfterSourceEdit() = runTest {
        serve("/list.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        FavouritesRepository(db).toggle(channels(id).single { it.name == "One" })

        serve("/list.m3u", loginList("u2/p2")) // same address, new keys
        successOf(repo.refresh(id) {})
        assertFalse(FavouritesRepository(db).isFavourite(channels(id).single { it.name == "One" }))
    }

    @Test fun failedSourceEditKeepsNewValuesAndOldChannelsThenCarriesOverLater() = runTest {
        serve("/u1.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/u1.m3u").toString()) {}).playlistId
        FavouritesRepository(db).toggle(channels(id).single { it.name == "One" })
        val before = channels(id)

        val r = repo.updateSource(id, SourceEdit.M3uUrl(server.url("/u2.m3u").toString())) {} // 404 for now
        assertEquals(ImportError.Http(404), (r as ImportResult.Failure).error)
        val p = playlist(id)
        assertEquals(server.url("/u2.m3u").toString(), p.url)
        assertTrue(p.rekeyPending)
        assertEquals(r.message, p.lastError)
        assertEquals(before, channels(id))

        // The next successful update (e.g. a scheduled one) still carries the favourite over.
        serve("/u2.m3u", loginList("u2/p2"))
        successOf(repo.refresh(id) {})
        assertTrue(FavouritesRepository(db).isFavourite(channels(id).single { it.name == "One" }))
        assertFalse(playlist(id).rekeyPending)
    }

    @Test fun sourceEditCanChangeTypeAndClearsOldCredentials() = runTest {
        serve("/u1.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/u1.m3u").toString()) {}).playlistId
        repo.setUserAgent(id, "Mine/2")
        val file = localFile("list.m3u", "One" to "http://s.example.test/1.ts")

        successOf(repo.updateSource(id, SourceEdit.LocalFile(file.absolutePath)) {})
        var p = playlist(id)
        assertEquals(PlaylistType.M3U_FILE, p.type)
        assertEquals(file.absolutePath, p.url)
        assertEquals("Mine/2", p.userAgent) // not a source value
        assertTrue(File(copiesDir, "$id.m3u").isFile)

        successOf(repo.updateSource(id, SourceEdit.Portal(portalCfg)) {})
        p = playlist(id)
        assertEquals(PlaylistType.STALKER, p.type)
        assertEquals("TOKEN-PLACEHOLDER-1", p.portalToken)
        assertFalse("the old file's copy goes", File(copiesDir, "$id.m3u").exists())

        successOf(repo.updateSource(id, SourceEdit.M3uUrl(server.url("/u1.m3u").toString())) {})
        p = playlist(id)
        assertEquals(PlaylistType.M3U, p.type)
        assertNull(p.mac)
        assertNull(p.portalToken)
        assertNull(p.portalEndpoint)
    }

    @Test fun portalEditInvalidatesSessionAndRediscovers() = runTest {
        val id = successOf(repo.addStalker("Portal", portalCfg) {}).playlistId
        successOf(repo.refresh(id) {}) // session cached by this repository
        portal.requests.clear()
        portal.expireToken()

        successOf(repo.updateSource(id, SourceEdit.Portal(portalCfg)) {})
        // Endpoint and token were cleared: a new probe and an empty-token handshake.
        assertTrue(portal.requests.any { it.requestUrl!!.encodedPath != portal.path })
        assertEquals("", portal.ofAction("handshake").first().requestUrl!!.queryParameter("token"))
        assertEquals("TOKEN-PLACEHOLDER-2", playlist(id).portalToken)
    }

    // --- user agents ---

    @Test fun downloadsSendUserAgent() = runTest {
        serve("/list.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        assertEquals("General/1", requests.last().getHeader("User-Agent"))
        repo.setUserAgent(id, "  Mine/2 ")
        assertEquals("Mine/2", playlist(id).userAgent)
        successOf(repo.refresh(id) {})
        assertEquals("Mine/2", requests.last().getHeader("User-Agent"))
        repo.setUserAgent(id, " ")
        assertNull(playlist(id).userAgent)

        // Xtream: every call (login, categories, streams), on add and on refresh.
        requests.clear()
        val res = { path: String -> javaClass.classLoader!!.getResourceAsStream("fixtures/xtream/$path")!!.readBytes().decodeToString() }
        serve("/player_api.php", res("auth_ok.json"))
        serve("get_live_categories", res("categories.json"))
        serve("get_live_streams", res("live_streams.json"))
        val creds = XtreamCredentials("http://${server.hostName}:${server.port}", "user1", "pass1")
        val xt = successOf(repo.addXtream("X", creds) {}).playlistId
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.getHeader("User-Agent") == "General/1" })
        requests.clear()
        repo.setUserAgent(xt, "Xt/3")
        successOf(repo.refresh(xt) {})
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.getHeader("User-Agent") == "Xt/3" })
    }

    // --- playlist options ---

    @Test fun renameIgnoresBlankAndOptionsAreStored() = runTest {
        serve("/list.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        repo.rename(id, "  Home  ")
        repo.rename(id, "   ")
        assertEquals("Home", playlist(id).name)
        repo.setUpdateOptions(id, 6, false)
        assertEquals(6 to false, playlist(id).let { it.updateHours to it.updateOnStart })
        repo.setUpdateOptions(id, null, null)
        assertEquals(null to null, playlist(id).let { it.updateHours to it.updateOnStart })
    }

    @Test fun disabledPlaylistStillRefreshableByHand() = runTest {
        serve("/list.m3u", loginList("u1/p1"))
        val id = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        ChannelEdits(db, ChannelOverlay(db)).setPlaylistEnabled(id, false)
        requests.clear()
        assertEquals(ImportResult.Success(id, 4), repo.refresh(id) {})
        assertEquals(1, requests.size)
        assertTrue("still hidden after the refresh", channels(id).none { it.visible })
    }

    // --- delete ---

    private fun programme(epgId: String, generation: Long = 1) = ProgrammeEntity(
        epgChannelId = epgId, start = Instant.parse("2026-11-09T10:00:00Z"), stop = Instant.parse("2026-11-09T11:00:00Z"),
        title = "Show", subTitle = null, description = null, category = null, generation = generation,
    )

    @Test fun deleteRemovesCopyAndUnreferencedProgrammes() = runTest {
        val file = localFile("list.m3u", "One" to "http://s.example.test/1.ts")
        val local = successOf(repo.addLocalFile("Mine", file.absolutePath) {}).playlistId
        serve("/list.m3u", loginList("u1/p1"))
        val other = successOf(repo.addM3u("P", server.url("/list.m3u").toString()) {}).playlistId
        db.channelDao().setEpgChannelId(channels(local).single().id, "gone")
        db.channelDao().setEpgChannelId(channels(other).first().id, "keep")
        db.programmeDao().insertAll(listOf(programme("gone"), programme("keep"), programme("orphan")))
        assertTrue(File(copiesDir, "$local.m3u").isFile)

        repo.delete(local)

        assertNull(db.playlistDao().byId(local))
        assertFalse(File(copiesDir, "$local.m3u").exists())
        assertEquals(1, db.rowCount("programmes"))
        assertNotNull(db.programmeDao().nowAndNext("keep", Instant.parse("2026-11-09T10:30:00Z")).singleOrNull())
    }

    @Test fun deleteKeepsPendingGeneration() = runTest {
        serve("/list.m3u", loginList("u1/p1"))
        val a = successOf(repo.addM3u("A", server.url("/list.m3u").toString()) {}).playlistId
        val b = successOf(repo.addM3u("B", server.url("/list.m3u").toString()) {}).playlistId
        // Another playlist's EPG refresh is writing generation 7; its channels don't own these ids yet.
        db.programmeDao().insertPending(7)
        db.programmeDao().insertAll(listOf(programme("new.id", generation = 7), programme("old.id", generation = 1)))

        repo.delete(a)

        assertNotNull(db.playlistDao().byId(b))
        assertEquals(1, db.rowCount("programmes"))
        db.programmeDao().deletePending(7)
        assertEquals("new.id", db.programmeDao().nowAndNext("new.id", Instant.parse("2026-11-09T10:30:00Z")).single().epgChannelId)
    }

    @Test fun deleteCleanUpRunsEvenWhenTheCallerIsCancelled() = runTest {
        val file = localFile("list.m3u", "One" to "http://s.example.test/1.ts")
        val id = successOf(repo.addLocalFile("Mine", file.absolutePath) {}).playlistId
        db.channelDao().setEpgChannelId(channels(id).single().id, "gone")
        db.programmeDao().insertAll(listOf(programme("gone")))

        val job = launch { repo.delete(id) }
        runCurrent() // holds the lock and is waiting on the database
        job.cancel()
        job.join()

        assertNull(db.playlistDao().byId(id))
        assertFalse("the copy goes", File(copiesDir, "$id.m3u").exists())
        assertEquals(0, db.rowCount("programmes"))
    }

    @Test fun sharedFileKeepsItsGrantUntilTheLastPlaylistGoes() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val uri = android.net.Uri.parse("content://io.github.loopholetv.test.shared/list.m3u")
        val body = m3u("One" to "http://s.example.test/1.ts")
        org.robolectric.Shadows.shadowOf(resolver).registerInputStreamSupplier(uri) { body.byteInputStream() }
        resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fun granted() = resolver.persistedUriPermissions.any { it.uri == uri }
        assertTrue(granted())

        val a = successOf(repo.addLocalFile("A", uri.toString()) {}).playlistId
        val b = successOf(repo.addLocalFile("B", uri.toString()) {}).playlistId
        repo.delete(a)
        assertTrue("B still reads it", granted())
        repo.delete(b)
        assertFalse(granted())
    }

    @Test fun failedPortalAddNeverShowsSecrets() = runTest {
        portal.accessDenied = true
        val cfg = portalCfg.copy(login = "user1", password = "pass1", serial = "SER123456")
        val r = repo.addStalker("Portal", cfg) {} as ImportResult.Failure
        assertEquals(ImportError.AuthFailed, r.error)
        for (s in listOf("00:1A:79:00:00:01", "00:1a:79", "001A79000001", "TOKEN-PLACEHOLDER", "user1", "pass1", "SER123456", portalServer.hostName)) {
            assertFalse("$s in ${r.message}", r.message.contains(s))
        }
        assertTrue(repo.playlists().first().isEmpty())
    }
}
