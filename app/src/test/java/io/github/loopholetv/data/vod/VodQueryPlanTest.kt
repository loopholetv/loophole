package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.FILM_CARD
import io.github.loopholetv.data.db.FILM_MATCH_JOIN
import io.github.loopholetv.data.db.FILM_VISIBLE
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.SHOW_CARD
import io.github.loopholetv.data.db.SHOW_MATCH_JOIN
import io.github.loopholetv.data.db.SHOW_VISIBLE
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.matchTitle
import io.github.loopholetv.data.db.filterQuery
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
        for (i in 0 until 50) {
            db.matchTitle(a, TitleKind.FILM, "f${i * 2}", i.toLong(), "Title $i", "/p$i.jpg")
            db.matchTitle(a, TitleKind.SHOW, "s$i", i.toLong(), "Show title $i", "/s$i.jpg")
        }
        val sqls = mapOf(
            "films(category)" to "SELECT $FILM_CARD FROM vod_films f $FILM_MATCH_JOIN WHERE f.category = 'Cat 1' AND $FILM_VISIBLE ORDER BY f.playlistId, f.position",
            "filmCategories" to "SELECT f.category AS title, COUNT(*) AS count FROM vod_films f WHERE $FILM_VISIBLE GROUP BY f.category ORDER BY MIN(f.playlistId), MIN(f.position)",
            "shows(null)" to "SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN WHERE s.category IS NULL AND $SHOW_VISIBLE ORDER BY s.name COLLATE NOCASE, s.playlistId, s.`key`",
            "searchShows" to "SELECT $SHOW_CARD FROM vod_shows s $SHOW_MATCH_JOIN WHERE (s.name LIKE '%1%' ESCAPE '\\' OR t.title LIKE '%1%' ESCAPE '\\') AND $SHOW_VISIBLE ORDER BY name LIMIT 10",
            "searchFilms" to "SELECT $FILM_CARD FROM vod_films f $FILM_MATCH_JOIN WHERE (f.name LIKE '%1%' ESCAPE '\\' OR t.title LIKE '%1%' ESCAPE '\\') AND $FILM_VISIBLE ORDER BY name LIMIT 10",
        )
        val plans = sqls.mapValues { plan(db, it.value) }
        assertTrue(plans.getValue("films(category)").contains("SEARCH TABLE vod_films AS f USING INDEX index_vod_films_category_playlistId_position"))
        // "All shows" sorts by name ignoring case, which the binary name index can't order: the rows
        // are found through the category index and only that filtered set is sorted.
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE vod_shows AS s USING INDEX index_vod_shows_category_name (category=?)"))
        assertTrue(plans.getValue("shows(null)").contains("USE TEMP B-TREE FOR ORDER BY"))
        assertFalse(plans.getValue("shows(null)").contains("SCAN TABLE vod_shows"))
        assertFalse(plans.getValue("searchShows").contains("SCAN TABLE vod_shows AS o"))
        // Every card reaches its match and its TMDB title by primary key, never by a scan.
        for ((name, plan) in plans.filterKeys { it != "filmCategories" }) {
            assertTrue(name, plan.contains("SEARCH TABLE vod_matches AS m USING INDEX sqlite_autoindex_vod_matches_1"))
            assertTrue(name, plan.contains("SEARCH TABLE tmdb_titles AS t USING INDEX sqlite_autoindex_tmdb_titles_1"))
            assertFalse(name, plan.contains("SCAN TABLE vod_matches") || plan.contains("SCAN TABLE tmdb_titles"))
        }
        // The show-name check finds same-named shows by name, and same-titled ones through the title index.
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE vod_matches AS m2 USING INDEX sqlite_autoindex_vod_matches_1"))
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE tmdb_titles AS t2 USING COVERING INDEX sqlite_autoindex_tmdb_titles_1"))
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE tmdb_titles AS t2 USING INDEX index_tmdb_titles_tmdbKind_title (tmdbKind=? AND title=?)"))
        assertTrue(plans.getValue("shows(null)").contains("SEARCH TABLE vod_matches AS m2 USING INDEX index_vod_matches_tmdbKind_tmdbId (tmdbKind=? AND tmdbId=?)"))
        assertFalse(plans.getValue("shows(null)").contains("SCAN TABLE vod_shows AS o"))
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
        db.matchTitle(a, TitleKind.FILM, "f1", 1, "Title", "/p.jpg")
        val sql = "SELECT $FILM_CARD, p.positionMs AS positionMs, p.durationMs AS durationMs " +
            "FROM vod_progress p JOIN vod_films f ON f.playlistId = p.playlistId AND f.`key` = p.`key` $FILM_MATCH_JOIN " +
            "WHERE p.kind = 'MOVIE' AND p.positionMs >= 30000 AND $FILM_VISIBLE " +
            "AND NOT EXISTS (SELECT 1 FROM vod_continue_dismissed d WHERE d.kind = 'FILM' " +
            "AND d.playlistId = p.playlistId AND d.`key` = p.`key` AND d.dismissedAt >= p.lastWatchedAt) " +
            "ORDER BY p.lastWatchedAt DESC LIMIT 20"
        val plan = plan(db, sql)
        assertFalse(plan.contains("SCAN TABLE vod_films") || plan.contains("SCAN vod_films"))
        assertTrue(plan.contains("SEARCH TABLE vod_progress AS p USING INDEX"))
        assertTrue(plan.contains("SEARCH TABLE vod_films AS f USING INDEX sqlite_autoindex_vod_films_1 (playlistId=? AND key=?)"))
        assertTrue(plan.contains("SEARCH TABLE vod_matches AS m USING INDEX sqlite_autoindex_vod_matches_1"))
        assertTrue(plan.contains("SEARCH TABLE tmdb_titles AS t USING INDEX sqlite_autoindex_tmdb_titles_1"))
        db.close()
    }

    /**
     * The Sort & filter grid walks the library once; each title reaches its match, its TMDB title,
     * its genres, the other copies of the same title and their progress through indexes.
     */
    @Test fun filteredGridUsesIndexesPerRow() = runTest {
        val db = inMemoryDb()
        val a = db.addPlaylist("A")
        db.vodWriteDao().upsertFilms(
            (0 until 3000).map { FilmEntity(a, "f$it", "Film $it", "Cat ${it % 20}", null, "u", null, null, null, it, Instant.EPOCH) },
        )
        db.vodWriteDao().insertShows(
            (0 until 3000).map { ShowEntity(a, "s$it", "Show ${it % 1500}", "G", null, null, Instant.EPOCH, it) },
        )
        for (i in 0 until 50) {
            db.matchTitle(a, TitleKind.FILM, "f${i * 2}", i.toLong(), "Title $i", "/p$i.jpg")
            db.matchTitle(a, TitleKind.SHOW, "s$i", i.toLong(), "Show title $i", "/s$i.jpg")
        }
        val films = plan(db, filterQuery(TitleKind.FILM, VodFilter(genres = setOf(28), rating = RatingMin.SEVEN, sort = VodSort.RATING)).sql)
        val shows = plan(db, filterQuery(TitleKind.SHOW, VodFilter(decades = setOf(1990)), countOnly = true).sql)
        for ((name, plan) in mapOf("films" to films, "shows" to shows)) {
            assertFalse(name, plan.contains("SCAN TABLE vod_matches") || plan.contains("SCAN TABLE tmdb_titles"))
            assertFalse(name, plan.contains("SCAN TABLE vod_progress") || plan.contains("SCAN TABLE vod_episodes"))
            assertTrue(name, plan.contains("SEARCH TABLE vod_matches AS m USING INDEX sqlite_autoindex_vod_matches_1"))
            assertTrue(name, plan.contains("SEARCH TABLE tmdb_titles AS t USING INDEX sqlite_autoindex_tmdb_titles_1"))
            assertTrue(name, plan.contains("SEARCH TABLE vod_matches AS mo USING INDEX index_vod_matches_tmdbKind_tmdbId (tmdbKind=? AND tmdbId=?)"))
        }
        assertTrue(films.contains("SEARCH TABLE vod_films AS o USING INDEX sqlite_autoindex_vod_films_1 (playlistId=? AND key=?)"))
        assertTrue(films.contains("SEARCH TABLE tmdb_title_genres AS l USING COVERING INDEX"))
        assertTrue(shows.contains("SEARCH TABLE vod_episodes AS pe USING INDEX index_vod_episodes_playlistId_showKey"))
        db.close()
    }
}
