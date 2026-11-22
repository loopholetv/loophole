package io.github.loopholetv.ui.recordings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingEntity
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.record.RecordingRunner
import io.github.loopholetv.record.RemoteRecording
import io.github.loopholetv.record.ScheduleRepository
import io.github.loopholetv.record.mayNotFit
import io.github.loopholetv.record.sameChannel
import io.github.loopholetv.record.storage.StorageFactory
import io.github.loopholetv.record.storage.freeBytesWithin
import io.github.loopholetv.ui.guide.GuideAt
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The other TV's recordings, listed for a pick. */
data class PeerListing(val peer: CastPeer, val items: List<RemoteRecording>)

/** A playlist's copy of a recording's channel that it can be recorded from instead. */
data class SourceChoice(val playlistId: Long, val playlistName: String)

data class RecordingsUi(
    val loaded: Boolean = false,
    val sections: List<RecordingsSection> = emptyList(),
    /** "Free: 212 GB (NAS)"; null until the storage has answered, and when none is chosen. */
    val freeText: String? = null,
    val otherTvs: List<OtherTv> = emptyList(),
    val storageMissing: Boolean = false,
    /** Ids of the rows whose channel's archive can supply the programme now. */
    val catchUp: Set<Long> = emptySet(),
    /** The time the sections were worked out at (the view model's clock). */
    val now: Instant = Instant.EPOCH,
    /** A delete that could not reach the file: the row to offer removing from the list. */
    val removePrompt: Long? = null,
    val peerListing: PeerListing? = null,
    /** A short notice that an action failed; clears itself, or on the next action. */
    val message: String? = null,
)

/** What the screen is asked to do beyond showing. */
sealed interface RecordingsEvent {
    data class Play(val id: Long, val live: Boolean) : RecordingsEvent
    data class PlayRemote(val peerId: String, val id: Long, val live: Boolean) : RecordingsEvent
    data class OpenGuide(val at: GuideAt) : RecordingsEvent
}

private const val MESSAGE_MS = 4_000L
private const val TAG = "Loophole"
const val ACTION_FAILED = "Couldn't change the recording."
const val PEER_UNREACHABLE = "Couldn't reach that TV."

private data class FreeSpace(val known: Boolean, val label: String?, val bytes: Long?)

private data class Computed(
    val sections: List<RecordingsSection>,
    val catchUp: Set<Long>,
    val free: FreeSpace,
    val now: Instant,
)

private data class Local(val removePrompt: Long? = null, val peerListing: PeerListing? = null, val message: String? = null)

