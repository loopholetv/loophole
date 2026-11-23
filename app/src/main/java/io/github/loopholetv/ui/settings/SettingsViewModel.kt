package io.github.loopholetv.ui.settings

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
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
import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.KeyBindings
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.data.settings.defaultTvName
import io.github.loopholetv.data.settings.effectivePastDays
import io.github.loopholetv.data.settings.isRemappable
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.tmdb.KeyStatus
import io.github.loopholetv.data.xtream.XTREAM_FORMAT_HLS
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.phone.PhoneJob
import io.github.loopholetv.phone.PhoneJobPhase
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.asSetting
import io.github.loopholetv.player.parseUdpProxy
import io.github.loopholetv.record.storage.LocalDirStorage
import io.github.loopholetv.record.storage.RecordingStorage
import io.github.loopholetv.record.storage.StorageKind
import io.github.loopholetv.record.storage.StorageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import io.github.loopholetv.update.UpdateAction
import io.github.loopholetv.update.UpdateCardUi
import io.github.loopholetv.update.UpdateStatus
import io.github.loopholetv.update.cardFor
import kotlinx.coroutines.job
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
    /** The streams this TV may open at once on the provider; null = Auto. */
    val connectionsAllowed: Int? = null,
    /** What Auto means for this playlist: the account's limit, or one. */
    val autoConnections: Int = 1,
    /** Xtream playlists only: how live streams are fetched; null = MPEG-TS, "hls". */
    val xtreamFormat: String? = null,
    val isXtream: Boolean = false,
    /** Read-only "Account" / "Expires" / "Connections" label and value pairs, only for what the provider told us. */
    val accountRows: List<Pair<String, String>> = emptyList(),
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
    /** Days the "Automatic" past-guide setting resolves to (the longest archive on offer, at most a week). */
    val automaticPastDays: Int = 0,
    /** False only before the first database read (a restored playlist page must not bounce back). */
    val loaded: Boolean = false,
    /** This TV's name for casting: the stored name, or the device default. */
    val tvName: String = "",
    /** The update found by "Check for updates" and its next step; null when none is on offer. */
    val updateCard: UpdateCardUi? = null,
    /** Phones paired with the phone page. */
    val pairedPhones: Int = 0,
    /** Shown on "Forget paired phones" after forgetting them failed; null otherwise. */
    val forgetPhonesError: String? = null,
    /** "Test" on the Recordings page. */
    val storageTest: StorageTestUi = StorageTestUi(),
    /** Free space of the chosen storage; null when unknown or none is chosen. */
    val freeBytes: Long? = null,
    /** Free space of internal storage, shown beside its choice. */
    val internalFreeBytes: Long? = null,
    val permissions: PermissionStates = PermissionStates(),
    /** The TMDB key's check, shown on "Film and show details". */
    val tmdbStatus: KeyStatus = KeyStatus.NONE,
)

/** The last "Test" of the chosen storage: [text] is "Works" or the storage's own plain-words message. */
data class StorageTestUi(val running: Boolean = false, val text: String? = null, val failed: Boolean = false)

/** What the two system permissions are now. */
data class PermissionStates(val overlay: Boolean = false, val batteryUnrestricted: Boolean = false)

/** The network folder form of an SMB share. */
data class SmbForm(val host: String, val share: String, val folder: String, val user: String, val password: String, val domain: String) {
    override fun toString() = "SmbForm(host=$host, share=$share, folder=$folder)"
}

data class NfsForm(val host: String, val export: String, val folder: String)

fun AppSettings.smbForm() = SmbForm(smbHost, smbShare, smbFolder, smbUser, smbPassword, smbDomain)

fun AppSettings.nfsForm() = NfsForm(nfsHost, nfsExport, nfsFolder)

const val FORGET_PHONES_FAILED = "Couldn't forget paired phones. Try again."

/** "2 phones paired", "1 phone paired", "No phones paired". */
fun pairedPhonesText(count: Int): String = when (count) {
    0 -> "No phones paired"
    1 -> "1 phone paired"
    else -> "$count phones paired"
}

private data class PhoneState(val job: PhoneJob? = null, val paired: Int = 0)

private data class UpdateAll(val playlistId: Long? = null, val count: Int = 0)

