package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.ShowSource
import io.github.loopholetv.data.xtream.XtreamCategory
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.data.xtream.XtreamFilm
import io.github.loopholetv.data.xtream.XtreamShow
import io.github.loopholetv.data.xtream.XtreamVodKind
import io.github.loopholetv.data.xtream.xtreamFilmKey
import io.github.loopholetv.data.xtream.xtreamShowKey
import io.github.loopholetv.data.xtream.xtreamVodUrl
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * [films] and [shows] listed (each counted once); [failed] when either list couldn't be read or
 * written. [bothFailed] is the films part's error when neither part completed, so an account with no
 * live channels can report it instead of "no channels". Its text is redacted by the caller.
 */
internal data class XtreamVodResult(val films: Int, val shows: Int, val failed: Boolean, val bothFailed: Exception? = null)

/**
 * Imports an Xtream account's films, then its shows, through [XtreamVodWriter]. Downloads run
 * outside any transaction. The two parts are independent: one failing doesn't stop the other.
 * A part that fails keeps what it already wrote and deletes nothing; a part that completes has
 * its vanished titles deleted afterwards, unless the account listed nothing at all and has no live
 * channels either (an empty answer is not trusted then).
 */
internal class XtreamVodImport(
    private val client: XtreamClient,
    private val db: AppDatabase,
    private val now: () -> Instant,
) {
    suspend fun run(playlistId: Long, creds: XtreamCredentials, userAgent: String?, liveChannels: Int): XtreamVodResult {
        val writer = XtreamVodWriter(db, playlistId, now())
        val filmsError = guarded(writer) { importFilms(writer, playlistId, creds, userAgent) }
        val showsError = guarded(writer) { importShows(writer, playlistId, creds, userAgent) }
        var filmsDone = filmsError == null
        var showsDone = showsError == null
        val films = writer.filmsSeen
        val shows = writer.showsSeen
        if (liveChannels > 0 || films + shows > 0) {
            if (filmsDone) filmsDone = guarded(writer) { writer.pruneFilms() } == null
            if (showsDone) showsDone = guarded(writer) { writer.pruneShows() } == null
        }
        val bothFailed = filmsError?.takeIf { showsError != null }
        return XtreamVodResult(films, shows, failed = !filmsDone || !showsDone, bothFailed = bothFailed)
    }

    /**
     * Runs [block] then flushes; the error when it threw, else null. What was buffered before a
     * failure is still written (it's newer than what is stored). Cancellation is rethrown.
     */
    private suspend fun guarded(writer: XtreamVodWriter, block: suspend () -> Unit): Exception? =
        try {
            block()
            writer.flush()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                writer.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The part already counts as failed.
            }
            e
        }

    private suspend fun importFilms(writer: XtreamVodWriter, playlistId: Long, creds: XtreamCredentials, userAgent: String?) {
        val categories = names(client.vodCategories(creds, userAgent))
        pipe<XtreamFilm, Unit>({ emit -> client.vodStreams(creds, userAgent, emit) }) { f ->
            writer.film(
                FilmEntity(
                    playlistId = playlistId,
                    key = xtreamFilmKey(f.streamId),
                    name = f.name,
                    category = f.categoryId?.let(categories::get) ?: UNCATEGORISED,
                    logo = f.icon,
                    streamUrl = xtreamVodUrl(creds, XtreamVodKind.MOVIE, f.streamId, f.container),
                    userAgent = null,
                    referrer = null,
                    tvgId = f.tmdbId?.let { "tmdb:$it" },
                    // A repeated key is skipped by the writer, so positions stay consecutive.
                    position = writer.filmsSeen + 1,
                    firstSeenAt = Instant.EPOCH, // set by the writer
                    container = f.container,
                    providerYear = f.year,
                    providerRating = f.rating,
                ),
            )
        }
    }

    private suspend fun importShows(writer: XtreamVodWriter, playlistId: Long, creds: XtreamCredentials, userAgent: String?) {
        val categories = names(client.seriesCategories(creds, userAgent))
        pipe<XtreamShow, Unit>({ emit -> client.series(creds, userAgent, emit) }) { s ->
            val category = s.categoryId?.let(categories::get)
            writer.show(
                ShowEntity(
                    playlistId = playlistId,
                    key = xtreamShowKey(s.seriesId),
                    name = s.name,
                    groupTitle = category ?: UNCATEGORISED,
                    // No category puts the show under "All shows" only.
                    category = category,
                    logo = s.cover,
                    firstSeenAt = Instant.EPOCH, // set by the writer
                    position = writer.showsSeen + 1,
                    source = ShowSource.XTREAM,
                    tvgId = s.tmdbId?.toString(),
                    lastModified = s.lastModified,
                    episodesFetchedAt = null, // set by the writer
                    plot = s.plot,
                    cast = s.cast,
                    genre = s.genre,
                    rating = s.rating,
                    year = s.year,
                    backdrop = s.backdrop,
                ),
            )
        }
    }

    /** Category id to name; a repeated id keeps its first name. */
    private fun names(categories: List<XtreamCategory>): Map<String, String> =
        HashMap<String, String>().apply { categories.forEach { putIfAbsent(it.id, it.name) } }
}
