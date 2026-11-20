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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.loopholetv.data.db.KeyRef
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

/**
 * Identifies one row by its kind as well as its title: a playlist category may be called "My list" or
 * "Continue watching" too, and must not be taken for the row of that name.
 */
internal fun rowId(row: VodRowModel): String = when (row) {
    is VodRowModel.Continue -> "fixed:continue"
    is VodRowModel.MyList -> "fixed:mylist"
    is VodRowModel.Recent -> "fixed:recent"
    is VodRowModel.Paged -> row.category?.let { "paged:$it" } ?: "paged-none"
    is VodRowModel.Grid -> "grid:${row.title}"
}

/** Identifies one card across rows (in the row [rowId]), so focus can return to it. */
internal fun cardId(rowId: String, card: PosterModel) = "$rowId\u0000${card.playlistId}\u0000${card.key}"

private fun RowCards.indexOf(rowId: String, id: String): Int =
    (0 until count).firstOrNull { i -> peek(i)?.let { cardId(rowId, it) } == id } ?: -1

/** The card a menu is open for, at [index] in [row]. */
private class MenuTarget(val row: VodRowModel, val index: Int, val card: PosterModel)

/** True once the row has shown real cards: a card still missing from it is gone, not just unloaded. */
private fun RowCards.hasLoaded(): Boolean = count > 0 && peek(0) != null

/**
 * The shared body of the Movies and Series screens: a column of rows of posters. OK on a Continue
 * watching card calls [onPlay], OK on any other card [onOpen]; a long OK opens the card's options.
 */
@Composable
internal fun VodBrowse(
    heading: String,
    vm: VodBrowseViewModel,
    onOpen: (playlistId: Long, key: String) -> Unit,
    onPlay: (VodPlay) -> Unit,
    onBack: () -> Unit,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    StopPlaybackOnResume()
    val allHidden by vm.allHidden.collectAsStateWithLifecycle()
    val myListKeys by vm.myListKeys.collectAsStateWithLifecycle()
    VodBrowseContent(
        heading = heading, rows = rows, hidden = hidden, allHidden = allHidden,
        onHideCategory = vm::hideCategory, onShowCategory = vm::showCategory,
        onOpen = onOpen, onBack = onBack,
        myListKeys = myListKeys, onPlay = onPlay,
        onSetInMyList = vm::setInMyList, onDismissContinue = vm::dismissContinue,
    )
}

