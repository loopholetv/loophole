package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.tmdb.KeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTextsTest {
    @Test fun settingsSectionIsCalledCompanion() {
        assertEquals("Companion", SECTION_COMPANION)
    }

    @Test fun detailsCopyIsExact() {
        assertEquals("Film and show details", SECTION_DETAILS)
        assertEquals("TMDB key", DT_KEY)
        assertEquals("Remove key", DT_REMOVE)
        assertEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.", TMDB_CREDIT)
    }

    @Test fun keyStatusWords() {
        assertNull(keyStatusText(KeyStatus.NONE))
        assertEquals("Checking…", keyStatusText(KeyStatus.CHECKING))
        assertEquals("Working", keyStatusText(KeyStatus.WORKING))
        assertEquals("Key not accepted", keyStatusText(KeyStatus.BAD_KEY))
        assertEquals("Couldn't reach TMDB", keyStatusText(KeyStatus.UNREACHABLE))
    }

    @Test fun keyIsMaskedToItsLastFour() {
        assertEquals("••••cdef", maskedTmdbKey(" 0123456789abcdef0123456789abcdef "))
        assertEquals("Not set", maskedTmdbKey(""))
        assertEquals("••••", maskedTmdbKey("ab"))
    }
}
