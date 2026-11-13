package io.github.loopholetv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastModelsTest {
    private val full = CastAnnounce("a1", "Living room", 8123, CastStatus.CASTING, true, "c9", "tok", 1_700_000L, seq = 42)
    private val bare = CastAnnounce("a2", "Bedroom \"TV\"", 8124, CastStatus.IDLE, false, null, null, null)

    @Test
    fun announceRoundTrips() {
        assertEquals(full, CastJson.announce(CastJson.announce(full)))
        assertEquals(bare, CastJson.announce(CastJson.announce(bare)))
    }

    @Test
    fun setupVersionRoundTripsAndReadsAsZeroWhenMissing() {
        val withSetup = full.copy(setup = 1)
        val json = CastJson.announce(withSetup)
        assertTrue(json, json.contains("\"setup\":1"))
        assertTrue(json, json.contains("\"v\":1"))
        assertEquals(withSetup, CastJson.announce(json))
        val older = json.replace(",\"setup\":1", "")
        assertEquals(0, CastJson.announce(older)!!.setup)
        assertEquals(0, CastJson.announce(json.replace("\"setup\":1", "\"setup\":-3"))!!.setup)
    }

    @Test
    fun announceUsesTheDocumentedKeys() {
        val json = CastJson.announce(full)
        listOf("id", "name", "port", "status", "accepts", "castId", "token", "startedAt", "seq", "v").forEach {
            assertTrue(json, json.contains("\"$it\":"))
        }
    }

    @Test
    fun garbageAndFutureVersionAreNull() {
        assertNull(CastJson.announce("{"))
        assertNull(CastJson.announce(""))
        assertNull(CastJson.announce("[]"))
        assertNull(CastJson.announce(CastJson.announce(full).replace("\"v\":1", "\"v\":2")))
        assertNull(CastJson.event("{"))
        assertNull(CastJson.event("nope"))
        assertNull(CastJson.event("""{"e":"unknown"}"""))
    }

    @Test
    fun eventRoundTripsEveryKind() {
        val events = listOf(
            CastEvent.Playing("c1", 3, "/s/abc/index.m3u8", true),
            CastEvent.Playing("c1", 4, "/s/abc/live", false),
            CastEvent.Idle("c1"),
            CastEvent.Stopped("c1", StopReason.STOPPED),
            CastEvent.Stopped("c1", StopReason.LEFT_APP),
            CastEvent.Stopped("c1", StopReason.EXTERNAL_PLAYER),
            CastEvent.Heartbeat("c1", 7, 2),
            CastEvent.Idle("c1", CastEvent.Idle.REASON_UNSUPPORTED),
            CastEvent.Heartbeat("c1", 7, 2, positionMs = 0),
            CastEvent.Heartbeat("c1", 7, 2, positionMs = 5_400_000_123L),
        )
        events.forEach { assertEquals(it, CastJson.event(CastJson.event(it))) }
    }

    @Test
    fun idleReasonAndHeartbeatPositionAreOptional() {
        assertEquals(CastEvent.Idle("c"), CastJson.event("""{"e":"idle","castId":"c"}"""))
        assertEquals(CastEvent.Idle("c", "unsupported"), CastJson.event("""{"e":"idle","castId":"c","reason":"unsupported"}"""))
        assertEquals(CastEvent.Idle("c"), CastJson.event("""{"e":"idle","castId":"c","reason":null}"""))
        assertEquals(
            CastEvent.Heartbeat("c", 1, 0),
            CastJson.event("""{"e":"heartbeat","castId":"c","epoch":1,"followers":0}"""),
        )
        assertEquals(
            CastEvent.Heartbeat("c", 1, 0, 90_000L),
            CastJson.event("""{"e":"heartbeat","castId":"c","epoch":1,"followers":0,"positionMs":90000}"""),
        )
        // A position that can't be one is dropped rather than failing the whole heartbeat.
        listOf("-5", "1.5", "\"9\"").forEach {
            assertEquals(
                it,
                CastEvent.Heartbeat("c", 1, 0),
                CastJson.event("""{"e":"heartbeat","castId":"c","epoch":1,"followers":0,"positionMs":$it}"""),
            )
        }
        val line = CastJson.event(CastEvent.Heartbeat("c", 1, 0, 42L))
        assertTrue(line, line.contains("\"positionMs\":42"))
        assertTrue(CastJson.event(CastEvent.Idle("c", "unsupported")).contains("\"reason\":\"unsupported\""))
    }

    @Test
    fun eventLineHasNoNewline() {
        val event = CastEvent.Playing("c\n1", 1, "/p\nq", false)
        val line = CastJson.event(event)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertEquals(event, CastJson.event(line))
    }

    private fun base(vararg drop: String, extra: String = "") =
        listOf(
            "id" to "\"a\"", "name" to "\"n\"", "port" to "80", "status" to "\"IDLE\"",
            "accepts" to "true", "seq" to "3", "v" to "1",
        ).filter { it.first !in drop }.joinToString(",", "{", "$extra}") { "\"${it.first}\":${it.second}" }

    @Test
    fun unknownKeysAreSkipped() {
        val json = base(extra = ""","x":{"a":[1,{"b":2}]},"y":[1,2]""")
        assertEquals("a", CastJson.announce(json)?.id)
    }

    @Test
    fun wrongTypedOrMissingRequiredFieldIsNull() {
        assertNull(CastJson.announce(base().replace("\"port\":80", "\"port\":\"80\"")))
        assertNull(CastJson.announce(base().replace("true", "\"yes\"")))
        assertNull(CastJson.announce(base().replace("\"IDLE\"", "\"BOGUS\"")))
        listOf("id", "name", "port", "status", "accepts", "seq", "v").forEach {
            assertNull(it, CastJson.announce(base(it)))
        }
        assertNull(CastJson.event("""{"e":"heartbeat","castId":"c"}"""))
        assertNull(CastJson.event("""{"e":"playing","castId":1,"epoch":1,"path":"/","hls":true}"""))
    }

    @Test
    fun namesWithNewlinesAndUnicodeRoundTrip() {
        val a = bare.copy(name = "Salon\n\u00e9t\u00e9 \u65e5\u672c \ud83d\udcfa")
        val json = CastJson.announce(a)
        assertFalse(json.contains('\n'))
        assertEquals(a, CastJson.announce(json))
    }

    @Test
    fun outOfRangeNumbersAreNull() {
        listOf("0", "65536", "-1", "80.5", "1e10").forEach {
            assertNull(it, CastJson.announce(base().replace("\"port\":80", "\"port\":$it")))
        }
        assertEquals(65535, CastJson.announce(base().replace("\"port\":80", "\"port\":65535"))?.port)
        listOf("-1", "2147483648", "1.5").forEach {
            assertNull(it, CastJson.event("""{"e":"heartbeat","castId":"c","epoch":$it,"followers":0}"""))
            assertNull(it, CastJson.event("""{"e":"heartbeat","castId":"c","epoch":0,"followers":$it}"""))
        }
        assertEquals(CastEvent.Heartbeat("c", 2147483647, 0),
            CastJson.event("""{"e":"heartbeat","castId":"c","epoch":2147483647,"followers":0}"""))
    }

    @Test
    fun toStringHidesTheToken() {
        assertFalse(full.copy(token = "s3cr3t").toString().contains("s3cr3t"))
        assertTrue(full.toString().contains("token=***"))
        val peer = CastPeer("a", "n", "h", 1, CastStatus.CASTING, true, "c", "secret", null, 0)
        assertFalse(peer.toString().contains("secret"))
        assertTrue(peer.toString().contains("token=***"))
    }

    @Test
    fun seqRoundTripsAndMustBeANonNegativeInteger() {
        assertEquals(42L, CastJson.announce(CastJson.announce(full))?.seq)
        assertEquals(3L, CastJson.announce(base())?.seq)
        val big = full.copy(seq = 1_731_000_000_000L)
        assertEquals(big, CastJson.announce(CastJson.announce(big)))
        listOf("-1", "1.5", "\"3\"").forEach {
            assertNull(it, CastJson.announce(base().replace("\"seq\":3", "\"seq\":$it")))
        }
    }

    @Test
    fun castingWithoutItsCastFieldsIsNull() {
        assertEquals(full, CastJson.announce(CastJson.announce(full)))
        assertNull(CastJson.announce(CastJson.announce(full.copy(castId = null))))
        assertNull(CastJson.announce(CastJson.announce(full.copy(token = null))))
        assertNull(CastJson.announce(CastJson.announce(full.copy(castStartedAtMs = null))))
        val casting = base().replace("\"IDLE\"", "\"CASTING\"")
        assertNull(CastJson.announce(casting))
        assertEquals(
            CastStatus.CASTING,
            CastJson.announce(casting.replace("}", ""","castId":"c","token":"t","startedAt":5}"""))?.status,
        )
        // Other statuses may carry no cast fields.
        assertEquals(CastStatus.WATCHING, CastJson.announce(base().replace("\"IDLE\"", "\"WATCHING\""))?.status)
    }
}
