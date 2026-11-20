package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.FILM_CARD
import io.github.loopholetv.data.db.FILM_VISIBLE
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.SHOW_CARD
import io.github.loopholetv.data.db.SHOW_VISIBLE
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The browse queries must reach their rows through indexes, not scan the vod tables per output row. */
@RunWith(AndroidJUnit4::class)
class VodQueryPlanTest {
    private fun plan(db: io.github.loopholetv.data.db.AppDatabase, sql: String): String =
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { c ->
            buildString { while (c.moveToNext()) appendLine(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }

    @Test fun plansUseIndexes() = runTest {
        val db = inMemoryDb()
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        db.vodWriteDao().upsertFilms(
            (0 until 3000).map {
                FilmEntity(if (it % 2 == 0) a else b, "f$it", "Film $it", "Cat ${it % 20}", null, "u", null, null, null, it, Instant.EPOCH)
            },
        )
        db.vodWriteDao().insertShows(
            (0 until 3000).map { ShowEntity(a, "s$it", "Show ${it % 1500}", "G", null, null, Instant.EPOCH, it) },
        )
        val sqls = mapOf(
            "films(category)" to "SELECT $FILM_CARD FROM vod_films f WHERE f.category = 'Cat 1' AND $FILM_VISIBLE ORDER BY f.playlistId, f.position",
            "filmCategories" to "SELECT f.category AS title, COUNT(*) AS count FROM vod_films f WHERE $FILM_VISIBLE GROUP BY f.category ORDER BY MIN(f.playlistId), MIN(f.position)",
            "shows(null)" to "SELECT $SHOW_CARD FROM vod_shows s WHERE s.category IS NULL AND $SHOW_VISIBLE ORDER BY s.name COLLATE NOCASE, s.playlistId, s.`key`",
            "searchShows" to "SELECT $SHOW_CARD FROM vod_shows s WHERE s.name LIKE '%1%' ESCAPE '\\' AND $SHOW_VISIBLE ORDER BY s.name LIMIT 10",
        )
        val plans = sqls.mapValues { plan(db, it.value) }
        assertTrue(plans.getValue("films(category)").contains("SEARCH TABLE vod_films AS f USING INDEX index_vod_films_category_playlistId_position"))
        // "All shows" sorts by name ignoring case, which the binary name index can't order: the rows
        // are found through the category index and only that filtered set is sorted.
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE vod_shows AS s USING INDEX index_vod_shows_category_name (category=?)"))
        assertTrue(plans.getValue("shows(null)").contains("USE TEMP B-TREE FOR ORDER BY"))
        assertFalse(plans.getValue("shows(null)").contains("SCAN TABLE vod_shows"))
        assertFalse(plans.getValue("searchShows").contains("SCAN TABLE vod_shows AS o"))
        db.close()
    }

    /** Continue watching films are found through the progress table, not by scanning every film. */
    @Test fun continueFilmsReachesProgressThroughItsIndex() = runTest {
        val db = inMemoryDb()
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(
            (0 until 3000).map { FilmEntity(a, "f$it", "Film $it", "Cat ${it % 20}", null, "u", null, null, null, it, Instant.EPOCH) },
        )
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, a, "f1", 60_000, 100_000, false, Instant.EPOCH))
        val sql = "SELECT $FILM_CARD, p.positionMs AS positionMs, p.durationMs AS durationMs " +
            "FROM vod_progress p JOIN vod_films f ON f.playlistId = p.playlistId AND f.`key` = p.`key` " +
            "WHERE p.kind = 'MOVIE' AND p.positionMs >= 30000 AND $FILM_VISIBLE " +
            "AND NOT EXISTS (SELECT 1 FROM vod_continue_dismissed d WHERE d.kind = 'FILM' " +
            "AND d.playlistId = p.playlistId AND d.`key` = p.`key` AND d.dismissedAt >= p.lastWatchedAt) " +
            "ORDER BY p.lastWatchedAt DESC LIMIT 20"
        val plan = plan(db, sql)
        assertFalse(plan.contains("SCAN TABLE vod_films") || plan.contains("SCAN vod_films"))
        assertTrue(plan.contains("SEARCH TABLE vod_progress AS p USING INDEX"))
        assertTrue(plan.contains("SEARCH TABLE vod_films AS f USING INDEX sqlite_autoindex_vod_films_1 (playlistId=? AND key=?)"))
        db.close()
    }
}
