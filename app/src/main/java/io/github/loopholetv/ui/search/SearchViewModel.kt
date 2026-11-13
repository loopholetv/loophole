package io.github.loopholetv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.ProgrammeHit
import io.github.loopholetv.data.db.searchPattern
import io.github.loopholetv.data.repo.shiftedBy
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.ui.guide.isAiring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.time.Instant

/** Quiet time after the last keystroke before a search runs. */
const val SEARCH_DEBOUNCE_MS = 300L

/** Most results of each kind (channels, films, shows, programmes) a search returns. */
const val SEARCH_LIMIT = 50

sealed interface SearchUi {
    /** Query too short to search. */
    data object Hint : SearchUi

    /** Nothing to show yet for the current query. */
    data object Searching : SearchUi

    /**
     * Matches for the current query. Channels come back first (a few ms); [programmesLoading] is
     * true while the slower programme search is still running.
     */
    data class Results(
        val channels: List<ChannelEntity>,
        val programmes: List<ProgrammeHit>,
        val programmesLoading: Boolean = false,
        val films: List<FilmCard> = emptyList(),
        val shows: List<ShowCard> = emptyList(),
    ) : SearchUi

    data class Empty(val query: String) : SearchUi
}

/** The programme as shown: stored times plus the channel's guide time shift. */
val ProgrammeHit.shown: ProgrammeEntity get() = programme.shiftedBy(shiftMinutes)

/** Airing at [now] in shown time (the shift applied). */
fun ProgrammeHit.isOnNow(now: Instant): Boolean = shown.isAiring(now)

/**
 * Debounced, latest-wins search over channel names and programme titles. A new query cancels the
 * previous one's database work; any failure shows as [SearchUi.Empty] rather than crashing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchChannels: suspend (pattern: String) -> List<ChannelEntity>,
    private val searchProgrammes: suspend (pattern: String, now: Instant) -> List<ProgrammeHit>,
    private val searchFilms: suspend (pattern: String) -> List<FilmCard>,
    private val searchShows: suspend (pattern: String) -> List<ShowCard>,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    // Eagerly: results must survive the screen being stopped (player on top) without re-running.
    val results: StateFlow<SearchUi> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { q ->
            val pattern = searchPattern(q)
            if (pattern == null) {
                emit(SearchUi.Hint)
                return@transformLatest
            }
            delay(SEARCH_DEBOUNCE_MS) // cancelled by the next keystroke (latest wins)
            emit(SearchUi.Searching)
            val outcome = try {
                val channels = withContext(io) { searchChannels(pattern) }
                emit(SearchUi.Results(channels, emptyList(), programmesLoading = true))
                // A failing film or show query must not discard the channel results.
                val films = try { withContext(io) { searchFilms(pattern) } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val shows = try { withContext(io) { searchShows(pattern) } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                emit(SearchUi.Results(channels, emptyList(), programmesLoading = true, films = films, shows = shows))
                val programmes = withContext(io) { searchProgrammes(pattern, clock()) }
                if (channels.isEmpty() && films.isEmpty() && shows.isEmpty() && programmes.isEmpty()) {
                    SearchUi.Empty(q)
                } else {
                    SearchUi.Results(channels, programmes, films = films, shows = shows)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SearchUi.Empty(q)
            }
            emit(outcome)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi.Hint)

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val dao = app.container.db.searchDao()
                val vod = app.container.vod
                return SearchViewModel(
                    searchChannels = { dao.channels(it, SEARCH_LIMIT) },
                    searchProgrammes = { p, now -> dao.programmes(p, now, SEARCH_LIMIT) },
                    searchFilms = { vod.searchFilms(it, SEARCH_LIMIT) },
                    searchShows = { vod.searchShows(it, SEARCH_LIMIT) },
                ) as T
            }
        }
    }
}
