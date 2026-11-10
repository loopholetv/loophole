package io.github.loopholetv.ui.settings

import android.view.KeyEvent
import io.github.loopholetv.ui.common.KeyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureKeyRouterTest {
    private val router = CaptureKeyRouter()

    @Test fun backCancelsOnReleaseOnly() {
        assertNull(router.onKey(KeyPhase.Down, KeyEvent.KEYCODE_BACK, 0))
        assertNull(router.onKey(KeyPhase.Down, KeyEvent.KEYCODE_BACK, 1))
        assertEquals(CaptureResult.Cancel, router.onKey(KeyPhase.Up, KeyEvent.KEYCODE_BACK, 0))
    }

    @Test fun buttonIsTakenOnFirstPressOnly() {
        assertEquals(CaptureResult.Key(KeyEvent.KEYCODE_PROG_RED), router.onKey(KeyPhase.Down, KeyEvent.KEYCODE_PROG_RED, 0))
        assertNull(router.onKey(KeyPhase.Down, KeyEvent.KEYCODE_PROG_RED, 2))
        assertNull(router.onKey(KeyPhase.Up, KeyEvent.KEYCODE_PROG_RED, 0))
    }

    @Test fun arrowsAreReportedSoThePageCanRefuseThem() {
        assertEquals(CaptureResult.Key(KeyEvent.KEYCODE_DPAD_LEFT), router.onKey(KeyPhase.Down, KeyEvent.KEYCODE_DPAD_LEFT, 0))
    }
}
