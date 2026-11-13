package io.github.loopholetv.player.engine

import android.content.Context
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.settings.ColourMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class ColourPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun taggedHdrFull() = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.allocate(25))
    }

    private fun MediaFormat.assertStandard() {
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        assertFalse(containsKey(MediaFormat.KEY_HDR_STATIC_INFO))
    }

    @Test
    fun standardReplacesStreamTagsWithSdrBt709Limited() {
        taggedHdrFull().apply { applyColourMode(ColourMode.STANDARD) }.assertStandard()
    }

    @Test
    fun standardTagsAnUntaggedStream() {
        MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply { applyColourMode(ColourMode.STANDARD) }.assertStandard()
    }

    @Test
    fun autoLeavesStreamTagsAlone() {
        val f = taggedHdrFull().apply { applyColourMode(ColourMode.AUTO) }
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, f.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_RANGE_FULL, f.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, f.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        assertTrue(f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO))
    }

    @Test
    fun engineUsesTheAppVideoRenderer() {
        val player = buildEnginePlayer(context, EngineParts())
        try {
            val video = (0 until player.rendererCount).map { player.getRenderer(it) }
                .filter { it.trackType == C.TRACK_TYPE_VIDEO }
            val renderer = video.first()
            assertTrue(renderer is EngineVideoRenderer)
            // A surface change (preview to full screen) gets a new decoder rather than redirecting the old one.
            assertTrue((renderer as EngineVideoRenderer).newDecoderOnSurfaceChange("c2.vendor.avc.decoder"))
            assertEquals(1, video.count { it is MediaCodecVideoRenderer })
        } finally {
            player.release()
        }
    }
}
