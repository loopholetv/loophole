package io.github.loopholetv.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SearchDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SearchDao
    private val now = Instant.ofEpochSecond(10_000)

    @Before fun setUp() {
        db = inMemoryDb()
        dao = db.searchDao()
    }

    @After fun tearDown() = db.close()

    private fun pattern(q: String) = searchPattern(q)!!

    private fun programme(epg: String, start: Long, stop: Long, title: String, generation: Long = 1) =
        ProgrammeEntity(
            epgChannelId = epg, start = now.plusSeconds(start), stop = now.plusSeconds(stop), title = title,
            subTitle = null, description = null, category = null, generation = generation,
        )

    @Test fun searchPatternEscapesWildcards() {
        assertEquals("%50\\%%", searchPattern("50%"))
        assertEquals("%a\\_b%", searchPattern("a_b"))
        assertEquals("%a\\\\b%", searchPattern("a\\b"))
        assertEquals("%news%", searchPattern("  news "))
        assertNull(searchPattern(" x "))
        assertNull(searchPattern(""))
        assertEquals(2, MIN_QUERY_LENGTH)
    }

    @Test fun searchMatchesLiterally() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(
            channel(p, "1", 1, "50% Sport"), channel(p, "2", 2, "500 Sport"), channel(p, "3", 3, "Tom's TV"),
            channel(p, "4", 4, "a_b"), channel(p, "5", 5, "axb"), channel(p, "6", 6, "back\\slash"),
        )
        assertEquals(listOf("50% Sport"), dao.channels(pattern("50%"), 10).map { it.name })
        assertEquals(listOf("Tom's TV"), dao.channels(pattern("Tom's"), 10).map { it.name })
        assertEquals(listOf("a_b"), dao.channels(pattern("a_b"), 10).map { it.name })
        assertEquals(listOf("back\\slash"), dao.channels(pattern("k\\s"), 10).map { it.name })
    }

    @Test fun channelSearchIgnoresAsciiCase() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "2", 2, "BBC Two"), channel(p, "1", 1, "BBC One"), channel(p, "3", 3, "ITV"))
        assertEquals(listOf("BBC One", "BBC Two"), dao.channels(pattern("bbc"), 10).map { it.name })
    }

    @Test fun programmeSearchOnlyNowAndUpcoming() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "1", 1, "One", epgChannelId = "e", logo = "http://logo.example.test/1.png"))
        db.programmeDao().insertAll(
            listOf(
                programme("e", 3600, 7200, "News later"),
                programme("e", -7200, -3600, "News ended"),
                programme("e", -3600, 0, "News just ended"), // stop == now: over
                programme("e", -60, 3600, "News now"),
                programme("e", -60, 3600, "Weather"),
                programme("other", 0, 60, "News nowhere"), // no channel carries it
            ),
        )
        val hits = dao.programmes(pattern("news"), now, 10)
        assertEquals(listOf("News now", "News later"), hits.map { it.programme.title })
        val first = hits.first()
        assertEquals("One", first.channelName)
        assertEquals(1, first.channelNumber)
        assertEquals("http://logo.example.test/1.png", first.channelLogo)
        assertEquals(db.channelDao().byNumber(1)!!.id, first.channelId)
    }

    @Test fun searchFindsNewAndOriginalName() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "1", 1, "BBC One", customName = "Beeb"), channel(p, "2", 2, "ITV"))
        assertEquals(listOf("1"), dao.channels(pattern("beeb"), 10).map { it.key })
        assertEquals(listOf("1"), dao.channels(pattern("bbc"), 10).map { it.key })
        assertEquals(listOf("2"), dao.channels(pattern("itv"), 10).map { it.key })
    }

    @Test fun channelSearchOrdersByGuideNumber() = runTest {
        val p1 = db.addPlaylist()
        val p2 = db.addPlaylist()
        db.insertChannels(channel(p1, "a", 1, "News A", guideNumber = 9), channel(p1, "b", 2, "News B", guideNumber = 2))
        db.insertChannels(channel(p2, "c", 1, "News C", guideNumber = 5))
        assertEquals(listOf("b", "c", "a"), dao.channels(pattern("news"), 10).map { it.key })
    }

    @Test fun programmeSearchUsesShiftForNow() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(
            channel(p, "s", 1, "Shifted", guideEpgId = "es", shift = 60, guideNumber = 7, customName = "Late TV")
                .copy(customLogo = "http://logo.example.test/custom.png"),
            channel(p, "u", 2, "Unshifted", epgChannelId = "eu"),
            channel(p, "n", 3, "Minus", epgChannelId = "en", shift = -60),
        )
        db.programmeDao().insertAll(
            listOf(
                programme("es", -5400, -1800, "Film shifted"), // ended 30 min ago; +60 → still on
                programme("eu", -5400, -1800, "Film unshifted"), // ended 30 min ago
                programme("en", -1800, 1800, "Film minus"), // stored as on now; -60 → ended 30 min ago
                programme("es", -50_000, -45_000, "Film long ago"), // ended long before the +60 window
            ),
        )
        val hit = dao.programmes(pattern("film"), now, 10).single()
        assertEquals("Film shifted", hit.programme.title)
        assertEquals(60, hit.shiftMinutes)
        assertEquals("Late TV", hit.channelName)
        assertEquals(7, hit.channelNumber)
        assertEquals("http://logo.example.test/custom.png", hit.channelLogo)
    }

    @Test fun programmeSearchJoinsOnGuideEpgId() = runTest {
        val p = db.addPlaylist()
        // Matched "auto" but manually mapped to "manual": only the manual id finds programmes.
        db.insertChannels(channel(p, "1", 1, "One", epgChannelId = "auto", guideEpgId = "manual"))
        db.programmeDao().insertAll(listOf(programme("auto", 0, 60, "Show auto"), programme("manual", 0, 60, "Show manual")))
        assertEquals(listOf("Show manual"), dao.programmes(pattern("show"), now, 10).map { it.programme.title })
    }

    @Test fun programmeSearchHidesPendingGeneration() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "1", 1, epgChannelId = "e"))
        db.programmeDao().insertAll(listOf(programme("e", 0, 60, "Film old", 1), programme("e", 0, 60, "Film new", 2)))
        db.programmeDao().insertPending(2)
        assertEquals(listOf("Film old"), dao.programmes(pattern("film"), now, 10).map { it.programme.title })
    }

    @Test fun programmeSharedByTwoChannelsReturnedOnce() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(channel(p, "hi", 5, "High", epgChannelId = "e"))
        db.insertChannels(channel(p, "lo", 3, "Low", epgChannelId = "e"))
        db.insertChannels(channel(p, "mid", 4, "Mid", epgChannelId = "e"))
        db.programmeDao().insertAll(listOf(programme("e", 0, 60, "Match")))
        val hit = dao.programmes(pattern("match"), now, 10).single()
        assertEquals("Low", hit.channelName)
        assertEquals(3, hit.channelNumber)
        assertEquals(db.channelDao().byNumber(3)!!.id, hit.channelId)
    }

    @Test fun searchRespectsLimit() = runTest {
        val p = db.addPlaylist()
        db.insertChannels(*(1..5).map { channel(p, "$it", it, "News $it", epgChannelId = "e") }.toTypedArray())
        db.programmeDao().insertAll((1..5).map { programme("e", it * 60L, it * 60L + 60, "News $it") })
        assertEquals(listOf("News 1", "News 2", "News 3"), dao.channels(pattern("news"), 3).map { it.name })
        assertEquals(listOf("News 1", "News 2"), dao.programmes(pattern("news"), now, 2).map { it.programme.title })
    }

    @Test fun programmeSearchRangesOnStopIndex() = runTest {
        val plan = withContext(Dispatchers.IO) {
            db.query("EXPLAIN QUERY PLAN $PROGRAMME_SEARCH_SQL", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
            }
        }
        println("PLAN programme search:\n" + plan.joinToString("\n"))
        val programmes = plan.first { it.contains(" p ") || it.endsWith(" p") || it.contains("programmes") }
        assertTrue(
            "programmes must be read via the stop index, plan: $plan",
            programmes.contains("INDEX index_programmes_stop_title_epgChannelId_generation (stop>?)"),
        )
    }

    @Test fun limitsMustBePositive() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.channels("%a%", 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.channels("%a%", -1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.programmes("%a%", now, 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.programmes("%a%", now, -1) } }
    }

    @Test fun guideDeleteAllClearsProgrammesPendingAndStatus() = runTest {
        val p = db.addPlaylist()
        db.programmeDao().insertAll(listOf(programme("e", 0, 60, "X")))
        db.programmeDao().insertPending(1)
        db.epgStatusDao().upsert(EpgStatusEntity(p, now, 1, true, null))
        db.programmeDao().deleteAll()
        db.programmeDao().deleteAllPending()
        db.epgStatusDao().deleteAll()
        assertNull(db.programmeDao().maxGeneration())
        assertEquals(emptyList<Long>(), db.programmeDao().pendingGenerations())
        assertNull(db.epgStatusDao().get(p))
    }
}
