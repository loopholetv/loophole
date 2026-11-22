package io.github.loopholetv.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.StopPlaybackOnResume
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.guide.GuideAt
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens
import io.github.loopholetv.ui.vod.requesterFor
import kotlinx.coroutines.delay

private val START_EARLY_CHOICES = listOf(0, 1, 2, 5, 10)
private val END_LATE_CHOICES = listOf(0, 1, 5, 10, 15, 30)
private val KEEP_LAST_CHOICES = listOf(1, 2, 3, 5, 10)
private const val KEEP_ALL = "Keep all"
private const val OWN_CHANNEL = "The channel it was booked on"
private const val CANCEL = "Cancel"
private const val START_EARLY = "Start early"
private const val END_LATE = "End late"
private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 50L

/** Which choice a row's dialog is asking for next. */
private sealed interface Ask {
    data class Menu(val row: RecordingRow) : Ask
    data class StartEarly(val row: RecordingRow) : Ask
    data class EndLate(val row: RecordingRow, val before: Int) : Ask
    data class KeepLast(val row: RecordingRow) : Ask
    /** The playlists to choose from are being read. */
    data class LoadSource(val row: RecordingRow) : Ask
    data class Source(val row: RecordingRow, val choices: List<SourceChoice>) : Ask
}

/**
 * Everything booked, running and recorded on this TV, and the other TVs' recordings. OK plays a recording
 * (or opens a reminder in the guide); a long OK or Menu opens what can be done with the row.
 */
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onPlay: (id: Long, live: Boolean) -> Unit,
    onPlayRemote: (peerId: String, id: Long, live: Boolean) -> Unit,
    onOpenGuide: (GuideAt) -> Unit,
    onSetUpStorage: () -> Unit,
    vm: RecordingsViewModel = viewModel(factory = RecordingsViewModel.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<Ask?>(null) }
    StopPlaybackOnResume()
    BackHandler(onBack = onBack)

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is RecordingsEvent.Play -> onPlay(e.id, e.live)
                is RecordingsEvent.PlayRemote -> onPlayRemote(e.peerId, e.id, e.live)
                is RecordingsEvent.OpenGuide -> onOpenGuide(e.at)
            }
        }
    }

    val first = remember { FocusRequester() }
    val empty = ui.sections.isEmpty()
    // The first row (or, with nothing recorded, the button) takes focus once the data is in.
    LaunchedEffect(ui.loaded, empty, ui.storageMissing) {
        if (!ui.loaded) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }

    // Focus follows the row the viewer was on: when it goes (cancelled, deleted) the one now at its place
    // (else the one before) takes it, and with no row left the title does, so keys are never lost.
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    val titleFocus = remember { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val keys = ui.sections.flatMap { s -> s.rows.map { it.key } }
    var previousKeys by remember { mutableStateOf(keys) }
    LaunchedEffect(keys) {
        val gone = focusedKey?.takeIf { it !in keys }
        if (gone != null) {
            val target = keys.getOrNull(previousKeys.indexOf(gone)) ?: keys.lastOrNull()
            val candidates = listOfNotNull(target?.let { rowFocus[it] }, first.takeIf { empty }, titleFocus)
            focusedKey = null
            previousKeys = keys
            repeat(FOCUS_TRIES) {
                if (candidates.any { runCatching { it.requestFocus() }.getOrDefault(false) }) return@LaunchedEffect
                delay(FOCUS_RETRY_MS)
            }
            return@LaunchedEffect
        }
        previousKeys = keys
    }
    // Back from the player: the row it was started from takes focus again.
    // Not while a menu or prompt is open: that window holds focus.
    val dialogOpen = ask != null || ui.removePrompt != null || ui.peerListing != null
    LifecycleResumeEffect(keys, dialogOpen) {
        if (!dialogOpen) focusedKey?.takeIf { it in keys }?.let { k -> runCatching { rowFocus[k]?.requestFocus() } }
        onPauseOrDispose { }
    }
    // Requesters of rows that are gone are dropped.
    LaunchedEffect(keys) { rowFocus.keys.retainAll(keys.toSet()) }

    Column(Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                RECORDINGS_TITLE, style = MaterialTheme.typography.headlineMedium, color = Tokens.OnBackground,
                modifier = Modifier.focusRequester(titleFocus).focusable(),
            )
            ui.freeText?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Tokens.Muted) }
        }
        ui.message?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Tokens.Error, modifier = Modifier.padding(top = 8.dp)) }
        if (empty && ui.loaded) {
            EmptyState(ui.storageMissing, onSetUpStorage, first)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            val firstKey = ui.sections.firstOrNull()?.rows?.firstOrNull()?.key
            for (section in ui.sections) {
                item(key = "head:${section.kind}") { SectionTitle(section.title) }
                items(section.rows, key = { it.key }) { row ->
                    RowItem(
                        row = row,
                        modifier = (if (row.key == firstKey) Modifier.focusRequester(first) else Modifier)
                            .focusRequester(rowFocus.requesterFor(row.key)),
                        onFocus = { focusedKey = row.key },
                        onClick = {
                            val reminder = row.reminder
                            val recording = row.recording
                            when {
                                reminder != null -> vm.openGuide(GuideAt(reminder.playlistId, reminder.channelKey, reminder.start.toEpochMilli()))
                                recording != null && playable(recording) -> vm.play(recording.id)
                                else -> ask = Ask.Menu(row)
                            }
                        },
                        onLongClick = { ask = Ask.Menu(row) },
                    )
                }
            }
            if (ui.otherTvs.isNotEmpty()) {
                item(key = "head:tvs") { SectionTitle(OTHER_TVS_TITLE) }
                items(ui.otherTvs, key = { "tv:${it.peer.id}" }) { tv ->
                    val takesFocus = empty && !ui.storageMissing && tv === ui.otherTvs.first()
                    OtherTvItem(tv, if (takesFocus) Modifier.focusRequester(first) else Modifier, onClick = { vm.openPeer(tv.peer) })
                }
            }
        }
    }

    ShowDialogs(ask, { ask = it }, ui, vm)
}

