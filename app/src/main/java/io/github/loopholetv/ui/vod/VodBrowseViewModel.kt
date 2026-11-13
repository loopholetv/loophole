package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.vod.ALL_SHOWS_GRID_THRESHOLD
import io.github.loopholetv.data.vod.CategoryRow
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val RECENT_ROW_TITLE = "Recently added"
const val ALL_SHOWS_ROW_TITLE = "All shows"

/** One poster as the browse screens show it: [subtitle] is the playlist a show comes from, if known. */
data class PosterModel(val playlistId: Long, val key: String, val title: String, val logo: String?, val subtitle: String?)

sealed interface VodRowModel {
    val title: String

    /** A short fixed list (the recently added films or shows). */
    data class Recent(override val title: String, val cards: List<PosterModel>) : VodRowModel

    /** A horizontally scrolling row of one category; a null [category] is every show without one. */
    data class Paged(override val title: String, val category: String?, val items: Flow<PagingData<PosterModel>>) : VodRowModel

    /** A long list laid out as rows of cards instead of one scrolling row. */
    data class Grid(override val title: String, val items: Flow<PagingData<PosterModel>>) : VodRowModel
}

/** The rows of the Movies ([VodKind.MOVIE]) or Series ([VodKind.EPISODE]) screen, and the categories hidden from them. */
class VodBrowseViewModel(
    private val kind: VodKind,
    private val repo: VodRepository,
    private val edits: ChannelEdits,
) : ViewModel() {
    // One pager per category, kept so a row keeps its loaded pages when the category list changes.
    private val pagers = HashMap<String?, Flow<PagingData<PosterModel>>>()

    private val rowSource = (if (kind == VodKind.MOVIE) filmRows() else showRows())
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
    private val hiddenSource = repo.hiddenCategories()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    val rows: StateFlow<List<VodRowModel>> = rowSource.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hidden: StateFlow<List<HiddenCategory>> = hiddenSource.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Nothing is left to show because every category is hidden (false until both have loaded). */
    val allHidden: StateFlow<Boolean> = combine(rowSource, hiddenSource) { r, h -> r.isEmpty() && h.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Hides [title] in every playlist that has it. */
    fun hideCategory(title: String) {
        viewModelScope.launch {
            repo.playlistsWithCategory(title).forEach { edits.setGroupHidden(it, title, true) }
        }
    }

    fun showCategory(c: HiddenCategory) {
        viewModelScope.launch { c.playlistIds.forEach { edits.setGroupHidden(it, c.title, false) } }
    }

    private fun filmRows(): Flow<List<VodRowModel>> =
        combine(repo.recentFilms(), repo.filmCategories()) { recent, categories ->
            recentRow(recent.map { it.poster() }) + categories.map { pagedRow(it) }
        }

    private fun showRows(): Flow<List<VodRowModel>> =
        combine(repo.recentShows(), repo.showCount(null), repo.showCategories()) { recent, uncategorised, categories ->
            recentRow(recent.map { it.poster() }) + allShowsRow(uncategorised) + categories.map { pagedRow(it) }
        }

    private fun recentRow(cards: List<PosterModel>): List<VodRowModel> =
        if (cards.isEmpty()) emptyList() else listOf(VodRowModel.Recent(RECENT_ROW_TITLE, cards))

    private fun allShowsRow(count: Int): List<VodRowModel> = when {
        count == 0 -> emptyList()
        count > ALL_SHOWS_GRID_THRESHOLD -> listOf(VodRowModel.Grid(ALL_SHOWS_ROW_TITLE, pager(null)))
        else -> listOf(VodRowModel.Paged(ALL_SHOWS_ROW_TITLE, null, pager(null)))
    }

    private fun pagedRow(c: CategoryRow) = VodRowModel.Paged(c.title, c.title, pager(c.title))

    private fun pager(category: String?): Flow<PagingData<PosterModel>> = pagers.getOrPut(category) {
        when (kind) {
            VodKind.MOVIE -> repo.films(category!!).map { page -> page.map { it.poster() } }
            VodKind.EPISODE -> repo.shows(category).map { page -> page.map { it.poster() } }
        }.cachedIn(viewModelScope)
    }

    class Factory(private val kind: VodKind) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
            return VodBrowseViewModel(kind, c.vod, c.channelEdits) as T
        }
    }
}

private fun FilmCard.poster() = PosterModel(playlistId, key, name, logo, null)
private fun ShowCard.poster() = PosterModel(playlistId, key, name, logo, playlistName)
