package io.github.loopholetv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.common.StopPlaybackOnResume
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens

private const val GRID_COLUMNS = 6
private val CARD_GAP = 12.dp

/** The cards of one row, whether a fixed list or a paged one; a null card is still loading. */
private class RowCards(val count: Int, val peek: (Int) -> PosterModel?, val get: (Int) -> PosterModel?) {
    companion object {
        fun of(cards: List<PosterModel>) = RowCards(cards.size, cards::getOrNull, cards::getOrNull)
        fun of(items: LazyPagingItems<PosterModel>) = RowCards(
            items.itemCount,
            { if (it < items.itemCount) items.peek(it) else null },
            { if (it < items.itemCount) items[it] else null },
        )
    }
}

/** Identifies one card across rows, so focus can return to it. */
internal fun cardId(row: String, card: PosterModel) = "$row\u0000${card.playlistId}\u0000${card.key}"

private fun RowCards.indexOf(row: String, id: String): Int =
    (0 until count).firstOrNull { i -> peek(i)?.let { cardId(row, it) } == id } ?: -1

/** True once the row has shown real cards: a card still missing from it is gone, not just unloaded. */
private fun RowCards.hasLoaded(): Boolean = count > 0 && peek(0) != null

/**
 * The shared body of the Movies and Series screens: a column of rows of posters. OK on a card calls
 * [onOpen]; a long OK opens the category options.
 */
@Composable
internal fun VodBrowse(
    heading: String,
    vm: VodBrowseViewModel,
    onOpen: (playlistId: Long, key: String) -> Unit,
    onBack: () -> Unit,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    StopPlaybackOnResume()
    val allHidden by vm.allHidden.collectAsStateWithLifecycle()
    VodBrowseContent(
        heading = heading, rows = rows, hidden = hidden, allHidden = allHidden,
        onHideCategory = vm::hideCategory, onShowCategory = vm::showCategory,
        onOpen = onOpen, onBack = onBack,
    )
}

/**
 * What the screen shows, with its edits as callbacks (the part the screen tests drive). With no rows
 * and [allHidden], a note and a button to show the hidden categories take their place.
 */
