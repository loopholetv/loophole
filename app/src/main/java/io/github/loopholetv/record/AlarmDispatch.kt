package io.github.loopholetv.record

import kotlinx.coroutines.sync.withLock

/** What an alarm asks for, and the recovery that runs after the app starts or the TV boots. */
interface AlarmHandler {
    /**
     * The receiver holds the broadcast open until this returns, for at most five seconds. Whatever must happen
     * while the alarm still keeps the TV awake (a wake lock, starting a foreground service) is done before
     * returning; longer work is handed off (launched on a scope) and the call returns within a second or two.
     */
    suspend fun onAlarm(action: AlarmAction, id: Long)

    /** Must be idempotent: it can run again after a boot, an update and an app start in quick succession. */
    suspend fun recover()
}

/** Routes alarms to whoever handles them; every call is a no-op while its handler is unset. */
class AlarmDispatch : AlarmHandler {
    @Volatile var reminders: (suspend (Long) -> Unit)? = null

    @Volatile var recordings: AlarmHandler? = null

    override suspend fun onAlarm(action: AlarmAction, id: Long) {
        when (action) {
            AlarmAction.REMIND -> reminders?.invoke(id)
            AlarmAction.PREPARE, AlarmAction.START, AlarmAction.STOP -> recordings?.onAlarm(action, id)
        }
    }

    override suspend fun recover() {
        recordings?.recover()
    }
}

/**
 * Recovers what was running, then re-arms the alarms. Single-flight: a caller arriving while another is
 * running waits, and then skips the recovery if one finished within [RECOVER_AGAIN_AFTER_MS] (it still re-arms).
 */
class RecoverAndRearm(
    private val handler: AlarmHandler,
    private val rearm: suspend () -> Unit,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var recoveredAt: Long? = null

    suspend fun run() = lock.withLock {
        val last = recoveredAt
        if (last == null || nowMs() - last >= RECOVER_AGAIN_AFTER_MS) {
            try {
                handler.recover()
                recoveredAt = nowMs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed recovery must not leave the alarms unset.
            }
        }
        rearm()
    }

    private companion object {
        const val RECOVER_AGAIN_AFTER_MS = 10_000L
    }
}
