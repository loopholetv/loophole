package io.github.loopholetv.data.db

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Pages of [query] read with LIMIT and OFFSET, and nothing else. Room's own paging source also counts
 * every row of the query on each first load, which only placeholders use; the grid has none, and its
 * filter bar already counts the rows once. Invalidated when any of [tables] changes.
 */
internal class GridPagingSource<T : Any>(
    private val db: AppDatabase,
    tables: Array<String>,
    private val query: SupportSQLiteQuery,
    private val read: suspend (SupportSQLiteQuery) -> List<T>,
) : PagingSource<Int, T>() {
    // Like Room's own source: a change before the first page is read is in that page already.
    private val firstLoadDone = AtomicBoolean(false)

    init {
        // Watched off the caller's thread (the tracker sets up its triggers in the database).
        val watch = CoroutineScope(Dispatchers.IO).launch {
            db.invalidationTracker.createFlow(*tables, emitInitialState = false).collect {
                if (firstLoadDone.get()) invalidate()
            }
        }
        registerInvalidatedCallback { watch.cancel() }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val key = params.key ?: 0
        // A page before [key] ends where the next one starts.
        val offset = if (params is LoadParams.Prepend) max(0, key - params.loadSize) else key
        val limit = if (params is LoadParams.Prepend) min(key, params.loadSize) else params.loadSize
        val rows = read(paged(limit, offset))
        firstLoadDone.set(true)
        if (invalid) return LoadResult.Invalid()
        return LoadResult.Page(
            data = rows,
            prevKey = offset.takeIf { it > 0 },
            nextKey = (offset + rows.size).takeIf { rows.size == limit },
        )
    }

    /** [query] with its arguments, limited to [limit] rows from [offset]. */
    private fun paged(limit: Int, offset: Int) = object : SupportSQLiteQuery {
        override val sql = "${query.sql} LIMIT $limit OFFSET $offset"
        override val argCount get() = query.argCount
        override fun bindTo(statement: SupportSQLiteProgram) = query.bindTo(statement)
    }

    /**
     * Reloads around the card last seen, so the grid stays where the viewer was. Without placeholders the
     * anchor counts from the first loaded card, so that page's start (its prevKey) is added to it.
     */
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val start = state.pages.firstOrNull()?.prevKey ?: 0
        return max(0, start + anchor - state.config.initialLoadSize / 2)
    }
}
