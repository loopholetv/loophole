package io.github.loopholetv.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.EditorChannelRow
import io.github.loopholetv.data.db.EditorGroupRow
import io.github.loopholetv.data.db.GroupSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val TAG_HIDDEN = "Hidden"
const val TAG_RENAMED = "Renamed"

/** The editor's trailing tags: "Hidden" and/or "Renamed". */
fun editorTags(hidden: Boolean, renamed: Boolean): List<String> = buildList {
    if (hidden) add(TAG_HIDDEN)
    if (renamed) add(TAG_RENAMED)
}

/** "Hidden" is the channel's own edit, not `visible` (which a hidden group also clears). */
val EditorChannelRow.tags: List<String> get() = editorTags(hidden, channel.customName != null)

/**
 * Where to scroll so the moved row at [index] stays fully visible, given the first and last fully
 * visible indexes (null: nothing laid out yet). Null when no scroll is needed.
 */
fun keepInViewScrollTarget(index: Int, firstFully: Int?, lastFully: Int?): Int? {
    if (firstFully == null || lastFully == null) return null
    return when {
        index < firstFully -> index
        index > lastFully -> (index - (lastFully - firstFully + 1) + 1).coerceAtLeast(0)
        else -> null
    }
}

/** One group row of the editor (hidden groups included), in the DAO's list order. */
data class EditorGroupItem(
    val title: String,
    val label: String,
    val hidden: Boolean,
    val renamed: Boolean,
    val count: Int,
    val sort: GroupSort,
) {
    val tags: List<String> get() = editorTags(hidden, renamed)
    val tagText: String? get() = tags.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * Settings → Playlists → (playlist) → "Groups and channels". [groups] is
 * `ChannelDao.editorGroups` (list order, never `order`), [channels] `editorChannels` of one group.
 * Dialog edits go through [actions]; moves are serialised so quick presses apply in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageViewModel(
    val playlistId: Long,
    groups: Flow<List<EditorGroupRow>>,
    private val channels: (title: String) -> Flow<PagingData<EditorChannelRow>>,
    private val edits: EditActions,
    refreshEpg: (playlistId: Long) -> Unit,
) : ViewModel() {
    val groups: StateFlow<List<EditorGroupItem>> = groups
        .map { rows -> rows.map { EditorGroupItem(it.title, it.label, it.hidden, it.renamed, it.count, it.sortMode) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openGroup = MutableStateFlow<String?>(null)

    /** The original title of the group whose page is open, or null on the groups page. */
    val openGroup: StateFlow<String?> = _openGroup.asStateFlow()

    val groupChannels: Flow<PagingData<EditorChannelRow>> = _openGroup
        .filterNotNull()
        // Start each group empty: the cached list would otherwise replay the previous group first.
        .flatMapLatest { channels(it).onStart { emit(PagingData.empty()) } }
        .cachedIn(viewModelScope)

    val actions = ChannelEditActions(edits, viewModelScope, refreshEpg)

    private val moveLock = Mutex()

    fun open(title: String) { _openGroup.value = title }
    fun close() { _openGroup.value = null }

    fun moveGroup(title: String, direction: Int) = move { edits.moveGroup(playlistId, title, direction) }
    fun moveChannel(channel: ChannelEntity, direction: Int) = move { edits.move(channel, direction) }

    private fun move(block: suspend () -> Unit) {
        viewModelScope.launch {
            moveLock.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The row simply stays where it is.
                }
            }
        }
    }

    companion object {
        /** Same as the guide's: placeholders keep indexes stable while a move reloads the list. */
        private val EDITOR_PAGING = PagingConfig(pageSize = 60, prefetchDistance = 60, initialLoadSize = 120)

        fun factory(playlistId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                val dao = c.db.channelDao()
                return ManageViewModel(
                    playlistId = playlistId,
                    groups = dao.editorGroups(playlistId),
                    channels = { title ->
                        Pager(EDITOR_PAGING) {
                            dao.editorChannels(playlistId, title)
                        }.flow
                    },
                    edits = c.channelEdits.asEditActions(),
                    refreshEpg = { id -> app.applicationScope.launch { c.refreshCoordinator.refreshEpg(id) } },
                ) as T
            }
        }
    }
}
