package io.github.loopholetv.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.loopholetv.LoopholeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** How long a receiver holds its broadcast open; the system allows about ten seconds. */
internal const val BROADCAST_HOLD_MS = 5_000L

/**
 * Runs [work] on [scope] with no time limit, and calls [finish] exactly once: when the work ends, or after
 * [holdMs] if it is still running (then [onHoldExpired] is told first and the work carries on).
 */
internal fun holdBroadcast(
    scope: CoroutineScope,
    holdMs: Long,
    finish: () -> Unit,
    onHoldExpired: () -> Unit,
    work: suspend () -> Unit,
): Job {
    val finished = AtomicBoolean(false)
    val job = scope.launch {
        try {
            work()
        } finally {
            if (finished.compareAndSet(false, true)) finish()
        }
    }
    val timer = scope.launch {
        delay(holdMs)
        if (job.isActive && finished.compareAndSet(false, true)) {
            onHoldExpired()
            finish()
        }
    }
    job.invokeOnCompletion { timer.cancel() }
    return job
}

/** Runs [work] on the application scope for a receiver, keeping the broadcast open for at most [BROADCAST_HOLD_MS]. */
internal fun BroadcastReceiver.runAsync(context: Context, label: String, work: suspend (LoopholeApp) -> Unit) {
    val app = context.applicationContext as LoopholeApp
    val pending = goAsync()
    holdBroadcast(
        app.applicationScope, BROADCAST_HOLD_MS, pending::finish,
        onHoldExpired = { Log.w("Loophole", "Alarm work still running after the broadcast was released: $label") },
    ) { work(app) }
}

/** Receives the alarms set by [RecordAlarms] and hands them to the app. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (action, id) = intent.alarm() ?: return
        runAsync(context, action.name) { it.container.alarmDispatch.onAlarm(action, id) }
    }
}
