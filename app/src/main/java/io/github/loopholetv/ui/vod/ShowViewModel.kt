package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.repo.EpisodeFetch
import io.github.loopholetv.data.repo.XtreamEpisodes
import io.github.loopholetv.data.vod.ContinueTarget
import io.github.loopholetv.data.vod.EpisodeView
import io.github.loopholetv.data.vod.ShowDetail
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.TmdbDetail
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.vod.continueTarget
import io.github.loopholetv.data.vod.resumeFrom
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One episode in the list; [label] is "E3", or empty for an episode without a number. [tmdbTitle]
 * and [plot] are TMDB's, where the show is matched and its season lookup found this episode's number.
 */
data class EpisodeRowUi(
    val key: String,
    val label: String,
    val name: String,
    val fraction: Float?,
    val watched: Boolean,
    val resumeMs: Long?,
    val tmdbTitle: String? = null,
    val plot: String? = null,
    val season: Int? = null,
) {
    /** What the row reads: "S1 E3 · <TMDB title>" where TMDB has one, else the playlist's name. */
    val heading: String
        get() = if (tmdbTitle != null && season != null && label.isNotEmpty()) "S$season $label · $tmdbTitle" else name
}

/**
 * What the show page's episode list area holds: the list ([READY]: there are episodes, or the show
 * is not fetched on demand), or a line saying they are loading, failed to load (with Try again) or
 * that the show has none yet.
 */
enum class EpisodesState { READY, LOADING, FAILED, EMPTY }

/**
 * What the show page shows. [tabs] are the seasons, then "Other"; [episodes] are those of the
 * [selectedTab]. [continueKey] is null when the show has no episodes. [details] are TMDB's, else the
 * provider's, null when there are neither; [name] and [logo] are the playlist's own unless TMDB matched.
 */
data class ShowUi(
    val name: String,
    val logo: String?,
    val summary: String,
    val tabs: List<String>,
    val selectedTab: Int,
    val episodes: List<EpisodeRowUi>,
    val continueLabel: String?,
    val continueKey: String?,
    val continueStartMs: Long,
    val inMyList: Boolean = false,
    val details: TitleDetailsUi? = null,
    val episodesState: EpisodesState = EpisodesState.READY,
)

/**
 * One show with its seasons and the episode its primary button plays. Null while loading or when the
 * show is gone. An Xtream show's episodes are fetched through [episodes] when the page opens, if
 * they are missing or stale.
 */
class ShowViewModel(
    private val playlistId: Long,
    private val key: String,
    private val repo: VodRepository,
    progress: VodProgressRepository,
    private val library: VodLibraryRepository,
    private val episodes: XtreamEpisodes? = null,
) : ViewModel() {
    private val fetchesEpisodes = episodes != null && XtreamEpisodes.isXtreamShow(key)

    // The page's own episode fetch: whether one runs, and what the last one did.
    private val fetch = MutableStateFlow(FetchStatus(running = fetchesEpisodes, last = null))

    // Until a tab or an episode is picked the page sits on the season of the continue episode.
    private val pick = MutableStateFlow<TabPick?>(null)

    /** What [ui] was last built from, so [showEpisode] can change it before the page is drawn. */
    private var latest: ShowInputs? = null

    /** The page's latest episode fetch; null when the show isn't fetched on demand. */
    internal var episodeFetch: Job? = null
        private set

    private val _ui = MutableStateFlow<ShowUi?>(null)
    private val _loaded = MutableStateFlow(false)
    val ui: StateFlow<ShowUi?> = _ui

    /** Whether [ui] has been read from the database yet: a null [ui] before that is only "loading". */
    val loaded: StateFlow<Boolean> = _loaded

    init {
        viewModelScope.launch {
            combine(
                repo.show(playlistId, key),
                progress.observeEpisodes(playlistId, key),
                pick,
                library.inMyList(TitleKind.SHOW, playlistId, key),
                combine(repo.showDetails(playlistId, key), fetch, ::Pair),
            ) { detail, progress, picked, inMyList, (tmdb, fetched) ->
                ShowInputs(detail, progress, inMyList, tmdb, fetched.takeIf { fetchesEpisodes })
                    .also { latest = it }.toUi(picked)
            }.collect {
                _ui.value = it
                _loaded.value = true
            }
        }
        if (fetchesEpisodes) fetchEpisodes(force = false)
    }

    /** Try again: fetches the show's episodes now, however fresh they are. */
    fun retryEpisodes() {
        if (!fetchesEpisodes || fetch.value.running) return
        fetchEpisodes(force = true)
    }

    private fun fetchEpisodes(force: Boolean) {
        val fetcher = episodes ?: return
        fetch.value = fetch.value.copy(running = true)
        episodeFetch = viewModelScope.launch {
            val result = fetcher.refreshIfStale(playlistId, key, force)
            fetch.value = FetchStatus(running = false, last = result)
        }
    }

    fun selectTab(index: Int) {
        pick.value = TabPick.Tab(index)
    }

    /**
     * Shows the season holding episode [episodeKey], such as the one the player last played, which an
     * auto-play run may have taken past the season it started on. It applies at once when the show is
     * loaded, so a page composed right after this call is already on that season.
     */
    fun showEpisode(episodeKey: String) {
        val picked = TabPick.Episode(episodeKey)
        pick.value = picked
        latest?.let { _ui.value = it.toUi(picked) }
    }

    /** Adds the show to My list, or removes it when it is there. */
    fun toggleMyList() {
        val inList = _ui.value?.inMyList ?: return
        viewModelScope.launch { library.setInMyList(TitleKind.SHOW, playlistId, key, !inList) }
    }

    /** Drops the show's TMDB match as wrong; the page goes back to the playlist's details. */
    fun rejectDetails() {
        viewModelScope.launch { repo.rejectDetails(TitleKind.SHOW, playlistId, key) }
    }
}