/**
 * What the screen shows, with its edits as callbacks (the part the screen tests drive). With no rows
 * and [allHidden], a note and a button to show the hidden categories take their place. [myListKeys]
 * says which cards' menus offer to remove them from My list rather than add them.
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
    lastOpenedSlotSeed: Int = -1,
    myListKeys: Set<KeyRef> = emptySet(),
    onPlay: (VodPlay) -> Unit = {},
    onSetInMyList: (PosterModel, Boolean) -> Unit = { _, _ -> },
    onDismissContinue: (PosterModel) -> Unit = {},
) {
    var lastOpened by rememberSaveable { mutableStateOf(lastOpenedSeed) }
    // The last opened card's place in its row: a Continue watching card may be gone on return.
    var lastSlot by rememberSaveable { mutableIntStateOf(lastOpenedSlotSeed) }
    // Where focus goes back to when the screen is entered again; spent once it has landed or is found gone.
    var restore by remember { mutableStateOf(lastOpened) }
    // The first row takes focus on a fresh entry, and again when the card to restore is gone.
    var wantsEntry by remember { mutableStateOf(lastOpened == null) }
    // The row a hidden category was in: when it has gone, the row now at its place takes focus.
    var hiddenFrom by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var options by remember { mutableStateOf<MenuTarget?>(null) }
    var listingHidden by remember { mutableStateOf(false) }
    val column = rememberLazyListState()
    val gridRow = rows.filterIsInstance<VodRowModel.Grid>().firstOrNull()
    val gridItems = gridRow?.items?.collectAsLazyPagingItems()
    val gridCards = gridItems?.let(RowCards::of)
    val gridRequesters = remember { HashMap<String, FocusRequester>() }

    BackHandler(onBack = onBack)

    val noteOpened: (VodRowModel, Int, PosterModel) -> Unit = { row, index, card ->
        lastOpened = cardId(rowId(row), card)
        lastSlot = index
    }
    val openPage: (VodRowModel, Int, PosterModel) -> Unit = { row, index, card ->
        noteOpened(row, index, card)
        onOpen(card.playlistId, card.key)
    }
    val open: (VodRowModel, Int, PosterModel) -> Unit = { row, index, card ->
        val play = card.play
        if (row is VodRowModel.Continue && play != null) {
            noteOpened(row, index, card)
            onPlay(play)
        } else {
            openPage(row, index, card)
        }
    }
    val longClick: (VodRowModel, Int, PosterModel) -> Unit = { row, index, card -> options = MenuTarget(row, index, card) }
    val restoreFailed = { restore = null; wantsEntry = true }

    // The row that should take focus now, if any.
    val claimId: String? = when {
        restore != null -> null
        hiddenFrom != null ->
            if (rows.any { rowId(it) == hiddenFrom?.first }) null
            else (rows.getOrNull(hiddenFrom!!.second) ?: rows.lastOrNull())?.let(::rowId)
        wantsEntry -> rows.firstOrNull()?.let(::rowId)
        else -> null
    }
    val claimed = { wantsEntry = false; hiddenFrom = null }

    // Back from a film or show: scroll to the row that was open, as the row itself then finds the card.
    LaunchedEffect(rows.isNotEmpty(), gridCards?.count) {
        val id = restore ?: return@LaunchedEffect
        if (rows.isEmpty()) return@LaunchedEffect
        val key = id.substringBefore('\u0000')
        val at = rows.indexOfFirst { rowId(it) == key }
        if (at < 0) restoreFailed() else column.scrollToItem(lazyIndexOf(rows, at, gridCards))
    }
    // A card in the grid: its line scrolls into view, then it takes focus.
    LaunchedEffect(restore, gridCards?.count) {
        val id = restore ?: return@LaunchedEffect
        val grid = gridRow ?: return@LaunchedEffect
        val cards = gridCards ?: return@LaunchedEffect
        val gridId = rowId(grid)
        val index = cards.indexOf(gridId, id)
        if (index < 0) {
            if (cards.hasLoaded() && id.startsWith(gridId + '\u0000')) restoreFailed()
            return@LaunchedEffect
        }
        column.scrollToItem(lazyIndexOf(rows, rows.indexOf(grid), cards) + 1 + index / GRID_COLUMNS)
        withFrameNanos { }
        if (gridRequesters[id]?.tryFocus() == true) restore = null
    }
    // The row claiming focus is brought into view; a grid's first card then takes it.
    LaunchedEffect(claimId, gridCards?.count) {
        val id = claimId ?: return@LaunchedEffect
        column.scrollToItem(lazyIndexOf(rows, rows.indexOfFirst { rowId(it) == id }, gridCards))
        val grid = gridRow
        val first = gridCards?.peek(0)
        if (grid != null && rowId(grid) == id && first != null) {
            withFrameNanos { }
            if (gridRequesters[cardId(id, first)]?.tryFocus() == true) claimed()
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
                val id = rowId(row)
                val inRow = { cardKey: String? -> cardKey?.takeIf { it.startsWith(id + '\u0000') } }
                @Composable
                fun cardRow(cards: RowCards) = CardRow(
                    title = row.title, rowId = id, cards = cards,
                    claim = claimId == id, onClaimed = claimed,
                    restore = inRow(restore),
                    // Only a Continue watching card may be gone on return: the card now in its place takes focus.
                    restoreSlot = lastSlot.takeIf { row is VodRowModel.Continue && it >= 0 },
                    onRestored = { restore = null }, onRestoreMissing = restoreFailed,
                    onOpen = { i, card -> open(row, i, card) }, onLongClick = { i, card -> longClick(row, i, card) },
                )
                when (row) {
                    is VodRowModel.Fixed -> item(key = "row:$id") { cardRow(RowCards.of(row.cards)) }
                    is VodRowModel.Paged -> item(key = "row:$id") { cardRow(RowCards.of(row.items.collectAsLazyPagingItems())) }
                    is VodRowModel.Grid -> {
                        item(key = "row:$id") { RowTitle(row.title) }
                        val cards = gridCards
                        if (cards != null) {
                            items(chunkCount(cards.count), key = { "grid:$it" }) { chunk ->
                                Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                    for (i in chunk * GRID_COLUMNS until minOf(cards.count, (chunk + 1) * GRID_COLUMNS)) {
                                        val card = cards.get(i)
                                        if (card == null) {
                                            Box(Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f))
                                        } else {
                                            PosterCard(
                                                title = card.title, logo = card.logo, subtitle = card.subtitle, fraction = card.fraction,
                                                onClick = { open(row, i, card) }, onLongClick = { longClick(row, i, card) },
                                                modifier = Modifier.focusRequester(gridRequesters.requesterFor(cardId(id, card))),
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

    options?.let { t ->
        val row = t.row
        val card = t.card
        val inMyList = row is VodRowModel.MyList || KeyRef(card.playlistId, card.key) in myListKeys
        val choices = buildList {
            if (row is VodRowModel.Continue) {
                add(ChannelOption(OPTION_REMOVE_CONTINUE) { options = null; onDismissContinue(card) })
                add(ChannelOption(OPTION_OPEN_PAGE) { options = null; openPage(row, t.index, card) })
            }
            if (inMyList) {
                add(ChannelOption(OPTION_REMOVE_MY_LIST) { options = null; onSetInMyList(card, false) })
            } else {
                add(ChannelOption(OPTION_ADD_MY_LIST) { options = null; onSetInMyList(card, true) })
            }
            if (canHide(row)) {
                add(ChannelOption(OPTION_HIDE_CATEGORY) {
                    hiddenFrom = rowId(row) to rows.indexOf(row)
                    onHideCategory(row.title)
                    options = null
                })
            }
            if (hidden.isNotEmpty()) add(ChannelOption(OPTION_SHOW_HIDDEN) { options = null; listingHidden = true })
        }
        ChannelOptionsDialog(card.title, choices) { options = null }
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

const val OPTION_REMOVE_CONTINUE = "Remove from Continue watching"
const val OPTION_OPEN_PAGE = "Open page"
const val OPTION_ADD_MY_LIST = "Add to My list"
const val OPTION_REMOVE_MY_LIST = "Remove from My list"
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
 * One scrolling row, [rowId] among the screen's rows. Its scroll position is kept per row. [restore] (a card
 * id) scrolls to that card and focuses it; when the row has loaded without it, the card at [restoreSlot]
 * (or the last card) takes focus instead if a slot is given, else [onRestoreMissing] is called. [claim]
 * focuses the first card.
 */
