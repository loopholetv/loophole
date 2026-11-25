package io.github.loopholetv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.common.StopPlaybackOnResume
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.parental.LocalLockMarks
import io.github.loopholetv.ui.parental.LocalPinPrompter
import io.github.loopholetv.ui.theme.Tokens

/** The back-stack entry state where the player leaves the key of the episode it played last. */
const val VOD_LAST_EPISODE = "vod_last_episode"

/** The episode the player left on this entry, if any; it is cleared, so it is used once. */
fun SavedStateHandle.takeLastEpisode(): String? = remove<String>(VOD_LAST_EPISODE)

/**
 * One show: a Continue button, its season tabs and the episodes of the selected season.
 * [takeLastEpisodeFromPlayer] gives, once, the episode the player played last before returning here.
 */
@Composable
fun ShowScreen(
    playlistId: Long,
    key: String,
    onPlay: (VodRef, Long) -> Unit,
    onBack: () -> Unit,
    takeLastEpisodeFromPlayer: () -> String? = { null },
) {
    val vm: ShowViewModel = viewModel(key = "show/$playlistId/$key", factory = showViewModelFactory(playlistId, key))
    // The page is composed afresh when the player closes; the episode that played last takes focus
    // again, on its season tab. After an auto-play run that is the run's last episode, which may be
    // in a later season than the one played from: that season is shown before the page is drawn.
    var lastEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    // Once per visit: before the page reads ui, so it is composed on that season.
    remember {
        takeLastEpisodeFromPlayer()?.also {
            lastEpisode = it
            vm.showEpisode(it)
        }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    StopPlaybackOnResume()
    val starter = rememberVodStarter(rememberCast()) { ref, ms -> onPlay(ref, ms) }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    CloseWhenGone(present = ui != null, loaded = loaded, onBack = onBack)
    val marks = LocalLockMarks.current
    val prompter = LocalPinPrompter.current
    ui?.let { show ->
        ShowContent(
            show,
            onSelectTab = vm::selectTab,
            onToggleMyList = vm::toggleMyList,
            onWrongDetails = vm::rejectDetails,
            onRetryEpisodes = vm::retryEpisodes,
            offerLock = marks.unlocked,
            locked = marks.show(playlistId, key),
            onToggleLock = { vm.setLocked(!marks.show(playlistId, key), prompter::ensurePinThen) },
            restoreEpisodeKey = lastEpisode,
            onEpisodeUsed = { used ->
                lastEpisode = used
                if (used != null) vm.selectTab(show.selectedTab)
            },
        ) { episodeKey, ms -> starter.start(VodRef(VodKind.EPISODE, playlistId, episodeKey), ms) }
    }
}

/** The show page body; [onPlay] gets an episode key and the position to start from, in ms. */
@Composable
internal fun ShowContent(
    ui: ShowUi,
    onSelectTab: (Int) -> Unit,
    onToggleMyList: () -> Unit = {},
    onWrongDetails: () -> Unit = {},
    onRetryEpisodes: () -> Unit = {},
    /** The lock pill is offered (a PIN exists and is entered); [locked] says which way it reads. */
    offerLock: Boolean = false,
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    restoreEpisodeKey: String? = null,
    onEpisodeUsed: (episodeKey: String?) -> Unit = {},
    onPlay: (episodeKey: String, startMs: Long) -> Unit,
) {
    val continueButton = remember { FocusRequester() }
    val selectedTab = remember { FocusRequester() }
    val myListButton = remember { FocusRequester() }
    val tryAgainButton = remember { FocusRequester() }
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    // A new list state per season, so switching season starts at the top.
    val listState = key(ui.selectedTab) { rememberLazyListState() }
    var pageFocused by remember { mutableStateOf(false) }
    // The episode to put focus back on, while that is still wanted: only if it is listed when the
    // page opens, and dropped when the user moves to another season.
    var restoreKey by remember { mutableStateOf(restoreEpisodeKey?.takeIf { k -> ui.episodes.any { it.key == k } }) }
    val openedOn = remember { ui.selectedTab }
    LaunchedEffect(ui.selectedTab) { if (ui.selectedTab != openedOn) restoreKey = null }
    val current by rememberUpdatedState(ui)
    LaunchedEffect(restoreKey) {
        val wanted = restoreKey ?: return@LaunchedEffect
        var landed = false
        repeat(RESTORE_TRIES) {
            // Anything holding focus (the episode, or whatever the user moved to) ends the restore.
            if (landed || pageFocused) { landed = true; return@repeat }
            val index = current.episodes.indexOfFirst { it.key == wanted }
            if (index >= 0) {
                listState.scrollToItem(index)
                landed = rowRequesters[wanted]?.tryFocus() == true
            }
            if (!landed) delay(RESTORE_RETRY_MS)
        }
        // Never leave nothing focused.
        if (!landed && !pageFocused) {
            (if (current.continueKey != null) continueButton else selectedTab).tryFocus()
        }
        restoreKey = null
    }
    DetailBackground(ui.details?.backdropUrl) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)
                .onFocusChanged { pageFocused = it.hasFocus },
        ) {
            DetailPoster(ui.logo, ui.name, ui.details?.badge)
            HeaderAboveList(Modifier.padding(start = 32.dp).fillMaxHeight()) {
                // The text: what gives way when the page is short.
                Column(Modifier.clipToBounds()) {
                    Text(ui.name, style = MaterialTheme.typography.headlineLarge, color = Tokens.OnBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // A matched show's meta line takes the place of the seasons and episodes summary.
                    Text(
                        ui.details?.let(::formatMeta) ?: ui.summary,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tokens.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ui.details?.let { DetailsText(it, overviewLines = SHOW_OVERVIEW_LINES) }
                }
                // The buttons and season tabs: always shown in full.
                Column {
                    // Opening on an episode to restore leaves focus to that; otherwise Continue, or the season
                    // tabs when there is nothing to continue, else Try again when the episodes failed to
                    // load, else My list. Each takes focus again when it appears, so focus follows the
                    // episodes as they load (and isn't lost when Try again goes away).
                    val openRestoring = remember { restoreKey != null }
                    if (!openRestoring) {
                        val failed = ui.episodesState == EpisodesState.FAILED
                        when {
                            ui.continueKey != null -> RequestFocusOnOpen(continueButton)
                            ui.tabs.isNotEmpty() -> RequestFocusOnOpen(selectedTab)
                            failed -> RequestFocusOnOpen(tryAgainButton)
                            else -> RequestFocusOnOpen(myListButton)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                        // The button stays in the row as its label changes with progress, so focus stays on it.
                        if (ui.continueKey != null && ui.continueLabel != null) {
                            PillButton(
                                label = ui.continueLabel,
                                selected = true,
                                modifier = Modifier.focusRequester(continueButton),
                                onClick = {
                                    onEpisodeUsed(null)
                                    onPlay(ui.continueKey, ui.continueStartMs)
                                },
                            )
                        }
                        PillButton(
                            label = myListLabel(ui.inMyList),
                            selected = false,
                            modifier = Modifier.focusRequester(myListButton),
                            onClick = onToggleMyList,
                        )
                        if (offerLock) PillButton(label = showLockLabel(locked), selected = false, onClick = onToggleLock)
                        if (ui.details?.fromTmdb == true) {
                            // The button goes away with the details, so focus moves first to Continue, else the
                            // season tab, else the first episode, else My list.
                            WrongDetailsButton {
                                val target = when {
                                    ui.continueKey != null -> continueButton
                                    ui.tabs.isNotEmpty() -> selectedTab
                                    ui.episodes.isNotEmpty() -> rowRequesters.requesterFor(ui.episodes.first().key)
                                    else -> myListButton
                                }
                                target.tryFocus()
                                onWrongDetails()
                            }
                        }
                    }
                    SeasonTabs(ui.tabs, ui.selectedTab, selectedTab, onSelectTab)
                }
                if (ui.episodesState == EpisodesState.READY) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(top = 12.dp).testTag(EPISODE_LIST_TAG),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ui.episodes, key = { it.key }) { row ->
                            EpisodeRow(row, Modifier.focusRequester(rowRequesters.requesterFor(row.key))) {
                                onEpisodeUsed(row.key)
                                onPlay(row.key, row.resumeMs ?: 0L)
                            }
                        }
                    }
                } else {
                    EpisodesMessage(ui.episodesState, tryAgainButton) {
                        // The button goes away while the fetch runs, so focus moves to My list first.
                        myListButton.tryFocus()
                        onRetryEpisodes()
                    }
                }
            }
        }
    }
}

