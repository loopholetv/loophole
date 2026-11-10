package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

private const val HTTP_500 = "The TV guide server returned an error (500)."
private const val DAMAGED = "The TV guide file is damaged or not in XMLTV format."
private const val NO_SOURCE = "No TV guide source. Add one in Settings → EPG."

@RunWith(RobolectricTestRunner::class)
class EpgRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: EpgRepository
    private val http = OkHttpClient()
    private var clock: Instant = Instant.parse("2026-11-07T10:00:00Z")
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes[request.requestUrl!!.encodedPath]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        repo = repoWith(EpgStoreOptions(pastDays = 7)) // 7 days of history; the new defaults have their own tests
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    // --- helpers ---

    private fun repoWith(o: EpgStoreOptions) = EpgRepository(db, http, now = { clock }, options = { o })

    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    private class P(val channel: String, val start: Instant, val stop: Instant, val title: String)

    private fun prog(channel: String, startOffsetMin: Long, minutes: Long, title: String = "$channel@$startOffsetMin") =
        P(channel, clock.plusSeconds(startOffsetMin * 60), clock.plusSeconds((startOffsetMin + minutes) * 60), title)

    private fun xmltv(channels: Map<String, List<String>>, programmes: List<P>, channelsFirst: Boolean = true) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        val ch = buildString {
            for ((id, names) in channels) {
                append("<channel id=\"$id\">")
                names.forEach { append("<display-name>$it</display-name>") }
                append("</channel>\n")
            }
        }
        if (channelsFirst) append(ch)
        for (p in programmes) {
            append("<programme start=\"${xmltvTime.format(p.start)}\" stop=\"${xmltvTime.format(p.stop)}\" channel=\"${p.channel}\">")
            append("<title>${p.title}</title><desc>About ${p.title}</desc></programme>\n")
        }
        if (!channelsFirst) append(ch)
        append("</tv>\n")
    }

    private fun serve(path: String, body: String) {
        routes[path] = { MockResponse().setBody(body) }
    }

    private fun serveCode(path: String, code: Int) {
        routes[path] = { MockResponse().setResponseCode(code) }
    }

    private fun url(path: String) = server.url(path).toString()

    /** [manual] becomes the playlist's one added guide source (a row in `epg_sources`). */
    private suspend fun playlist(epgUrls: List<String>, manual: String? = null, type: PlaylistType = PlaylistType.M3U): Long =
        db.playlistDao().insert(
            PlaylistEntity(
                name = "P",
                type = type,
                url = when (type) {
                    PlaylistType.M3U -> "http://iptv.example.test/get.php?username=u1&password=secret99"
                    PlaylistType.STALKER -> "http://portal.example.test/c/"
                    else -> null
                },
                xtreamServer = if (type == PlaylistType.XTREAM) "http://${server.hostName}:${server.port}" else null,
                xtreamUser = if (type == PlaylistType.XTREAM) "user1" else null,
                xtreamPass = if (type == PlaylistType.XTREAM) "pass1" else null,
                epgUrlsFromSource = epgUrls.joinToString("\n"),
                manualEpgUrl = null,
                lastRefreshedAt = null,
                lastError = null,
            ),
        ).also { id -> if (manual != null) db.epgSourceDao().add(id, manual) }

    private suspend fun channels(playlistId: Long, vararg spec: Pair<String, String?>) {
        db.channelDao().insertAll(
            spec.mapIndexed { i, (name, tvgId) ->
                ChannelEntity(
                    playlistId = playlistId, key = "k$i", number = i + 1, name = name,
                    streamUrl = "http://stream.example.test/$i.ts", logo = null, groupTitle = "G", groupOrder = 0,
                    sortOrder = i, tvgId = tvgId, tvgName = null, userAgent = null, referrer = null,
                    catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = null,
                )
            },
        )
    }

    private suspend fun stored(vararg ids: String): List<ProgrammeEntity> =
        db.programmeDao().inWindow(ids.toList(), Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).first()

    private fun countAll(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programmes").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private suspend fun epgIdsOf(playlistId: Long) = db.channelDao().channelsOf(playlistId).map { it.epgChannelId }

    /** Playlist with channels a, b (tvg-id) and a guide offering a, b and c. */
    private suspend fun standardSetup(): Long {
        serve(
            "/guide.xml",
            xmltv(
                mapOf("a" to listOf("Alpha"), "b" to listOf("Beta"), "c" to listOf("Gamma")),
                listOf(prog("a", 0, 60), prog("a", 60, 30), prog("b", 0, 30), prog("c", 0, 30)),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha TV" to "a", "Beta TV" to "b")
        return id
    }

    // --- tests ---

    @Test fun refreshStoresOnlyMatchedProgrammes() = runTest {
        val id = standardSetup()
        val progress = mutableListOf<Int>()
        val status = repo.refresh(id) { progress += it }

        assertTrue(status.ok)
        assertEquals(listOf("a@0", "a@60", "b@0"), stored("a", "b", "c").map { it.title })
        assertEquals(3, countAll())
        assertEquals(listOf("a", "b"), epgIdsOf(id))
        assertEquals(3, progress.last())
        val first = stored("a").first()
        assertEquals("About a@0", first.description)
        assertEquals(clock, first.start)
    }

    @Test fun statusReportsMatchedCount() = runTest {
        serve(
            "/guide.xml",
            xmltv(
                mapOf("x1" to listOf("One"), "x2" to listOf("Two"), "x3" to listOf("Fox Sports 504")),
                listOf(prog("x1", 0, 30)),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "One" to "X1", "Two" to null, "AU: FOX SPORTS 504 HD" to "unknown", "Four" to null, "Five" to "x5")

        val status = repo.refresh(id) {}

        assertEquals(3, status.matchedChannels)
        assertTrue(status.ok)
        assertNull(status.message)
        assertEquals(clock, status.updatedAt)
        assertEquals(status, repo.status(id).first())
        assertEquals(listOf("x1", "x2", "x3", null, null), epgIdsOf(id))
    }

    @Test fun mergesMultipleSources() = runTest {
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        serve(
            "/two.xml",
            xmltv(mapOf("a" to listOf("Alpha"), "b" to listOf("Beta")), listOf(prog("a", 0, 30, "dup"), prog("b", 0, 30))),
        )
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a", "Beta" to "b")

        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals(2, status.matchedChannels)
        // "a" was matched by the first source, so the second source's "a" programmes are not added again.
        assertEquals(listOf("a@0", "b@0"), stored("a", "b").map { it.title })
    }

    @Test fun failedDownloadKeepsOldProgrammes() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)

        serveCode("/guide.xml", 500)
        clock = clock.plusSeconds(3600)
        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(HTTP_500, status.message)
        assertEquals(clock.minusSeconds(3600), status.updatedAt)
        assertEquals(2, status.matchedChannels)
        assertEquals(listOf("a@0", "a@60", "b@0"), stored("a", "b").map { it.title })
        assertEquals(status, repo.status(id).first())
    }

    @Test fun corruptGzipKeepsOldProgrammes() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)

        val corrupt = Buffer().write(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7))
        routes["/guide.xml"] = { MockResponse().setBody(corrupt.copy()) }
        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(DAMAGED, status.message)
        assertEquals(3, countAll())
    }

    @Test fun malformedXmlKeepsOldProgrammesAndDropsPartialRows() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)

        val many = (0 until 1200).map { prog("a", it * 5L, 5, "new$it") }
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), many).removeSuffix("</tv>\n") + "<programme <<<")
        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(DAMAGED, status.message)
        assertEquals(listOf("a@0", "a@60", "b@0"), stored("a", "b").map { it.title })
    }

    @Test fun oldProgrammesPruned() = runTest {
        serve(
            "/guide.xml",
            xmltv(
                mapOf("a" to listOf("Alpha")),
                listOf(prog("a", -8 * 24 * 60L - 60, 60, "eight days"), prog("a", -6 * 24 * 60L, 60, "six days")),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a", "Beta" to "b")
        repo.refresh(id) {}
        assertEquals(listOf("six days"), stored("a").map { it.title })

        // Rows of a channel no longer in the guide are pruned once they ended over 7 days ago.
        serve("/guide.xml", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30))))
        clock = clock.plus(Duration.ofDays(2))
        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(emptyList<ProgrammeEntity>(), stored("a"))
        assertEquals(1, countAll())
    }

    @Test fun inWindowReturnsOverlapping() = runTest {
        clock = Instant.parse("2026-11-07T08:00:00Z")
        serve(
            "/guide.xml",
            xmltv(
                mapOf("a" to listOf("Alpha")),
                listOf(prog("a", 0, 120, "08-10"), prog("a", 120, 120, "10-12"), prog("a", 240, 60, "12-13")),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a")
        repo.refresh(id) {}

        val window = db.programmeDao()
            .inWindow(listOf("a"), Instant.parse("2026-11-07T11:00:00Z"), Instant.parse("2026-11-07T11:30:00Z")).first()
        assertEquals(listOf("10-12"), window.map { it.title })

        val nowNext = db.programmeDao().nowAndNext("a", Instant.parse("2026-11-07T11:00:00Z"))
        assertEquals(listOf("10-12", "12-13"), nowNext.map { it.title })
    }

    @Test fun largeEpgStreams() = runTest {
        val channelCount = 100
        val perChannel = 2000
        val body = Buffer()
        GzipSink(body).buffer().use { out ->
            out.writeUtf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 0 until channelCount) out.writeUtf8("<channel id=\"ch$c\"><display-name>Channel $c</display-name></channel>\n")
            val base = clock.minus(Duration.ofDays(1))
            for (c in 0 until channelCount) {
                for (i in 0 until perChannel) {
                    val start = base.plusSeconds(i * 300L)
                    out.writeUtf8(
                        "<programme start=\"${xmltvTime.format(start)}\" stop=\"${xmltvTime.format(start.plusSeconds(300))}\" " +
                            "channel=\"ch$c\"><title>Show $i</title><desc>Programme number $i on channel $c</desc></programme>\n",
                    )
                }
            }
            out.writeUtf8("</tv>\n")
        }
        routes["/big.xml.gz"] = { MockResponse().setBody(body.copy()) }
        val id = playlist(listOf(url("/big.xml.gz")))
        channels(id, *Array(channelCount) { "Channel $it" to "ch$it" })

        var last = 0
        val status = repo.refresh(id) { last = it }

        assertTrue(status.message, status.ok)
        assertEquals(channelCount, status.matchedChannels)
        assertEquals(channelCount * perChannel, countAll())
        assertEquals(channelCount * perChannel, last)
    }

    @Test fun noSourceGivesHelpfulStatus() = runTest {
        val id = playlist(emptyList())
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(NO_SOURCE, status.message)
        assertEquals(0, status.matchedChannels)
        assertNull(status.updatedAt)
        assertEquals(0, server.requestCount)
    }

    @Test fun manualUrlIsUsedAndXtreamUsesItsGuide() = runTest {
        serve("/manual.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        val m3u = playlist(emptyList())
        channels(m3u, "Alpha" to "a")
        repo.addSource(m3u, "  ${url("/manual.xml")} ")
        assertEquals(listOf(url("/manual.xml")), repo.sources(m3u).first().map { it.url })
        assertTrue(repo.refresh(m3u) {}.ok)
        repo.removeSource(repo.sources(m3u).first().single().id)
        assertEquals(emptyList<String>(), repo.sources(m3u).first().map { it.url })

        serve("/xmltv.php", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30))))
        val xt = playlist(emptyList(), type = PlaylistType.XTREAM)
        channels(xt, "Beta" to "b")
        assertTrue(repo.refresh(xt) {}.ok)
        assertEquals(listOf("b"), epgIdsOf(xt))
        val request = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.last()
        assertEquals("user1", request.requestUrl!!.queryParameter("username"))
    }

    @Test fun epgChannelIdPreservedOnFailure() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(listOf("a", "b"), epgIdsOf(id))

        // The new guide would map nothing, but it fails half-way: mappings must stay.
        serve("/guide.xml", xmltv(mapOf("zzz" to listOf("Nobody")), emptyList()).removeSuffix("</tv>\n") + "<oops")
        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(listOf("a", "b"), epgIdsOf(id))
        assertEquals(2, status.matchedChannels)
    }

    @Test fun fullySuccessfulRefreshClearsVanishedMappings() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(listOf("a", "b"), epgIdsOf(id))

        // Only "a" remains in the sole source; "b" vanished.
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertNull(status.message)
        assertEquals(listOf<String?>("a", null), epgIdsOf(id))
        assertEquals(1, status.matchedChannels)
    }

    @Test fun manualUrlWithoutSchemeImports() = runTest {
        serve("/epg.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        val id = playlist(emptyList(), manual = "${server.hostName}:${server.port}/epg.xml")
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) {}

        assertTrue(status.message, status.ok)
        assertEquals(listOf<String?>("a"), epgIdsOf(id))
    }

    @Test fun oneSourceFailingCommitsTheOthers() = runTest {
        serve("/good.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        serveCode("/bad.xml", 500)
        val id = playlist(listOf(url("/good.xml")), manual = url("/bad.xml"))
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals("(1 of 2 guide sources failed)", status.message)
        assertEquals(1, countAll())
        assertEquals(listOf<String?>("a"), epgIdsOf(id))
        assertEquals(1, status.matchedChannels)
        assertEquals(clock, status.updatedAt)
        assertEquals(0, pendingCount())
    }

    @Test fun programmesBeforeChannelsMatchByTvgId() = runTest {
        serve(
            "/guide.xml",
            xmltv(mapOf("a" to listOf("Alpha"), "b" to listOf("Beta")), listOf(prog("a", 0, 30), prog("b", 0, 30)), channelsFirst = false),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Whatever" to "A", "Beta" to null)

        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals(listOf("a", null), epgIdsOf(id))
        assertEquals(listOf("a@0"), stored("a", "b").map { it.title })
    }

    @Test fun unreachableServerIsReportedWithoutSecrets() = runTest {
        val dead = MockWebServer().also { it.start() }
        val deadUrl = dead.url("/guide.xml?token=abc123secret").toString()
        dead.shutdown()
        val id = playlist(listOf(deadUrl))
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals("Couldn't reach the TV guide server.", status.message)
    }

    // --- refresh generations: committed reads, killed runs, deleted playlist ---

    private fun countGeneration(generation: Long): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programmes WHERE generation = ?", arrayOf<Any>(generation)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun pendingCount(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM epg_pending").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test fun readsDuringRefreshSeeOnlyCommittedGeneration() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)

        val many = (0 until 1200).map { prog("a", it * 5L, 5, "new$it") }
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha"), "b" to listOf("Beta")), many))
        val windows = mutableListOf<List<String>>()
        val nowNexts = mutableListOf<List<String>>()
        val status = repo.refresh(id) {
            runBlocking {
                windows += db.programmeDao().inWindow(listOf("a", "b"), clock, clock.plusSeconds(7200)).first().map { it.title }
                nowNexts += db.programmeDao().nowAndNext("a", clock).map { it.title }
            }
        }

        assertTrue(status.ok)
        assertEquals(3, windows.size) // 500, 1000, 1200: every batch is flushed before the commit
        windows.forEach { assertEquals(listOf("a@0", "a@60", "b@0"), it) }
        nowNexts.forEach { assertEquals(listOf("a@0", "a@60"), it) }
        // After the commit only the new generation is left for the matched ids.
        assertEquals(listOf("new0", "new1"), db.programmeDao().nowAndNext("a", clock).map { it.title })
        assertEquals(0, pendingCount())
    }

    @Test fun killedRunIsHiddenAndCleanedOnNextRefresh() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)

        // A refresh killed mid-way: rows and its pending marker written, never committed.
        val orphan = Long.MAX_VALUE / 2
        db.programmeDao().insertPending(orphan)
        db.programmeDao().insertAll(
            listOf(ProgrammeEntity(epgChannelId = "a", start = clock, stop = clock.plusSeconds(60), title = "orphan",
                subTitle = null, description = null, category = null, generation = orphan)),
        )
        assertEquals(listOf("a@0", "a@60", "b@0"), stored("a", "b").map { it.title })
        assertEquals(listOf("a@0", "a@60"), db.programmeDao().nowAndNext("a", clock).map { it.title })

        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(0, countGeneration(orphan))
        assertEquals(0, pendingCount())
        assertEquals(listOf("a@0", "a@60", "b@0"), stored("a", "b").map { it.title })
    }

    @Test fun playlistDeletedDuringRefreshReturnsNormally() = runTest {
        val many = (0 until 1200).map { prog("a", it * 5L, 5, "new$it") }
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), many))
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) { runBlocking { db.playlistDao().delete(id) } }

        assertFalse(status.ok)
        assertEquals("This playlist was removed.", status.message)
        assertEquals(0, countAll())
        assertEquals(0, pendingCount())
        assertNull(repo.status(id).first())
    }

    @Test fun playlistDeletedBeforeAFailureReturnsNormally() = runTest {
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a")
        routes["/guide.xml"] = {
            runBlocking { db.playlistDao().delete(id) }
            MockResponse().setResponseCode(500)
        }

        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals("This playlist was removed.", status.message)
        assertNull(repo.status(id).first())
    }

    @Test fun inWindowRejectsMoreThan900Ids() {
        db.programmeDao().inWindow(List(900) { "id$it" }, Instant.EPOCH, clock)
        assertThrows(IllegalArgumentException::class.java) {
            db.programmeDao().inWindow(List(901) { "id$it" }, Instant.EPOCH, clock)
        }
    }

    // --- partial success ---

    @Test fun secondSourceFailingKeepsFirstSourcesProgrammes() = runTest {
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "old a"))))
        serve("/two.xml", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30, "old b"))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a", "Beta" to "b")
        assertTrue(repo.refresh(id) {}.ok)

        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "new a"), prog("a", 30, 30, "new a2"))))
        serveCode("/two.xml", 500)
        clock = clock.plusSeconds(3600)
        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals("(1 of 2 guide sources failed)", status.message)
        assertEquals(clock, status.updatedAt)
        assertEquals(2, status.matchedChannels)
        assertEquals(status, repo.status(id).first())
        assertEquals(listOf("new a", "new a2"), stored("a").map { it.title })
        // Source 2's channel keeps its previous guide and mapping.
        assertEquals(listOf("old b"), stored("b").map { it.title })
        assertEquals(listOf("a", "b"), epgIdsOf(id))
        assertEquals(3, countAll())
        assertEquals(0, pendingCount())
    }

    @Test fun firstSourceDroppingMidStreamIsUndoneSecondCommits() = runTest {
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "old a"))))
        serve("/two.xml", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30, "old b"))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a", "Beta" to "b")
        assertTrue(repo.refresh(id) {}.ok)

        // Source 1 sends 3000 programmes but the connection drops half-way (after > 500 rows were flushed).
        val many = (0 until 3000).map { prog("a", it * 5L, 5, "new$it") }
        val big = xmltv(mapOf("a" to listOf("Alpha")), many)
        routes["/one.xml"] = {
            MockResponse().setBody(big).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        serve("/two.xml", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30, "new b"))))
        var maxProgress = 0
        clock = clock.plusSeconds(3600)
        val status = repo.refresh(id) { maxProgress = maxOf(maxProgress, it) }

        assertTrue("source 1 must have flushed rows before failing", maxProgress > 500)
        assertTrue(status.ok)
        assertEquals("(1 of 2 guide sources failed)", status.message)
        assertEquals(2, status.matchedChannels)
        assertEquals(listOf("old a"), stored("a").map { it.title })
        assertEquals(listOf("new b"), stored("b").map { it.title })
        assertEquals(listOf("a", "b"), epgIdsOf(id))
        assertEquals(2, countAll())
        assertEquals(0, pendingCount())
    }

    @Test fun failedSourceReleasesItsChannelsForLaterSources() = runTest {
        // Source 1 claims Alpha by tvg-id, then turns out to be damaged; source 2 offers Alpha by name.
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))).removeSuffix("</tv>\n") + "<oops")
        serve("/two.xml", xmltv(mapOf("a2" to listOf("Alpha")), listOf(prog("a2", 0, 30))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a")

        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals("(1 of 2 guide sources failed)", status.message)
        assertEquals(listOf<String?>("a2"), epgIdsOf(id))
        assertEquals(listOf("a2@0"), stored("a", "a2").map { it.title })
        assertEquals(1, countAll())
    }

    @Test fun allSourcesFailingKeepsOldGuide() = runTest {
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "old a"))))
        serve("/two.xml", xmltv(mapOf("b" to listOf("Beta")), listOf(prog("b", 0, 30, "old b"))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a", "Beta" to "b")
        val first = repo.refresh(id) {}
        assertTrue(first.ok)

        serveCode("/one.xml", 500)
        serve("/two.xml", "<tv><programme <<<")
        clock = clock.plusSeconds(3600)
        val status = repo.refresh(id) {}

        assertFalse(status.ok)
        assertEquals(HTTP_500, status.message)
        assertEquals(first.updatedAt, status.updatedAt)
        assertEquals(2, status.matchedChannels)
        assertEquals(listOf("old a", "old b"), stored("a", "b").map { it.title })
        assertEquals(listOf("a", "b"), epgIdsOf(id))
        assertEquals(2, countAll())
        assertEquals(0, pendingCount())
    }

    // --- storage options and clearing ---

    /** One programme per offset (minutes from the clock, 30 minutes long), titled by the offset. */
    private suspend fun refreshWithEnded(o: EpgStoreOptions?, vararg startOffsetMin: Long): List<String> {
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), startOffsetMin.map { prog("a", it, 30, "p$it") }))
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a")
        val r = if (o == null) EpgRepository(db, http, now = { clock }) else repoWith(o)
        assertTrue(r.refresh(id) {}.ok)
        return stored("a").map { it.title }
    }

    @Test fun defaultOptionsAreZeroDaysWithDescriptions() {
        assertEquals(EpgStoreOptions(pastDays = 0, storeDescriptions = true), EpgStoreOptions())
    }

    @Test fun defaultPastDaysZeroPrunesEndedProgrammesWithThirtyMinuteGrace() = runTest {
        // ended 31 min before start: removed; ended 29 min before: kept; airing across the start: kept.
        val kept = refreshWithEnded(null, -61, -59, -10)
        assertEquals(listOf("p-59", "p-10"), kept)
    }

    @Test fun pastDaysOptionPrunes() = runTest {
        val h = 60L
        val kept = refreshWithEnded(EpgStoreOptions(pastDays = 1), -30 * h - 30, -20 * h - 30)
        assertEquals(listOf("p-1230"), kept)
    }

    @Test fun negativePastDaysBehavesAsZero() = runTest {
        val kept = refreshWithEnded(EpgStoreOptions(pastDays = -1), -61, -59, -10)
        assertEquals(listOf("p-59", "p-10"), kept)
    }

    @Test fun pastDaysSevenKeepsAWeek() = runTest {
        val kept = refreshWithEnded(EpgStoreOptions(pastDays = 7), -6 * 24 * 60L, -8 * 24 * 60L)
        assertEquals(listOf("p-8640"), kept)
    }

    @Test fun pruneKeepsCatchupDays() = runTest {
        val h = 60L
        val id = playlist(listOf(url("/guide.xml")))
        db.channelDao().insertAll(
            listOf(
                ChannelEntity(
                    playlistId = id, key = "c", number = 1, name = "Alpha",
                    streamUrl = "http://stream.example.test/c.ts", logo = null, groupTitle = "G", groupOrder = 0,
                    sortOrder = 0, tvgId = "a", tvgName = null, userAgent = null, referrer = null,
                    catchupType = "xc", catchupDays = 2, catchupSource = null, epgChannelId = null,
                ),
            ),
        )
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", -36 * h - 30, 30, "kept"), prog("a", -60 * h - 30, 30, "dropped"))))
        val options = epgStoreOptions(AppSettings(epgPastDays = AUTO_PAST_DAYS)) { db.channelDao().maxCatchupDays() }
        assertEquals(2, options.pastDays)
        assertTrue(repoWith(options).refresh(id) {}.ok)
        assertEquals(listOf("kept"), stored("a").map { it.title })
    }

    @Test fun descriptionsOffStoresNull() = runTest {
        refreshWithEnded(EpgStoreOptions(storeDescriptions = false), 0, 30)
        assertEquals(listOf<String?>(null, null), stored("a").map { it.description })
    }

    @Test fun descriptionsOnKeepsThem() = runTest {
        refreshWithEnded(EpgStoreOptions(storeDescriptions = true), 0)
        assertEquals(listOf<String?>("About p0"), stored("a").map { it.description })
    }

    @Test fun clearGuideRemovesProgrammesAndStatusKeepsMappings() = runTest {
        val id = standardSetup()
        assertTrue(repo.refresh(id) {}.ok)
        db.programmeDao().insertPending(Long.MAX_VALUE / 3)

        repo.clearGuide()

        assertEquals(0, countAll())
        assertEquals(0, pendingCount())
        assertNull(repo.status(id).first())
        assertEquals(listOf("a", "b"), epgIdsOf(id))
    }

    @Test fun clearGuideWaitsForRunningRefresh() = runTest {
        val id = standardSetup()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val body = xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30)))
        routes["/guide.xml"] = {
            started.countDown()
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody(body)
        }
        val refresh = async(Dispatchers.Default) { repo.refresh(id) {} }
        withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
        val clear = async(Dispatchers.Default) { repo.clearGuide() }
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertFalse("clearGuide must wait for the refresh", clear.isCompleted)

        gate.countDown()
        assertTrue(refresh.await().ok)
        clear.await()

        assertEquals(0, countAll())
        assertNull(repo.status(id).first())
    }

    // --- several sources, guide-channel list, manual mapping, time shift ---

    private fun requestedPaths(): List<String> = requests.map { it.requestUrl!!.encodedPath }

    private suspend fun guideChannels(playlistId: Long): Set<Pair<String, String>> =
        db.epgChannelDao().search(playlistId, "%", 1_000).map { it.epgId to it.displayName }.toSet()

    private suspend fun guideIdOf(playlistId: Long, key: String) =
        db.channelDao().channelsOf(playlistId).single { it.key == key }.guideEpgId

    @Test fun severalSourcesInOrderFirstWins() = runTest {
        serve("/one.xml", xmltv(mapOf("a.uk" to listOf("Alpha")), listOf(prog("a.uk", 0, 30, "from one"))))
        serve("/two.xml", xmltv(mapOf("a.uk" to listOf("Alpha")), listOf(prog("a.uk", 0, 30, "from two"))))
        val id = playlist(emptyList())
        repo.addSource(id, url("/one.xml"))
        repo.addSource(id, url("/two.xml"))
        channels(id, "Alpha" to "a.uk")

        assertTrue(repo.refresh(id) {}.ok)

        assertEquals(listOf("/one.xml", "/two.xml"), requestedPaths())
        assertEquals(listOf("from one"), stored("a.uk").map { it.title })
        assertEquals(listOf<String?>("a.uk"), epgIdsOf(id))
    }

    @Test fun sourceEpgCanBeTurnedOff() = runTest {
        serve("/announced.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "announced"))))
        serve("/added.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30, "added"))))
        val id = playlist(listOf(url("/announced.xml")), manual = url("/added.xml"))
        channels(id, "Alpha" to "a")

        repo.setUseSourceEpg(id, false)
        assertTrue(repo.refresh(id) {}.ok)

        assertEquals(listOf("/added.xml"), requestedPaths())
        assertEquals(listOf("added"), stored("a").map { it.title })

        // With no added link left, nothing is downloaded at all.
        repo.removeSource(repo.sources(id).first().single().id)
        assertEquals(NO_SOURCE, repo.refresh(id) {}.message)
        assertEquals(1, requests.size)
    }

    @Test fun addAndRemoveSources() = runTest {
        val id = playlist(emptyList())
        repo.addSource(id, "  http://example.test/one.xml ")
        repo.addSource(id, "   ")
        repo.addSource(id, "http://example.test/two.xml")

        val added = repo.sources(id).first()
        assertEquals(listOf("http://example.test/one.xml", "http://example.test/two.xml"), added.map { it.url })
        assertEquals(listOf(0, 1), added.map { it.position })

        repo.removeSource(added.first().id)
        assertEquals(listOf("http://example.test/two.xml"), repo.sources(id).first().map { it.url })
    }

    @Test fun epgSourceUrlsCombineAnnouncedAndAdded() {
        val p = PlaylistEntity(
            name = "P", type = PlaylistType.M3U, url = null, xtreamServer = null, xtreamUser = null, xtreamPass = null,
            epgUrlsFromSource = " http://example.test/a.xml \n\nhttp://example.test/b.xml", manualEpgUrl = "http://example.test/old.xml",
            lastRefreshedAt = null, lastError = null,
        )
        val never: (io.github.loopholetv.data.xtream.XtreamCredentials) -> String = { error("not Xtream") }
        val added = listOf("http://example.test/b.xml", " http://example.test/c.xml", " ")

        assertEquals(
            listOf("http://example.test/a.xml", "http://example.test/b.xml", "http://example.test/c.xml"),
            epgSourceUrls(p, never, added),
        )
        assertEquals(
            listOf("http://example.test/b.xml", "http://example.test/c.xml"),
            epgSourceUrls(p.copy(useSourceEpg = false), never, added),
        )
        val stalker = p.copy(type = PlaylistType.STALKER)
        assertEquals(listOf(PORTAL_EPG_SOURCE), announcedEpgUrls(stalker, never))
        assertEquals(listOf(PORTAL_EPG_SOURCE, "http://example.test/b.xml"), epgSourceUrls(stalker, never, added.take(1)))
    }

    @Test fun guideChannelListStored() = runTest {
        serve(
            "/one.xml",
            xmltv(
                mapOf("a.uk" to listOf("Alpha", "Alpha HD"), "b.uk" to listOf("Beta"), "c.uk" to emptyList()),
                listOf(prog("a.uk", 0, 30)),
            ),
        )
        serve("/two.xml", xmltv(mapOf("a.uk" to listOf("Other Alpha"), "d.uk" to listOf("Delta")), emptyList()))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a.uk")

        assertTrue(repo.refresh(id) {}.ok)

        // Every declared guide channel, matched or not; the first source and first name win; no name → id.
        assertEquals(
            setOf("a.uk" to "Alpha", "b.uk" to "Beta", "c.uk" to "c.uk", "d.uk" to "Delta"),
            guideChannels(id),
        )
    }

    @Test fun guideChannelListKeptOnPartialFailure() = runTest {
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha"), "b" to listOf("Beta")), emptyList()))
        serve("/two.xml", xmltv(mapOf("c" to listOf("Gamma")), emptyList()))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to "a")
        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(setOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma"), guideChannels(id))

        // Source one fails: its channels stay listed; source two's new channel is added.
        serveCode("/one.xml", 500)
        serve("/two.xml", xmltv(mapOf("c" to listOf("Gamma 2"), "d" to listOf("Delta")), emptyList()))
        val partial = repo.refresh(id) {}
        assertEquals("(1 of 2 guide sources failed)", partial.message)
        assertEquals(setOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma 2", "d" to "Delta"), guideChannels(id))

        // Every source works again: the list is replaced.
        serve("/one.xml", xmltv(mapOf("a" to listOf("Alpha")), emptyList()))
        serve("/two.xml", xmltv(emptyMap(), emptyList()))
        assertNull(repo.refresh(id) {}.message)
        assertEquals(setOf("a" to "Alpha"), guideChannels(id))

        // Every source fails: nothing changes.
        serveCode("/one.xml", 500)
        serveCode("/two.xml", 500)
        assertFalse(repo.refresh(id) {}.ok)
        assertEquals(setOf("a" to "Alpha"), guideChannels(id))
    }

    @Test fun manualMappingSurvivesFullRefresh() = runTest {
        serve(
            "/guide.xml",
            xmltv(
                mapOf("a.uk" to listOf("Alpha"), "other.id" to listOf("Something Else")),
                listOf(prog("a.uk", 0, 30, "alpha show"), prog("other.id", 0, 30, "other 1")),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a.uk")
        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgOverride = "other.id"))

        assertTrue(repo.refresh(id) {}.ok)
        assertEquals("other.id", guideIdOf(id, "k0"))
        assertEquals(listOf("other 1"), stored("other.id").map { it.title })
        // The matcher left the channel alone, so nobody owns a.uk.
        assertEquals(emptyList<ProgrammeEntity>(), stored("a.uk"))

        // A second fully successful refresh swaps the override's programmes and keeps the mapping.
        serve(
            "/guide.xml",
            xmltv(mapOf("other.id" to listOf("Something Else")), listOf(prog("other.id", 30, 30, "other 2"))),
        )
        clock = clock.plusSeconds(60)
        val status = repo.refresh(id) {}
        assertTrue(status.ok)
        assertNull(status.message)
        assertEquals("other.id", guideIdOf(id, "k0"))
        assertEquals(listOf("other 2"), stored("other.id").map { it.title })
    }

    @Test fun overrideAndAutomaticMatchShareProgrammes() = runTest {
        // The override's id appears only in the second source, which therefore owns it.
        serve("/one.xml", xmltv(mapOf("x" to listOf("Xray")), listOf(prog("x", 0, 30))))
        serve("/two.xml", xmltv(mapOf("b.uk" to listOf("Beta")), listOf(prog("b.uk", 0, 30), prog("b.uk", 30, 30))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to null, "Beta" to "b.uk")
        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgOverride = "b.uk"))

        assertTrue(repo.refresh(id) {}.ok)

        assertEquals("b.uk", guideIdOf(id, "k0"))
        assertEquals("b.uk", guideIdOf(id, "k1"))
        assertEquals("b.uk", db.channelDao().channelsOf(id).single { it.key == "k1" }.epgChannelId)
        assertEquals(listOf("b.uk@0", "b.uk@30"), stored("b.uk").map { it.title }) // stored once
    }

    @Test fun downloadsSendUserAgent() = runTest {
        val id = standardSetup()
        val withUa = EpgRepository(db, http, now = { clock }, userAgentFor = { "Loophole-Test/${it.id}" })
        assertTrue(withUa.refresh(id) {}.ok)
        assertEquals("Loophole-Test/$id", requests.last().getHeader("User-Agent"))

        assertTrue(repo.refresh(id) {}.ok) // no user agent: OkHttp's own
        assertTrue(requests.last().getHeader("User-Agent")!!.startsWith("okhttp/"))
    }

    @Test fun portalSourceDispatchesToPortalReader() = runTest {
        val id = playlist(emptyList(), type = PlaylistType.STALKER)
        channels(id, "Portal One" to "p.1")
        var reads = 0
        val portal = EpgRepository(
            db, http, now = { clock },
            portalEpg = { p, emit ->
                assertEquals(id, p.id)
                reads++
                emit(io.github.loopholetv.data.xmltv.XmltvChannel("p.1", listOf("Portal One"), null))
                emit(io.github.loopholetv.data.xmltv.XmltvProgramme("p.1", clock, clock.plusSeconds(1800), "Show", null, null, null))
            },
        )

        val status = portal.refresh(id) {}

        assertTrue(status.ok)
        assertNull(status.message)
        assertEquals(1, reads)
        assertEquals(listOf("Show"), stored("p.1").map { it.title })
        assertEquals(setOf("p.1" to "Portal One"), guideChannels(id))
        assertEquals(0, requests.size)

        // Without a portal reader (or when it fails) the status is a fixed text.
        val failed = repo.refresh(id) {}
        assertFalse(failed.ok)
        assertEquals("Couldn't reach the TV guide server.", failed.message)
        assertEquals(listOf("Show"), stored("p.1").map { it.title })
    }

    @Test fun stalkerSecretsCoverPortalAddresses() = runTest {
        val id = playlist(emptyList(), type = PlaylistType.STALKER)
        db.playlistDao().setPortalSession(id, "http://portal.example.test/stalker_portal/server/load.php", "tok123")
        channels(id, "Portal One" to "p.1")
        val p = db.playlistDao().byId(id)!!
        val secrets = playlistSecrets(p)
        for (s in listOf("http://portal.example.test/c/", "portal.example.test", p.portalEndpoint!!, "tok123")) {
            assertTrue("missing $s", s in secrets)
        }
    }

    @Test fun positiveShiftKeepsProgrammesFromPruning() = runTest {
        // Ended 90 minutes before the refresh started (pastDays 0 keeps 30 minutes).
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", -120, 30, "ended"), prog("a", 0, 30, "now"))))
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a")
        val r = repoWith(EpgStoreOptions(pastDays = 0))

        assertTrue(r.refresh(id) {}.ok)
        assertEquals(listOf("now"), stored("a").map { it.title })

        // Shown 2 hours later, it has only just ended on screen: kept.
        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgShiftMinutes = 120))
        assertTrue(r.refresh(id) {}.ok)
        assertEquals(listOf("ended", "now"), stored("a").map { it.title })
    }

    // --- manual overrides and reserved ids across sources ---

    @Test fun overrideKeepsLastAutomaticIdAfterFullRefresh() = runTest {
        serve(
            "/guide.xml",
            xmltv(
                mapOf("a.uk" to listOf("Alpha"), "other.id" to listOf("Something Else")),
                listOf(prog("a.uk", 0, 30), prog("other.id", 0, 30)),
            ),
        )
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to "a.uk")
        assertTrue(repo.refresh(id) {}.ok)
        assertEquals(listOf<String?>("a.uk"), epgIdsOf(id))

        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgOverride = "other.id"))
        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertNull(status.message) // a full success: unmatched channels would be cleared
        assertEquals(listOf<String?>("a.uk"), epgIdsOf(id)) // kept for "Automatic"
        assertEquals("other.id", guideIdOf(id, "k0"))
    }

    @Test fun reservedIdOwnedByFailedSourceGoesToNextSource() = runTest {
        // Source one lists the chosen id with programmes, then breaks off; source two also has it.
        serve(
            "/one.xml",
            xmltv(mapOf("b.uk" to listOf("Beta")), listOf(prog("b.uk", 0, 30, "from one"))).removeSuffix("</tv>\n") + "<oops",
        )
        serve("/two.xml", xmltv(mapOf("b.uk" to listOf("Beta")), listOf(prog("b.uk", 0, 30, "from two"))))
        val id = playlist(listOf(url("/one.xml")), manual = url("/two.xml"))
        channels(id, "Alpha" to null)
        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgOverride = "b.uk"))

        val status = repo.refresh(id) {}

        assertTrue(status.ok)
        assertEquals("(1 of 2 guide sources failed)", status.message)
        assertEquals(listOf("from two"), stored("b.uk").map { it.title })
        assertEquals(1, countAll())
        assertEquals("b.uk", guideIdOf(id, "k0"))
        assertEquals(0, pendingCount())
    }

    @Test fun overrideAndAutomaticMatchInSameSourceStoredOnce() = runTest {
        serve("/guide.xml", xmltv(mapOf("b.uk" to listOf("Beta")), listOf(prog("b.uk", 0, 30), prog("b.uk", 30, 30))))
        val id = playlist(listOf(url("/guide.xml")))
        channels(id, "Alpha" to null, "Beta" to "b.uk")
        db.channelPrefsDao().upsert(io.github.loopholetv.data.db.ChannelPrefsEntity(id, "k0", epgOverride = "b.uk"))

        assertTrue(repo.refresh(id) {}.ok)

        assertEquals("b.uk", guideIdOf(id, "k0"))
        assertEquals("b.uk", guideIdOf(id, "k1"))
        assertEquals(listOf("b.uk@0", "b.uk@30"), stored("b.uk").map { it.title })
        assertEquals(2, countAll())
    }

    @Test fun typedPortalLinkNeverReachesPortalReader() = runTest {
        serve("/guide.xml", xmltv(mapOf("a" to listOf("Alpha")), listOf(prog("a", 0, 30))))
        val m3u = playlist(listOf(url("/guide.xml")))
        channels(m3u, "Alpha" to "a")
        repo.addSource(m3u, " $PORTAL_EPG_SOURCE ")
        var portalReads = 0
        val r = EpgRepository(db, http, now = { clock }, portalEpg = { _, _ -> portalReads++ })

        val status = r.refresh(m3u) {}
        assertTrue(status.ok)
        assertNull(status.message) // one source, and it worked

        // A portal playlist whose own guide is off doesn't read it through a typed link either.
        val portal = playlist(emptyList(), manual = PORTAL_EPG_SOURCE, type = PlaylistType.STALKER)
        repo.setUseSourceEpg(portal, false)
        assertEquals(NO_SOURCE, r.refresh(portal) {}.message)
        assertEquals(0, portalReads)

        val never: (io.github.loopholetv.data.xtream.XtreamCredentials) -> String = { error("not Xtream") }
        val stalker = db.playlistDao().byId(portal)!!
        assertEquals(emptyList<String>(), epgSourceUrls(stalker, never, listOf(PORTAL_EPG_SOURCE)))
        assertEquals(listOf(PORTAL_EPG_SOURCE), epgSourceUrls(stalker.copy(useSourceEpg = true), never, listOf(" $PORTAL_EPG_SOURCE")))
    }
}