/** The page's episode fetch: [running], and what the last one did ([last], null before the first ends). */
private data class FetchStatus(val running: Boolean, val last: EpisodeFetch?)

/** The season tab the user chose, or the season of an episode to show. */
private sealed interface TabPick {
    data class Tab(val index: Int) : TabPick
    data class Episode(val key: String) : TabPick
}

private class ShowInputs(
    val detail: ShowDetail?,
    val progress: Map<String, VodProgressEntity>,
    val inMyList: Boolean,
    val tmdb: TmdbDetail?,
    val fetch: FetchStatus?,
) {
    fun toUi(picked: TabPick?): ShowUi? = showUi(detail, progress, picked, inMyList, tmdb, fetch)
}

/**
 * The list area's state for a show fetched on demand ([fetch] non-null). "No episodes yet" needs the
 * stored fetch time, which is written with the episodes, so a fetch that has just ended but whose
 * episodes the page hasn't read yet still shows as loading. A show already fetched empty says so
 * while the page's first check runs; Try again shows as loading.
 */
private fun episodesState(detail: ShowDetail, fetch: FetchStatus?): EpisodesState = when {
    fetch == null || detail.seasons.any { it.episodes.isNotEmpty() } -> EpisodesState.READY
    fetch.running && (detail.show.episodesFetchedAt == null || fetch.last != null) -> EpisodesState.LOADING
    fetch.last == EpisodeFetch.FAILED -> EpisodesState.FAILED
    // NOT_NEEDED with nothing stored: nothing more will come, so don't spin.
    detail.show.episodesFetchedAt != null || fetch.last == EpisodeFetch.NOT_NEEDED -> EpisodesState.EMPTY
    else -> EpisodesState.LOADING
}

private fun showUi(
    detail: ShowDetail?,
    progress: Map<String, VodProgressEntity>,
    picked: TabPick?,
    inMyList: Boolean,
    tmdb: TmdbDetail?,
    fetch: FetchStatus?,
): ShowUi? {
    detail ?: return null
    val tmdbEpisodes = tmdb?.episodes.orEmpty().associateBy { it.season to it.episode }
    val seasons = detail.seasons
    val views = seasons.flatMap { s ->
        s.episodes.map { EpisodeView(it.key, it.season, it.episode, it.name, progress[it.key]) }
    }
    fun seasonOf(key: String) = seasons.indexOfFirst { s -> s.episodes.any { it.key == key } }
    val target = continueTarget(views)
    val targetTab = target?.let { seasonOf(it.key) } ?: -1
    val pickedTab = when (picked) {
        is TabPick.Tab -> picked.index
        // An episode no longer listed leaves the page on the continue season.
        is TabPick.Episode -> seasonOf(picked.key).takeIf { it >= 0 } ?: targetTab
        null -> targetTab
    }
    val selected = pickedTab.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val rows = seasons.getOrNull(selected)?.episodes.orEmpty().map { e ->
        val p = progress[e.key]
        val te = if (e.season != null && e.episode != null) tmdbEpisodes[e.season to e.episode] else null
        EpisodeRowUi(
            key = e.key,
            label = if (e.season != null && e.episode != null) "E${e.episode}" else "",
            name = e.name,
            fraction = p?.takeIf { it.positionMs > 0 && (it.durationMs ?: 0) > 0 }
                ?.let { (it.positionMs.toFloat() / it.durationMs!!).coerceIn(0f, 1f) },
            watched = p?.watched == true,
            resumeMs = resumeFrom(p),
            tmdbTitle = te?.title?.takeIf { it.isNotBlank() },
            plot = te?.overview?.takeIf { it.isNotBlank() },
            season = e.season,
        )
    }
    return ShowUi(
        name = tmdb?.title?.title ?: detail.show.name,
        logo = tmdb.posterOr(detail.show.logo),
        summary = summaryOf(seasons.count { it.number != null }, views.size),
        tabs = seasons.map { it.number?.let { n -> "Season $n" } ?: "Other" },
        selectedTab = selected,
        episodes = rows,
        continueLabel = target?.let(::continueLabel),
        continueKey = target?.key,
        continueStartMs = target?.let { resumeFrom(progress[it.key]) } ?: 0L,
        inMyList = inMyList,
        details = tmdb?.toUi() ?: detail.show.let {
            providerDetailsUi(it.plot, it.cast, it.genre, it.rating, it.year, null, it.backdrop)
        },
        episodesState = episodesState(detail, fetch),
    )
}

/** "3 seasons · 24 episodes"; the seasons part is left out when no episode has a season. */
private fun summaryOf(seasons: Int, episodes: Int): String {
    val eps = "$episodes ${if (episodes == 1) "episode" else "episodes"}"
    return if (seasons == 0) eps else "$seasons ${if (seasons == 1) "season" else "seasons"} · $eps"
}

private fun continueLabel(t: ContinueTarget): String {
    val verb = if (t.started) "Continue" else "Play"
    return if (t.season != null && t.episode != null) "$verb S${t.season} E${t.episode}" else "$verb ${t.name}"
}

fun showViewModelFactory(playlistId: Long, key: String): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
        ShowViewModel(playlistId, key, c.vod, c.vodProgress, c.vodLibrary, c.xtreamEpisodes)
    }
}