@Composable
private fun ShowDialogs(ask: Ask?, setAsk: (Ask?) -> Unit, ui: RecordingsUi, vm: RecordingsViewModel) {
    val now = ui.now
    when (ask) {
        null -> Unit
        is Ask.Menu -> {
            val row = ask.row
            val actions = rowActions(row, now, canCatchUp = row.recording?.id in ui.catchUp)
            ChannelOptionsDialog(
                title = row.title,
                options = actions.map { a -> ChannelOption(a.label) { runAction(a, row, vm, setAsk) } },
                onDismiss = { setAsk(null) },
            )
        }
        is Ask.StartEarly -> ChannelOptionsDialog(
            title = START_EARLY,
            options = START_EARLY_CHOICES.map { m -> ChannelOption(minutesText(m)) { setAsk(Ask.EndLate(ask.row, m)) } },
            onDismiss = { setAsk(null) },
        )
        is Ask.EndLate -> ChannelOptionsDialog(
            title = END_LATE,
            options = END_LATE_CHOICES.map { m ->
                ChannelOption(minutesText(m)) {
                    ask.row.recording?.let { vm.editPadding(it.id, ask.before, m) }
                    setAsk(null)
                }
            },
            onDismiss = { setAsk(null) },
        )
        is Ask.KeepLast -> ChannelOptionsDialog(
            title = RowAction.KEEP_LAST.label,
            options = listOf(ChannelOption(KEEP_ALL) { keepLast(ask.row, null, vm, setAsk) }) +
                KEEP_LAST_CHOICES.map { n -> ChannelOption("Last $n") { keepLast(ask.row, n, vm, setAsk) } },
            onDismiss = { setAsk(null) },
        )
        // Read when asked for, so the list reflects the playlists as they are now.
        is Ask.LoadSource -> LaunchedEffect(ask) {
            setAsk(Ask.Source(ask.row, ask.row.recording?.let { vm.sourceChoices(it.id) }.orEmpty()))
        }
        is Ask.Source -> ChannelOptionsDialog(
            title = RowAction.CHANGE_SOURCE.label,
            options = listOf(ChannelOption(OWN_CHANNEL) { changeSource(ask.row, null, vm, setAsk) }) +
                ask.choices.map { c -> ChannelOption(noteFrom(c.playlistName)) { changeSource(ask.row, c.playlistId, vm, setAsk) } },
            onDismiss = { setAsk(null) },
        )
    }
    ui.removePrompt?.let {
        ChannelOptionsDialog(
            title = DELETE_FAILED_PROMPT,
            options = listOf(
                ChannelOption(REMOVE_FROM_LIST) { vm.removeFromList(it) },
                ChannelOption(CANCEL) { vm.dismissRemovePrompt() },
            ),
            onDismiss = vm::dismissRemovePrompt,
        )
    }
    ui.peerListing?.let { listing ->
        ChannelOptionsDialog(
            title = listing.peer.name,
            options = listing.items.map { item ->
                ChannelOption("${item.title} · ${item.channel}") { vm.closePeer(); vm.playRemote(listing.peer, item) }
            },
            onDismiss = vm::closePeer,
        )
    }
}

