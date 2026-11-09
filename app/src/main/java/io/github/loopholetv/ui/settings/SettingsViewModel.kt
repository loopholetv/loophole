package io.github.loopholetv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.PORTAL_ERROR_COPY
import io.github.loopholetv.data.repo.errorCopy
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.asSetting
import io.github.loopholetv.player.parseUdpProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/** Result of the last "Update playlist" for one playlist (this session). */
sealed interface PlaylistOp {
    data object Idle : PlaylistOp
    data class Updating(val count: Int) : PlaylistOp
    data class Updated(val count: Int) : PlaylistOp
    /** [message] is fixed copy for the failure, never the raw (URL-bearing) import message. */
    data class Failed(val message: String) : PlaylistOp
}

data class PlaylistRowUi(
    val id: Long,
    val name: String,
    val channelCount: Int,
    val op: PlaylistOp = PlaylistOp.Idle,
    val enabled: Boolean = true,
    val userAgent: String? = null,
    val updateHours: Int? = null,
    val updateOnStart: Boolean? = null,
    /** "Updated 9 Nov 14:05", "Updating… 250 channels", an error, … (never a raw import message). */
    val statusText: String = "",
    /** [statusText] reports a failure (this session's or a stored one). */
    val statusIsError: Boolean = false,
    /** "1,360 channels · On · Updated 9 Nov 14:05". */
    val secondaryText: String = "",
) {
    val busy: Boolean get() = op is PlaylistOp.Updating
}

data class SettingsUi(
    val playlists: List<PlaylistRowUi> = emptyList(),
    val epg: List<EpgUi> = emptyList(),
    /** Playlist awaiting delete confirmation. */
    val confirmDelete: PlaylistRowUi? = null,
    val options: AppSettings = AppSettings(),
    val confirmClearEpg: Boolean = false,
    /** True after a confirmed "Clear EPG" has finished. */
    val epgCleared: Boolean = false,
    val updatingAll: Boolean = false,
    /** "Updating <name>… N channels" while "Update all playlists" runs. */
    val updateAllText: String? = null,
    /** Shown under "UDP proxy" after an invalid entry (nothing was saved). */
    val udpProxyError: String? = null,
    /** Added guide link awaiting the "Remove this guide link?" confirmation. */
    val confirmRemoveSource: EpgSourceUi? = null,
    /** False only before the first database read (a restored playlist page must not bounce back). */
    val loaded: Boolean = false,
)

private data class UpdateAll(val playlistId: Long? = null, val count: Int = 0)

