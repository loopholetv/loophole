package io.github.loopholetv.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.RecordingState
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Persisted by ordinal in alarm intents and request codes: add new actions at the end. */
enum class AlarmAction { REMIND, PREPARE, STOP, START }

internal const val EXTRA_ALARM_ACTION = "action"
internal const val EXTRA_ALARM_ID = "id"

private const val ARMED_PREFS = "record_alarms"
private const val ARMED_KEY = "armed"

/** Ids are below 2^28 so the action fits in the top bits without colliding. */
fun requestCode(action: AlarmAction, id: Long): Int {
    require(id in 0 until MAX_ALARM_ID) { "alarm id out of range" }
    return (action.ordinal shl 28) or id.toInt()
}

private const val MAX_ALARM_ID = 1L shl 28
/** An event already due fires this long after the re-arm. */
private const val LATE_FIRE_DELAY_MS = 1_000L

private data class Armed(val action: AlarmAction, val id: Long, val atMs: Long) {
    val key get() = "${action.ordinal}:$id"
}

/**
 * One system alarm per future event: a reminder's lead time, a recording's warning (just before its padded
 * start), its start, and the end of a running recording. [rearm] replaces the whole set, so it is safe to call after
 * any change, restart, boot or clock change. The armed set is kept in app-private preferences, so alarms
 * for bookings that are gone can still be cancelled after the process was killed.
 */
class RecordAlarms(
    context: Context,
    private val db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Instant,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = this.context.getSharedPreferences(ARMED_PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    suspend fun rearm() = lock.withLock {
        val wanted = wanted()
        val previous = prefs.getStringSet(ARMED_KEY, emptySet()).orEmpty()
        val keys = wanted.mapTo(HashSet()) { it.key }
        // Record the union first, so a failure part-way through can't leave an alarm nobody knows to cancel.
        prefs.edit { putStringSet(ARMED_KEY, previous + keys) }
        try {
            previous.mapNotNull(::parse).filter { it.key !in keys }.forEach { cancel(it.action, it.id) }
            wanted.forEach(::set)
        } finally {
            // Keys cancelled above are gone; anything not yet handled stays recorded.
            prefs.edit { putStringSet(ARMED_KEY, keys) }
        }
    }

    /** Events still ahead of us, plus overdue ones that must act at once: a trigger already past fires almost immediately. */
    private suspend fun wanted(): List<Armed> {
        val now = clock()
        val nowMs = now.toEpochMilli()
        val soonest = nowMs + LATE_FIRE_DELAY_MS
        val dao = db.recordDao()
        val lead = settings().reminderLeadMin * 60_000L
        val reminders = dao.observeReminders(now).first()
            .filter { it.notifiedAt == null && it.start > now }
            .map { Armed(AlarmAction.REMIND, it.id, maxOf(it.start.toEpochMilli() - lead, soonest)) }
        val recordings = dao.inState(RecordingState.SCHEDULED, RecordingState.RECORDING).map {
            val w = it.window()
            when {
                it.state == RecordingState.RECORDING ->
                    listOf(Armed(AlarmAction.STOP, it.id, maxOf(w.until.toEpochMilli(), soonest)))
                w.until > now -> {
                    val startAt = maxOf(w.from.toEpochMilli(), soonest)
                    // Too late for a warning ahead of the start: START decides everything, warning included.
                    val prepare = if (w.from.toEpochMilli() > soonest) {
                        Armed(AlarmAction.PREPARE, it.id, maxOf(w.from.toEpochMilli() - WARNING_LEAD_MS, soonest))
                    } else {
                        null
                    }
                    listOfNotNull(prepare, Armed(AlarmAction.START, it.id, startAt))
                }
                else -> emptyList()
            }
        }.flatten()
        return (reminders + recordings).filter { it.id in 0 until MAX_ALARM_ID }
    }

    private fun intent(action: AlarmAction, id: Long) =
        Intent().setComponent(ComponentName(context, AlarmReceiver::class.java))
            .putExtra(EXTRA_ALARM_ACTION, action.ordinal)
            .putExtra(EXTRA_ALARM_ID, id)

    private fun pending(action: AlarmAction, id: Long) = PendingIntent.getBroadcast(
        context, requestCode(action, id), intent(action, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(action: AlarmAction, id: Long) = manager.cancel(pending(action, id))

    private fun set(a: Armed) {
        val op = pending(a.action, a.id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.atMs, op)
                return
            } catch (_: SecurityException) {
                // The exact-alarm permission was withdrawn after the check; fall through to an inexact one.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.atMs, op)
    }

    private fun parse(key: String): Armed? {
        val (o, i) = key.split(':').takeIf { it.size == 2 } ?: return null
        val action = AlarmAction.entries.getOrNull(o.toIntOrNull() ?: return null) ?: return null
        return Armed(action, i.toLongOrNull() ?: return null, 0)
    }
}

/** Reads the alarm an [AlarmReceiver] intent carries; null for anything malformed. */
internal fun Intent.alarm(): Pair<AlarmAction, Long>? {
    val action = AlarmAction.entries.getOrNull(getIntExtra(EXTRA_ALARM_ACTION, -1)) ?: return null
    val id = getLongExtra(EXTRA_ALARM_ID, -1L).takeIf { it >= 0 } ?: return null
    return action to id
}
