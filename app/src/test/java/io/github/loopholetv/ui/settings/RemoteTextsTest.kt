package io.github.loopholetv.ui.settings

import android.view.KeyEvent
import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.PlayerAction
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTextsTest {
    @Test fun bindingRowText() {
        assertEquals("Rewind · Rewind", bindingRowText(KeyEvent.KEYCODE_MEDIA_REWIND, PlayerAction.REWIND))
        assertEquals("Red button · Add/remove favourite", bindingRowText(KeyEvent.KEYCODE_PROG_RED, PlayerAction.FAVOURITE))
        assertEquals("Page up · Page up", bindingRowText(KeyEvent.KEYCODE_PAGE_UP, GuideAction.PAGE_UP))
    }

    @Test fun pastDaysAutomaticText() {
        assertEquals("Automatic (3 days)", pastDaysText(AUTO_PAST_DAYS, 3))
        assertEquals("Automatic (1 day)", pastDaysText(AUTO_PAST_DAYS, 1))
        assertEquals("Automatic (0 days)", pastDaysText(AUTO_PAST_DAYS, 0))
        assertEquals("2 days", pastDaysText(2, 5))
        assertEquals("None", pastDaysText(0, 5))
    }
}
