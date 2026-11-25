package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.ProviderDetailsEntity
import io.github.loopholetv.data.repo.XtreamFilmDetails
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.vod.resumeFrom
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the film page shows. [resumeMs] is where a started film resumes (null: not started) and
 * [fraction] how far along it is, when the length is known. [details] are TMDB's, null when the
 * film is not matched; [title] and [logo] are then the playlist's own.
 */
data class FilmUi(
    val title: String,
    val category: String,
    val logo: String?,
    val resumeMs: Long?,
    val fraction: Float?,
    val inMyList: Boolean = false,
    val details: TitleDetailsUi? = null,
)

/** A button on the film page and where it starts playback, in ms. */
data class FilmButton(val label: String, val startMs: Long)

/** [Play] for a film that has not been started; otherwise [Resume from ...] and [Start over]. */
fun filmButtons(ui: FilmUi): List<FilmButton> =
    if (ui.resumeMs == null) listOf(FilmButton("Play", 0L))
    else listOf(FilmButton(resumeLabel(ui.resumeMs), ui.resumeMs), FilmButton("Start over", 0L))

/** "Resume from 42:10", or "Resume from 1:02:03" from an hour on. */
fun resumeLabel(positionMs: Long): String {
    val total = positionMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "Resume from " + if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The lock pill's label. */
fun filmLockLabel(locked: Boolean): String = if (locked) "Unlock film" else "Lock film"

/** The My list pill's label. */
fun myListLabel(inList: Boolean): String = if (inList) "✓ In My list" else "+ My list"

/**
 * One film, with where its playback would resume. [ui] is null while loading and when the film is
 * gone. An Xtream film's provider details are fetched through [filmDetails] once when the page opens,
 * if they are missing or stale and TMDB hasn't matched the film.
 */
class FilmViewModel(
    private val playlistId: Long,
    private val key: String,
    private val repo: VodRepository,
    progress: VodProgressRepository,
    private val library: VodLibraryRepository,
    filmDetails: XtreamFilmDetails? = null,
    /** Writes the film's own lock. */
    private val writeLocked: suspend (locked: Boolean) -> Unit = {},
) : ViewModel() {
    private val _ui = MutableStateFlow<FilmUi?>(null)
    private val _loaded = MutableStateFlow(false)
    val ui: StateFlow<FilmUi?> = _ui

    /** Whether [ui] has been read from the database yet: a null [ui] before that is only "loading". */
    val loaded: StateFlow<Boolean> = _loaded

    /** The page's provider-details fetch, skipped when TMDB matched the film; null when it isn't an Xtream one. */
    internal val providerFetch: Job? = filmDetails?.takeIf { XtreamFilmDetails.isXtreamFilm(key) }?.let { info ->
        viewModelScope.launch {
            if (repo.filmDetails(playlistId, key).first() == null) info.refreshIfStale(playlistId, key)
        }
    }

    init {
        viewModelScope.launch {
            combine(
                repo.film(playlistId, key),
                progress.observe(VodRef(VodKind.MOVIE, playlistId, key)),
                library.inMyList(TitleKind.FILM, playlistId, key),
                repo.filmDetails(playlistId, key),
                repo.providerFilmDetails(playlistId, key),
            ) { detail, p, inList, tmdb, provider ->
                detail?.let {
                    val resume = resumeFrom(p)
                    val duration = p?.durationMs
                    FilmUi(
                        title = tmdb?.title?.title ?: it.film.name,
                        category = it.film.category,
                        logo = tmdb.posterOr(it.film.logo),
                        resumeMs = resume,
                        fraction = if (resume != null && duration != null && duration > 0) {
                            (resume.toFloat() / duration).coerceIn(0f, 1f)
                        } else null,
                        inMyList = inList,
                        details = tmdb?.toUi() ?: providerDetailsOf(it.film, provider),
                    )
                }
            }.collect {
                _ui.value = it
                _loaded.value = true
            }
        }
    }

    /** Adds the film to My list, or removes it when it is there. */
    fun toggleMyList() {
        val inList = _ui.value?.inMyList ?: return
        viewModelScope.launch { library.setInMyList(TitleKind.FILM, playlistId, key, !inList) }
    }

    /**
     * Locks or unlocks the film. Both first run [ensurePin] (sets a PIN when there is none, or asks for
     * it; at once while unlocked); nothing changes when that is not completed.
     */
    fun setLocked(locked: Boolean, ensurePin: suspend () -> Boolean) {
        viewModelScope.launch { if (ensurePin()) writeLocked(locked) }
    }

    /** Drops the film's TMDB match as wrong; the page goes back to the playlist's details. */
    fun rejectDetails() {
        viewModelScope.launch { repo.rejectDetails(TitleKind.FILM, playlistId, key) }
    }
}

/** The provider's details, with the film list's year and rating until (or where) they say none. */
private fun providerDetailsOf(film: FilmEntity, d: ProviderDetailsEntity?): TitleDetailsUi? = providerDetailsUi(
    plot = d?.plot,
    cast = d?.cast,
    genre = d?.genre,
    rating = d?.rating ?: film.providerRating,
    year = d?.year ?: film.providerYear,
    runtimeMinutes = d?.runtimeMinutes,
    backdrop = d?.backdrop,
)

fun filmViewModelFactory(playlistId: Long, key: String): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
        FilmViewModel(
            playlistId, key, c.vod, c.vodProgress, c.vodLibrary, c.xtreamFilmDetails,
            writeLocked = { locked -> c.channelEdits.setLocked(ChannelRef(playlistId, key), locked) },
        )
    }
}