private data class Ops(
    val updateCard: UpdateCardUi? = null,
    val playlist: Map<Long, PlaylistOp> = emptyMap(),
    val epgUpdating: Set<Long> = emptySet(),
    val confirmDelete: Long? = null,
    val confirmClearEpg: Boolean = false,
    val epgCleared: Boolean = false,
    val updateAll: UpdateAll? = null,
    val udpProxyError: String? = null,
    val confirmRemoveSource: Long? = null,
    val maxCatchupDays: Int? = null,
    val forgetPhonesFailed: Boolean = false,
    val storageTest: StorageTestUi = StorageTestUi(),
    val freeBytes: Long? = null,
    val internalFreeBytes: Long? = null,
    val permissions: PermissionStates = PermissionStates(),
    val tmdbStatus: KeyStatus = KeyStatus.NONE,
)

/** Films and shows a playlist holds. */
data class VodCounts(val films: Int = 0, val shows: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val playlists: Flow<List<PlaylistEntity>>,
    channelCounts: Flow<Map<Long, Int>>,
    /** Films and shows per playlist (absent = none); shown beside the channel count. */
    vodCounts: Flow<Map<Long, VodCounts>> = flowOf(emptyMap()),
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
    /** The same manual update check as About's. */
    private val checkForUpdatesNow: () -> Unit = {},
    /** The update offered now, as the card on the guide shows it. */
    updateStatus: Flow<UpdateStatus> = flowOf(UpdateStatus.None),
    private val performUpdateAction: (UpdateAction) -> Unit = {},
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
    /** Whether this device can show picture-in-picture; options for it are hidden otherwise. */
    val pipSupported: Boolean = false,
    /** The longest archive any channel offers, in days; null when none does. */
    private val maxCatchupDays: suspend () -> Int? = { null },
    /** The name used when none is set. */
    private val defaultTvName: String = "Loophole TV",
    /** The playlist load started from the phone (running, or just finished); its row shows it. */
    phoneJob: Flow<PhoneJob?> = flowOf(null),
    pairedPhones: Flow<Int> = flowOf(0),
    private val forgetPairedPhones: suspend () -> Unit = {},
    /** The storage the settings choose, or null when none is set up. */
    private val storageFor: (AppSettings) -> RecordingStorage? = { null },
    private val internalFreeBytes: suspend () -> Long? = { null },
    /** Reads the overlay and battery permissions now. */
    private val permissions: () -> PermissionStates = { PermissionStates() },
    private val setConnectionsAllowed: suspend (Long, Int?) -> Unit = { _, _ -> },
    private val setXtreamFormat: suspend (Long, String?) -> Unit = { _, _ -> },
    tmdbStatus: Flow<KeyStatus> = flowOf(KeyStatus.NONE),
    /** Stores and checks the TMDB key; blank removes it. */
    private val saveTmdbKey: suspend (String) -> Unit = {},
    private val removeTmdbKey: suspend () -> Unit = {},
    private val recheckTmdbKey: suspend () -> Unit = {},
) : ViewModel() {
    private val settingsFlow = settings
    private val work: CoroutineScope get() = workScope ?: viewModelScope

    /** The storage Test running, if any. */
    private var testJob: Job? = null

    /** The free-space read running, if any. */
    private var infoJob: Job? = null
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

    private val counts = combine(channelCounts, vodCounts) { channels, vod -> channels to vod }

    private val phone = combine(phoneJob, pairedPhones) { job, paired -> PhoneState(job, paired) }

    private val opsAndPhone = combine(ops, phone) { o, p -> o to p }

    val state: StateFlow<SettingsUi> = combine(playlists, counts, perPlaylist, opsAndPhone, settings) { list, (channels, vod), epg, (o, phoneNow), opts ->
        val names = list.associate { it.id to it.name }
        val rows = list.map {
            val v = vod[it.id] ?: VodCounts()
            val count = channels[it.id] ?: 0
            rowText.row(it, count, rowOp(o.playlist[it.id], phoneNow.job, it.id, count), v.films, v.shows)
        }
        val epgUi = list.map { p -> epgBuilder.build(p, epg[p.id]?.first, epg[p.id]?.second.orEmpty(), p.id in o.epgUpdating) }
        SettingsUi(
            playlists = rows,
            epg = epgUi,
            confirmDelete = rows.firstOrNull { it.id == o.confirmDelete },
            options = opts,
            confirmClearEpg = o.confirmClearEpg,
            epgCleared = o.epgCleared,
            updatingAll = o.updateAll != null,
            updateAllText = o.updateAll?.playlistId?.let { "Updating ${names[it].orEmpty()}… ${contentCounts(o.updateAll.count, vod[it]?.films ?: 0, vod[it]?.shows ?: 0, locale)}" },
            udpProxyError = o.udpProxyError,
            confirmRemoveSource = o.confirmRemoveSource?.let { sid -> epgUi.flatMap { it.sources }.firstOrNull { it.id == sid } },
            automaticPastDays = effectivePastDays(AUTO_PAST_DAYS, o.maxCatchupDays),
            loaded = true,
            tvName = opts.castTvName ?: defaultTvName,
            updateCard = o.updateCard,
            pairedPhones = phoneNow.paired,
            forgetPhonesError = if (o.forgetPhonesFailed) FORGET_PHONES_FAILED else null,
            storageTest = o.storageTest,
            freeBytes = o.freeBytes,
            internalFreeBytes = o.internalFreeBytes,
            permissions = o.permissions,
            tmdbStatus = o.tmdbStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    /** The row's own update wins; otherwise a phone load running on [id] shows as updating. */
    private fun rowOp(own: PlaylistOp?, job: PhoneJob?, id: Long, channelCount: Int): PlaylistOp {
        if (own is PlaylistOp.Updating || job?.playlistId != id) return own ?: PlaylistOp.Idle
        return when (val phase = job.phase) {
            is PhoneJobPhase.LoadingChannels -> PlaylistOp.Updating(phase.count)
            // The channels are in: the guide follows as part of the same load.
            is PhoneJobPhase.LoadingGuide -> PlaylistOp.Updating(channelCount)
            is PhoneJobPhase.Done, is PhoneJobPhase.Failed -> own ?: PlaylistOp.Idle
        }
    }

    init {
        viewModelScope.launch {
            updateStatus.collect { st -> ops.update { it.copy(updateCard = cardFor(st)) } }
        }
        viewModelScope.launch {
            tmdbStatus.collect { st -> ops.update { it.copy(tmdbStatus = st) } }
        }
        viewModelScope.launch {
            val days = try {
                maxCatchupDays()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            ops.update { it.copy(maxCatchupDays = days) }
        }
        refreshPermissions()
        refreshStorageInfo()
    }

    /** Reads the overlay and battery permissions again (the page is on screen again). */
    fun refreshPermissions() {
        val now = try {
            permissions()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PermissionStates()
        }
        ops.update { it.copy(permissions = now) }
    }

    /** Reads the free space of the chosen storage and of internal storage; a newer read replaces one still running. */
    fun refreshStorageInfo() {
        infoJob?.cancel()
        infoJob = work.launch {
            val internal = safeBytes { internalFreeBytes() }
            val chosen = storageFor(settingsFlow.first())
            val free = chosen?.let { s -> safeBytes { s.freeBytes() } }
            ops.update { it.copy(internalFreeBytes = internal, freeBytes = free) }
        }
    }

    private suspend fun safeBytes(read: suspend () -> Long?): Long? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Writes, reads back and deletes a test file in the chosen storage; the result shows under "Test". */
    fun testStorage() {
        if (ops.value.storageTest.running || testJob?.isActive == true) return
        ops.update { it.copy(storageTest = StorageTestUi(running = true)) }
        testJob = work.launch {
            val me = coroutineContext.job
            try {
                val tested = settingsFlow.first()
                val storage = storageFor(tested)
                val result = when {
                    storage == null -> StorageTestUi(text = RC_NOT_SET_HINT, failed = true)
                    else -> try {
                        when (val r = storage.test()) {
                            StorageResult.Ok -> StorageTestUi(text = RC_TEST_WORKS)
                            is StorageResult.Failed -> StorageTestUi(text = r.message, failed = true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Never the exception's own text: it may carry a user name.
                        StorageTestUi(text = RC_TEST_FAILED, failed = true)
                    }
                }
                val free = storage?.let { s -> safeBytes { s.freeBytes() } }
                // The storage changed while it ran: the result is about a storage no longer chosen.
                if (storageOf(settingsFlow.first()) == storageOf(tested)) {
                    ops.update { it.copy(storageTest = result, freeBytes = free ?: it.freeBytes) }
                }
            } finally {
                // However it ended, Test can be pressed again; a newer test's own state is left alone.
                if (testJob == null || testJob === me) {
                    ops.update { o -> if (o.storageTest.running) o.copy(storageTest = o.storageTest.copy(running = false)) else o }
                }
            }
        }
    }

    /** What decides where recordings go: a change to any of it makes a test result stale. */
    private fun storageOf(s: AppSettings) = listOf(
        s.recordStorage, s.smbHost, s.smbShare, s.smbFolder, s.smbUser, s.smbPassword, s.smbDomain,
        s.nfsHost, s.nfsExport, s.nfsFolder, s.usbTreeUri,
    )

    /** A changed storage setting: the old test result no longer applies, and the free space is read again. */
    private fun changeStorage(transform: (AppSettings) -> AppSettings) {
        testJob?.cancel()
        testJob = null
        ops.update { it.copy(storageTest = StorageTestUi(), freeBytes = null) }
        work.launch {
            updateSettings(transform)
            refreshStorageInfo()
        }
    }

    fun setStorageKind(kind: StorageKind) = changeStorage { it.copy(recordStorage = kind) }

    /** Chooses the network folder and saves its form (trimmed; the password as typed). */
    fun saveSmb(f: SmbForm) = changeStorage {
        it.copy(
            recordStorage = StorageKind.SMB, smbHost = f.host.trim(), smbShare = f.share.trim(), smbFolder = f.folder.trim(),
            smbUser = f.user.trim(), smbPassword = f.password, smbDomain = f.domain.trim(),
        )
    }

    fun saveNfs(f: NfsForm) = changeStorage {
        it.copy(recordStorage = StorageKind.NFS, nfsHost = f.host.trim(), nfsExport = f.export.trim(), nfsFolder = f.folder.trim())
    }

    /** The picked drive folder; it becomes the place to record. */
    fun setUsbTree(uri: String) = changeStorage { it.copy(recordStorage = StorageKind.USB, usbTreeUri = uri) }

    /** `null` = Auto; one to five otherwise. */
    fun setConnectionsAllowed(id: Long, n: Int?) {
        if (n != null && n !in 1..5) return
        work.launch { setConnectionsAllowed.invoke(id, n) }
    }

    /** `null` = MPEG-TS, "hls" otherwise; takes effect the next time a channel opens. */
    fun setXtreamFormat(id: Long, format: String?) {
        if (format != null && format != XTREAM_FORMAT_HLS) return
        work.launch { setXtreamFormat.invoke(id, format) }
    }

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
        // A copied setup is replacing the playlists: no error, the row shows what is stored.
        ImportResult.Stopped -> PlaylistOp.Idle
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

    /** On the work scope, so closing the panel doesn't stop the check. */
    fun saveTmdbKey(raw: String) = runQuietly { saveTmdbKey.invoke(raw) }

    fun removeTmdbKey() = runQuietly { removeTmdbKey.invoke() }

    /** "Film and show details" opened with a key set. */
    fun recheckTmdbKey() = runQuietly { recheckTmdbKey.invoke() }

    /** A failure leaves the status as it was; nothing about the key is shown or logged. */
    private fun runQuietly(block: suspend () -> Unit) {
        work.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The status row keeps its last value.
            }
        }
    }

    /** Blank goes back to the device name. */
    fun setTvName(name: String) = setOption { it.copy(castTvName = name.trim().takeIf { n -> n.isNotEmpty() }) }

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

    /** Ignored while that playlist is updating (here or from the phone). */
    fun requestDelete(id: Long) {
        if (ops.value.playlist[id] is PlaylistOp.Updating || state.value.playlists.firstOrNull { it.id == id }?.busy == true) return
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

    /** Changes only the player settings. */
    fun setPlayback(transform: (PlaybackSettings) -> PlaybackSettings) =
        setOption { it.copy(playback = transform(it.playback)) }

    /** Changes only the remote-control settings. */
    fun setRemote(transform: (RemoteSettings) -> RemoteSettings) =
        setOption { it.copy(remote = transform(it.remote)) }

    private val resetBindingsPrompt = MutableStateFlow(false)

    /** True while "Reset all buttons to how they started?" is waiting for an answer. */
    val resetPrompt: StateFlow<Boolean> = resetBindingsPrompt

    /**
     * Whether [code] may be set up as a button; false (with nothing changed) for arrows, OK, Back,
     * Home, Menu, number keys and the other buttons that keep their fixed job.
     */
    fun captureKey(@Suppress("UNUSED_PARAMETER") context: BindingContext, code: Int): Boolean = isRemappable(code)

    /**
     * Sets what [code] does while a video plays. `Nothing` on a button that has a built-in job keeps
     * it as an override that switches the job off; on any other button it removes the entry.
     */
    fun setBinding(code: Int, action: PlayerAction) = setRemote { r ->
        val player = r.bindings.player
        val next = if (action == PlayerAction.NONE && code !in KeyBindings.DEFAULT.player) player - code else player + (code to action)
        r.copy(bindings = r.bindings.copy(player = next))
    }

    /** Same as the player overload, for the TV guide. */
    fun setBinding(code: Int, action: GuideAction) = setRemote { r ->
        val guide = r.bindings.guide
        val next = if (action == GuideAction.NONE && code !in KeyBindings.DEFAULT.guide) guide - code else guide + (code to action)
        r.copy(bindings = r.bindings.copy(guide = next))
    }

    fun requestResetBindings() { resetBindingsPrompt.value = true }

    fun cancelResetBindings() { resetBindingsPrompt.value = false }

    fun confirmResetBindings() {
        if (!resetBindingsPrompt.value) return
        resetBindingsPrompt.value = false
        setRemote { it.copy(bindings = KeyBindings.DEFAULT) }
    }

    private val forgetPrompt = MutableStateFlow(false)

    /** True while "Forget all paired phones?" is waiting for an answer. */
    val forgetPhonesPrompt: StateFlow<Boolean> = forgetPrompt

    /** Ignored with no phone paired. */
    fun requestForgetPhones() {
        if (state.value.pairedPhones > 0) forgetPrompt.value = true
    }

    fun cancelForgetPhones() { forgetPrompt.value = false }

    fun confirmForgetPhones() {
        if (!forgetPrompt.value) return
        forgetPrompt.value = false
        work.launch {
            val failed = try {
                forgetPairedPhones()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The phones are still paired: the row says so, and the count stays as it was.
                true
            }
            ops.update { it.copy(forgetPhonesFailed = failed) }
        }
    }

    fun checkForUpdatesNow() = checkForUpdatesNow.invoke()

    /** A step of the update offered by the last check (Update, Install, ...). */
    fun performUpdate(action: UpdateAction) = performUpdateAction(action)

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
                    vodCounts = combine(c.db.vodBrowseDao().filmCountsByPlaylist(), c.db.vodBrowseDao().showCountsByPlaylist()) { f, sh ->
                        val films = f.associate { it.playlistId to it.count }
                        val shows = sh.associate { it.playlistId to it.count }
                        (films.keys + shows.keys).associateWith { VodCounts(films[it] ?: 0, shows[it] ?: 0) }
                    },
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
                    checkForUpdatesNow = { c.updater.checkNow() },
                    updateStatus = c.updater.status,
                    performUpdateAction = { c.updater.perform(it) },
                    rename = { id, n -> c.playlistRepository.rename(id, n) },
                    setEnabled = { id, on ->
                        c.channelEdits.setPlaylistEnabled(id, on)
                        // A playlist the film and show fill left out while it was off is filled now.
                        if (on) c.vodFill.afterEnabled(id)
                    },
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
                    pipSupported = c.pipSupported,
                    maxCatchupDays = { c.db.channelDao().maxCatchupDays() },
                    defaultTvName = defaultTvName(app),
                    phoneJob = c.phoneJobs.current,
                    pairedPhones = c.settingsStore.pairedCount,
                    forgetPairedPhones = { c.phonePairing.forgetAll() },
                    storageFor = c.storageFactory::current,
                    internalFreeBytes = { LocalDirStorage.internal(app)?.freeBytes() },
                    permissions = { PermissionStates(canDrawOverlays(app), ignoresBatteryOptimisations(app)) },
                    setConnectionsAllowed = { id, n -> c.db.playlistDao().setConnectionsAllowed(id, n) },
                    setXtreamFormat = { id, f -> c.db.playlistDao().setXtreamFormat(id, f) },
                    tmdbStatus = c.tmdbKeyService.status,
                    saveTmdbKey = { k -> c.tmdbKeyService.save(k) },
                    removeTmdbKey = { c.tmdbKeyService.remove() },
                    recheckTmdbKey = { c.tmdbKeyService.recheck() },
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

private fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

private fun ignoresBatteryOptimisations(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) == true