internal const val LOADING_EPISODES = "Loading episodes…"
internal const val EPISODES_FAILED = "Couldn't load episodes"
internal const val TRY_AGAIN = "Try again"
internal const val NO_EPISODES = "No episodes yet"

/** The list area of a show whose episodes are loading, failed to load or are none yet; the last two offer Try again ([onRetry]). */
@Composable
private fun EpisodesMessage(state: EpisodesState, tryAgain: FocusRequester, onRetry: () -> Unit) {
    Column(Modifier.padding(top = 12.dp).testTag(EPISODE_LIST_TAG)) {
        val line = when (state) {
            EpisodesState.LOADING -> LOADING_EPISODES
            EpisodesState.FAILED -> EPISODES_FAILED
            EpisodesState.EMPTY, EpisodesState.READY -> NO_EPISODES
        }
        Text(line, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        // A show with none yet may only have been refused for now, so it can be asked again too.
        if (state == EpisodesState.FAILED || state == EpisodesState.EMPTY) {
            PillButton(
                label = TRY_AGAIN,
                selected = false,
                modifier = Modifier.focusRequester(tryAgain).padding(top = 12.dp),
                onClick = onRetry,
            )
        }
    }
}

private const val SHOW_OVERVIEW_LINES = 2
internal val MIN_LIST_HEIGHT = 160.dp
internal const val EPISODE_LIST_TAG = "episode-list"

/**
 * Three children stacked: the header text, the controls (buttons and season tabs) and the episode
 * list, which takes the height left. The controls always get their full height; the text gets at most
 * what leaves the list [MIN_LIST_HEIGHT], so large fonts or a short screen clip the text, never the
 * controls, rather than squeeze the list away.
 */
@Composable
private fun HeaderAboveList(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (text, controls, list) = measurables
        val loose = constraints.copy(minHeight = 0)
        if (!constraints.hasBoundedHeight) {
            // Nothing to share out: each takes its natural height.
            val placed = listOf(text, controls, list).map { it.measure(loose) }
            return@Layout layout(constraints.maxWidth, placed.sumOf { it.height }) {
                var y = 0
                placed.forEach { it.placeRelative(0, y); y += it.height }
            }
        }
        val total = constraints.maxHeight
        val c = controls.measure(loose)
        val minList = MIN_LIST_HEIGHT.roundToPx().coerceAtMost((total - c.height).coerceAtLeast(0))
        val t = text.measure(loose.copy(maxHeight = (total - c.height - minList).coerceAtLeast(0)))
        val listHeight = (total - t.height - c.height).coerceAtLeast(0)
        val l = list.measure(loose.copy(minHeight = listHeight, maxHeight = listHeight))
        layout(constraints.maxWidth, total) {
            t.placeRelative(0, 0)
            c.placeRelative(0, t.height)
            l.placeRelative(0, t.height + c.height)
        }
    }
}

