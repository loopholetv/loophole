package io.github.loopholetv.data.vod

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FILM_GRID_TABLES
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.GridPagingSource
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.filterQuery
import io.github.loopholetv.data.db.matchTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Collections

/** The Sort & filter grid pages: in order, without gaps, invalidated by writes, and with no COUNT of their own. */
@RunWith(AndroidJUnit4::class)
class GridPagingTest {
    private val sql: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var db: AppDatabase
    private lateinit var repo: VodRepository
    private var p = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .setQueryCallback({ q, _ -> sql += q }, Runnable::run)
            .build()
        repo = VodRepository(db) { Instant.EPOCH }
        p = db.addPlaylist()
        db.vodWriteDao().upsertFilms(
            (0 until 50).map { FilmEntity(p, "f%02d".format(it), "Film %02d".format(it), "C", null, "u", null, null, null, it, Instant.EPOCH) },
        )
    }

    @After fun tearDown() = db.close()

    private val byTitle = VodFilter(sort = VodSort.TITLE)

    private suspend fun <T : Any> PagingSource<Int, T>.page(params: PagingSource.LoadParams<Int>) =
        withContext(Dispatchers.IO) { load(params) } as PagingSource.LoadResult.Page<Int, T>

    @Test fun pagesInOrderWithoutACount() = runTest {
        val source = repo.filmGridSource(byTitle)
        sql.clear()

        val first = source.page(PagingSource.LoadParams.Refresh(null, 20, false))
        val second = source.page(PagingSource.LoadParams.Append(first.nextKey!!, 20, false))
        val last = source.page(PagingSource.LoadParams.Append(second.nextKey!!, 20, false))

        assertEquals((0 until 50).map { "f%02d".format(it) }, (first.data + second.data + last.data).map { it.key })
        assertNull(first.prevKey)
        assertNull(last.nextKey)
        val before = source.page(PagingSource.LoadParams.Prepend(30, 20, false))
        assertEquals((10 until 30).map { "f%02d".format(it) }, before.data.map { it.key })
        assertEquals(10, before.prevKey)
        assertTrue(sql.joinToString("\n"), sql.none { it.contains("COUNT(", ignoreCase = true) })
    }

    @Test fun aWriteInvalidatesTheGrid() = runTest {
        val source = repo.filmGridSource(byTitle)
        source.page(PagingSource.LoadParams.Refresh(null, 20, false))

        db.matchTitle(p, TitleKind.FILM, "f07", 1, "Heat")
        withContext(Dispatchers.IO) { db.invalidationTracker.refreshVersionsSync() }

        val deadline = System.currentTimeMillis() + 5_000
        while (!source.invalid && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(source.invalid)
    }

    private suspend fun addFilms(from: Int, until: Int) {
        db.vodWriteDao().upsertFilms(
            (from until until).map { FilmEntity(p, "f%03d".format(it), "Film %03d".format(it), "C", null, "u", null, null, null, it, Instant.EPOCH) },
        )
    }

    private val config = PagingConfig(pageSize = 20, initialLoadSize = 60, enablePlaceholders = false)

    /** The state the grid hands back: [pages] as loaded, the viewer at [anchor] counted from the first loaded card. */
    private fun <T : Any> state(pages: List<PagingSource.LoadResult.Page<Int, T>>, anchor: Int) =
        PagingState(pages, anchor, config, leadingPlaceholderCount = 0)

    @Test fun refreshKeyAtTheTopCentresOnTheViewer() = runTest {
        val source = repo.filmGridSource(byTitle)
        val first = source.page(PagingSource.LoadParams.Refresh(null, 60, false))
        assertEquals(10, source.getRefreshKey(state(listOf(first), anchor = 40)))
        assertEquals(0, source.getRefreshKey(state(listOf(first), anchor = 5)))
    }

    @Test fun refreshKeyKeepsItsPlaceAfterRefreshesFurtherDown() = runTest {
        addFilms(50, 300)
        // The viewer scrolled to the card at row 210; the grid was reloaded from row 200 before.
        val source = repo.filmGridSource(byTitle)
        val page = source.page(PagingSource.LoadParams.Refresh(200, 60, false))
        assertEquals(200, page.prevKey)
        val key = source.getRefreshKey(state(listOf(page), anchor = 10))
        assertEquals(180, key)

        // Details arrive: the grid is reloaded from that key, and again with the viewer still on row 210.
        val again = repo.filmGridSource(byTitle)
        val reloaded = again.page(PagingSource.LoadParams.Refresh(key, 60, false))
        val row210 = again.page(PagingSource.LoadParams.Refresh(null, 300, false)).data[210]
        assertEquals(row210, reloaded.data[210 - 180])
        assertEquals(180, again.getRefreshKey(state(listOf(reloaded), anchor = 210 - 180)))

        // A page read before the first one moves where the count starts from.
        val before = again.page(PagingSource.LoadParams.Prepend(180, 20, false))
        assertEquals(180, again.getRefreshKey(state(listOf(before, reloaded), anchor = 210 - 160)))
    }

    @Test fun pagesKeepTheQueryArguments() = runTest {
        val inner = filterQuery(TitleKind.FILM, byTitle).sql
        val source = GridPagingSource(
            db, FILM_GRID_TABLES, SimpleSQLiteQuery("SELECT * FROM ($inner) WHERE name >= ? ORDER BY name", arrayOf("Film 40")),
        ) { db.vodFilterDao().films(it) }

        val page = source.page(PagingSource.LoadParams.Refresh(null, 5, false))
        assertEquals((40 until 45).map { "f%02d".format(it) }, page.data.map { it.key })
    }
}
