package io.github.loopholetv.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class SideRailTest {
    @Test fun railTvLabelIsGuide() {
        assertEquals("Guide", RailItem.TV.label)
    }
}
