package io.github.loopholetv.data.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class PortalIdentityTest {
    private fun hex(algorithm: String, input: String): String =
        MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    @Test fun derivesFromTheMac() {
        val mac = "00:1A:79:AB:CD:EF"
        val id = deriveIdentity(mac)!!
        val serial = hex("MD5", mac).take(13)
        assertEquals(serial, id.serial)
        assertEquals(13, id.serial.length)
        assertEquals(hex("SHA-256", mac), id.deviceId)
        assertEquals(id.deviceId, id.deviceId2)
        assertEquals(hex("SHA-256", serial + mac), id.signature)
    }

    @Test fun knownAnswer() {
        val id = deriveIdentity("00:1A:79:AB:CD:EF")!!
        assertEquals("FA30BAAEA4EE0", id.serial)
        assertEquals("A446559A63A6A489959198534E649760C6A9A6474DEE7C20314C2F1903B36422", id.deviceId)
        assertEquals(id.deviceId, id.deviceId2)
        assertEquals("5D85E09AC9EC7A3A687B69A887847A7F0BF0FB6EB31C035D8DE23610629B8883", id.signature)
    }

    @Test fun caseAndSeparatorsDoNotMatter() {
        val expected = deriveIdentity("00:1A:79:AB:CD:EF")
        assertEquals(expected, deriveIdentity("00-1a-79-ab-cd-ef"))
        assertEquals(expected, deriveIdentity(" 001a79abcdef "))
    }

    @Test fun invalidMacGivesNothing() {
        assertNull(deriveIdentity("00:1A:79"))
        assertNull(deriveIdentity(""))
    }

    @Test fun toStringHidesTheValues() {
        val id = deriveIdentity("00:1A:79:AB:CD:EF")!!
        assertFalse(id.toString().contains(id.serial))
        assertFalse(id.toString().contains(id.deviceId))
    }

    @Test fun fillsOnlyBlankFields() {
        val cfg = PortalConfig("http://p.example", "00:1A:79:AB:CD:EF", serial = "TYPED", deviceId2 = " ")
        val filled = cfg.withDerivedIdentity()
        val id = deriveIdentity(cfg.mac)!!
        assertEquals("TYPED", filled.serial)
        assertEquals(id.deviceId, filled.deviceId)
        assertEquals(id.deviceId2, filled.deviceId2)
        assertEquals(id.signature, filled.signature)
    }
}
