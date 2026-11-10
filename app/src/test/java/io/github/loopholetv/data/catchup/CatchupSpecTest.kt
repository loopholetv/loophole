package io.github.loopholetv.data.catchup

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupSpecTest {
    private val xcUrl = "http://example.test/live/u/p/42.ts"
    private val now = Instant.ofEpochSecond(1794350000)

    @Test fun modesParsedCaseInsensitively() {
        assertEquals(CatchupMode.XC, catchupSpec("XC", 3, null, xcUrl)?.mode)
        assertEquals(CatchupMode.FLUSSONIC_HLS, catchupSpec("Flussonic", 3, null, "http://example.test/c/index.m3u8")?.mode)
        assertEquals(CatchupMode.FLUSSONIC_HLS, catchupSpec("flussonic-hls", 3, null, "http://example.test/c/index.m3u8")?.mode)
        assertEquals(CatchupMode.FLUSSONIC_TS, catchupSpec("fs", 3, null, "http://example.test/c/mpegts")?.mode)
        assertEquals(CatchupMode.FLUSSONIC_TS, catchupSpec("flussonic-ts", 3, null, "http://example.test/c/mpegts")?.mode)
        assertEquals(CatchupMode.SHIFT, catchupSpec("timeshift", 3, null, "http://example.test/c.ts")?.mode)
        assertEquals(CatchupMode.SHIFT, catchupSpec(" Shift ", 3, null, "http://example.test/c.ts")?.mode)
        for (type in listOf("vod", "xtream", "", null)) {
            assertNull("type=$type", catchupSpec(type, 3, "?s={utc}", xcUrl))
        }
    }

    @Test fun missingDaysDefaultsToOne() {
        assertEquals(1, DEFAULT_CATCHUP_DAYS)
        assertEquals(1, catchupSpec("xc", null, null, xcUrl)?.days)
        assertEquals(7, catchupSpec("xc", 7, null, xcUrl)?.days)
        assertNull(catchupSpec("xc", 0, null, xcUrl))
        assertNull(catchupSpec("xc", -1, null, xcUrl))
    }

    @Test fun appendWithoutSourceIsNoCatchup() {
        assertNull(catchupSpec("append", 3, null, "http://example.test/c.m3u8"))
        assertNull(catchupSpec("append", 3, "  ", "http://example.test/c.m3u8"))
        assertNull(catchupSpec("default", 3, null, "http://example.test/c.m3u8"))
        assertNotNull(catchupSpec("default", 3, "?s={utc}", "http://example.test/c.m3u8"))
        assertNotNull(catchupSpec("append", 3, "?s={utc}", "http://example.test/c.m3u8"))
    }

    @Test fun xcNeedsMatchingStreamUrl() {
        assertNotNull(catchupSpec("xc", 3, null, "http://example.test/live/u/p/42.ts"))
        assertNotNull(catchupSpec("xc", 3, null, "http://example.test/live/u/p/42.m3u8"))
        assertNotNull(catchupSpec("xc", 3, null, "http://example.test/live/u/p/42"))
        assertNotNull(catchupSpec("xc", 3, null, "http://example.test/u/p/42"))
        assertNull(catchupSpec("xc", 3, null, "http://example.test/live/u/p/42.ts?x=1"))
        assertNull(catchupSpec("xc", 3, null, "http://example.test/index.html"))
    }

    @Test fun canPlayWindow() {
        val spec = catchupSpec("xc", 3, null, xcUrl)!!
        assertEquals(Duration.ofSeconds(120), spec.liveLag)
        assertEquals(now.minus(Duration.ofDays(3)), spec.earliest(now))
        assertTrue(spec.canPlay(now.minus(Duration.ofHours(1)), now))
        assertFalse(spec.canPlay(now.minusSeconds(30), now))
        assertFalse(spec.canPlay(now.minus(Duration.ofDays(3)).minus(Duration.ofMinutes(1)), now))
        assertTrue(spec.canPlay(now.minus(Duration.ofDays(3)).plus(Duration.ofMinutes(1)), now))
    }

    @Test fun secondGranularSourcesUseShortLag() {
        val spec = catchupSpec("shift", 3, null, "http://example.test/c.ts")!!
        assertEquals(Duration.ofSeconds(60), spec.liveLag)
        assertTrue(spec.canPlay(now.minusSeconds(90), now))
        assertFalse(spec.canPlay(now.minusSeconds(30), now))
    }

    @Test fun minuteGranularDetection() {
        fun granular(source: String) = catchupSpec("default", 1, source, "http://example.test/c")!!.minuteGranular
        assertFalse(granular("http://example.test/a?s={utc}"))
        assertFalse(granular("http://example.test/a?s={utc:YmdHMS}"))
        assertFalse(granular("http://example.test/a?o={offset:1}"))
        assertTrue(granular("http://example.test/a?d={Y}{m}{d}"))
        assertTrue(granular("http://example.test/a?d={H}"))
        assertTrue(granular("http://example.test/a?d={utc:YmdHM}"))
        assertTrue(granular("http://example.test/a?d=\${start:Ymd}"))
        assertTrue(granular("http://example.test/a?d={duration:60}"))
        assertTrue(catchupSpec("xc", 1, null, xcUrl)!!.minuteGranular)
        assertFalse(catchupSpec("shift", 1, null, "http://example.test/c.ts")!!.minuteGranular)
    }
}
