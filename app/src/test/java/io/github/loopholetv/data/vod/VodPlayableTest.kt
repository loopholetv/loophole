package io.github.loopholetv.data.vod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlayableTest {
    @Test fun toStringHidesTheStreamUrl() {
        val p = VodPlayable(
            VodRef(VodKind.EPISODE, 3, "xe:101"), "Pilot",
            "http://xt.test/series/user1/s3cretPass/101.mkv", "UA/1", "http://ref.test/",
        )
        val text = p.toString()
        assertFalse(text, text.contains("s3cretPass"))
        assertFalse(text, text.contains("user1"))
        assertTrue(text, text.contains("xe:101"))
        assertTrue(text, text.contains("Pilot"))
    }
}
