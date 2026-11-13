package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.FILM_CARD
import io.github.loopholetv.data.db.FILM_VISIBLE
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.SHOW_CARD
import io.github.loopholetv.data.db.SHOW_VISIBLE
import io.github.loopholetv.data.db.ShowEntity
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
        plans.forEach { (k, v) -> println("PLAN $k\n$v") }
        assertTrue(plans.getValue("films(category)").contains("SEARCH TABLE vod_films AS f USING INDEX index_vod_films_category_playlistId_position"))
        // "All shows" sorts by name ignoring case, which the binary name index can't order: the rows
        // are found through the category index and only that filtered set is sorted.
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE vod_shows AS s USING INDEX index_vod_shows_category_name (category=?)"))
        assertTrue(plans.getValue("shows(null)").contains("USE TEMP B-TREE FOR ORDER BY"))
        assertFalse(plans.getValue("shows(null)").contains("SCAN TABLE vod_shows"))
        assertFalse(plans.getValue("searchShows").contains("SCAN TABLE vod_shows AS o"))
        db.close()
    }
}
