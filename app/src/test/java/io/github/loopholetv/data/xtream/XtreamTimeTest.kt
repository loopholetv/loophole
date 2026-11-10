package io.github.loopholetv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamTimeTest {
    @Test fun xtreamZoneFromInfo() {
        assertEquals("America/New_York", xtreamZone("America/New_York", null, null))
        // 21:30 local against 20:00 UTC.
        assertEquals("+01:30", xtreamZone(null, "2026-11-10 21:30:00", 1794340800))
        // 21:37 snaps to the nearest quarter hour.
        assertEquals("+01:30", xtreamZone(null, "2026-11-10 21:37:00", 1794340800))
        assertEquals("-05:00", xtreamZone(null, "2026-11-10 15:00:00", 1794340800))
        assertEquals("+00:00", xtreamZone(null, "2026-11-10 20:00:00", 1794340800))
        assertNull(xtreamZone("Not/AZone", null, null))
        assertNull(xtreamZone(null, null, null))
        assertNull(xtreamZone(null, "garbage", 1794340800))
    }

    @Test fun bareOffsetIsNotAZoneId() {
        assertNull(xtreamZone("+02:00", null, null))
        assertEquals("+01:00", xtreamZone("+02:00", "2026-11-10 21:00:00", 1794340800))
    }

    @Test fun prefixedOffsetIdsBecomeOffsets() {
        assertEquals("+02:00", xtreamZone("UTC+2", null, null))
        assertEquals("-05:00", xtreamZone("GMT-5", null, null))
        assertEquals("+00:00", xtreamZone("UTC+0", null, null))
        assertEquals("UTC", xtreamZone("UTC", null, null))
        assertEquals("Etc/GMT+2", xtreamZone("Etc/GMT+2", null, null))
    }

    @Test fun archiveExtChoice() {
        assertEquals("ts", archiveExt(emptyList()))
        assertEquals("ts", archiveExt(listOf("m3u8", "ts")))
        assertEquals("m3u8", archiveExt(listOf("m3u8")))
        assertEquals("ts", archiveExt(listOf("rtmp")))
    }

    @Test fun xtreamZoneUsesAsciiDigitsUnderArabicLocale() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale("ar"))
        try {
            val zone = xtreamZone(null, "2026-11-10 21:30:00", 1794340800)
            assertEquals("+01:30", zone)
            assertEquals(java.time.ZoneOffset.ofHoursMinutes(1, 30), java.time.ZoneId.of(zone!!))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
