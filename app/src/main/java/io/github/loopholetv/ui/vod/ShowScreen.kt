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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.loopholetv.ui.theme.Tokens

/** One show: a Continue button, its season tabs and the episodes of the selected season. */
@Composable
fun ShowScreen(
    playlistId: Long,
    key: String,
    onPlay: (VodRef, Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ShowViewModel = viewModel(key = "show/$playlistId/$key", factory = showViewModelFactory(playlistId, key))
    val ui by vm.ui.collectAsStateWithLifecycle()
    StopPlaybackOnResume()
    val starter = rememberVodStarter(rememberCast()) { ref, ms -> onPlay(ref, ms) }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    CloseWhenGone(present = ui != null, loaded = loaded, onBack = onBack)
    // The page is composed afresh when the player closes; the episode played from the list takes
    // focus again, on the season tab it was played from.
    var lastEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    ui?.let { show ->
        ShowContent(
            show,
            onSelectTab = vm::selectTab,
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
    restoreEpisodeKey: String? = null,
    onEpisodeUsed: (episodeKey: String?) -> Unit = {},
    onPlay: (episodeKey: String, startMs: Long) -> Unit,
) {
    val continueButton = remember { FocusRequester() }
    val selectedTab = remember { FocusRequester() }
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
    Row(
        Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 27.dp)
            .onFocusChanged { pageFocused = it.hasFocus },
    ) {
        DetailPoster(ui.logo, ui.name)
        Column(Modifier.padding(start = 32.dp).fillMaxHeight()) {
            Text(ui.name, style = MaterialTheme.typography.headlineLarge, color = Tokens.OnBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ui.summary, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted, modifier = Modifier.padding(top = 4.dp))
            // Opening on an episode to restore leaves focus to that; otherwise Continue, or the season
            // tabs when there is nothing to continue.
            val openRestoring = remember { restoreKey != null }
            if (ui.continueKey != null && ui.continueLabel != null) {
                // The button stays in the column as its label changes with progress, so focus stays on it.
                if (!openRestoring) RequestFocusOnOpen(continueButton)
                PillButton(
                    label = ui.continueLabel,
                    selected = true,
                    modifier = Modifier.padding(top = 16.dp).focusRequester(continueButton),
                    onClick = {
                        onEpisodeUsed(null)
                        onPlay(ui.continueKey, ui.continueStartMs)
                    },
                )
            } else if (!openRestoring && ui.tabs.isNotEmpty()) {
                RequestFocusOnOpen(selectedTab)
            }
            SeasonTabs(ui.tabs, ui.selectedTab, selectedTab, onSelectTab)
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 12.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ui.episodes, key = { it.key }) { row ->
                    EpisodeRow(row, Modifier.focusRequester(rowRequesters.requesterFor(row.key))) {
                        onEpisodeUsed(row.key)
                        onPlay(row.key, row.resumeMs ?: 0L)
                    }
                }
            }
        }
    }
}

private const val RESTORE_TRIES = 20
private const val RESTORE_RETRY_MS = 50L

private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

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
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.width(56.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.fraction?.let { ProgressBar(it, Modifier.padding(top = 6.dp).width(200.dp)) }
        }
        if (row.watched) {
            Text("Watched", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
