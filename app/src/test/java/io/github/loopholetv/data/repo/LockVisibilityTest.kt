package io.github.loopholetv.data.repo

import app.cash.turbine.test
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.db.rowCount
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class LockVisibilityTest {
    private lateinit var db: AppDatabase
    private lateinit var overlay: ChannelOverlay
    private lateinit var edits: ChannelEdits
    private val playlists = mutableListOf<OverlayPlaylist>()

    @Before fun setUp() {
        db = inMemoryDb()
        overlay = ChannelOverlay(db)
        edits = ChannelEdits(db, overlay)
    }

    @After fun tearDown() {
        playlists.forEach { it.close() }
        db.close()
    }

    private suspend fun setUnlocked(unlocked: Boolean) {
        db.lockDao().setUnlocked(unlocked)
        overlay.applyLockState()
    }

    private suspend fun visibleKeys(playlistId: Long) =
        db.channelDao().channelsOf(playlistId).filter { it.visible }.map { it.key }.sorted()

    private suspend fun seed(): Long {
        val p = db.addPlaylist()
        db.insertChannels(
            channel(p, "a1", 1, groupTitle = "A"), channel(p, "a2", 2, groupTitle = "A"),
            channel(p, "b1", 3, groupTitle = "B"), channel(p, "b2", 4, groupTitle = "B"),
        )
        overlay.refreshAll()
        return p
    }

    @Test fun lockedGroupHiddenWhileLocked() = runTest {
        val p = seed()
        edits.setGroupLocked(p, "A", true)
        assertEquals(listOf("b1", "b2"), visibleKeys(p))

        setUnlocked(true)
        assertEquals(listOf("a1", "a2", "b1", "b2"), visibleKeys(p))

        setUnlocked(false)
        assertEquals(listOf("b1", "b2"), visibleKeys(p))
    }

    @Test fun missingLockStateRowCountsAsLocked() = runTest {
        val p = seed()
        edits.setGroupLocked(p, "A", true)
        assertEquals(0, db.rowCount("lock_state"))
        assertEquals(listOf("b1", "b2"), visibleKeys(p))
    }

    @Test fun lockedChannelInOpenGroup() = runTest {
        val p = seed()
        edits.setLocked(ChannelRef(p, "b2"), true)
        assertEquals(listOf("a1", "a2", "b1"), visibleKeys(p))
        setUnlocked(true)
        assertEquals(listOf("a1", "a2", "b1", "b2"), visibleKeys(p))
    }

    @Test fun hiddenAndLockedNeedsBoth() = runTest {
        val p = seed()
        edits.setHidden(ChannelRef(p, "a1"), true)
        edits.setLocked(ChannelRef(p, "a1"), true)
        setUnlocked(true)
        assertEquals(listOf("a2", "b1", "b2"), visibleKeys(p))
        edits.setHidden(ChannelRef(p, "a1"), false)
        assertEquals(listOf("a1", "a2", "b1", "b2"), visibleKeys(p))
    }

    @Test fun refreshKeepsLockedChannelsHidden() = runTest(timeout = 60.seconds) {
        val p = OverlayPlaylist(db).also { playlists += it }
        p.add(listOf(Entry("L1", "Locked"), Entry("L2", "Locked"), Entry("O1", "Open"), Entry("O2", "Open")))
        edits.setGroupLocked(p.id, "Locked", true)

        db.channelDao().groups().test {
            assertEquals(listOf("Open"), awaitItem().map { it.title })

            overlay.applyPlaylist(p.id)
            p.refresh(listOf(Entry("L1", "Locked"), Entry("L2", "Locked"), Entry("O1", "Open"), Entry("O2", "Open"), Entry("O3", "Open")))
            // A later visible change proves every earlier emission has been delivered.
            edits.setHidden(p.ref("O3"), true)

            val seen = mutableListOf<List<String>>()
            var last = awaitItem()
            seen += last.map { it.title }
            while (last.sumOf { it.count } != 2) {
                last = awaitItem()
                seen += last.map { it.title }
            }
            assertTrue(seen.toString(), seen.none { "Locked" in it })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("O1", "O2"), db.channelDao().channelsOf(p.id).filter { it.visible }.map { it.name }.sorted())
    }

    @Test fun filmCategoryAndFilmLocks() = runTest(timeout = 60.seconds) {
        val p = db.addPlaylist()
        db.vodWriteDao().upsertFilms(listOf(film(p, "f1", "Action"), film(p, "f2", "Action"), film(p, "f3", "Drama")))
        val dao = db.vodBrowseDao()

        edits.setGroupLocked(p, "Drama", true)
        edits.setLocked(ChannelRef(p, "f2"), true)

        dao.filmCategories().test {
            assertEquals(listOf("Action" to 1), awaitItem().map { it.title to it.count })
            assertEquals(listOf("f1"), dao.searchFilms("%f%", 10).map { it.key })

            setUnlocked(true)
            assertEquals(listOf("Action" to 2, "Drama" to 1), awaitItem().map { it.title to it.count })
            assertEquals(listOf("f1", "f2", "f3"), dao.searchFilms("%f%", 10).map { it.key })
            cancelAndIgnoreRemainingEvents()
        }
        setUnlocked(false)
        assertNull(dao.film(p, "f2").first())
        assertNull(dao.film(p, "f3").first())
    }

    @Test fun showAndShowGroupLocks() = runTest(timeout = 60.seconds) {
        val p = db.addPlaylist()
        db.vodWriteDao().insertShows(listOf(show(p, "s1", "Crime"), show(p, "s2", "Crime"), show(p, "s3", "Comedy", "Fun")))
        val dao = db.vodBrowseDao()

        edits.setShowLocked(p, "s2", true)
        edits.setGroupLocked(p, "Comedy", true)

        dao.showCount("Crime").test {
            assertEquals(1, awaitItem())
            assertEquals(listOf("s1"), dao.searchShows("%s%", 10).map { it.key })

            setUnlocked(true)
            assertEquals(2, awaitItem())
            assertEquals(listOf("s1", "s2", "s3"), dao.searchShows("%s%", 10).map { it.key })
            cancelAndIgnoreRemainingEvents()
        }

        setUnlocked(false)
        edits.setShowLocked(p, "s2", false)
        assertEquals(listOf("s1", "s2"), dao.searchShows("%s%", 10).map { it.key })
        assertEquals(0, db.rowCount("show_prefs"))
    }

    @Test fun rederiveLockedIsFast() = runTest(timeout = 120.seconds) {
        val p = db.addPlaylist()
        db.insertChannels(
            *(1..20_000).map { channel(p, "k$it", it, groupTitle = "G${it % 10}") }.toTypedArray(),
        )
        overlay.refreshAll()
        edits.setGroupLocked(p, "G0", true)
        assertEquals(18_000, visibleKeys(p).size)

        db.lockDao().setUnlocked(true)
        val start = System.nanoTime()
        overlay.applyLockState()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("applyLockState over 20000 channels (2000 locked): $ms ms")

        assertTrue("took $ms ms", ms < 1_000)
        assertEquals(20_000, visibleKeys(p).size)
    }

    @Test fun shownIgnoringLockTrueForLockedChannel() = runTest {
        val p = seed()
        val ids = db.channelDao().channelsOf(p).associate { it.key to it.id }
        edits.setLocked(ChannelRef(p, "a1"), true)
        edits.setHidden(ChannelRef(p, "a2"), true)
        val dao = db.channelDao()

        assertFalse(db.channelDao().byKey(p, "a1")!!.visible)
        assertEquals(true, dao.shownIgnoringLock(ids.getValue("a1")).first())
        assertEquals(false, dao.shownIgnoringLock(ids.getValue("a2")).first())
        assertEquals(true, dao.shownIgnoringLock(ids.getValue("b1")).first())
        assertNull(dao.shownIgnoringLock(-1).first())
    }

    @Test fun clearAllLocksKeepsEveryOtherEdit() = runTest {
        val p = seed()
        // A new prefs column must be covered by the default-row deletes in LockDao (and by a row below).
        assertEquals(
            setOf(
                "playlistId", "channelKey", "locked", "hidden", "customName", "customLogo", "epgOverride",
                "epgShiftMinutes", "position", "videoDecoder", "audioDecoder", "externalPlayer", "aspectMode",
            ),
            columnsOf("channel_prefs"),
        )
        assertEquals(
            setOf("playlistId", "groupTitle", "locked", "hidden", "customName", "position", "sortMode"),
            columnsOf("group_prefs"),
        )
        val channelRows = listOf(
            ChannelPrefsEntity(p, "c-hidden", hidden = true),
            ChannelPrefsEntity(p, "c-name", customName = "n"),
            ChannelPrefsEntity(p, "c-logo", customLogo = "l"),
            ChannelPrefsEntity(p, "c-epg", epgOverride = "e"),
            ChannelPrefsEntity(p, "c-shift", epgShiftMinutes = 30),
            ChannelPrefsEntity(p, "c-pos", position = 3),
            ChannelPrefsEntity(p, "c-video", videoDecoder = DecoderChoice.SOFTWARE),
            ChannelPrefsEntity(p, "c-audio", audioDecoder = DecoderChoice.SOFTWARE),
            ChannelPrefsEntity(p, "c-ext", externalPlayer = "ask"),
            ChannelPrefsEntity(p, "c-aspect", aspectMode = AspectMode.ZOOM),
        )
        val groupRows = listOf(
            GroupPrefsEntity(p, "g-hidden", hidden = true),
            GroupPrefsEntity(p, "g-name", customName = "n"),
            GroupPrefsEntity(p, "g-pos", position = 2),
            GroupPrefsEntity(p, "g-sort", sortMode = GroupSort.NAME),
        )
        channelRows.forEach { db.channelPrefsDao().upsert(it.copy(locked = true)) }
        groupRows.forEach { db.groupPrefsDao().upsert(it.copy(locked = true)) }
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "c-only-lock", locked = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "g-only-lock", locked = true))
        edits.setShowLocked(p, "s", true)

        edits.clearAllLocks()

        assertEquals(channelRows.size, db.channelPrefsDao().forPlaylist(p).size)
        channelRows.forEach { assertEquals(it, db.channelPrefsDao().get(p, it.channelKey)) }
        groupRows.forEach { assertEquals(it, db.groupPrefsDao().get(p, it.groupTitle)) }
        assertNull(db.channelPrefsDao().get(p, "c-only-lock"))
        assertNull(db.groupPrefsDao().get(p, "g-only-lock"))
        assertEquals(0, db.rowCount("show_prefs"))
        assertFalse(db.lockDao().hasAnyLock())
    }

    private suspend fun columnsOf(table: String): Set<String> = withContext(Dispatchers.IO) {
        db.query("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet()
        }
    }

    private fun film(p: Long, key: String, category: String) =
        FilmEntity(p, key, key, category, null, "http://s.test/$key.mp4", null, null, null, 0, Instant.EPOCH)

    private fun show(p: Long, key: String, group: String, category: String? = group) =
        ShowEntity(p, key, key, group, category, null, Instant.EPOCH, 0)
}
