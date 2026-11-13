package io.github.loopholetv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ContentCountsTextTest {
    private val en = Locale.US

    @Test fun channelsOnlyKeepsTheExistingWording() = assertEquals("1,360 channels", contentCounts(1360, 0, 0, en))

    @Test fun singularForms() = assertEquals("1 channel · 1 film · 1 show", contentCounts(1, 1, 1, en))

    @Test fun allThreeParts() = assertEquals("120 channels · 850 films · 40 shows", contentCounts(120, 850, 40, en))

    @Test fun filmsOnlyPlaylistDoesNotSayZeroChannels() = assertEquals("850 films", contentCounts(0, 850, 0, en))

    @Test fun showsAndFilmsWithoutChannels() = assertEquals("1,200 films · 3 shows", contentCounts(0, 1200, 3, en))

    @Test fun emptyPlaylistStillSaysZeroChannels() = assertEquals("0 channels", contentCounts(0, 0, 0, en))
}
