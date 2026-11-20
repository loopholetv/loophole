package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ContinueDismissalEntity
import io.github.loopholetv.data.db.KeyRef
import io.github.loopholetv.data.db.MyListEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant

/** The most episode-progress rows read to find the most recent shows. */
private const val RECENT_PROGRESS_ROWS = 200

/**
 * One entry of the Continue watching row. [play] is what to open: the film, or a show's target
 * episode. [key] is the film key or the show key.
 */
data class ContinueCard(
    val title: String,
    val playlistId: Long,
    val key: String,
    val logo: String?,
    val caption: String?,
    val fraction: Float?,
    val play: VodRef,
    val startMs: Long,
)

/** My list and Continue watching for the Movies and Series screens. */
class VodLibraryRepository(
    private val db: AppDatabase,
    private val repo: VodRepository,
    private val clock: () -> Instant,
) {
    private val dao = db.vodLibraryDao()

    fun continueFilms(): Flow<List<ContinueCard>> = dao.continueFilms(NOT_STARTED_MS, CONTINUE_LIMIT).map { rows ->
        rows.map { row ->
            ContinueCard(
                title = row.card.name,
                playlistId = row.card.playlistId,
                key = row.card.key,
                logo = row.card.logo,
                caption = null,
                fraction = fractionOf(row.positionMs, row.durationMs),
                play = VodRef(VodKind.MOVIE, row.card.playlistId, row.card.key),
                startMs = row.positionMs.takeIf { it >= NOT_STARTED_MS } ?: 0L,
            )
        }
    }

    /**
     * Shows with something to continue, most recently watched first. Each show's episodes and
     * progress are read only while the row still has room, so at most the shown cards and the
     * dropped shows are loaded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueShows(): Flow<List<ContinueCard>> =
        combine(dao.recentEpisodeProgress(RECENT_PROGRESS_ROWS), dao.observeDismissals(TitleKind.SHOW)) { rows, dismissals ->
            val dismissedAt = dismissals.associate { KeyRef(it.playlistId, it.key) to it.dismissedAt }
            // Rows come most recent first, so the first row of a show is its latest watch.
            rows.distinctBy { KeyRef(it.playlistId, it.showKey) }
                .filter { dismissedAt[KeyRef(it.playlistId, it.showKey)]?.let { at -> at >= it.lastWatchedAt } != true }
        }.mapLatest { shows ->
            val cards = ArrayList<ContinueCard>()
            for (s in shows) {
                if (cards.size >= CONTINUE_LIMIT) break
                showCard(s.playlistId, s.showKey)?.let { cards += it }
            }
            cards
        }

    private suspend fun showCard(playlistId: Long, showKey: String): ContinueCard? {
        val show = db.vodBrowseDao().show(playlistId, showKey).first() ?: return null
        val progress = db.vodProgressDao().observeForShow(VodKind.EPISODE, playlistId, showKey).first()
            .associateBy { it.key }
        val views = repo.episodesOnce(playlistId, showKey)
            .map { EpisodeView(it.key, it.season, it.episode, it.name, progress[it.key]) }
        val target = continueRowTarget(views) ?: return null
        val p = progress[target.key]
        return ContinueCard(
            title = show.name,
            playlistId = playlistId,
            key = showKey,
            logo = show.logo,
            caption = if (target.season != null && target.episode != null) "S${target.season} E${target.episode}" else target.name,
            fraction = p?.let { fractionOf(it.positionMs, it.durationMs) },
            play = VodRef(VodKind.EPISODE, playlistId, target.key),
            startMs = resumeFrom(p) ?: 0L,
        )
    }

    fun myListFilms(): Flow<List<FilmCard>> = dao.myListFilms()

    fun myListShows(): Flow<List<ShowCard>> = dao.myListShows()

    fun inMyList(kind: TitleKind, playlistId: Long, key: String): Flow<Boolean> = dao.observeInList(kind, playlistId, key)

    fun myListKeys(kind: TitleKind): Flow<Set<KeyRef>> = dao.observeListKeys(kind).map { it.toSet() }

    suspend fun setInMyList(kind: TitleKind, playlistId: Long, key: String, inList: Boolean) {
        if (inList) dao.add(MyListEntity(kind, playlistId, key, clock())) else dao.remove(kind, playlistId, key)
    }

    suspend fun dismissContinue(kind: TitleKind, playlistId: Long, key: String) {
        dao.dismiss(ContinueDismissalEntity(kind, playlistId, key, clock()))
    }

    private fun fractionOf(positionMs: Long, durationMs: Long?): Float? =
        if (durationMs != null && durationMs > 0 && positionMs > 0) positionMs.toFloat() / durationMs else null
}
