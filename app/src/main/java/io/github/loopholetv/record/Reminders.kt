package io.github.loopholetv.record

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** The launch extra that carries a channel to open: [playChannelExtra] of its [ChannelRef]. */
const val EXTRA_PLAY_CHANNEL = "io.github.loopholetv.PLAY_CHANNEL"

/** A channel as a launch extra: the playlist id, a slash, then the channel key (which may hold slashes). */
fun playChannelExtra(ref: ChannelRef): String = "${ref.playlistId}/${ref.key}"

fun parsePlayChannel(extra: String?): ChannelRef? {
    val at = extra?.indexOf('/') ?: return null
    if (at <= 0) return null
    val playlistId = extra.substring(0, at).toLongOrNull() ?: return null
    return ChannelRef(playlistId, extra.substring(at + 1)).takeIf { it.key.isNotEmpty() }
}

/** What a reminder pop-up shows. [ref] is the channel its Watch opens. */
data class ReminderPrompt(
    val id: Long,
    val channel: String,
    val title: String,
    val start: Instant,
    val leadMin: Int,
    val ref: ChannelRef? = null,
)

/** One way of showing a reminder; showing again for the same id replaces it, and dismissing one that is gone does nothing. */
interface ReminderSurface {
    fun show(p: ReminderPrompt)

    fun dismiss(id: Long)
}

