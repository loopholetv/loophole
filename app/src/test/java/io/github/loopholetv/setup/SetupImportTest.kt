package io.github.loopholetv.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.HistoryEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.loadAll
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.LocalM3uOpener
import io.github.loopholetv.data.repo.PlaylistRepository
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.SettingsStore
import io.github.loopholetv.data.xtream.XtreamClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SetupImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The TV that sends its setup. */
    private lateinit var source: AppDatabase

    /** The TV that receives it. */
    private lateinit var target: AppDatabase

    @Before fun setUp() {
        source = inMemoryDb()
        target = inMemoryDb()
    }

    @After fun tearDown() {
        source.close()
        target.close()
    }

    private val resolver get() = ApplicationProvider.getApplicationContext<Context>().contentResolver

    private fun dataStore(scope: CoroutineScope, name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { File(tmp.root, "$name.preferences_pb") }

    private fun sources(tv: String) = File(tmp.root, "$tv/sources")

    private fun files(tv: String) = LocalM3uOpener(resolver, sources(tv))

    private fun playlist(name: String, type: PlaylistType, url: String? = null) = PlaylistEntity(
        name = name, type = type, url = url, xtreamServer = null, xtreamUser = null, xtreamPass = null,
        epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
    )

    private fun localFile(name: String, bytes: ByteArray): File =
        File(tmp.root, "media/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun ok(result: SetupImportResult): SetupImportResult.Ok =
        result as? SetupImportResult.Ok ?: throw AssertionError("import failed")

    /** Fills [source] with one playlist of every type, edits, guide links and favourites. */
    private suspend fun fillSource(fileBytes: ByteArray = "#EXTM3U\n".toByteArray()): Map<String, Long> {
        val pd = source.playlistDao()
        val m3u = pd.insert(
            playlist("Main", PlaylistType.M3U, "http://m3u.example.test/list.m3u").copy(
                enabled = false, userAgent = "UA", updateHours = 6, updateOnStart = false, useSourceEpg = false,
                manualEpgUrl = "http://legacy.example.test/epg.xml",
                epgUrlsFromSource = "http://announced.example.test/epg.xml", lastRefreshedAt = Instant.ofEpochMilli(5),
                lastError = "Failed", rekeyPending = true,
            ),
        )
        val xtream = pd.insert(
            playlist("Xtream", PlaylistType.XTREAM).copy(
                xtreamServer = "http://xt.example.test", xtreamUser = "u", xtreamPass = "p",
                xtreamTimeZone = "Europe/London", xtreamArchiveExt = "ts",
            ),
        )
        val portal = pd.insert(
            playlist("Portal", PlaylistType.STALKER, "http://portal.example.test/c/").copy(
                mac = "00:1A:79:00:00:01", portalLogin = "l", portalPassword = "pw", portalSerial = "sn",
                portalDeviceId = "d1", portalDeviceId2 = "d2", portalSignature = "sig",
                portalEndpoint = "http://portal.example.test/portal.php", portalToken = "token",
            ),
        )
        val file = pd.insert(playlist("File", PlaylistType.M3U_FILE, localFile("list.m3u", fileBytes).absolutePath))
        source.epgSourceDao().add(m3u, "http://epg1.example.test/a.xml")
        source.epgSourceDao().add(m3u, "http://epg2.example.test/b.xml")
        source.channelPrefsDao().upsert(
            ChannelPrefsEntity(
                m3u, "k1", hidden = true, customName = "One", customLogo = "http://logo.example.test/1.png",
                epgOverride = "one.uk", epgShiftMinutes = 60, position = 2, aspectMode = AspectMode.ZOOM,
                videoDecoder = DecoderChoice.SOFTWARE, audioDecoder = DecoderChoice.SOFTWARE, externalPlayer = "ask",
            ),
        )
        // Only not-copied edits: nothing of it travels.
        source.channelPrefsDao().upsert(ChannelPrefsEntity(xtream, "7", externalPlayer = "org.example.player"))
        source.channelPrefsDao().upsert(ChannelPrefsEntity(file, "f1", customName = "Film"))
        source.groupPrefsDao().upsert(GroupPrefsEntity(m3u, "News", hidden = true, customName = "Headlines", position = 1, sortMode = GroupSort.NAME))
        source.groupPrefsDao().upsert(GroupPrefsEntity(portal, "Sport", position = 0, sortMode = GroupSort.CUSTOM))
        source.favouriteDao().add(ChannelRef(xtream, "7"), Instant.ofEpochMilli(10))
        source.favouriteDao().add(ChannelRef(m3u, "k1"), Instant.ofEpochMilli(20))
        source.favouriteDao().add(ChannelRef(file, "f1"), Instant.ofEpochMilli(30))
        return mapOf("m3u" to m3u, "xtream" to xtream, "portal" to portal, "file" to file)
    }

    /** What a TV holds of the copied tables, with playlist ids replaced by their position. */
    private data class Copied(
        val playlists: List<PlaylistEntity>,
        val links: List<Pair<Int, String>>,
        val channelEdits: List<ChannelPrefsEntity>,
        val groupEdits: List<GroupPrefsEntity>,
        val favourites: List<Triple<Int, String, Instant>>,
    )

    private suspend fun AppDatabase.copied(): Copied = withTransaction {
        val dao = setupDao()
        val playlists = dao.playlists()
        val index = playlists.mapIndexed { i, p -> p.id to i.toLong() }.toMap()
        Copied(
            playlists = playlists.map { if (it.type == PlaylistType.M3U_FILE) it.copy(id = 0, url = null) else it.copy(id = 0) },
            links = dao.epgSources().map { index.getValue(it.playlistId).toInt() to it.url },
            channelEdits = dao.channelPrefs().map { it.copy(playlistId = index.getValue(it.playlistId)) },
            groupEdits = dao.groupPrefs().map { it.copy(playlistId = index.getValue(it.playlistId)) },
            favourites = dao.favourites().map { Triple(index.getValue(it.playlistId).toInt(), it.channelKey, it.addedAt) },
        )
    }

    /** [Copied] as the receiving TV should hold it: refreshed, session and hardware values cleared. */
    private fun Copied.asSent() = copy(
        playlists = playlists.map {
            it.copy(
                epgUrlsFromSource = "", lastRefreshedAt = null, lastError = null, rekeyPending = false,
                portalEndpoint = null, portalToken = null, xtreamTimeZone = null, xtreamArchiveExt = null,
            )
        },
        channelEdits = channelEdits
            .map { it.copy(videoDecoder = null, audioDecoder = null, externalPlayer = null) }
            .filterNot { it.isDefault() },
    )

    @Test
    fun hasPlaylistsSaysWhetherAnyPlaylistExists() = runTest {
        assertFalse(target.setupDao().hasPlaylists())
        target.playlistDao().insert(playlist("Main", PlaylistType.M3U, "http://example.test/a.m3u"))
        assertTrue(target.setupDao().hasPlaylists())
    }

    @Test
    fun roundTripPreservesEverythingCopied() = runTest {
        val fileBytes = "#EXTM3U\n#EXTINF:-1,Film\nhttp://f.example.test/1.ts\n".toByteArray()
        fillSource(fileBytes)
        val sourceSettings = SettingsStore(dataStore(backgroundScope, "source"))
        sourceSettings.update { changedSettings() }
        // The receiving TV has a playlist, edits and favourites of its own: all replaced.
        val old = target.playlistDao().insert(playlist("Old", PlaylistType.M3U, "http://old.example.test/a.m3u"))
        target.epgSourceDao().add(old, "http://old.example.test/epg.xml")
        target.groupPrefsDao().upsert(GroupPrefsEntity(old, "Old group", hidden = true))
        target.favouriteDao().add(ChannelRef(old, "x"), Instant.ofEpochMilli(1))
        val targetSettings = SettingsStore(dataStore(backgroundScope, "target"))

        val exported = SetupExporter(source, sourceSettings, files("source")).export("Living room")
        assertEquals(0, exported.skippedLargeFiles)
        val bundle = SetupJson.decode(SetupJson.encode(exported.bundle), files("target")::newTemp)!!
        assertEquals("Living room", bundle.fromName)

        val result = ok(SetupImporter(target, targetSettings, files("target")).import(bundle))

        assertEquals(0, result.skippedFiles)
        assertEquals(listOf(RemovedPlaylist(old, PlaylistType.M3U, "http://old.example.test/a.m3u")), result.removed)
        assertEquals(source.copied().asSent(), target.copied())
        assertEquals(SetupSettings.from(changedSettings()), SetupSettings.from(targetSettings.current()))
        // The received file is the new playlist's last-good copy, read by path like any local file.
        val received = target.setupDao().playlists().single { it.type == PlaylistType.M3U_FILE }
        assertEquals(files("target").copyFile(received.id).absolutePath, received.url)
        assertArrayEquals(fileBytes, File(received.url!!).readBytes())
        val repo = PlaylistRepository(target, OkHttpClient(), XtreamClient(OkHttpClient()), local = files("target"))
        assertTrue(repo.refresh(received.id) {} is ImportResult.Success)
        assertNull(target.playlistDao().byId(received.id)!!.lastError)
        assertEquals(listOf("Film"), target.channelDao().channelsOf(received.id).map { it.name })
    }

    @Test
    fun notCopiedSettingsAreKept() = runTest {
        fillSource()
        val sourceSettings = SettingsStore(dataStore(backgroundScope, "source"))
        sourceSettings.update { changedSettings() }
        val targetSettings = SettingsStore(dataStore(backgroundScope, "target"))
        val mine = AppSettings(castTvName = "Bedroom", castAllowJoin = true, lastGuideScope = "favourites")
        targetSettings.update { mine }
        val selfId = targetSettings.castSelfId()
        // The receiving TV already had the same M3U playlist: its history and per-channel
        // hardware choices stay with it; a playlist that isn't sent loses its history with it.
        val same = target.playlistDao().insert(playlist("Mine", PlaylistType.M3U, "http://m3u.example.test/list.m3u"))
        val other = target.playlistDao().insert(playlist("Other", PlaylistType.M3U, "http://other.example.test/a.m3u"))
        target.channelPrefsDao().upsert(
            ChannelPrefsEntity(same, "k1", videoDecoder = DecoderChoice.HARDWARE, audioDecoder = DecoderChoice.SOFTWARE, externalPlayer = "org.example.player"),
        )
        target.channelPrefsDao().upsert(ChannelPrefsEntity(same, "k9", videoDecoder = DecoderChoice.SOFTWARE, customName = "Mine"))
        target.setupDao().insertHistory(
            listOf(HistoryEntity(same, "k1", Instant.ofEpochMilli(100)), HistoryEntity(other, "o", Instant.ofEpochMilli(200))),
        )

        val bundle = SetupExporter(source, sourceSettings, files("source")).export("Living room").bundle
        assertEquals(0, ok(SetupImporter(target, targetSettings, files("target")).import(bundle)).skippedFiles)

        val after = targetSettings.current()
        assertEquals("Bedroom", after.castTvName)
        assertTrue(after.castAllowJoin)
        assertEquals("favourites", after.lastGuideScope)
        assertEquals(
            mine.playback.copy(
                bufferPreset = changedSettings().playback.bufferPreset,
                audioLanguage = "de", subtitleLanguage = "fr",
                subtitleStyle = changedSettings().playback.subtitleStyle, pipOnHome = true,
            ),
            after.playback,
        )
        assertEquals(selfId, targetSettings.castSelfId())

        val newId = target.setupDao().playlists().single { it.url == "http://m3u.example.test/list.m3u" }.id
        assertEquals(listOf(HistoryEntity(newId, "k1", Instant.ofEpochMilli(100))), target.setupDao().history())
        val prefs = target.setupDao().channelPrefs().filter { it.playlistId == newId }.associateBy { it.channelKey }
        assertEquals(
            ChannelPrefsEntity(
                newId, "k1", hidden = true, customName = "One", customLogo = "http://logo.example.test/1.png",
                epgOverride = "one.uk", epgShiftMinutes = 60, position = 2, aspectMode = AspectMode.ZOOM,
                videoDecoder = DecoderChoice.HARDWARE, audioDecoder = DecoderChoice.SOFTWARE, externalPlayer = "org.example.player",
            ),
            prefs["k1"],
        )
        // The edit itself is mirrored away; the TV's own decoder choice for that channel stays.
        assertEquals(ChannelPrefsEntity(newId, "k9", videoDecoder = DecoderChoice.SOFTWARE), prefs["k9"])
    }

    @Test
    fun favouritesAndEditsReattachAfterImport() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val body = buildString {
                append("#EXTM3U\n")
                listOf("a", "b", "c").forEach { append("#EXTINF:-1 group-title=\"G\",$it\nhttp://stream.example.test/$it.ts\n") }
            }
            val http = OkHttpClient()
            val url = server.url("/list.m3u").toString()
            server.enqueue(MockResponse().setBody(body))
            val sourceRepo = PlaylistRepository(source, http, XtreamClient(http))
            val id = (sourceRepo.addM3u("P", url) {} as ImportResult.Success).playlistId
            val byName = source.channelDao().channelsOf(id).associateBy { it.name }
            source.favouriteDao().add(ChannelRef(id, byName.getValue("c").key), Instant.ofEpochMilli(1))
            source.favouriteDao().add(ChannelRef(id, byName.getValue("a").key), Instant.ofEpochMilli(2))
            source.channelPrefsDao().upsert(ChannelPrefsEntity(id, byName.getValue("b").key, customName = "Bee"))
            val sourceSettings = SettingsStore(dataStore(backgroundScope, "source"))
            val targetSettings = SettingsStore(dataStore(backgroundScope, "target"))

            val bundle = SetupExporter(source, sourceSettings, files("source")).export("Living room").bundle
            ok(SetupImporter(target, targetSettings, files("target")).import(bundle))
            val newId = target.setupDao().playlists().single().id
            server.enqueue(MockResponse().setBody(body))
            assertTrue(PlaylistRepository(target, http, XtreamClient(http)).refresh(newId) {} is ImportResult.Success)

            assertEquals(listOf("c", "a"), target.favouriteDao().channels().loadAll().map { it.name })
            assertEquals("Bee", target.channelDao().channelsOf(newId).single { it.name == "b" }.customName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failedImportChangesNothing() = runTest {
        fillSource()
        val sourceSettings = SettingsStore(dataStore(backgroundScope, "source"))
        sourceSettings.update { changedSettings() }
        val bundle = SetupExporter(source, sourceSettings, files("source")).export("Living room").bundle

        // Each step in turn: a received file can't be staged; the settings write fails before it
        // is stored; it fails after it was stored; the transaction fails after the settings were
        // stored (as a failing commit would).
        for (failure in Failure.entries) {
            val name = "target-${failure.name}"
            val real = dataStore(backgroundScope, name)
            val store = if (failure == Failure.BEFORE_WRITE || failure == Failure.AFTER_WRITE) FailingStore(real, failure) else real
            val targetSettings = SettingsStore(store)
            val mine = AppSettings(castTvName = "Bedroom", confirmExit = false, playlistUpdateHours = 3)
            targetSettings.update { mine }
            val db = inMemoryDb()
            try {
                val old = db.playlistDao().insert(playlist("Old", PlaylistType.M3U, "http://old.example.test/a.m3u"))
                db.favouriteDao().add(ChannelRef(old, "x"), Instant.ofEpochMilli(1))
                db.channelPrefsDao().upsert(ChannelPrefsEntity(old, "x", customName = "X"))
                val before = db.copied()
                val files = if (failure == Failure.STAGE) {
                    LocalM3uOpener(resolver, sources(name), copyOutput = { throw IOException("disk full") })
                } else {
                    files(name)
                }
                val importer = SetupImporter(
                    db, targetSettings, files,
                    afterSettingsWritten = { if (failure == Failure.COMMIT) throw IllegalStateException("commit failed") },
                )

                assertEquals(failure.name, SetupImportResult.Failed, importer.import(bundle))

                assertEquals(failure.name, before, db.copied())
                assertEquals(failure.name, mine, SettingsStore(real).current())
                assertEquals(failure.name, emptyList<String>(), sources(name).listFiles().orEmpty().map { it.name })
            } finally {
                db.close()
            }
        }
    }

    /** Three file playlists: the steps that handle files run more than once. */
    private fun threeFiles() = sampleBundle().let { b ->
        b.copy(
            playlists = b.playlists + listOf(1, 2).map { i ->
                SetupPlaylist(name = "More $i", type = PlaylistType.M3U_FILE, file = SetupFile("#EXTM3U\n$i\n".toByteArray()))
            },
        )
    }

    @Test
    fun failedFileStepsChangeNothing() = runTest {
        for (step in listOf("second stage", "second rename")) {
            val name = "target-${step.replace(' ', '-')}"
            val real = dataStore(backgroundScope, name)
            val targetSettings = SettingsStore(real)
            val mine = AppSettings(castTvName = "Bedroom", confirmExit = false)
            targetSettings.update { mine }
            val db = inMemoryDb()
            try {
                val old = db.playlistDao().insert(playlist("Old", PlaylistType.M3U, "http://old.example.test/a.m3u"))
                db.favouriteDao().add(ChannelRef(old, "x"), Instant.ofEpochMilli(1))
                val before = db.copied()
                var opened = 0
                val files = LocalM3uOpener(resolver, sources(name), copyOutput = { f ->
                    if (step == "second stage" && ++opened == 2) throw IOException("disk full") else f.outputStream()
                })
                if (step == "second rename") {
                    // New ids follow the old one in bundle order: positions 3, 4 and 5 are the files.
                    // The second file's copy can't be put in place.
                    File(sources(name), "${old + 5}.m3u").apply { mkdirs() }.resolve("in-the-way").writeText("x")
                }
                val leftBefore = sources(name).listFiles().orEmpty().map { it.name }.sorted()

                assertEquals(step, SetupImportResult.Failed, SetupImporter(db, targetSettings, files).import(threeFiles()))

                assertEquals(step, before, db.copied())
                assertEquals(step, mine, targetSettings.current())
                assertEquals(step, leftBefore, sources(name).listFiles().orEmpty().map { it.name }.sorted())
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun importsNeverOverlapAcrossImporters() = runTest {
        val settings = SettingsStore(dataStore(backgroundScope, "target"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var secondRan = false
        val first = SetupImporter(target, settings, files("target"), afterSettingsWritten = {
            entered.complete(Unit)
            gate.await()
        })
        val second = SetupImporter(target, settings, files("target"), afterSettingsWritten = { secondRan = true })

        val a = async { first.import(sampleBundle()) }
        entered.await()
        val b = async { second.import(sampleBundle()) }
        withContext(Dispatchers.Default) { delay(300) }
        assertFalse(secondRan)
        gate.complete(Unit)

        ok(a.await())
        ok(b.await())
        assertTrue(secondRan)
    }

    @Test
    fun importRunsUnderTheRefreshLock() = runTest {
        val lock = Mutex()
        var lockedDuringImport = false
        val importer = SetupImporter(
            target, SettingsStore(dataStore(backgroundScope, "target")), files("target"),
            serialize = { block -> lock.withLock { block() } },
            afterSettingsWritten = { lockedDuringImport = lock.isLocked },
        )
        ok(importer.import(sampleBundle()))
        assertTrue(lockedDuringImport)
        assertFalse(lock.isLocked)
    }

    @Test
    fun largeLocalFileIsSkippedAndCounted() = runTest {
        val ids = fillSource()
        val big = source.playlistDao().insert(
            playlist("Big", PlaylistType.M3U_FILE, localFile("big.m3u", ByteArray(MAX_SETUP_FILE_BYTES + 1) { 'a'.code.toByte() }).absolutePath),
        )
        source.channelPrefsDao().upsert(ChannelPrefsEntity(big, "b1", customName = "Big one"))
        source.groupPrefsDao().upsert(GroupPrefsEntity(big, "Big group", hidden = true))
        source.epgSourceDao().add(big, "http://epg.example.test/big.xml")
        source.favouriteDao().add(ChannelRef(big, "b1"), Instant.ofEpochMilli(99))
        // A file at the cap still travels.
        source.playlistDao().update(
            source.playlistDao().byId(ids.getValue("file"))!!.copy(
                url = localFile("full.m3u", ByteArray(MAX_SETUP_FILE_BYTES) { 'b'.code.toByte() }).absolutePath,
            ),
        )
        val sourceSettings = SettingsStore(dataStore(backgroundScope, "source"))
        val targetSettings = SettingsStore(dataStore(backgroundScope, "target"))

        val exported = SetupExporter(source, sourceSettings, files("source")).export("Living room")

        assertEquals(1, exported.skippedLargeFiles)
        assertEquals(1, exported.bundle.skippedFiles)
        assertEquals(listOf("Main", "Xtream", "Portal", "File"), exported.bundle.playlists.map { it.name })
        assertEquals(MAX_SETUP_FILE_BYTES.toLong(), exported.bundle.playlists.last().file!!.size)
        val bundle = SetupJson.decode(SetupJson.encode(exported.bundle), files("target")::newTemp)!!
        // The receiving TV is the one that says how many were left out.
        assertEquals(1, ok(SetupImporter(target, targetSettings, files("target")).import(bundle)).skippedFiles)
        val copied = target.copied()
        assertEquals(listOf("Main", "Xtream", "Portal", "File"), copied.playlists.map { it.name })
        assertFalse(copied.channelEdits.any { it.channelKey == "b1" })
        assertFalse(copied.groupEdits.any { it.groupTitle == "Big group" })
        assertFalse(copied.links.any { it.second.contains("big") })
        assertEquals(listOf("7", "k1", "f1"), copied.favourites.map { it.second })
    }

    @Test
    fun filesPastTheTotalAreSkippedAndCounted() = runTest {
        val pd = source.playlistDao()
        listOf("one" to 80, "two" to 80, "three" to 60).forEach { (name, size) ->
            pd.insert(playlist(name, PlaylistType.M3U_FILE, localFile("$name.m3u", ByteArray(size)).absolutePath))
        }
        val exporter = SetupExporter(
            source, SettingsStore(dataStore(backgroundScope, "source")), files("source"),
            maxFileBytes = 100, maxTotalFileBytes = 150,
        )

        val exported = exporter.export("Living room")

        assertEquals(1, exported.skippedLargeFiles)
        assertEquals(listOf("one", "three"), exported.bundle.playlists.map { it.name })
    }

    @Test
    fun receiverLeavesOutFilesPastTheCaps() = runTest {
        val sample = sampleBundle()
        val oversized = SetupPlaylist(name = "Huge", type = PlaylistType.M3U_FILE, file = SetupFile(ByteArray(MAX_SETUP_FILE_BYTES + 1)))
        val bundle = sample.copy(
            playlists = listOf(oversized) + sample.playlists,
            favourites = listOf(SetupFavourite(0, "h", 1)) + sample.favourites.map { it.copy(playlist = it.playlist + 1) },
        )

        val result = ok(SetupImporter(target, SettingsStore(dataStore(backgroundScope, "target")), files("target")).import(bundle))

        assertEquals(sample.skippedFiles + 1, result.skippedFiles)
        val copied = target.copied()
        assertEquals(sample.playlists.map { it.name }, copied.playlists.map { it.name })
        assertEquals(sample.favourites.map { it.playlist to it.key }, copied.favourites.map { it.first to it.second })
    }

    @Test
    fun importCleansUpTheReplacedFilePlaylists() = runTest {
        val targetSettings = SettingsStore(dataStore(backgroundScope, "target"))
        ok(SetupImporter(target, targetSettings, files("target")).import(sampleBundle()))
        val firstPath = target.setupDao().playlists().single { it.type == PlaylistType.M3U_FILE }.url!!
        assertTrue(File(firstPath).exists())

        ok(SetupImporter(target, targetSettings, files("target")).import(sampleBundle()))

        assertFalse(File(firstPath).exists())
        val secondPath = target.setupDao().playlists().single { it.type == PlaylistType.M3U_FILE }.url!!
        assertEquals(listOf(File(secondPath).name), sources("target").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun watchersNeverSeeTheTvWithoutPlaylistsMidImport() = kotlinx.coroutines.runBlocking {
        target.playlistDao().insert(playlist("Old", PlaylistType.M3U, "http://old.example.test/a.m3u"))
        val work = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        try {
            val counts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            work.launch { target.playlistDao().all().collect { counts += it.size } }
            kotlinx.coroutines.withTimeout(10_000) { while (counts.isEmpty()) delay(10) }
            val bundle = sampleBundle()

            ok(SetupImporter(target, SettingsStore(dataStore(work, "target")), files("target")).import(bundle))

            kotlinx.coroutines.withTimeout(10_000) { while (counts.last() != bundle.playlists.size) delay(10) }
            assertFalse("the playlist count never reads 0: $counts", 0 in counts)
        } finally {
            work.cancel()
        }
    }

    private enum class Failure { STAGE, BEFORE_WRITE, AFTER_WRITE, COMMIT }

    /** A settings store whose second write (the import's) fails, before or after it is stored. */
    private class FailingStore(private val real: DataStore<Preferences>, private val failure: Failure) : DataStore<Preferences> {
        private var writes = 0
        override val data: Flow<Preferences> get() = real.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            // The first write is the test's own setup; later ones (the write-back) go through.
            if (++writes != 2) return real.updateData(transform)
            if (failure == Failure.AFTER_WRITE) real.updateData(transform) else transform(real.data.first())
            throw IOException("disk full")
        }
    }
}
