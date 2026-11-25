package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.KeyRef
import io.github.loopholetv.data.db.failClosed
import io.github.loopholetv.data.db.isChannelLocked
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.vod.ALL_SHOWS_GRID_THRESHOLD
import io.github.loopholetv.data.vod.CategoryRow
import io.github.loopholetv.data.vod.ContinueCard
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.data.vod.TitleKind
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodLibraryRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.db.ReminderEntity
import io.github.loopholetv.data.tmdb.Genre
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodSort
import io.github.loopholetv.ui.recordings.whenText
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val RECENT_ROW_TITLE = "Recently added"
const val ALL_SHOWS_ROW_TITLE = "All shows"
const val CONTINUE_ROW_TITLE = "Continue watching"
const val MY_LIST_ROW_TITLE = "My list"
const val SORT_FILTER_LABEL = "Sort & filter"
const val CLEAR_FILTER_LABEL = "Clear"

/** The title of the Sort & filter grid's row: it is not drawn, the filter bar above it says what it holds. */
const val FILTERED_GRID_TITLE = "filtered"

/** How long the grid waits for the panel's choices to settle before it is read again. */
private const val FILTER_DEBOUNCE_MS = 250L

/** What a Continue watching card plays: the film or episode [ref], from [startMs]. */
data class VodPlay(val ref: VodRef, val startMs: Long)

/**
 * One poster as the browse screens show it: [subtitle] is the playlist a show comes from, if known
 * (on a Continue card, the episode caption). [fraction] and [play] are set on Continue cards only.
 */
data class PosterModel(
    val playlistId: Long,
    val key: String,
    val title: String,
    val logo: String?,
    val subtitle: String?,
    val fraction: Float? = null,
    val play: VodPlay? = null,
    /** Set on a reminder card: the showing it is for (the card then opens the guide there). */
    val programme: ProgrammeTarget? = null,
    /** The quality tag of a matched title, e.g. "4K", drawn as a chip on the card. */
    val badge: String? = null,
)

/** A booked reminder as a card of My list: [startMs] is the showing's start, [timeText] when it airs. */
data class ProgrammeTarget(
    val reminderId: Long,
    val playlistId: Long,
    val channelKey: String,
    val startMs: Long,
    val timeText: String,
)

sealed interface VodRowModel {
    val title: String

    /** A row that is a short fixed list of cards. */
    sealed interface Fixed : VodRowModel {
        val cards: List<PosterModel>
    }

    /** A short fixed list (the recently added films or shows). */
    data class Recent(override val title: String, override val cards: List<PosterModel>) : Fixed

    /** Titles to pick up again: unfinished films, and the next episode of shows being watched. */
    data class Continue(override val title: String, override val cards: List<PosterModel>) : Fixed

    /** The titles the viewer saved. */
    data class MyList(override val title: String, override val cards: List<PosterModel>) : Fixed

    /** A horizontally scrolling row of one category; a null [category] is every show without one. */
    data class Paged(override val title: String, val category: String?, val items: Flow<PagingData<PosterModel>>) : VodRowModel

    /** A long list laid out as rows of cards instead of one scrolling row. */
    data class Grid(override val title: String, val items: Flow<PagingData<PosterModel>>) : VodRowModel

    /** The Sort & filter button above the rows, while nothing is chosen. */
    data object FilterButton : VodRowModel {
        override val title: String get() = SORT_FILTER_LABEL
    }

    /** What the grid below holds ([summary]) and how many titles ([count]), with the panel's button and Clear. */
    data class FilterBar(val summary: String, val count: Int) : VodRowModel {
        override val title: String get() = summary
    }
}

