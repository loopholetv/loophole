package io.github.loopholetv.setup

import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRequestTest {
    private var now = 1_000L
    private val plain = "secret-playlist-login".toByteArray()

    private fun request(code: String = "4821") =
        SetupRequest("r1", code, { now }, now + SetupRequest.LIFETIME_MS)

    private fun sealFor(code: String, id: String = "r1") =
        SetupCrypto.seal(plain, code, SetupCrypto.aadFor(id))

    @Test fun acceptsTheRightCode() {
        val out = request().tryOpen(sealFor("4821"))
        assertArrayEquals(plain, (out as SetupRequest.Outcome.Accepted).plain)
    }

    @Test fun threeWrongCodesCancel() {
        val r = request()
        val wrong = sealFor("0000")
        assertEquals(SetupRequest.Outcome.WrongCode(2), r.tryOpen(wrong))
        assertEquals(SetupRequest.Outcome.WrongCode(1), r.tryOpen(wrong))
        assertEquals(SetupRequest.Outcome.Cancelled, r.tryOpen(wrong))
        // Once cancelled, even the right code opens nothing.
        assertEquals(SetupRequest.Outcome.Cancelled, r.tryOpen(sealFor("4821")))
    }

    @Test fun expiresAfter2Minutes() {
        val r = request()
        now += 2 * 60_000L - 1
        assertTrue(r.tryOpen(sealFor("4821")) is SetupRequest.Outcome.Accepted)
        val late = request()
        now += 2 * 60_000L
        assertEquals(SetupRequest.Outcome.Expired, late.tryOpen(sealFor("4821")))
    }

    @Test fun bundleWithoutActiveRequestRejected() {
        val sealed = sealFor("4821")
        val used = request()
        used.tryOpen(sealed)
        assertEquals(SetupRequest.Outcome.Cancelled, used.tryOpen(sealed))
        val cancelled = request().also { it.cancel() }
        assertEquals(SetupRequest.Outcome.Cancelled, cancelled.tryOpen(sealed))
    }

    @Test fun noSecretsInToString() {
        val r = request("4821")
        val text = r.toString() + r.tryOpen(sealFor("4821")).toString()
        assertFalse(text.contains("4821"))
        assertFalse(text.contains("secret-playlist-login"))
    }

    @Test fun sealForAnotherRequestIsRejected() {
        assertEquals(SetupRequest.Outcome.WrongCode(2), request().tryOpen(sealFor("4821", id = "other")))
    }

    @Test fun expiredStaysExpired() {
        val r = request()
        now += SetupRequest.LIFETIME_MS
        assertEquals(SetupRequest.Outcome.Expired, r.tryOpen(sealFor("4821")))
        assertEquals(SetupRequest.Outcome.Expired, r.tryOpen(sealFor("4821")))
    }

    @Test fun cancelBeforeTheResultIsTakenWins() {
        // The clock is read right before the guess; cancelling there must beat the right code.
        lateinit var r: SetupRequest
        r = SetupRequest("r1", "4821", { r.cancel(); now }, now + 60_000)
        assertEquals(SetupRequest.Outcome.Cancelled, r.tryOpen(sealFor("4821")))
    }

    @Test fun cancelDoesNotWaitForAGuessInProgress() {
        val r = request()
        val sealed = sealFor("4821")
        val guess = Thread { r.tryOpen(sealed) }
        guess.start()
        r.cancel() // returns without taking the guess lock
        guess.join()
        assertEquals(SetupRequest.Outcome.Cancelled, r.tryOpen(sealed))
    }

    @Test fun garbageCountsAsWrongAttempt() {
        assertEquals(SetupRequest.Outcome.WrongCode(2), request().tryOpen(ByteArray(5)))
    }

    @Test fun parallelGuessesNeverExceedTheLimit() {
        val r = request()
        val wrong = sealFor("0000")
        val results = Collections.synchronizedList(mutableListOf<SetupRequest.Outcome>())
        val threads = List(8) { Thread { results += r.tryOpen(wrong) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(2, results.count { it is SetupRequest.Outcome.WrongCode })
        assertEquals(6, results.count { it == SetupRequest.Outcome.Cancelled })
    }
}
