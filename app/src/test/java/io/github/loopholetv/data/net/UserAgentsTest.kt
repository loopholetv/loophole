package io.github.loopholetv.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {
    @Test fun defaultUsesVersion() = assertEquals("Loophole/1.2", UserAgents.default("1.2"))

    @Test fun streamPrecedence() {
        assertEquals("VLC", UserAgents.forStream("VLC", "P", "G", "D"))
        assertEquals("G", UserAgents.forStream(null, " ", "G", "D"))
        assertEquals("P", UserAgents.forStream("", "P", "G", "D"))
        assertEquals("D", UserAgents.forStream(null, "", "  ", "D"))
    }

    @Test fun nonAsciiCharactersAreDroppedOrFallBack() {
        // OkHttp rejects header values outside \t and 0x20..0x7E: such a user agent broke every request.
        assertEquals("Caf/1.0", UserAgents.forDownload("Café/1.0", "G", "D"))
        assertEquals("Caf/1.0", UserAgents.forStream("Café/1.0", "P", "G", "D"))
        // Nothing usable left: the next candidate, then the default.
        assertEquals("G", UserAgents.forDownload("ΕΛΛΑΔΑ", "G", "D"))
        assertEquals("D", UserAgents.forStream("\u0001\u0002", "€€", "  ", "D"))
        assertEquals("A\tB", UserAgents.forDownload("A\tB\n", null, "D"))
    }

    @Test fun acceptableInput() {
        assertTrue(UserAgents.isAcceptable("Kodi/20 (Linux; Android 9)"))
        assertTrue(UserAgents.isAcceptable("  ")) // blank = default
        assertFalse(UserAgents.isAcceptable("Café/1.0"))
        assertFalse(UserAgents.isAcceptable("a\nb"))
    }

    @Test fun downloadPrecedence() {
        assertEquals("P", UserAgents.forDownload("P", "G", "D"))
        assertEquals("G", UserAgents.forDownload(" ", "G", "D"))
        assertEquals("D", UserAgents.forDownload(null, null, "D"))
    }
}