/** The rows of the Movies ([VodKind.MOVIE]) or Series ([VodKind.EPISODE]) screen, and the categories hidden from them. */
class VodBrowseViewModel(
    private val kind: VodKind,
    private val repo: VodRepository,
    private val library: VodLibraryRepository,
    private val edits: ChannelEdits,
    /** The reminders booked and not yet aired; they lead the My list row. */
    reminders: Flow<List<ReminderEntity>> = flowOf(emptyList()),
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The logo of a reminder's channel, if it has one. */
    private val channelLogo: suspend (playlistId: Long, channelKey: String) -> String? = { _, _ -> null },
    private val cancelReminder: suspend (id: Long) -> Unit = {},
    /** Whether a TMDB key is set; with one (or with details already fetched) Sort & filter is offered. */
    hasKey: Flow<Boolean> = flowOf(false),
    /** The remembered Sort & filter choice of this screen, and where a new one is remembered. */
    savedFilter: Flow<VodFilter> = flowOf(VodFilter()),
    private val saveFilter: suspend (VodFilter) -> Unit = {},
    /** True while the PIN is not entered, so reminders of locked channels stay out of My list. */
    lockedNow: Flow<Boolean> = flowOf(false),
    /** Whether a channel has a lock (its own or its group's), whatever the PIN state. */
    private val channelLocked: suspend (playlistId: Long, channelKey: String) -> Boolean = { _, _ -> false },
) : ViewModel() {
    // One pager per category, kept so a row keeps its loaded pages when the category list changes.
    private val titleKind = if (kind == VodKind.MOVIE) TitleKind.FILM else TitleKind.SHOW

    private val pagers = HashMap<String?, Flow<PagingData<PosterModel>>>()

    private val reminderCards: Flow<List<PosterModel>> = combine(reminders, lockedNow) { list, locked ->
        val now = clock()
        list.filter { it.stop > now && !(locked && channelLocked(it.playlistId, it.channelKey)) }
            .sortedBy { it.start }.map { it.card(now) }
    }

    private val rowSource = (if (kind == VodKind.MOVIE) filmRows() else showRows())
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
    private val hiddenSource = repo.hiddenCategories()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** Running time is a choice on Movies only. */
    val offersRuntime: Boolean = kind == VodKind.MOVIE

    // Null until the remembered choice has been read, so the rows don't flash before the grid.
    private val chosen = MutableStateFlow<VodFilter?>(null)

    /** The Sort & filter choice as the panel shows it; it applies to the grid a moment later. */
    val filter: StateFlow<VodFilter> = chosen.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, VodFilter())

    // Null until the genres have been read once.
    private val presentGenres: StateFlow<List<Genre>?> = repo.genresPresent(titleKind)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The genres the panel offers: those of this library's titles with details, by name. */
    val genres: StateFlow<List<Genre>> = presentGenres.map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val showFilterButton: Flow<Boolean> =
        combine(hasKey, repo.hasDetails(titleKind)) { key, details -> key || details }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            val saved = try {
                savedFilter.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An unreadable choice is no choice.
                VodFilter()
            }
            // Read once, so a remembered genre no title has any more is dropped.
            val present = presentGenres.filterNotNull().first()
            // A choice made while it was being read wins.
            if (chosen.value == null) chosen.value = normalised(saved, present)
        }
    }

    // The choice the grid follows: the first and Clear at once, the panel's edits once they settle.
    @OptIn(FlowPreview::class)
    private val applied: Flow<VodFilter> = run {
        var first = true
        chosen.filterNotNull().debounce { f ->
            if (first || f.isDefault) 0L.also { first = false } else FILTER_DEBOUNCE_MS
        }.distinctUntilChanged()
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    // One pager for the grid, switched to each new choice, so the cards survive a trip to a page and back.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filteredPages: Flow<PagingData<PosterModel>> = applied.flatMapLatest { f ->
        when {
            f.isDefault -> flowOf(PagingData.empty<PosterModel>())
            kind == VodKind.MOVIE -> repo.filteredFilms(f).map { page -> page.map { it.poster() } }
            kind == VodKind.EPISODE -> repo.filteredShows(f).map { page -> page.map { it.poster() } }
            else -> emptyFlow<PagingData<PosterModel>>()
        }
    }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<VodRowModel>> = applied.flatMapLatest { f ->
        if (f.isDefault) {
            combine(rowSource, showFilterButton) { r, button -> if (button) listOf(VodRowModel.FilterButton) + r else r }
        } else {
            combine(repo.filteredCount(titleKind, f), genres) { count, g ->
                listOf(
                    VodRowModel.FilterBar(filterSummary(f, g.associate { it.id to it.name }), count),
                    VodRowModel.Grid(FILTERED_GRID_TITLE, filteredPages),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Chooses [f] (live, from the panel) and remembers it. */
    fun setFilter(f: VodFilter) {
        val next = normalised(f, presentGenres.value)
        chosen.value = next
        viewModelScope.launch { saveFilter(next) }
    }

    /** Back to the rows: nothing chosen, and nothing remembered. */
    fun clearFilter() = setFilter(VodFilter())

    /**
     * Series has no running time: a band or sort by it would be an invisible choice that hides shows.
     * Likewise a genre none of the titles has (any more), once [present] genres are known.
     */
    private fun normalised(f: VodFilter, present: List<Genre>?): VodFilter {
        val runtimeOk = if (offersRuntime) f
        else f.copy(runtime = RuntimeBand.ANY, sort = if (f.sort == VodSort.RUNTIME) VodSort.RECENT else f.sort)
        if (present == null) return runtimeOk
        val ids = present.mapTo(HashSet()) { it.id }
        return runtimeOk.copy(genres = runtimeOk.genres.filterTo(HashSet()) { it in ids })
    }

    val hidden: StateFlow<List<HiddenCategory>> = hiddenSource.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Nothing is left to show because every category is hidden (false until both have loaded). */
    val allHidden: StateFlow<Boolean> = combine(rowSource, hiddenSource) { r, h -> r.isEmpty() && h.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The titles in My list, to mark cards in their menus. */
    val myListKeys: StateFlow<Set<KeyRef>> = library.myListKeys(titleKind)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun setInMyList(card: PosterModel, inList: Boolean) {
        viewModelScope.launch { library.setInMyList(titleKind, card.playlistId, card.key, inList) }
    }

    fun cancelReminder(card: PosterModel) {
        val id = card.programme?.reminderId ?: return
        viewModelScope.launch { cancelReminder(id) }
    }

    private suspend fun ReminderEntity.card(now: Instant): PosterModel = PosterModel(
        playlistId = playlistId, key = "reminder:$id", title = title, logo = channelLogo(playlistId, channelKey), subtitle = null,
        programme = ProgrammeTarget(id, playlistId, channelKey, start.toEpochMilli(), whenText(start, now, zone)),
    )

    fun dismissContinue(card: PosterModel) {
        viewModelScope.launch { library.dismissContinue(titleKind, card.playlistId, card.key) }
    }

    /** Hides [title] in every playlist that has it. */
    fun hideCategory(title: String) {
        viewModelScope.launch {
            repo.playlistsWithCategory(title).forEach { edits.setGroupHidden(it, title, true) }
        }
    }

    /**
     * Locks or unlocks [title] in every playlist that has it. Both first run [ensurePin] (sets a PIN when
     * there is none, or asks for it; at once while unlocked); nothing changes when that is not completed.
     */
    fun lockCategory(title: String, locked: Boolean, ensurePin: suspend () -> Boolean) {
        viewModelScope.launch {
            if (!ensurePin()) return@launch
            repo.playlistsWithCategory(title).forEach { edits.setGroupLocked(it, title, locked) }
        }
    }

    fun showCategory(c: HiddenCategory) {
        viewModelScope.launch { c.playlistIds.forEach { edits.setGroupHidden(it, c.title, false) } }
    }

    private fun filmRows(): Flow<List<VodRowModel>> =
        combine(
            library.continueFilms(), library.myListFilms(), reminderCards, repo.recentFilms(), repo.filmCategories(),
        ) { cont, mine, due, recent, categories ->
            continueRow(cont) + myListRow(due + mine.map { it.poster() }) + recentRow(recent.map { it.poster() }) +
                categories.map { pagedRow(it) }
        }

    private fun showRows(): Flow<List<VodRowModel>> =
        combine(
            library.continueShows(), library.myListShows(), reminderCards, repo.recentShows(), repo.showCount(null),
            repo.showCategories(),
        ) { parts ->
            @Suppress("UNCHECKED_CAST")
            val cont = parts[0] as List<ContinueCard>
            @Suppress("UNCHECKED_CAST")
            val mine = parts[1] as List<ShowCard>
            @Suppress("UNCHECKED_CAST")
            val due = parts[2] as List<PosterModel>
            @Suppress("UNCHECKED_CAST")
            val recent = parts[3] as List<ShowCard>
            val uncategorised = parts[4] as Int
            @Suppress("UNCHECKED_CAST")
            val categories = parts[5] as List<CategoryRow>
            continueRow(cont) + myListRow(due + mine.map { it.poster() }) + recentRow(recent.map { it.poster() }) +
                allShowsRow(uncategorised) + categories.map { pagedRow(it) }
        }

    private fun continueRow(cards: List<ContinueCard>): List<VodRowModel> =
        if (cards.isEmpty()) emptyList() else listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, cards.map { it.poster() }))

    private fun myListRow(cards: List<PosterModel>): List<VodRowModel> =
        if (cards.isEmpty()) emptyList() else listOf(VodRowModel.MyList(MY_LIST_ROW_TITLE, cards))

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
            VodKind.RECORDING -> kotlinx.coroutines.flow.emptyFlow()
        }.cachedIn(viewModelScope)
    }

    class Factory(private val kind: VodKind) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
            val titleKind = if (kind == VodKind.MOVIE) TitleKind.FILM else TitleKind.SHOW
            return VodBrowseViewModel(
                kind, c.vod, c.vodLibrary, c.channelEdits,
                reminders = c.scheduleRepository.upcomingReminders(),
                channelLogo = { playlistId, key -> c.db.channelDao().byKey(playlistId, key)?.logo },
                cancelReminder = c.scheduleRepository::cancelReminder,
                // Read again whenever the settings change, so saving or removing the key shows or hides the button.
                hasKey = c.settingsStore.settings.map { c.hasTmdbKey() }.distinctUntilChanged(),
                savedFilter = c.settingsStore.vodFilter(titleKind),
                saveFilter = { f -> c.settingsStore.setVodFilter(titleKind, f) },
                lockedNow = c.parentalLock.state.map { it !is LockState.Unlocked },
                channelLocked = { playlistId, key ->
                    failClosed { c.db.lockDao().isChannelLocked(playlistId, key) }
                },
            ) as T
        }
    }
}

private fun FilmCard.poster() = PosterModel(playlistId, key, name, logo, null, badge = badge)
private fun ShowCard.poster() = PosterModel(playlistId, key, name, logo, playlistName, badge = badge)
private fun ContinueCard.poster() =
    PosterModel(playlistId, key, title, logo, caption, fraction, VodPlay(play, startMs), badge = badge)
