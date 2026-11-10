package io.github.loopholetv.ui.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.SubtitleBackground
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoSurfaceTest {

    @Test
    fun resizeModeForEachAspect() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, resizeModeFor(AspectMode.FIT))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, resizeModeFor(AspectMode.STRETCH))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, resizeModeFor(AspectMode.ZOOM))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, resizeModeFor(AspectMode.RATIO_16_9))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, resizeModeFor(AspectMode.RATIO_4_3))
    }

    @Test
    fun forcedRatioOnlyForFixedModes() {
        assertEquals(16f / 9f, forcedRatio(AspectMode.RATIO_16_9)!!, 0f)
        assertEquals(4f / 3f, forcedRatio(AspectMode.RATIO_4_3)!!, 0f)
        assertNull(forcedRatio(AspectMode.FIT))
        assertNull(forcedRatio(AspectMode.STRETCH))
        assertNull(forcedRatio(AspectMode.ZOOM))
    }

    @Test
    fun captionStyleFromSettings() {
        val style = captionStyle(
            SubtitleStyle(
                size = SubtitleSize.LARGE,
                colour = SubtitleColour.YELLOW,
                background = SubtitleBackground.BLACK,
                edge = SubtitleEdge.SHADOW,
            ),
        )
        assertEquals(0xFFFFE14D.toInt(), style.foregroundColor)
        assertEquals(0xFF000000.toInt(), style.backgroundColor)
        assertEquals(Color.TRANSPARENT, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
        assertEquals(Color.BLACK, style.edgeColor)
        assertNull(style.typeface)
    }

    @Test
    fun captionStyleEdgesAndDefaults() {
        val defaults = captionStyle(SubtitleStyle())
        assertEquals(0xFFFFFFFF.toInt(), defaults.foregroundColor)
        assertEquals(Color.TRANSPARENT, defaults.backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, defaults.edgeType)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, captionStyle(SubtitleStyle(edge = SubtitleEdge.NONE)).edgeType)
        assertEquals(
            0x99000000.toInt(),
            captionStyle(SubtitleStyle(background = SubtitleBackground.SEE_THROUGH)).backgroundColor,
        )
    }

    @Test
    fun subtitleTextFractionScalesWithSize() {
        val base = androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
        assertEquals(base, subtitleTextFraction(SubtitleSize.NORMAL), 1e-6f)
        assertEquals(base * 1.5f, subtitleTextFraction(SubtitleSize.EXTRA_LARGE), 1e-6f)
    }

    @Test
    fun afrWaitsForSettledFrameRate() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(25f, 0)
        assertNull(afr.due(999))
        assertEquals(25f, afr.due(1_000))
        assertNull(afr.due(2_000))
    }

    @Test
    fun afrWaitsForFirstFrame() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onFrameRate(25f, 0)
        assertNull(afr.due(5_000))
        afr.onRenderedFirstFrame(5_000)
        assertNull(afr.due(5_999))
        assertEquals(25f, afr.due(6_000))
    }

    @Test
    fun afrRestartsWhenRateChanges() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(25f, 0)
        afr.onFrameRate(50f, 1_500)
        assertNull(afr.due(2_499))
        assertEquals(50f, afr.due(2_500))
        assertNull(afr.due(4_000))
    }

    @Test
    fun afrSameRateDoesNotRestartTimer() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(25f, 0)
        afr.onFrameRate(25f, 900)
        assertEquals(25f, afr.due(1_000))
    }

    @Test
    fun afrIgnoresUnknownRate() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(null, 0)
        assertNull(afr.due(10_000))
        afr.onFrameRate(0f, 10_000)
        assertNull(afr.due(20_000))
        afr.onFrameRate(-1f, 20_000)
        assertNull(afr.due(30_000))
        afr.onFrameRate(Float.NaN, 30_000)
        assertNull(afr.due(40_000))
    }

    @Test
    fun afrResetWaitsForNextFirstFrame() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(25f, 0)
        assertEquals(25f, afr.due(1_000))
        afr.reset()
        afr.onFrameRate(25f, 2_000)
        assertNull(afr.due(9_000))
        afr.onRenderedFirstFrame(9_000)
        assertEquals(25f, afr.due(10_000))
    }

    @Test
    fun afrNeverInPipOrWhenOff() {
        assertTrue(afrEnabled(autoFrameRate = true, inPip = false))
        assertFalse(afrEnabled(autoFrameRate = false, inPip = false))
        assertFalse(afrEnabled(autoFrameRate = true, inPip = true))
        assertFalse(afrEnabled(autoFrameRate = false, inPip = true))
    }

    @Test
    fun surfaceCallbackFalseInPip() {
        assertTrue(surfaceAttachedFor(inPip = false, disposed = false))
        assertFalse(surfaceAttachedFor(inPip = true, disposed = false))
        assertFalse(surfaceAttachedFor(inPip = false, disposed = true))
        assertFalse(surfaceAttachedFor(inPip = true, disposed = true))
    }

    @Test
    fun subtitlesLeaveTheContentFrameOnlyForZoom() {
        assertEquals(SubtitleHost.OVERLAY, subtitleHostFor(AspectMode.ZOOM))
        for (mode in AspectMode.entries - AspectMode.ZOOM) {
            assertEquals(SubtitleHost.CONTENT_FRAME, subtitleHostFor(mode))
        }
    }

    @Test
    fun subtitlesHiddenWhileTheQuickMenuIsOpen() {
        val view = androidx.media3.ui.SubtitleView(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        view.showSubtitles(subtitlesShown(quickMenuOpen = true))
        assertEquals(android.view.View.INVISIBLE, view.visibility)
        view.showSubtitles(subtitlesShown(quickMenuOpen = false))
        assertEquals(android.view.View.VISIBLE, view.visibility)
    }

    @Test
    fun pictureShowingNeedsReadyAndAVideoSize() {
        assertTrue(pictureShowing(Player.STATE_READY, VideoSize(1920, 1080)))
        assertFalse(pictureShowing(Player.STATE_READY, VideoSize.UNKNOWN))
        assertFalse(pictureShowing(Player.STATE_BUFFERING, VideoSize(1920, 1080)))
        assertFalse(pictureShowing(Player.STATE_IDLE, VideoSize(1920, 1080)))
    }

    @Test
    fun afrStartWhilePictureShowingCountsFrameAsNow() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.start(25f, pictureShowing = true, nowMs = 5_000)
        assertNull(afr.due(5_999))
        assertEquals(25f, afr.due(6_000))
    }

    @Test
    fun afrStartWithoutPictureWaitsForFirstFrame() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.onRenderedFirstFrame(0)
        afr.onFrameRate(50f, 0)
        afr.start(25f, pictureShowing = false, nowMs = 5_000)
        assertNull(afr.due(9_000))
        afr.onRenderedFirstFrame(9_000)
        assertEquals(25f, afr.due(10_000))
    }

    @Test
    fun afrMediaChangeReportsSameRateAgainAfterNewFrame() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.start(25f, pictureShowing = true, nowMs = 0)
        assertEquals(25f, afr.due(1_000))
        afr.onMediaChanged(25f, 2_000)
        assertNull(afr.due(3_500))
        afr.onRenderedFirstFrame(3_000)
        assertEquals(25f, afr.due(4_000))
    }

    @Test
    fun afrMsUntilDue() {
        val afr = AfrTrigger(settleMs = 1_000)
        assertNull(afr.msUntilDue(0))
        afr.onFrameRate(25f, 0)
        assertNull(afr.msUntilDue(0))
        afr.onRenderedFirstFrame(200)
        assertEquals(1_000L, afr.msUntilDue(200))
        assertEquals(0L, afr.msUntilDue(5_000))
        assertEquals(25f, afr.due(5_000))
        assertNull(afr.msUntilDue(5_000))
    }

    @Test
    fun afrRetryReportsTheRateAgain() {
        val afr = AfrTrigger(settleMs = 1_000)
        afr.start(25f, pictureShowing = true, nowMs = 0)
        assertEquals(25f, afr.due(1_000))
        afr.retry()
        assertEquals(0L, afr.msUntilDue(1_250))
        assertEquals(25f, afr.due(1_250))
        assertNull(afr.due(2_000))
    }
}
