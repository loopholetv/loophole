package io.github.loopholetv.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockDaoTest {
    private lateinit var db: AppDatabase
    private var p = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    private fun addFilm(key: String) {
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO vod_films (playlistId, `key`, name, category, streamUrl, position, firstSeenAt)
               VALUES ($p, '$key', 'Film', 'C', 'http://s.test/$key.mp4', 0, 5)""",
        )
    }

    @Test fun countsSplitFilmsFromChannels() = runTest {
        addFilm("film")
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "A", locked = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "B", locked = true))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "chan", locked = true))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "film", locked = true))
        db.lockDao().upsertShowPrefs(ShowPrefsEntity(p, "show", locked = true))

        assertEquals(LockCounts(2, 1, 1, 1), db.lockDao().counts().first())
    }

    @Test fun clearAllLocksKeepsOtherEdits() = runTest {
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "both", hidden = true, locked = true))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "only", locked = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "G1", customName = "Mine", locked = true))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "G2", locked = true))
        db.lockDao().upsertShowPrefs(ShowPrefsEntity(p, "show", locked = true))
        assertTrue(db.lockDao().hasAnyLock())

        db.lockDao().clearAllLocks()

        val kept = db.channelPrefsDao().get(p, "both")!!
        assertTrue(kept.hidden)
        assertFalse(kept.locked)
        assertNull(db.channelPrefsDao().get(p, "only"))
        assertEquals("Mine", db.groupPrefsDao().forPlaylist(p).single().customName)
        assertEquals(0, db.rowCount("show_prefs"))
        assertFalse(db.lockDao().hasAnyLock())
        assertEquals(LockCounts(0, 0, 0, 0), db.lockDao().counts().first())
    }

    @Test fun lockOnlyRowIsNotDefault() {
        assertFalse(ChannelPrefsEntity(1, "k", locked = true).isDefault())
        assertFalse(GroupPrefsEntity(1, "g", locked = true).isDefault())
        assertTrue(ChannelPrefsEntity(1, "k").isDefault())
    }

    @Test fun unlockedStateWorksWithoutASeededRow() = runTest {
        assertFalse(db.lockDao().unlocked().first())
        db.lockDao().setUnlocked(true)
        assertTrue(db.lockDao().unlocked().first())
        db.lockDao().setUnlocked(false)
        assertFalse(db.lockDao().unlocked().first())
        assertEquals(1, db.rowCount("lock_state"))
    }

    @Test fun showPrefsRoundTrip() = runTest {
        assertNull(db.lockDao().showPrefs(p, "s"))
        db.lockDao().upsertShowPrefs(ShowPrefsEntity(p, "s", locked = true))
        assertTrue(db.lockDao().showPrefs(p, "s")!!.locked)
        db.lockDao().deleteShowPrefs(p, "s")
        assertNull(db.lockDao().showPrefs(p, "s"))
    }

    @Test fun aChannelRekeyOntoAnExistingRowKeepsTheLock() = runTest {
        val dao = db.channelPrefsDao()
        dao.upsert(ChannelPrefsEntity(p, "old", locked = true))
        // The new key already has its own edits: that row wins, but the lock is not lost.
        dao.upsert(ChannelPrefsEntity(p, "new", customName = "Mine"))
        db.maintenanceDao().rekey(p, "old", "new")
        val kept = dao.get(p, "new")!!
        assertTrue(kept.locked)
        assertEquals("Mine", kept.customName)
        // No leftover on the old key is counted as a second locked channel.
        assertNull(dao.get(p, "old"))
        assertEquals(1, db.lockDao().counts().first().channels)

        // Without a conflict the row just moves.
        dao.upsert(ChannelPrefsEntity(p, "a", locked = true))
        db.maintenanceDao().rekey(p, "a", "b")
        assertTrue(dao.get(p, "b")!!.locked)
        assertNull(dao.get(p, "a"))
    }

    @Test fun aFilmRekeyOntoAnExistingRowKeepsTheLockAndLeavesNoLeftover() = runTest {
        addFilm("new")
        val dao = db.channelPrefsDao()
        dao.upsert(ChannelPrefsEntity(p, "old", locked = true))
        dao.upsert(ChannelPrefsEntity(p, "new", hidden = true))
        db.maintenanceDao().rekeyFilmPrefs(p, "old", "new")
        val kept = dao.get(p, "new")!!
        assertTrue(kept.locked)
        assertTrue(kept.hidden)
        assertNull(dao.get(p, "old"))
        assertEquals(LockCounts(0, 0, 1, 0), db.lockDao().counts().first())
    }
}