private fun runAction(action: RowAction, row: RecordingRow, vm: RecordingsViewModel, setAsk: (Ask?) -> Unit) {
    val recording = row.recording
    val reminder = row.reminder
    setAsk(null)
    when (action) {
        RowAction.PLAY -> recording?.let { vm.play(it.id) }
        RowAction.DELETE -> recording?.let { vm.delete(it.id) }
        RowAction.RETRY -> recording?.let { vm.retry(it.id, fromArchive = false) }
        RowAction.RECORD_FROM_CATCH_UP -> recording?.let { vm.retry(it.id, fromArchive = true) }
        RowAction.EDIT_PADDING -> setAsk(Ask.StartEarly(row))
        RowAction.CANCEL_RECORDING -> recording?.let { vm.cancelRecording(it.id) }
        RowAction.CANCEL_SERIES -> recording?.seriesId?.let(vm::cancelSeries)
        RowAction.KEEP_LAST -> setAsk(Ask.KeepLast(row))
        RowAction.CHANGE_SOURCE -> setAsk(Ask.LoadSource(row))
        RowAction.CANCEL_REMINDER -> reminder?.let { vm.cancelReminder(it.id) }
        RowAction.CANCEL_SERIES_REMINDER -> reminder?.seriesId?.let(vm::cancelSeries)
    }
}

private fun keepLast(row: RecordingRow, n: Int?, vm: RecordingsViewModel, setAsk: (Ask?) -> Unit) {
    row.recording?.seriesId?.let { vm.setKeepLast(it, n) }
    setAsk(null)
}

private fun changeSource(row: RecordingRow, playlistId: Long?, vm: RecordingsViewModel, setAsk: (Ask?) -> Unit) {
    row.recording?.let { vm.changeSource(it.id, playlistId) }
    setAsk(null)
}

private fun minutesText(m: Int) = "$m min"

@Composable
private fun EmptyState(storageMissing: Boolean, onSetUpStorage: () -> Unit, focus: FocusRequester) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(EMPTY_RECORDINGS, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        if (storageMissing) {
            PillButton(
                label = SET_UP_STORAGE,
                selected = true,
                onClick = onSetUpStorage,
                modifier = Modifier.padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, modifier = Modifier.padding(top = 12.dp))
}

private val ROW_SHAPE = RoundedCornerShape(8.dp)

@Composable
private fun RowItem(row: RecordingRow, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val details = buildList {
        add(row.channel)
        add(row.whenText)
        row.sizeText?.let(::add)
    }.joinToString(" · ")
    val status = (listOf(row.stateText).filter { it.isNotEmpty() } + row.notes).joinToString(" · ")
    FocusableRow(modifier, onClick, onLongClick, onFocus) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, color = Tokens.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(details, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.problem) Tokens.Error else Tokens.AccentLight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun OtherTvItem(tv: OtherTv, modifier: Modifier, onClick: () -> Unit) {
    FocusableRow(modifier, onClick, onClick) {
        Text(tv.peer.name, style = MaterialTheme.typography.titleSmall, color = Tokens.OnBackground, modifier = Modifier.weight(1f))
        tv.count?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted) }
    }
}

@Composable
private fun FocusableRow(modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit = {}, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Tokens.Surface, ROW_SHAPE)
            .border(if (focused) 3.dp else 0.dp, if (focused) Tokens.Accent else Color.Transparent, ROW_SHAPE)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .semantics(mergeDescendants = true) { }
            .okPress(onClick, onLongClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        content()
    }
}
