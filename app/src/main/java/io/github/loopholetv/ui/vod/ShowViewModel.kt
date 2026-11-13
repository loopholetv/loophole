package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.vod.ContinueTarget
import io.github.loopholetv.data.vod.EpisodeView
import io.github.loopholetv.data.vod.ShowDetail
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.vod.continueTarget
import io.github.loopholetv.data.vod.resumeFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One episode in the list; [label] is "E3", or empty for an episode without a number. */
data class EpisodeRowUi(
    val key: String,
    val label: String,
    val name: String,
    val fraction: Float?,
    val watched: Boolean,
    val resumeMs: Long?,
)

/**
 * What the show page shows. [tabs] are the seasons, then "Other"; [episodes] are those of the
 * [selectedTab]. [continueKey] is null when the show has no episodes.
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
)

/** One show with its seasons and the episode its primary button plays. Null while loading or when the show is gone. */
class ShowViewModel(
    playlistId: Long,
    key: String,
    repo: VodRepository,
    progress: VodProgressRepository,
) : ViewModel() {
    // Until a tab is picked the page sits on the season of the continue episode.
    private val pickedTab = MutableStateFlow<Int?>(null)

    private val _ui = MutableStateFlow<ShowUi?>(null)
    private val _loaded = MutableStateFlow(false)
    val ui: StateFlow<ShowUi?> = _ui

    /** Whether [ui] has been read from the database yet: a null [ui] before that is only "loading". */
    val loaded: StateFlow<Boolean> = _loaded

    init {
        viewModelScope.launch {
            combine(repo.show(playlistId, key), progress.observeEpisodes(playlistId, key), pickedTab, ::showUi).collect {
                _ui.value = it
                _loaded.value = true
            }
        }
    }

    fun selectTab(index: Int) {
        pickedTab.value = index
    }
}

private fun showUi(detail: ShowDetail?, progress: Map<String, VodProgressEntity>, picked: Int?): ShowUi? {
    detail ?: return null
    val seasons = detail.seasons
    val views = seasons.flatMap { s ->
        s.episodes.map { EpisodeView(it.key, it.season, it.episode, it.name, progress[it.key]) }
    }
    val target = continueTarget(views)
    val targetTab = target?.let { t -> seasons.indexOfFirst { s -> s.episodes.any { it.key == t.key } } } ?: -1
    val selected = (picked ?: targetTab).coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val rows = seasons.getOrNull(selected)?.episodes.orEmpty().map { e ->
        val p = progress[e.key]
        EpisodeRowUi(
            key = e.key,
            label = if (e.season != null && e.episode != null) "E${e.episode}" else "",
            name = e.name,
            fraction = p?.takeIf { it.positionMs > 0 && (it.durationMs ?: 0) > 0 }
                ?.let { (it.positionMs.toFloat() / it.durationMs!!).coerceIn(0f, 1f) },
            watched = p?.watched == true,
            resumeMs = resumeFrom(p),
        )
    }
    return ShowUi(
        name = detail.show.name,
        logo = detail.show.logo,
        summary = summaryOf(seasons.count { it.number != null }, views.size),
        tabs = seasons.map { it.number?.let { n -> "Season $n" } ?: "Other" },
        selectedTab = selected,
        episodes = rows,
        continueLabel = target?.let(::continueLabel),
        continueKey = target?.key,
        continueStartMs = target?.let { resumeFrom(progress[it.key]) } ?: 0L,
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
        ShowViewModel(playlistId, key, c.vod, c.vodProgress)
    }
}