private const val RESTORE_TRIES = 20
private const val RESTORE_RETRY_MS = 50L


@Composable
private fun SeasonTabs(tabs: List<String>, selected: Int, selectedRequester: FocusRequester, onSelect: (Int) -> Unit) {
    if (tabs.isEmpty()) return
    // Opens scrolled to the selected season, so a show with many seasons does not hide it.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    LazyRow(
        state = state,
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tabs) { i, title ->
            PillButton(
                label = title,
                selected = i == selected,
                modifier = if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier,
                onClick = { onSelect(i) },
            )
        }
    }
}

@Composable
private fun EpisodeRow(row: EpisodeRowUi, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val text = if (focused) Tokens.OnFocus else Tokens.OnBackground
    val muted = if (focused) Tokens.OnFocus else Tokens.Muted
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (focused) Tokens.FocusFill else Tokens.Surface)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { }
            .okPress(onClick, null)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.label.isNotEmpty()) {
            // A TMDB heading carries "S1 E3" itself; the column stays so the rows line up.
            Text(
                if (row.tmdbTitle == null) row.label else "",
                style = MaterialTheme.typography.titleMedium,
                color = muted,
                modifier = Modifier.width(56.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(row.heading, style = MaterialTheme.typography.titleMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.plot?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            row.fraction?.let { ProgressBar(it, Modifier.padding(top = 6.dp).width(200.dp)) }
        }
        if (row.watched) {
            Text("Watched", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