/** The pop-up's headline, and `<title> · <HH:mm>` (the start in [zone]). */
fun reminderText(p: ReminderPrompt, zone: ZoneId): Pair<String, String> {
    val headline = if (p.leadMin <= 0) "Starting now on ${p.channel}" else "Starts in ${p.leadMin} min on ${p.channel}"
    return headline to "${p.title} · ${TIME.format(p.start.atZone(zone))}"
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** How long an alarm waits for its reminder to be shown before letting the broadcast go. */
private const val ALARM_JOIN_MS = 4_000L

/** An in-app card still waiting this many minutes past its lead after the start is no longer shown. */
private const val STALE_AFTER_START_MIN = 5

private enum class Where { IN_APP, OVERLAY, NOTIFICATION }

private class Showing(
    val prompt: ReminderPrompt,
    val ref: ChannelRef,
    @Volatile var where: Where,
    val autoSwitch: Boolean,
    val stop: Instant,
) {
    @Volatile var timer: Job? = null
}

/**
 * Shows a booked reminder when its alarm fires: in Loophole while it is in front ([inApp]), else over the
 * other app when that is allowed, else as a notification. The first answer wins; after [POPUP_TIMEOUT_MS] it
 * goes away, and with Switch automatically on it opens the channel (through [watch] in Loophole, through
 * [launch] from the overlay; a notification only goes away). Every alarm marks its reminder notified first,
 * even if nothing could be shown, so the alarms are never set for it again.
 *
 * [scope] runs on the main thread in the app, where the surfaces draw.
 */
class Reminders(
    db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val foreground: () -> Boolean,
    private val canOverlay: () -> Boolean,
    private val overlay: ReminderSurface,
    private val notify: ReminderSurface,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    /** Brings Loophole to the front on the channel; used when an answer or the timeout comes from outside it. */
    private val launch: (ChannelRef) -> Unit = {},
    /**
     * Whether the channel is locked and the PIN not entered. A reminder can fire on a cold start before any
     * screen has run, so this reads the lock flags itself and must fail closed.
     */
    private val lockedNow: suspend (ChannelRef) -> Boolean = { false },
) {
    private val dao = db.recordDao()
    private val showing = ConcurrentHashMap<Long, Showing>()

    private val _inApp = MutableStateFlow<ReminderPrompt?>(null)

    /** The reminder to show inside Loophole, until it is answered or times out. */
    val inApp: StateFlow<ReminderPrompt?> = _inApp.asStateFlow()

    private val _watch = MutableSharedFlow<ChannelRef>(extraBufferCapacity = 4)

    /** The viewer chose (or the timeout chose) to watch the channel while Loophole is in front. */
    val watch: SharedFlow<ChannelRef> = _watch.asSharedFlow()

    /**
     * Shows the reminder on [scope], and returns once it is shown (or after [ALARM_JOIN_MS]), while the alarm's
     * broadcast still keeps the TV awake.
     */
    suspend fun onAlarm(id: Long) {
        val job = alarm(id)
        withTimeoutOrNull(ALARM_JOIN_MS) { job.join() }
    }

    internal fun alarm(id: Long): Job = scope.launch { present(id) }

    internal fun timerJob(id: Long): Job? = showing[id]?.timer

    fun answer(id: Long, watch: Boolean) {
        val s = end(id, fromTimer = false) ?: return
        if (watch) deliver(s)
    }

    private suspend fun present(id: Long) {
        val row = dao.reminder(id) ?: return
        // First, so a failure below can't leave the alarm to be set again.
        dao.markNotified(id, clock())
        val now = clock()
        if (row.stop <= now) return
        val ref = ChannelRef(row.playlistId, row.channelKey)
        // A locked channel's reminder shows nothing; the row stays.
        if (lockedNow(ref)) return
        val settings = settings()
        val remainingMin = ((row.start.toEpochMilli() - now.toEpochMilli() + 59_999L) / 60_000L).toInt()
        val lead = minOf(settings.reminderLeadMin, remainingMin).coerceAtLeast(0)
        val prompt = ReminderPrompt(id, row.channelName, row.title, row.start, lead, ref)
        val s = Showing(prompt, ref, surfaceFor(), settings.reminderAutoSwitch, row.stop)
        // One window and one card: whatever showed there ends (no timer left to switch under the new one).
        when (s.where) {
            Where.IN_APP -> _inApp.value?.let { end(it.id, fromTimer = false) }
            Where.OVERLAY -> showing.values.filter { it.where == Where.OVERLAY }.forEach { end(it.prompt.id, fromTimer = false) }
            Where.NOTIFICATION -> Unit
        }
        showing[id] = s
        when (s.where) {
            Where.IN_APP -> _inApp.value = prompt
            Where.OVERLAY -> if (runCatching { overlay.show(prompt) }.isFailure) s.where = Where.NOTIFICATION
            Where.NOTIFICATION -> Unit
        }
        if (s.where == Where.NOTIFICATION && runCatching { notify.show(prompt) }.isFailure) {
            showing.remove(id)
            return
        }
        // The in-app card may wait behind a recording question: its time starts at [shown].
        if (s.where != Where.IN_APP) startTimer(s)
    }

    /**
     * The in-app card for [id] is now on screen: its [POPUP_TIMEOUT_MS] starts (once). A card that waited
     * too long (its programme is over, or it started more than its lead plus [STALE_AFTER_START_MIN] ago) is
     * dropped instead.
     */
    fun shown(id: Long) {
        val s = showing[id] ?: return
        if (s.where != Where.IN_APP) return
        val now = clock()
        val staleAt = s.prompt.start.plusSeconds((s.prompt.leadMin + STALE_AFTER_START_MIN) * 60L)
        if (now >= s.stop || now > staleAt) {
            end(id, fromTimer = false)
            return
        }
        startTimer(s)
    }

    private fun startTimer(s: Showing) {
        synchronized(s) {
            if (s.timer != null) return
            s.timer = scope.launch {
                delayMs(POPUP_TIMEOUT_MS)
                expire(s.prompt.id)
            }
        }
    }

    private fun surfaceFor() = when {
        foreground() -> Where.IN_APP
        canOverlay() -> Where.OVERLAY
        else -> Where.NOTIFICATION
    }

    private fun expire(id: Long) {
        val s = end(id, fromTimer = true) ?: return
        if (s.autoSwitch && s.where != Where.NOTIFICATION) deliver(s)
    }

    /** Takes the showing out, once: the caller that gets it decides the outcome. */
    private fun end(id: Long, fromTimer: Boolean): Showing? {
        val s = showing.remove(id) ?: return null
        if (!fromTimer) s.timer?.cancel()
        when (s.where) {
            Where.IN_APP -> _inApp.value = _inApp.value?.takeUnless { it.id == id }
            Where.OVERLAY -> runCatching { overlay.dismiss(id) }
            Where.NOTIFICATION -> runCatching { notify.dismiss(id) }
        }
        return s
    }

    private fun deliver(s: Showing) {
        when (s.where) {
            Where.IN_APP -> _watch.tryEmit(s.ref)
            Where.OVERLAY, Where.NOTIFICATION -> runCatching { launch(s.ref) }
        }
    }
}