/** The Recordings screen: what is booked, running and done on this TV, and what the other TVs have. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordingsViewModel(
    private val db: AppDatabase,
    private val schedule: ScheduleRepository,
    private val runner: RecordingRunner,
    private val storage: StorageFactory,
    settings: Flow<AppSettings>,
    peers: StateFlow<List<CastPeer>>,
    private val listRemote: suspend (CastPeer) -> List<RemoteRecording>,
    private val clock: () -> Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Waits out how long a notice stays up. */
    private val noticeTime: suspend () -> Unit = { delay(MESSAGE_MS) },
) : ViewModel() {
    private val dao = db.recordDao()
    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<RecordingsEvent>(Channel.BUFFERED)

    val events: Flow<RecordingsEvent> = eventChannel.receiveAsFlow()

    private val free: Flow<FreeSpace> = settings.map { s ->
        val store = storage.current(s) ?: return@map FreeSpace(true, null, null)
        FreeSpace(true, store.label, freeBytesWithin { store.freeBytes() })
    }.onStart { emit(FreeSpace(false, null, null)) }

    private val computed: Flow<Computed> = combine(
        dao.observeRecordings(), schedule.upcomingReminders(), dao.observeSeries(), db.playlistDao().all(), free,
    ) { rows, reminders, series, playlists, free ->
        val now = clock()
        val missing = missingFromGuide(rows, now)
        val perName = playlists.associate { it.id to it.name }
        val clashes = rows.filter { it.state == RecordingState.SCHEDULED }.associate { it.id to clashOf(it, rows, playlists) }
        val done = rows.filter { it.state == RecordingState.DONE }
        val sections = recordingsSections(
            rows, reminders, series,
            inGuide = { it.id !in missing },
            now = now, zone = zone,
            clashWith = { clashes[it.id] },
            mayNotFit = { mayNotFit(it, free.bytes, done) },
            sourceName = { r -> r.sourcePlaylistId?.let(perName::get) },
        )
        Computed(sections, catchUpIds(rows, now), free, now)
    }

    private val otherTvs: Flow<List<OtherTv>> = peers
        .map { list -> list.filter { it.rec != null } }
        .distinctUntilChanged { a, b -> a.map(::peerKey) == b.map(::peerKey) }
        .transformLatest { list ->
            emit(list.map { OtherTv(it, null) })
            if (list.isNotEmpty()) {
                emit(coroutineScope { list.map { p -> async { OtherTv(p, countOf(p)) } }.awaitAll() })
            }
        }

    val ui: StateFlow<RecordingsUi> = combine(computed, settings, otherTvs, local) { c, s, tvs, l ->
        RecordingsUi(
            loaded = true,
            sections = c.sections,
            freeText = if (c.free.known && c.free.label != null) freeText(c.free.bytes, c.free.label) else null,
            otherTvs = tvs,
            storageMissing = storage.current(s) == null,
            catchUp = c.catchUp,
            now = c.now,
            removePrompt = l.removePrompt,
            peerListing = l.peerListing,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingsUi())

    // --- actions ---

    fun play(id: Long) {
        launchSafely {
            val r = dao.recording(id) ?: return@launchSafely
            if (playable(r)) eventChannel.send(RecordingsEvent.Play(id, live = r.state == RecordingState.RECORDING))
        }
    }

    fun openGuide(at: GuideAt) {
        eventChannel.trySend(RecordingsEvent.OpenGuide(at))
    }

    /** Deletes the file, then the row; when the file can't be reached, offers to remove just the row. */
    fun delete(id: Long) {
        clearMessage()
        viewModelScope.launch {
            // A storage that can't be reached raises instead of answering false: the same prompt either way.
            val deleted = try {
                runner.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recording action failed: ${e.javaClass.simpleName}")
                false
            }
            if (!deleted) local.value = local.value.copy(removePrompt = id)
        }
    }

    fun removeFromList(id: Long) {
        launchSafely {
            dao.deleteRecording(id)
            local.value = local.value.copy(removePrompt = null)
        }
    }

    fun dismissRemovePrompt() {
        local.value = local.value.copy(removePrompt = null)
    }

    fun retry(id: Long, fromArchive: Boolean) {
        launchSafely { runner.retry(id, fromArchive) }
    }

    fun cancelReminder(id: Long) {
        launchSafely { schedule.cancelReminder(id) }
    }

    fun cancelRecording(id: Long) {
        launchSafely { schedule.cancelRecording(id) }
    }

    fun cancelSeries(id: Long) {
        launchSafely { schedule.cancelSeries(id) }
    }

    fun editPadding(id: Long, before: Int, after: Int) {
        launchSafely { schedule.setPadding(id, before, after) }
    }

    fun setKeepLast(seriesId: Long, n: Int?) {
        launchSafely { schedule.setKeepLast(seriesId, n) }
    }

    /** The playlists that carry the same channel as the one [id] was booked on, other than its own. */
    suspend fun sourceChoices(id: Long): List<SourceChoice> {
        val r = dao.recording(id) ?: return emptyList()
        val own = db.channelDao().byKey(r.playlistId, r.channelKey) ?: return emptyList()
        return db.playlistDao().all().first()
            .filter { it.id != r.playlistId && it.enabled }
            .filter { p -> db.channelDao().channelsOf(p.id).any { sameChannel(own, it) } }
            .map { SourceChoice(it.id, it.name) }
    }

    /** Records [id] from [playlistId]'s copy of its channel; null records from the channel it was booked on. */
    fun changeSource(id: Long, playlistId: Long?) {
        launchSafely {
            if (playlistId == null) {
                schedule.changeSource(id, null, null)
                return@launchSafely
            }
            val r = dao.recording(id) ?: return@launchSafely
            val own = db.channelDao().byKey(r.playlistId, r.channelKey) ?: return@launchSafely
            val other = db.channelDao().channelsOf(playlistId).firstOrNull { sameChannel(own, it) } ?: return@launchSafely
            schedule.changeSource(id, playlistId, other.key)
        }
    }

    fun openPeer(peer: CastPeer) {
        launchSafely {
            val items = remoteList(peer)
            if (items == null) {
                showMessage(PEER_UNREACHABLE)
                return@launchSafely
            }
            local.value = local.value.copy(peerListing = PeerListing(peer, items))
        }
    }

    fun closePeer() {
        local.value = local.value.copy(peerListing = null)
    }

    fun playRemote(peer: CastPeer, item: RemoteRecording) {
        eventChannel.trySend(RecordingsEvent.PlayRemote(peer.id, item.id, live = item.recording))
    }

    // --- internals ---

    /** An action that touches storage or the database must not take the app down when that fails. */
    private fun launchSafely(block: suspend () -> Unit) {
        clearMessage()
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recording action failed: ${e.javaClass.simpleName}")
                showMessage(ACTION_FAILED)
            }
        }
    }

    private var messageJob: Job? = null

    private fun showMessage(text: String) {
        local.value = local.value.copy(message = text)
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            noticeTime()
            local.value = local.value.copy(message = null)
        }
    }

    private fun clearMessage() {
        messageJob?.cancel()
        if (local.value.message != null) local.value = local.value.copy(message = null)
    }

    private fun peerKey(p: CastPeer) = listOf(p.id, p.name, p.host, p.port, p.rec)

    private suspend fun remoteList(peer: CastPeer): List<RemoteRecording>? = try {
        listRemote(peer)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun countOf(peer: CastPeer): Int? = remoteList(peer)?.size

    /** Scheduled bookings whose programme the committed guide no longer lists at that time and title. */
    private suspend fun missingFromGuide(rows: List<RecordingEntity>, now: Instant): Set<Long> = withContext(Dispatchers.IO) {
        val missing = HashSet<Long>()
        val cache = HashMap<Pair<String, Int>, List<io.github.loopholetv.data.db.ProgrammeEntity>>()
        for (r in rows) {
            val epg = r.epgChannelId ?: continue
            if (r.state != RecordingState.SCHEDULED || r.stop <= now) continue
            val shift = db.channelDao().byKey(r.playlistId, r.channelKey)?.epgShiftMinutes ?: 0
            val listed = cache.getOrPut(epg to shift) { db.programmeDao().upcomingFor(epg, now.minusSeconds(shift * 60L)) }
            val found = listed.any {
                it.start.plusSeconds(shift * 60L) == r.start && it.title.trim().equals(r.title.trim(), ignoreCase = true)
            }
            if (!found) missing += r.id
        }
        missing
    }

    private suspend fun catchUpIds(rows: List<RecordingEntity>, now: Instant): Set<Long> = withContext(Dispatchers.IO) {
        val ids = HashSet<Long>()
        for (r in rows) {
            if (r.state == RecordingState.SCHEDULED || r.state == RecordingState.RECORDING || r.state == RecordingState.DONE) continue
            val channel = db.channelDao().byKey(r.sourcePlaylistId ?: r.playlistId, r.sourceChannelKey ?: r.channelKey) ?: continue
            if (channel.catchup()?.canPlay(r.start, now) == true) ids += r.id
        }
        ids
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val c = (extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
                return RecordingsViewModel(
                    c.db, c.scheduleRepository, c.recordingRunner, c.storageFactory, c.settingsStore.settings,
                    c.castController.peers, c.recordingsClient::list, Instant::now,
                ) as T
            }
        }
    }
}
