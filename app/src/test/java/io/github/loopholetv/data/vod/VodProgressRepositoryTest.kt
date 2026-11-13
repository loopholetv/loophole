package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.rowCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class VodProgressRepositoryTest {
    private lateinit var db: AppDatabase
    private var now = Instant.ofEpochSecond(1_000_000)
    private lateinit var repo: VodProgressRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = VodProgressRepository(db.vodProgressDao()) { now }
    }

    @After fun tearDown() = db.close()

    private fun row(kind: VodKind, p: Long, key: String, at: Instant) =
        VodProgressEntity(kind, p, key, 60_000, null, false, at)

    @Test fun recordThenObserveEmitsRow() = runTest {
        val p = db.addPlaylist()
        val ref = VodRef(VodKind.MOVIE, p, "film")
        repo.record(ref, 120_000, 3_600_000)
        val got = repo.observe(ref).first()!!
        assertEquals(120_000L, got.positionMs)
        assertEquals(now, got.lastWatchedAt)
    }

    @Test fun recordUnderThresholdStoresNothing() = runTest {
        val p = db.addPlaylist()
        val ref = VodRef(VodKind.MOVIE, p, "film")
        repo.record(ref, 5_000, 3_600_000)
        assertNull(repo.observe(ref).first())
    }

    private fun episode(p: Long, key: String, show: String) =
        EpisodeEntity(p, key, show, "G", key, 1, 1, null, "u", null, null, null, 0, now)

    @Test fun observeEpisodesCoversWholeShowOnly() = runTest {
        val p = db.addPlaylist()
        val big = (1..1_200).map { "big$it" }
        db.vodWriteDao().upsertEpisodes(big.map { episode(p, it, "big") } + episode(p, "other1", "other"))
        big.forEach { repo.record(VodRef(VodKind.EPISODE, p, it), 60_000, null) }
        repo.record(VodRef(VodKind.EPISODE, p, "other1"), 60_000, null)
        val map = repo.observeEpisodes(p, "big").first()
        assertEquals(big.toSet(), map.keys)
    }

    @Test fun rekeyMovesRow() = runTest {
        val p = db.addPlaylist()
        val dao = db.vodProgressDao()
        dao.upsert(row(VodKind.MOVIE, p, "old", now))
        dao.rekey(VodKind.MOVIE, p, "old", "new")
        assertNull(dao.get(VodKind.MOVIE, p, "old"))
        assertEquals(60_000L, dao.get(VodKind.MOVIE, p, "new")!!.positionMs)
    }

    @Test fun deleteOrphansRemovesOnlyOldRowsWithoutItem() = runTest {
        val p = db.addPlaylist()
        val old = Instant.ofEpochSecond(100)
        val cutoff = Instant.ofEpochSecond(1_000)
        db.vodWriteDao().upsertFilms(listOf(FilmEntity(p, "film", "F", "C", null, "u", null, null, null, 0, old)))
        db.vodWriteDao().upsertEpisodes(
            listOf(EpisodeEntity(p, "ep", "show", "G", "E", 1, 1, null, "u", null, null, null, 0, old)),
        )
        val dao = db.vodProgressDao()
        dao.upsert(row(VodKind.MOVIE, p, "film", old))
        dao.upsert(row(VodKind.EPISODE, p, "ep", old))
        dao.upsert(row(VodKind.MOVIE, p, "gone-old", old))
        dao.upsert(row(VodKind.MOVIE, p, "gone-recent", Instant.ofEpochSecond(2_000)))
        dao.deleteOrphans(cutoff)
        assertEquals(3, db.rowCount("vod_progress"))
        assertNull(dao.get(VodKind.MOVIE, p, "gone-old"))
    }
}
