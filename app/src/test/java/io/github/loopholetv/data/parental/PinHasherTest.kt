package io.github.loopholetv.data.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PinHasherTest {
    @Test fun sameSaltSameHash() {
        val salt = PinHasher.newSalt()
        assertEquals(PinHasher.hash("1234", salt), PinHasher.hash("1234", salt))
    }

    @Test fun differentSaltDifferentHash() {
        assertNotEquals(PinHasher.hash("1234", PinHasher.newSalt()), PinHasher.hash("1234", PinHasher.newSalt()))
    }

    @Test fun matchesOnlyTheRightPin() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(PinHasher.matches("1234", salt, hash))
        assertFalse(PinHasher.matches("1235", salt, hash))
        assertFalse(PinHasher.matches("1234", PinHasher.newSalt(), hash))
    }

    @Test fun hashIsBase64Of32Bytes() {
        assertEquals(32, Base64.getDecoder().decode(PinHasher.hash("0000", PinHasher.newSalt())).size)
        assertEquals(16, Base64.getDecoder().decode(PinHasher.newSalt()).size)
    }

    @Test fun fallbackMatchesTheLibraryDerivation() {
        val salt = Base64.getDecoder().decode(PinHasher.newSalt())
        val expected = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec("4821".toCharArray(), salt, 20_000, 256)).encoded
        assertEquals(expected.toList(), PinHasher.pbkdf2("4821".toByteArray(), salt).toList())
    }

    @Test fun fallbackMatchesAKnownVector() {
        val key = PinHasher.pbkdf2("password".toByteArray(), "salt".toByteArray(), 4096)
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            key.joinToString("") { "%02x".format(it) },
        )
    }
}