private data class Ops(
    val playlist: Map<Long, PlaylistOp> = emptyMap(),
    val epgUpdating: Set<Long> = emptySet(),
    val confirmDelete: Long? = null,
    val confirmClearEpg: Boolean = false,
    val epgCleared: Boolean = false,
    val updateAll: UpdateAll? = null,
    val udpProxyError: String? = null,
    val confirmRemoveSource: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val playlists: Flow<List<PlaylistEntity>>,
    channelCounts: Flow<Map<Long, Int>>,
    epgStatus: (Long) -> Flow<EpgStatusEntity?>,
    /** RefreshCoordinator.refreshPlaylist(chainEpg = false): the guide update after it is the VM's ([updatePlaylist]). */
    private val refreshPlaylist: suspend (Long, (Int) -> Unit) -> ImportResult,
    private val deletePlaylist: suspend (Long) -> Unit,
    private val refreshEpg: suspend (Long, (Int) -> Unit) -> EpgStatusEntity,
    private val stopPlayback: () -> Unit,
    xtreamEpgUrl: (XtreamCredentials) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    /**
     * Where updates, deletes and saves run. The app scope in production, so closing the panel (which
     * clears this ViewModel) doesn't cancel a running update; defaults to [viewModelScope].
     */
    private val workScope: CoroutineScope? = null,
    settings: Flow<AppSettings> = flowOf(AppSettings()),
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit = {},
    private val clearEpg: suspend () -> Unit = {},
    private val rename: suspend (Long, String) -> Unit = { _, _ -> },
    private val setEnabled: suspend (Long, Boolean) -> Unit = { _, _ -> },
    private val setPlaylistUserAgent: suspend (Long, String?) -> Unit = { _, _ -> },
    private val setUpdateOptions: suspend (Long, Int?, Boolean?) -> Unit = { _, _, _ -> },
    /** RefreshCoordinator.refreshAll: it runs each playlist's EPG chain itself. */
    private val refreshAll: suspend ((Long, Int) -> Unit) -> Map<Long, ImportResult> = { emptyMap() },
    epgSources: (Long) -> Flow<List<EpgSourceEntity>> = { flowOf(emptyList()) },
    private val addEpgSource: suspend (Long, String) -> Unit = { _, _ -> },
    private val removeEpgSource: suspend (Long) -> Unit = {},
    private val setUseSourceEpg: suspend (Long, Boolean) -> Unit = { _, _ -> },
    /** The playlist of the channel that is buffering, playing or failed; null when idle. */
    playingPlaylistId: Flow<Long?> = flowOf(null),
) : ViewModel() {
    private val work: CoroutineScope get() = workScope ?: viewModelScope
    private val ops = MutableStateFlow(Ops())
    private val epgBuilder = EpgUiBuilder(xtreamEpgUrl, zone, locale)
    private val rowText = PlaylistRowText(zone, locale)
    private val playing = playingPlaylistId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val entities = playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val perPlaylist: Flow<Map<Long, Pair<EpgStatusEntity?, List<EpgSourceEntity>>>> =
        playlists.map { l -> l.map { it.id } }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> combine(epgStatus(id), epgSources(id)) { s, src -> id to (s to src) } }) { it.toMap() }
        }

    val state: StateFlow<SettingsUi> = combine(playlists, channelCounts, perPlaylist, ops, settings) { list, counts, epg, o, opts ->
        val names = list.associate { it.id to it.name }
        val rows = list.map { rowText.row(it, counts[it.id] ?: 0, o.playlist[it.id] ?: PlaylistOp.Idle) }
        val epgUi = list.map { p -> epgBuilder.build(p, epg[p.id]?.first, epg[p.id]?.second.orEmpty(), p.id in o.epgUpdating) }
        SettingsUi(
            playlists = rows,
            epg = epgUi,
            confirmDelete = rows.firstOrNull { it.id == o.confirmDelete },
            options = opts,
            confirmClearEpg = o.confirmClearEpg,
            epgCleared = o.epgCleared,
            updatingAll = o.updateAll != null,
            updateAllText = o.updateAll?.playlistId?.let { "Updating ${names[it].orEmpty()}… ${channelsGrouped(o.updateAll.count, locale)}" },
            udpProxyError = o.udpProxyError,
            confirmRemoveSource = o.confirmRemoveSource?.let { sid -> epgUi.flatMap { it.sources }.firstOrNull { it.id == sid } },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    fun updatePlaylist(id: Long) {
        if (ops.value.playlist[id] is PlaylistOp.Updating) return
        setOp(id, PlaylistOp.Updating(0))
        work.launch {
            val result = safely { refreshPlaylist(id) { n -> setOp(id, PlaylistOp.Updating(n)) } }
            setOp(id, result.toOp())
            // A manual update always refreshes the guide afterwards, with progress;
            // a turned-off playlist's guide isn't updated.
            val enabled = entities.value.firstOrNull { it.id == id }?.enabled == true
            if (result is ImportResult.Success && state.value.options.epgUpdateOnPlaylistChange && enabled) updateEpg(id)
        }
    }

    /** Settings → Playlists → "Update all playlists": every turned-on playlist, one at a time. */
    fun updateAll() {
        if (ops.value.updateAll != null) return
        ops.update { it.copy(updateAll = UpdateAll()) }
        work.launch {
            val results = try {
                refreshAll { id, n ->
                    ops.update { it.copy(updateAll = UpdateAll(id, n), playlist = it.playlist + (id to PlaylistOp.Updating(n))) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            ops.update { o ->
                // Rows left "Updating" by a playlist the coordinator skipped go back to their stored state.
                val cleared = o.playlist.filterValues { it !is PlaylistOp.Updating }
                o.copy(updateAll = null, playlist = cleared + results.mapValues { (_, r) -> r.toOp() })
            }
        }
    }

    private suspend fun safely(block: suspend () -> ImportResult): ImportResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ImportResult.Failure(ImportError.Unexpected, "")
    }

    private fun ImportResult.toOp(): PlaylistOp = when (this) {
        is ImportResult.Success -> PlaylistOp.Updated(channelCount)
        // A portal's failures carry fixed copy of their own; everything else gets errorCopy.
        is ImportResult.Failure -> PlaylistOp.Failed(message.trim().takeIf { it in PORTAL_ERROR_COPY } ?: errorCopy(error))
    }

    private fun setOp(id: Long, op: PlaylistOp) = ops.update { it.copy(playlist = it.playlist + (id to op)) }

    /** Turning off the playing channel's playlist stops playback first. */
    fun setEnabled(id: Long, enabled: Boolean) {
        if (!enabled && playing.value == id) stopPlayback()
        work.launch { setEnabled.invoke(id, enabled) }
    }

    /** Blank is ignored. */
    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        work.launch { rename.invoke(id, trimmed) }
    }

    /** Blank = the general user agent (stored as null); one a header can't carry is not saved. */
    fun setPlaylistUserAgent(id: Long, ua: String) {
        if (!UserAgents.isAcceptable(ua)) return
        val value = ua.trim().takeIf { it.isNotEmpty() }
        work.launch { setPlaylistUserAgent.invoke(id, value) }
    }

    /** `null` = "Same as default"; keeps the playlist's "Update on app start". */
    fun setPlaylistInterval(id: Long, hours: Int?) {
        val p = entities.value.firstOrNull { it.id == id } ?: return
        work.launch { setUpdateOptions(id, hours, p.updateOnStart) }
    }

    /** `null` = "Same as default"; keeps the playlist's interval. */
    fun setPlaylistOnStart(id: Long, onStart: Boolean?) {
        val p = entities.value.firstOrNull { it.id == id } ?: return
        work.launch { setUpdateOptions(id, p.updateHours, onStart) }
    }

    /** Blank = the built-in default (stored as null); one a header can't carry is not saved. */
    fun setGeneralUserAgent(ua: String) {
        if (!UserAgents.isAcceptable(ua)) return
        val value = ua.trim().takeIf { it.isNotEmpty() }
        setOption { it.copy(userAgent = value) }
    }

    /** Blank clears; anything [parseUdpProxy] rejects shows [UDP_PROXY_INVALID] and saves nothing. */
    fun setUdpProxy(input: String) {
        if (input.isBlank()) {
            ops.update { it.copy(udpProxyError = null) }
            setOption { it.copy(udpProxy = null) }
            return
        }
        val proxy = parseUdpProxy(input)
        if (proxy == null) {
            ops.update { it.copy(udpProxyError = UDP_PROXY_INVALID) }
            return
        }
        ops.update { it.copy(udpProxyError = null) }
        setOption { it.copy(udpProxy = proxy.asSetting()) }
    }

    /** Drops a stale "Use the form …" hint (the proxy dialog is opening again). */
    fun clearUdpProxyError() = ops.update { it.copy(udpProxyError = null) }

    /** Ignored while that playlist is updating. */
    fun requestDelete(id: Long) {
        if (ops.value.playlist[id] is PlaylistOp.Updating) return
        ops.update { it.copy(confirmDelete = id) }
    }

    fun cancelDelete() = ops.update { it.copy(confirmDelete = null) }

    /** Stops playback first: the playing channel may belong to the deleted playlist. */
    fun confirmDelete() {
        val id = ops.value.confirmDelete ?: return
        ops.update { it.copy(confirmDelete = null) }
        stopPlayback()
        work.launch {
            // Leaving for onboarding when the last playlist is gone is an app-level rule (AppNav).
            deletePlaylist(id)
        }
    }

    fun updateEpg(playlistId: Long) {
        if (playlistId in ops.value.epgUpdating) return
        ops.update { it.copy(epgUpdating = it.epgUpdating + playlistId, epgCleared = false) }
        work.launch {
            try {
                refreshEpg(playlistId) { }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The repository stores its own failure status; anything else leaves the old status.
            } finally {
                ops.update { it.copy(epgUpdating = it.epgUpdating - playlistId) }
            }
        }
    }

    /** Appends a guide link (trimmed). Blank does nothing: it never removes an existing source. */
    fun addEpgSource(playlistId: Long, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        work.launch { addEpgSource.invoke(playlistId, trimmed) }
    }

    /** Asks "Remove this guide link?" first; ignored for an unknown source. */
    fun requestRemoveEpgSource(sourceId: Long) {
        if (state.value.epg.none { e -> e.sources.any { it.id == sourceId } }) return
        ops.update { it.copy(confirmRemoveSource = sourceId) }
    }

    fun cancelRemoveEpgSource() = ops.update { it.copy(confirmRemoveSource = null) }

    fun confirmRemoveEpgSource() {
        val sourceId = ops.value.confirmRemoveSource ?: return
        ops.update { it.copy(confirmRemoveSource = null) }
        work.launch { removeEpgSource.invoke(sourceId) }
    }

    /** Whether the playlist's own announced guide is read. */
    fun setUseOwnGuide(playlistId: Long, use: Boolean) {
        work.launch { setUseSourceEpg(playlistId, use) }
    }

    fun setOption(transform: (AppSettings) -> AppSettings) {
        work.launch { updateSettings(transform) }
    }

    fun requestClearEpg() = ops.update { it.copy(confirmClearEpg = true, epgCleared = false) }

    fun cancelClearEpg() = ops.update { it.copy(confirmClearEpg = false) }

    /** Runs on the work scope and waits for any running refresh; the message shows once it returns. */
    fun confirmClearEpg() {
        if (!ops.value.confirmClearEpg) return
        ops.update { it.copy(confirmClearEpg = false) }
        work.launch {
            try {
                clearEpg()
                ops.update { it.copy(epgCleared = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing to show; the guide simply was not cleared.
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                return SettingsViewModel(
                    playlists = c.playlistRepository.playlists(),
                    channelCounts = c.db.channelDao().countsByPlaylist().map { l -> l.associate { it.playlistId to it.count } },
                    epgStatus = c.epgRepository::status,
                    refreshPlaylist = { id, p -> c.refreshCoordinator.refreshPlaylist(id, p, chainEpg = false) },
                    deletePlaylist = { id -> c.playlistRepository.delete(id) },
                    refreshEpg = { id, p -> c.epgRepository.refresh(id, p) },
                    stopPlayback = { c.playback.stop() },
                    xtreamEpgUrl = c.xtream::epgUrl,
                    workScope = app.applicationScope,
                    settings = c.settingsStore.settings,
                    updateSettings = c.settingsStore::update,
                    clearEpg = c.epgRepository::clearGuide,
                    rename = { id, n -> c.playlistRepository.rename(id, n) },
                    setEnabled = { id, on -> c.channelEdits.setPlaylistEnabled(id, on) },
                    setPlaylistUserAgent = { id, ua -> c.playlistRepository.setUserAgent(id, ua) },
                    setUpdateOptions = { id, h, s -> c.playlistRepository.setUpdateOptions(id, h, s) },
                    refreshAll = { p -> c.refreshCoordinator.refreshAll(p) },
                    epgSources = c.epgRepository::sources,
                    addEpgSource = { id, u -> c.epgRepository.addSource(id, u) },
                    removeEpgSource = { sid -> c.epgRepository.removeSource(sid) },
                    setUseSourceEpg = { id, use -> c.epgRepository.setUseSourceEpg(id, use) },
                    playingPlaylistId = c.playback.state
                        .map { it.channelId() }
                        .distinctUntilChanged()
                        .map { id -> id?.let { playlistOfChannel(it) { cid -> c.db.channelDao().byId(cid)?.playlistId } } },
                ) as T
            }
        }
    }
}

private fun PlaybackState.channelId(): Long? = when (this) {
    PlaybackState.Idle -> null
    is PlaybackState.Buffering -> channelId
    is PlaybackState.Playing -> channelId
    is PlaybackState.Error -> channelId
}

/** A failing lookup counts as unknown (playback is then left to the app-level watcher). */
private suspend fun playlistOfChannel(channelId: Long, lookUp: suspend (Long) -> Long?): Long? = try {
    lookUp(channelId)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
