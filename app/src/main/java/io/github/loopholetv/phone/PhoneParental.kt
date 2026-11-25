package io.github.loopholetv.phone

import io.github.loopholetv.data.parental.PinTries
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the phone server needs from the PIN: its settings, a raw PIN check, and when start-up is done. */
class PhoneParental(
    /** Completes once the start-up relock has run; routes wait for it before answering. */
    val ready: suspend () -> Unit = { },
    val settings: suspend () -> ParentalSettings = { ParentalSettings() },
    /** A raw check with no side effects. Only called with exactly four ASCII digits. */
    val matches: suspend (pin: String) -> Boolean = { false },
    /** Milliseconds. */
    val clock: () -> Long = System::currentTimeMillis,
)

/** The outcome of a PIN typed on a phone. */
internal sealed interface PhonePin {
    data object Ok : PhonePin

    data object Wrong : PhonePin

    data class Wait(val seconds: Int) : PhonePin
}

/**
 * Which paired phones have entered the PIN. The unlock belongs to one phone's cookie, lives in
 * memory only, and never touches the TV's own lock state. Wrong tries are counted per phone.
 */
internal class PhoneSessions(private val parental: PhoneParental) {
    private class Session(val tries: PinTries) {
        @Volatile var untilMs = 0L
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Every phone's wrong tries together, so several paired phones can't multiply the rate. */
    private val allTries = PinTries(parental.clock)

    /** One PIN check at a time: parallel requests can't outrun the limits. */
    private val checking = Mutex()

    private val hashLock = Any()
    private var knownHash: String? = null
    private var hashKnown = false

    /** The settings now; a PIN that changed or was removed since the last look ends every phone's unlock. */
    private suspend fun settings(): ParentalSettings {
        val s = parental.settings()
        synchronized(hashLock) {
            if (hashKnown && knownHash != s.pinHash) sessions.values.forEach { it.untilMs = 0L }
            knownHash = s.pinHash
            hashKnown = true
        }
        return s
    }

    private fun session(token: String) = sessions.getOrPut(token) { Session(PinTries(parental.clock)) }

    private fun unlocked(token: String?): Boolean {
        val until = token?.let { sessions[it]?.untilMs } ?: return false
        return parental.clock() < until
    }

    /** A PIN exists and this phone hasn't entered it (or its time ran out). */
    suspend fun locked(token: String?): Boolean = settings().hasPin && !unlocked(token)

    /** Every route is closed to this phone: the Settings lock is on, a PIN exists, and it hasn't been entered. */
    suspend fun settingsLocked(token: String?): Boolean {
        val s = settings()
        return s.hasPin && s.settingsLock != SettingsLock.OFF && !unlocked(token)
    }

    /** Checks [pin]; one try at a time per phone, so parallel requests can't outrun the limit. */
    suspend fun enter(token: String, pin: String?): PhonePin {
        val session = session(token)
        return checking.withLock {
            val settings = settings()
            // Nothing to check against: nothing is locked.
            if (!settings.hasPin) return@withLock PhonePin.Ok
            waitSeconds(session)?.let { return@withLock PhonePin.Wait(it) }
            // Anything but four ASCII digits is wrong without hashing.
            if (pin != null && isPin(pin) && parental.matches(pin)) {
                session.tries.reset()
                allTries.reset()
                val minutes = settings.unlockMinutes.takeIf { it > 0 } ?: PHONE_UNTIL_LEAVE_MINUTES
                session.untilMs = parental.clock() + minutes * MINUTE_MS
                return@withLock PhonePin.Ok
            }
            session.tries.wrong()
            allTries.wrong()
            waitSeconds(session)?.let { PhonePin.Wait(it) } ?: PhonePin.Wrong
        }
    }

    /** The longer refusal of this phone's and the all-phones count, or null when a try is allowed. */
    private fun waitSeconds(session: Session): Int? =
        listOfNotNull(session.tries.waitSeconds(), allTries.waitSeconds()).maxOrNull()

    fun forget(token: String) {
        sessions.remove(token)
    }

    private fun isPin(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    private companion object {
        const val PIN_LENGTH = 4
        const val MINUTE_MS = 60_000L

        /** "Until you leave Loophole" has no meaning on a phone, which can't tell when that happens. */
        const val PHONE_UNTIL_LEAVE_MINUTES = 15
    }
}
