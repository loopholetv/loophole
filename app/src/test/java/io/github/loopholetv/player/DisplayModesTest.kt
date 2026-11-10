package io.github.loopholetv.player

import android.view.Display
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
class DisplayModesTest {

    private val rates = listOf(60f, 23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f)

    // Id 0 is the 60 Hz mode, matching the display's current mode id in Robolectric.
    private val fullHd = rates.mapIndexed { i, hz -> ModeInfo(i, 1920, 1080, hz) }
    private val current = fullHd.first()

    private fun pick(fps: Float, modes: List<ModeInfo> = fullHd) = pickDisplayMode(modes, current, fps)?.refreshHz

    @Test
    fun pickDisplayModeCases() {
        assertEquals(50f, pick(25f))
        assertEquals(50f, pick(50f))
        assertEquals(59.94f, pick(29.97f))
        assertNull(pick(30f))
        assertNull(pick(60f))
        assertEquals(23.976f, pick(23.976f))
        assertEquals(24f, pick(24f))
        assertNull(pick(0f))
        assertNull(pick(-1f))
        assertEquals(50f, pick(12.5f))
        val fiftyOnlyIn4k = listOf(current, ModeInfo(9, 3840, 2160, 50f))
        assertNull(pick(25f, fiftyOnlyIn4k))
    }

    @Test
    fun pickPrefersLowestRateAboveSixtyWhenNothingBelow() {
        val modes = listOf(current, ModeInfo(1, 1920, 1080, 120f), ModeInfo(2, 1920, 1080, 240f))
        assertEquals(120f, pick(120f, modes))
    }

    @Test
    fun pickIgnoresNonFiniteFrameRate() {
        assertNull(pick(Float.NaN))
        assertNull(pick(Float.POSITIVE_INFINITY))
    }

    private fun displayModes(): Array<Display.Mode> = fullHd.map {
        ShadowDisplayManager.ModeBuilder.modeBuilder(it.id)
            .setWidth(it.width).setHeight(it.height).setRefreshRate(it.refreshHz).build()
    }.toTypedArray()

    private fun window() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().window

    @Test
    fun switcherSetsAndRestoresPreferredMode() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, *displayModes())
        val window = window()
        val switcher = DisplayModeSwitcher(window)

        assertTrue(switcher.matchFrameRate(25f))
        assertEquals(fullHd.first { it.refreshHz == 50f }.id, window.attributes.preferredDisplayModeId)

        switcher.restore()
        assertEquals(0, window.attributes.preferredDisplayModeId)
    }

    @Test
    fun switcherNoopWhenAlreadyMatching() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, *displayModes())
        val window = window()
        val switcher = DisplayModeSwitcher(window)

        assertFalse(switcher.matchFrameRate(30f))
        assertFalse(switcher.matchFrameRate(0f))
        assertEquals(0, window.attributes.preferredDisplayModeId)

        assertTrue(switcher.matchFrameRate(25f))
        assertFalse(switcher.matchFrameRate(50f))
    }

    @Test
    fun switcherReturnsToCurrentModeWhenAnotherIsPending() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, *displayModes())
        val window = window()
        val switcher = DisplayModeSwitcher(window)

        assertTrue(switcher.matchFrameRate(25f))
        // The display is still at 60 Hz; 30 fps content should cancel the pending 50 Hz request.
        assertTrue(switcher.matchFrameRate(30f))
        assertEquals(current.id, window.attributes.preferredDisplayModeId)
    }
}
