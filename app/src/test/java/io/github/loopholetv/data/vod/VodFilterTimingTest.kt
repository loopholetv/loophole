package io.github.loopholetv.data.vod

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.MatchState
import io.github.loopholetv.data.db.TmdbTitleEntity
import io.github.loopholetv.data.db.VodMatchEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.filterQuery
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.tmdb.TmdbKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Times the Sort & filter grid's queries on 10,000 films (two playlists, 8,000 with details, 1,000 of
 * them the same title in both). Printed to the test's output; the bound only catches a lost index.
 */
@RunWith(AndroidJUnit4::class)
class VodFilterTimingTest {
    private fun AppDatabase.ms(sql: String): Double {
        val c = openHelper.writableDatabase
        val runs = (1..7).map {
            val start = System.nanoTime()
            c.query(sql).use { cur -> while (cur.moveToNext()) Unit }
            (System.nanoTime() - start) / 1e6
        }
        return runs.sorted()[runs.size / 2]
    }

    @Test fun gridQueriesOn10kFilms() = runTest {
        val db = inMemoryDb()
        val a = db.addPlaylist("A")
        val b = db.addPlaylist("B")
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        db.vodWriteDao().upsertFilms(
            (0 until 10_000).map {
                FilmEntity(if (it < 5000) a else b, "f$it", "Film $it", "Cat ${it % 40}", null, "u", null, null, null, it, t0.plusSeconds(it.toLong()))
            },
        )
        val dao = db.tmdbDao()
        db.withTransaction {
            run {
                for (i in 0 until 8000) {
                    // Films 4000..4999 and 5000..5999 share TMDB ids: one card each.
                    val id = (if (i >= 5000) i - 1000 else i).toLong()
                    dao.upsertTitle(
                        TmdbTitleEntity(
                            TmdbKind.MOVIE, id, "Title $id", null, 1950 + (id % 70).toInt(), null, "/p.jpg", null,
                            (id % 100) / 10.0, 100, 80 + (id % 80).toInt(), null, null, "en-US", t0,
                        ),
                        listOf(18 + (id % 5).toInt(), 28),
                    )
                    dao.upsertMatch(
                        VodMatchEntity(
                            if (i < 5000) a else b, TitleKind.FILM, "f$i", "fp$i", MatchState.MATCHED, TmdbKind.MOVIE, id,
                            null, "Film $i", null, t0, t0,
                        ),
                    )
                }
            }
        }
        val cases = mapOf(
            "sort by rating" to VodFilter(sort = VodSort.RATING),
            "genre + 7+" to VodFilter(genres = setOf(18), rating = RatingMin.SEVEN, sort = VodSort.YEAR),
        )
        for ((name, f) in cases) {
            val page = filterQuery(TitleKind.FILM, f).sql
            val count = filterQuery(TitleKind.FILM, f, countOnly = true).sql
            val first = db.ms("$page LIMIT 72 OFFSET 0")
            val offset = db.ms("$page LIMIT 24 OFFSET 5000")
            val bar = db.ms(count)
            val pagingCount = db.ms("SELECT COUNT(*) FROM ($page)")
            println("TIMING $name: first page ${"%.1f".format(first)} ms, offset page ${"%.1f".format(offset)} ms, " +
                "bar count ${"%.1f".format(bar)} ms, paging count ${"%.1f".format(pagingCount)} ms")
            assertTrue("$name first page $first ms", first < 5_000)
        }
        db.close()
    }
}