@Composable
private fun CardRow(
    title: String,
    rowId: String,
    cards: RowCards,
    claim: Boolean,
    onClaimed: () -> Unit,
    restore: String?,
    restoreSlot: Int?,
    onRestored: () -> Unit,
    onRestoreMissing: () -> Unit,
    onOpen: (Int, PosterModel) -> Unit,
    onLongClick: (Int, PosterModel) -> Unit,
) {
    val state = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val first = cards.peek(0)

    suspend fun focusAt(index: Int): Boolean {
        state.scrollToItem(index)
        withFrameNanos { }
        val card = cards.peek(index) ?: return false
        return requesters[cardId(rowId, card)]?.tryFocus() == true
    }

    LaunchedEffect(restore, cards.count, first) {
        val id = restore ?: return@LaunchedEffect
        val index = cards.indexOf(rowId, id)
        val target = when {
            index >= 0 -> index
            !cards.hasLoaded() -> return@LaunchedEffect
            restoreSlot != null -> minOf(restoreSlot, cards.count - 1)
            else -> { onRestoreMissing(); return@LaunchedEffect }
        }
        if (focusAt(target)) onRestored()
    }
    LaunchedEffect(claim, first) {
        if (!claim || first == null) return@LaunchedEffect
        // The row may have been scrolled sideways: bring its first card back so it is composed.
        state.scrollToItem(0)
        withFrameNanos { }
        if (requesters[cardId(rowId, first)]?.tryFocus() == true) onClaimed()
    }

    Column {
        RowTitle(title)
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            modifier = Modifier.focusGroup(),
        ) {
            items(cards.count, key = { i -> cards.peek(i)?.let { cardId(rowId, it) } ?: "placeholder:$i" }) { i ->
                val card = cards.get(i)
                if (card == null) {
                    Box(Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f))
                } else {
                    PosterCard(
                        title = card.title, logo = card.logo, subtitle = card.subtitle, fraction = card.fraction,
                        onClick = { onOpen(i, card) }, onLongClick = { onLongClick(i, card) },
                        modifier = Modifier.focusRequester(requesters.requesterFor(cardId(rowId, card))),
                    )
                }
            }
        }
    }
}

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/** The requester kept for [key], made on first use (not composable, so it is not a remember-in-composition call). */
internal fun MutableMap<String, FocusRequester>.requesterFor(key: String): FocusRequester = getOrPut(key) { FocusRequester() }
