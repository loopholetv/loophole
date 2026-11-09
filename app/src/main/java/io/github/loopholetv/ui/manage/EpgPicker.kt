package io.github.loopholetv.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.EpgChannelEntity
import io.github.loopholetv.ui.common.DialogCard
import io.github.loopholetv.ui.common.DialogTitle
import io.github.loopholetv.ui.common.TypingField
import io.github.loopholetv.ui.common.rememberTypingState
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val EPG_PICKER_DEBOUNCE_MS = 300L

/** Most guide channels listed at once; the filter narrows a longer list. */
const val EPG_PICKER_LIMIT = 200

private const val MAX_FILTER_LENGTH = 100

/** Focuses [requester], retrying for a few frames while the dialog lays it out. */
private suspend fun focusWhenReady(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        delay(50)
    }
}

const val EPG_PICKER_TITLE = "Choose TV guide"
const val EPG_PICKER_AUTOMATIC = "Automatic"

/** Empty state: this playlist's EPG sources have listed no guide channels yet. */
const val EPG_PICKER_EMPTY = "No guide channels yet. Update the EPG first."
const val EPG_PICKER_NO_MATCH = "No guide channels match."

/**
 * The `LIKE` pattern (with `ESCAPE '\'`) for the picker's filter, or null (list everything) when
 * the trimmed filter is empty. Unlike search, one character already filters.
 */
internal fun epgPickerPattern(query: String): String? {
    val q = query.trim()
    if (q.isEmpty()) return null
    return buildString(q.length + 8) {
        append('%')
        for (ch in q) {
            if (ch == '\\' || ch == '%' || ch == '_') append('\\')
            append(ch)
        }
        append('%')
    }
}

/**
 * The guide-channel list behind "Choose TV guide": [search] gets [epgPickerPattern] of the filter
 * (null = first rows). The first list loads at once; later filters wait [EPG_PICKER_DEBOUNCE_MS]
 * and the latest one wins. At most [EPG_PICKER_LIMIT] rows. Runs in [scope] (the dialog's).
 */
class EpgPickerViewModel(
    private val scope: CoroutineScope,
    private val search: suspend (pattern: String?) -> List<EpgChannelEntity>,
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<EpgChannelEntity>>(emptyList())
    val results: StateFlow<List<EpgChannelEntity>> = _results.asStateFlow()

    private val _resultsQuery = MutableStateFlow("")

    /** The filter [results] were read for (lags [query] by the debounce). */
    val resultsQuery: StateFlow<String> = _resultsQuery.asStateFlow()

    /** False until the first list has been read (so the empty state doesn't flash). */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var job: Job? = null

    init { load("", debounce = false) }

    fun setQuery(q: String) {
        if (q == _query.value) return
        _query.value = q
        load(q, debounce = true)
    }

    private fun load(q: String, debounce: Boolean) {
        job?.cancel()
        job = scope.launch {
            if (debounce) delay(EPG_PICKER_DEBOUNCE_MS)
            val rows = try {
                search(epgPickerPattern(q)).take(EPG_PICKER_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // A superseded load is cancelled before it gets here; the check covers a search that
            // ignored cancellation.
            if (_query.value == q) {
                _results.value = rows
                _resultsQuery.value = q
                _loaded.value = true
            }
        }
    }
}

/**
 * "Choose TV guide" for one channel of [playlistId]: a filter field, "Automatic" (→ null:
 * undo the manual choice), then the playlist's guide channels (name, id underneath). [current] is
 * the manual guide id, marked ✓. [onPick] gets the chosen id; Back calls [onDismiss].
 */
@Composable
fun EpgPickerDialog(playlistId: Long, current: String?, onPick: (epgId: String?) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as LoopholeApp
    val scope = rememberCoroutineScope()
    val vm = remember(playlistId) {
        val dao = app.container.db.epgChannelDao()
        EpgPickerViewModel(scope) { p -> dao.search(playlistId, p ?: "%", EPG_PICKER_LIMIT) }
    }
    EpgPickerContent(vm, current, onPick, onDismiss)
}

@Composable
private fun EpgPickerContent(
    vm: EpgPickerViewModel,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val resultsQuery by vm.resultsQuery.collectAsStateWithLifecycle()
    val typing = rememberTypingState()
    val automatic = remember { FocusRequester() }
    val currentRow = remember { FocusRequester() }
    var picked by remember { mutableStateOf(false) }
    val pick: (String?) -> Unit = { id -> typing.stop(); if (!picked) { picked = true; onPick(id) } }
    val list = rememberLazyListState()
    // Focus starts on the current manual choice when the first list has it, else on "Automatic".
    val currentIndex = if (current == null) -1 else results.indexOfFirst { it.epgId == current }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        if (!loaded || placed) return@LaunchedEffect
        placed = true
        if (currentIndex >= 0) {
            list.scrollToItem(currentIndex + 1)
            focusWhenReady(currentRow)
        } else {
            focusWhenReady(automatic)
        }
    }
    DialogCard(onDismiss = { typing.stop(); onDismiss() }, width = 560.dp) {
        DialogTitle(EPG_PICKER_TITLE)
        TypingField(
            state = typing,
            label = "Filter",
            value = query,
            onChange = { vm.setQuery(it.take(MAX_FILTER_LENGTH)) },
            keyboardType = KeyboardType.Text,
        )
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 380.dp)) {
            item(key = "automatic") {
                PickRow(
                    headline = EPG_PICKER_AUTOMATIC,
                    trailing = if (current == null) "✓" else null,
                    modifier = Modifier.focusRequester(automatic),
                    onClick = { pick(null) },
                )
            }
            if (loaded && results.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (resultsQuery.isBlank()) EPG_PICKER_EMPTY else EPG_PICKER_NO_MATCH,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.Muted,
                    )
                }
            }
            items(results, key = { it.epgId }) { row ->
                PickRow(
                    headline = row.displayName,
                    supporting = row.epgId,
                    trailing = if (row.epgId == current) "✓" else null,
                    modifier = if (row.epgId == current) Modifier.focusRequester(currentRow) else Modifier,
                    onClick = { pick(row.epgId) },
                )
            }
        }
    }
}
