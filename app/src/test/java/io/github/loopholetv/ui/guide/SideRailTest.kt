package io.github.loopholetv.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideRailTest {
    @Test fun railTvLabelIsGuide() {
        assertEquals("Guide", RailItem.TV.label)
    }

    @Test fun railHasNoMyListAndKeepsRecordings() {
        val labels = RailItem.entries.map { it.label }
        assertFalse("My list" in labels)
        assertTrue("Recordings" in labels)
    }
}
