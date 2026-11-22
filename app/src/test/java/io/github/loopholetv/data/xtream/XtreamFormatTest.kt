package io.github.loopholetv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamFormatTest {
    private val ts = "http://example.test:8080/live/u/p/42.ts"

    @Test fun hlsRewritesTsToM3u8() {
        assertEquals("http://example.test:8080/live/u/p/42.m3u8", applyXtreamFormat(ts, "hls"))
    }

    @Test fun queryStringIsKept() {
        assertEquals(
            "http://example.test:8080/live/u/p/42.m3u8?token=a.ts",
            applyXtreamFormat("http://example.test:8080/live/u/p/42.ts?token=a.ts", "hls"),
        )
    }

    @Test fun urlsWithoutTsAreUntouched() {
        val m3u8 = "http://example.test:8080/live/u/p/42.m3u8"
        val bare = "http://example.test:8080/live/u/p/42"
        assertEquals(m3u8, applyXtreamFormat(m3u8, "hls"))
        assertEquals(bare, applyXtreamFormat(bare, "hls"))
    }

    @Test fun nullOrTsFormatIsUntouched() {
        assertEquals(ts, applyXtreamFormat(ts, null))
        assertEquals(ts, applyXtreamFormat(ts, "ts"))
    }
}
