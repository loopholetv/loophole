package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ProgrammeEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideAtTest {
    @Test fun roundTripsAKeyWithSlashes() {
        val at = GuideAt(4, "live/channel 1/x", 1_700_000_000_000)
        assertEquals(at, GuideAt.parse(at.encode()))
    }

    @Test fun garbageParsesToNull() {
        assertNull(GuideAt.parse(null))
        assertNull(GuideAt.parse(""))
        assertNull(GuideAt.parse("x/key/12"))
        assertNull(GuideAt.parse("4/key/soon"))
        assertNull(GuideAt.parse("4//12"))
    }

    private fun programme(startMin: Long, lengthMin: Long = 30) = ProgrammeEntity(
        epgChannelId = "e", start = Instant.ofEpochSecond(startMin * 60), stop = Instant.ofEpochSecond((startMin + lengthMin) * 60),
        title = "P$startMin", subTitle = null, description = null, category = null, generation = 1,
    )

    @Test fun programmeAtPrefersTheExactStartThenTheOneCovering() {
        val list = listOf(programme(0), programme(30), programme(60))
        assertEquals("P30", programmeAt(list, 30 * 60_000L)?.title)
        assertEquals("P30", programmeAt(list, 45 * 60_000L)?.title)
        assertNull(programmeAt(list, 200 * 60_000L))
    }
}
