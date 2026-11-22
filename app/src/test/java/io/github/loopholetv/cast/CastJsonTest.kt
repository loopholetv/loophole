package io.github.loopholetv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastJsonTest {
    private val base = CastAnnounce("a1", "Living room", 8123, CastStatus.IDLE, true, null, null, null, seq = 3)

    @Test fun announceRoundTripsRec() {
        val a = base.copy(rec = "r3c")
        val json = CastJson.announce(a)
        assertTrue(json, json.contains("\"rec\":\"r3c\""))
        assertEquals(a, CastJson.announce(json))
    }

    @Test fun oldAnnounceWithoutRecDecodes() {
        val json = CastJson.announce(base)
        assertTrue(json, json.contains("\"rec\":null"))
        assertNull(CastJson.announce(json)!!.rec)
        val older = """{"id":"a1","name":"n","port":8123,"status":"IDLE","accepts":true,"seq":3,"v":1}"""
        assertNull(CastJson.announce(older)!!.rec)
    }

    @Test fun recIsNeverPrinted() {
        assertFalse(base.copy(rec = "secret").toString().contains("secret"))
        val peer = CastPeer("a", "n", "h", 1, CastStatus.IDLE, true, null, null, null, 0, rec = "secret")
        assertFalse(peer.toString().contains("secret"))
    }
}
