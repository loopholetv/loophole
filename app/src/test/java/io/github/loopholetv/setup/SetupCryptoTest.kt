package io.github.loopholetv.setup

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCryptoTest {
    private val plain = "http://example.test/list.m3u?user=a&pass=b".toByteArray()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun sealOpenRoundTrip() {
        val sealed = SetupCrypto.seal(plain, "4821")
        assertEquals(16 + 12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, SetupCrypto.open(sealed, "4821"))
    }

    @Test fun wrongCodeIsNull() {
        assertNull(SetupCrypto.open(SetupCrypto.seal(plain, "4821"), "4822"))
    }

    @Test fun tamperIsNull() {
        val sealed = SetupCrypto.seal(plain, "4821")
        for (i in listOf(0, 20, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", SetupCrypto.open(bad, "4821"))
        }
        assertNull(SetupCrypto.open(sealed.copyOf(10), "4821"))
        assertNull(SetupCrypto.open(ByteArray(0), "4821"))
    }

    @Test fun codesAreFourDigits() {
        val random = SecureRandom()
        repeat(2000) { assertTrue(SetupCrypto.newCode(random).matches(Regex("[0-9]{4}"))) }
    }

    @Test fun sealsDifferEachTime() {
        assertTrue(!SetupCrypto.seal(plain, "1234").contentEquals(SetupCrypto.seal(plain, "1234")))
    }

    // Published PBKDF2-HMAC-SHA256 vectors; every device must derive these exact bytes.
    @Test fun pbkdf2MatchesPublishedVectors() {
        val p = "password".toByteArray()
        val s = "salt".toByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(SetupCrypto.pbkdf2Sha256(p, s, 1, 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(SetupCrypto.pbkdf2Sha256(p, s, 2, 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(SetupCrypto.pbkdf2Sha256(p, s, 4096, 32)),
        )
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(SetupCrypto.pbkdf2Sha256("passwd".toByteArray(), s, 1, 64)),
        )
    }

    @Test fun badCodesAreRejected() {
        val sealed = SetupCrypto.seal(plain, "4821")
        for (bad in listOf("", "123", "12345", "12a4", "١٢٣٤")) {
            val e = runCatching { SetupCrypto.seal(plain, bad) }.exceptionOrNull()
            assertTrue(bad, e is IllegalArgumentException && e.message!!.contains("four digits"))
            assertNull(bad, SetupCrypto.open(sealed, bad))
        }
    }

    @Test fun aadMustMatch() {
        val sealed = SetupCrypto.seal(plain, "4821", SetupCrypto.aadFor("a"))
        assertArrayEquals(plain, SetupCrypto.open(sealed, "4821", SetupCrypto.aadFor("a")))
        assertNull(SetupCrypto.open(sealed, "4821", SetupCrypto.aadFor("b")))
        assertNull(SetupCrypto.open(sealed, "4821"))
    }

    @Test fun newCodeZeroPads() {
        val stub = object : SecureRandom() {
            override fun nextInt(bound: Int) = 7
        }
        assertEquals("0007", SetupCrypto.newCode(stub))
    }

    @Test fun emptyPayloadRoundTrips() {
        assertNotNull(SetupCrypto.open(SetupCrypto.seal(ByteArray(0), "0000"), "0000"))
    }
}
