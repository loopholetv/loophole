package io.github.loopholetv.phone

import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStore : PairingStore {
    val phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    var port: Int? = null
    override suspend fun pairedPhones() = phones.value
    override val pairedCount = phones.map { it.size }
    override suspend fun savePairedPhones(list: List<PairedPhone>) { phones.value = list }
    override suspend fun phonePort() = port
    override suspend fun savePhonePort(port: Int) { this.port = port }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhonePairingTest {
    private val store = MemoryStore()
    private var now = 1_000_000L
    private val pairing = PhonePairing(store, { now })
    private val a = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
    private val b = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 11))

    private var nextHost = 20

    private suspend fun pairOne(ua: String? = null): String {
        pairing.open()
        val from = InetAddress.getByAddress(byteArrayOf(10, 0, 0, nextHost++.toByte()))
        return (pairing.pair(pairing.code.value!!, from, ua) as PairResult.Paired).token
    }

    @Test
    fun codeIsFourDigitsWhileOpen() {
        pairing.open()
        assertTrue(Regex("\\d{4}").matches(pairing.code.value!!))
    }

    @Test
    fun noCodeWhenClosed() = runTest {
        assertEquals(null, pairing.code.value)
        assertEquals(PairResult.NotShowing, pairing.pair("1234", a, null))
        pairing.open()
        pairing.close()
        assertEquals(null, pairing.code.value)
        assertEquals(PairResult.NotShowing, pairing.pair("1234", a, null))
    }

    @Test
    fun rightCodePairs() = runTest {
        val token = pairOne("Mozilla/5.0 (Linux; Android 14) Chrome/120.0 Mobile Safari/537.36")
        assertTrue(pairing.isPaired(token))
        assertFalse(pairing.isPaired(token + "x"))
        assertFalse(pairing.isPaired(null))
        val stored = store.pairedPhones().single()
        assertEquals("Chrome on Android", stored.name)
        assertEquals(64, stored.hash.length)
        assertNotEquals(token, stored.hash)
        assertFalse(stored.toString().contains(stored.hash))
        assertFalse(PairResult.Paired(token).toString().contains(token))
    }

    @Test
    fun wrongCodeThreeTimesChangesCode() = runTest {
        pairing.open()
        val first = pairing.code.value!!
        val wrong = if (first == "0000") "0001" else "0000"
        repeat(3) { assertEquals(PairResult.WrongCode, pairing.pair(wrong, a, null)) }
        // The new code is random and could repeat by chance; the count must at least restart.
        assertNotNull(pairing.code.value)
        val current = pairing.code.value!!
        val other = if (current == "0000") "0001" else "0000"
        assertEquals(PairResult.WrongCode, pairing.pair(other, a, null))
        assertEquals(current, pairing.code.value)
    }

    @Test
    fun codeChangesAfterThreeWrongGuessesWithSeededRandom() = runTest {
        val seeded = PhonePairing(store, { now }, java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        seeded.open()
        val seen = mutableSetOf(seeded.code.value)
        repeat(30) {
            val c = seeded.code.value!!
            val wrong = if (c == "0000") "0001" else "0000"
            repeat(3) { seeded.pair(wrong, InetAddress.getByAddress(byteArrayOf(10, 0, 0, (it + 1).toByte())), null) }
            seen += seeded.code.value
        }
        assertTrue(seen.size > 1)
    }

    @Test
    fun tooManyAttemptsFromOneAddress() = runTest {
        pairing.open()
        repeat(10) { pairing.pair("abcd", a, null) }
        assertEquals(PairResult.TooMany, pairing.pair(pairing.code.value!!, a, null))
        assertTrue(pairing.pair(pairing.code.value!!, b, null) is PairResult.Paired)
        now += 60_000
        assertTrue(pairing.pair(pairing.code.value!!, a, null) is PairResult.Paired)
    }

    @Test
    fun forgetAllUnpairs() = runTest {
        val t1 = pairOne()
        val t2 = pairOne()
        pairing.forgetAll()
        assertFalse(pairing.isPaired(t1))
        assertFalse(pairing.isPaired(t2))
    }

    @Test
    fun forgetOneKeepsOthers() = runTest {
        val t1 = pairOne()
        val t2 = pairOne()
        pairing.forget(t1)
        assertFalse(pairing.isPaired(t1))
        assertTrue(pairing.isPaired(t2))
    }

    @Test
    fun twentyFirstPhoneDropsOldest() = runTest {
        val first = pairOne()
        repeat(20) { now += 1_000; pairOne() }
        assertEquals(20, store.pairedPhones().size)
        assertFalse(pairing.isPaired(first))
    }

    @Test
    fun useIsNotedAtMostOncePerMinute() = runTest {
        val token = pairOne()
        val paired = store.pairedPhones().single().lastUsedMs
        now += 30_000
        assertTrue(pairing.isPaired(token))
        assertEquals(paired, store.pairedPhones().single().lastUsedMs)
        now += 30_000
        assertTrue(pairing.isPaired(token))
        assertEquals(now, store.pairedPhones().single().lastUsedMs)
    }

    @Test
    fun phoneNameFromUserAgent() {
        assertEquals(
            "Chrome on Android",
            phoneName("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
        )
        assertEquals(
            "Safari on iPhone",
            phoneName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"),
        )
        assertEquals(
            "Firefox on Windows",
            phoneName("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"),
        )
        assertEquals("Browser", phoneName(null))
        assertEquals("Browser", phoneName("curl/8.0"))
    }

    @Test
    fun connectedEmitsOnPair() = runTest(UnconfinedTestDispatcher()) {
        var seen = 0
        val job = launch { pairing.connected.collect { seen++ } }
        pairOne()
        assertEquals(1, seen)
        job.cancel()
    }

    @Test
    fun pairingsRoundTripThroughJson() {
        val list = listOf(PairedPhone("ab", "Chrome \"x\" on Android", 5L), PairedPhone("cd", "Browser", 9L))
        assertEquals(list, decodePairings(encodePairings(list)))
        assertEquals(emptyList<PairedPhone>(), decodePairings("not json"))
        assertEquals(emptyList<PairedPhone>(), decodePairings(null))
    }
}