@Composable
internal fun VodBrowseContent(
    heading: String,
    rows: List<VodRowModel>,
    hidden: List<HiddenCategory>,
    allHidden: Boolean = false,
    onHideCategory: (String) -> Unit,
    onShowCategory: (HiddenCategory) -> Unit,
    onOpen: (playlistId: Long, key: String) -> Unit,
    onBack: () -> Unit,
    lastOpenedSeed: String? = null,
) {
    var lastOpened by rememberSaveable { mutableStateOf(lastOpenedSeed) }
    // Where focus goes back to when the screen is entered again; spent once it has landed or is found gone.
    var restore by remember { mutableStateOf(lastOpened) }
    // The first row takes focus on a fresh entry, and again when the card to restore is gone.
    var wantsEntry by remember { mutableStateOf(lastOpened == null) }
    // The row a hidden category was in: when it has gone, the row now at its place takes focus.
    var hiddenFrom by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var options by remember { mutableStateOf<VodRowModel?>(null) }
    var listingHidden by remember { mutableStateOf(false) }
    val column = rememberLazyListState()
    val gridRow = rows.filterIsInstance<VodRowModel.Grid>().firstOrNull()
    val gridItems = gridRow?.items?.collectAsLazyPagingItems()
    val gridCards = gridItems?.let(RowCards::of)
    val gridRequesters = remember { HashMap<String, FocusRequester>() }

    BackHandler(onBack = onBack)

    val open: (VodRowModel, PosterModel) -> Unit = { row, card ->
        lastOpened = cardId(row.title, card)
        onOpen(card.playlistId, card.key)
    }
    val longClick: (VodRowModel) -> Unit = { row ->
        if (canHide(row) || hidden.isNotEmpty()) options = row
    }
    val restoreFailed = { restore = null; wantsEntry = true }

    // The row that should take focus now, if any.
    val claimTitle: String? = when {
        restore != null -> null
        hiddenFrom != null ->
            if (rows.any { it.title == hiddenFrom?.first }) null
            else (rows.getOrNull(hiddenFrom!!.second) ?: rows.lastOrNull())?.title
        wantsEntry -> rows.firstOrNull()?.title
        else -> null
    }
    val claimed = { wantsEntry = false; hiddenFrom = null }

    // Back from a film or show: scroll to the row that was open, as the row itself then finds the card.
    LaunchedEffect(rows.isNotEmpty(), gridCards?.count) {
        val id = restore ?: return@LaunchedEffect
        if (rows.isEmpty()) return@LaunchedEffect
        val title = id.substringBefore('\u0000')
        val at = rows.indexOfFirst { it.title == title }
        if (at < 0) restoreFailed() else column.scrollToItem(lazyIndexOf(rows, at, gridCards))
    }
    // A card in the grid: its line scrolls into view, then it takes focus.
    LaunchedEffect(restore, gridCards?.count) {
        val id = restore ?: return@LaunchedEffect
        val grid = gridRow ?: return@LaunchedEffect
        val cards = gridCards ?: return@LaunchedEffect
        val index = cards.indexOf(grid.title, id)
        if (index < 0) {
            if (cards.hasLoaded() && id.startsWith(grid.title + '\u0000')) restoreFailed()
            return@LaunchedEffect
        }
        column.scrollToItem(lazyIndexOf(rows, rows.indexOf(grid), cards) + 1 + index / GRID_COLUMNS)
        withFrameNanos { }
        if (gridRequesters[id]?.tryFocus() == true) restore = null
    }
    // The row claiming focus is brought into view; a grid's first card then takes it.
    LaunchedEffect(claimTitle, gridCards?.count) {
        val title = claimTitle ?: return@LaunchedEffect
        column.scrollToItem(lazyIndexOf(rows, rows.indexOfFirst { it.title == title }, gridCards))
        val grid = gridRow
        val first = gridCards?.peek(0)
        if (grid?.title == title && first != null) {
            withFrameNanos { }
            if (gridRequesters[cardId(title, first)]?.tryFocus() == true) claimed()
        }
    }

    Column(Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text(heading, style = MaterialTheme.typography.headlineMedium, color = Tokens.OnBackground)
        if (rows.isEmpty() && allHidden) {
            // Whatever row held focus is gone; once a category is shown again the first row takes it.
            AllHiddenNote(onShowHidden = { listingHidden = true }, onFocused = { hiddenFrom = null; wantsEntry = true })
        }
        LazyColumn(
            state = column,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            rows.forEach { row ->
                when (row) {
                    is VodRowModel.Recent -> item(key = "row:${row.title}") {
                        CardRow(
                            title = row.title, cards = RowCards.of(row.cards),
                            claim = claimTitle == row.title, onClaimed = claimed,
                            restore = restore?.takeIf { it.startsWith(row.title + '\u0000') },
                            onRestored = { restore = null }, onRestoreMissing = restoreFailed,
                            onOpen = { open(row, it) }, onLongClick = { longClick(row) },
                        )
                    }
                    is VodRowModel.Paged -> item(key = "row:${row.title}") {
                        val items = row.items.collectAsLazyPagingItems()
                        CardRow(
                            title = row.title, cards = RowCards.of(items),
                            claim = claimTitle == row.title, onClaimed = claimed,
                            restore = restore?.takeIf { it.startsWith(row.title + '\u0000') },
                            onRestored = { restore = null }, onRestoreMissing = restoreFailed,
                            onOpen = { open(row, it) }, onLongClick = { longClick(row) },
                        )
                    }
                    is VodRowModel.Grid -> {
                        item(key = "row:${row.title}") { RowTitle(row.title) }
                        val cards = gridCards
                        if (cards != null) {
                            items(chunkCount(cards.count), key = { "grid:$it" }) { chunk ->
                                Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                    for (i in chunk * GRID_COLUMNS until minOf(cards.count, (chunk + 1) * GRID_COLUMNS)) {
                                        val card = cards.get(i)
                                        if (card == null) {
                                            Box(Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f))
                                        } else {
                                            val id = cardId(row.title, card)
                                            PosterCard(
                                                title = card.title, logo = card.logo, subtitle = card.subtitle,
                                                onClick = { open(row, card) }, onLongClick = { longClick(row) },
                                                modifier = Modifier.focusRequester(gridRequesters.requesterFor(id)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    options?.let { row ->
        val choices = buildList {
            if (canHide(row)) {
                add(ChannelOption(OPTION_HIDE_CATEGORY) {
                    hiddenFrom = row.title to rows.indexOf(row)
                    onHideCategory(row.title)
                    options = null
                })
            }
            if (hidden.isNotEmpty()) add(ChannelOption(OPTION_SHOW_HIDDEN) { options = null; listingHidden = true })
        }
        ChannelOptionsDialog(row.title, choices) { options = null }
    }
    if (listingHidden) {
        ChannelOptionsDialog(
            title = OPTION_SHOW_HIDDEN,
            options = hidden.map { c -> ChannelOption(c.title) { onShowCategory(c); listingHidden = false } },
        ) { listingHidden = false }
    }
}

/** Every category is hidden: says so, with the way back focused. */
@Composable
private fun AllHiddenNote(onShowHidden: () -> Unit, onFocused: () -> Unit) {
    val requester = remember { FocusRequester() }
    RequestFocusOnOpen(requester)
    Column(Modifier.padding(top = 24.dp)) {
        Text(ALL_HIDDEN_NOTE, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        PillButton(
            label = OPTION_SHOW_HIDDEN,
            selected = true,
            onFocus = onFocused,
            onClick = onShowHidden,
            modifier = Modifier.padding(top = 16.dp).focusRequester(requester),
        )
    }
}

private fun canHide(row: VodRowModel) = row is VodRowModel.Paged && row.category != null

const val OPTION_HIDE_CATEGORY = "Hide category"
const val OPTION_SHOW_HIDDEN = "Show hidden categories"
const val ALL_HIDDEN_NOTE = "All categories are hidden"

private fun chunkCount(cards: Int) = (cards + GRID_COLUMNS - 1) / GRID_COLUMNS

/** Position in the screen's list of the row at [rowIndex]: a grid takes one entry per line of cards. */
private fun lazyIndexOf(rows: List<VodRowModel>, rowIndex: Int, gridCards: RowCards?): Int =
    rows.take(rowIndex).sumOf { if (it is VodRowModel.Grid) 1 + chunkCount(gridCards?.count ?: 0) else 1 }

@Composable
private fun RowTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, modifier = Modifier.padding(bottom = 8.dp))
}

/**
 * One scrolling row. Its scroll position is kept per row. [restore] (a card id) scrolls to that card and
 * focuses it, or calls [onRestoreMissing] when the row has loaded without it; [claim] focuses the first card.
 */
@Composable
private fun CardRow(
    title: String,
    cards: RowCards,
    claim: Boolean,
    onClaimed: () -> Unit,
    restore: String?,
    onRestored: () -> Unit,
    onRestoreMissing: () -> Unit,
    onOpen: (PosterModel) -> Unit,
    onLongClick: () -> Unit,
) {
    val state = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val first = cards.peek(0)

    LaunchedEffect(restore, cards.count, first) {
        val id = restore ?: return@LaunchedEffect
        val index = cards.indexOf(title, id)
        if (index < 0) {
            if (cards.hasLoaded()) onRestoreMissing()
            return@LaunchedEffect
        }
        state.scrollToItem(index)
        withFrameNanos { }
        if (requesters[id]?.tryFocus() == true) onRestored()
    }
    LaunchedEffect(claim, first) {
        if (!claim || first == null) return@LaunchedEffect
        // The row may have been scrolled sideways: bring its first card back so it is composed.
        state.scrollToItem(0)
        withFrameNanos { }
        if (requesters[cardId(title, first)]?.tryFocus() == true) onClaimed()
    }

    Column {
        RowTitle(title)
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            modifier = Modifier.focusGroup(),
        ) {
            items(cards.count, key = { i -> cards.peek(i)?.let { cardId(title, it) } ?: "placeholder:$i" }) { i ->
                val card = cards.get(i)
                if (card == null) {
                    Box(Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f))
                } else {
                    PosterCard(
                        title = card.title, logo = card.logo, subtitle = card.subtitle,
                        onClick = { onOpen(card) }, onLongClick = onLongClick,
                        modifier = Modifier.focusRequester(requesters.requesterFor(cardId(title, card))),
                    )
                }
            }
        }
    }
}

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/** The requester kept for [key], made on first use (not composable, so it is not a remember-in-composition call). */
internal fun MutableMap<String, FocusRequester>.requesterFor(key: String): FocusRequester = getOrPut(key) { FocusRequester() }
