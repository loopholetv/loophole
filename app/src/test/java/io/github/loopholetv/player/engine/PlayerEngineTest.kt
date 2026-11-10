package io.github.loopholetv.player.engine

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.settings.BufferPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ffmpegUnavailableReportsFalseWithoutThrowing() {
        // Robolectric cannot load the native library, which stands in for a device where it fails to load.
        assertFalse(ffmpegAvailable())
        assertFalse(ffmpegSupports(MimeTypes.AUDIO_AC3))
        assertFalse(ffmpegSupports(MimeTypes.AUDIO_MPEG_L2))
    }

    @Test
    fun ffmpegRendererClassPresent() {
        assertNotNull(Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"))
    }

    @Test
    fun engineBuildsPlayerWithLoadControl() {
        val parts = EngineParts(loadControl = SwitchableLoadControl(BufferPreset.SMALL, 256))
        val player = buildEnginePlayer(context, parts)
        try {
            assertEquals(Player.STATE_IDLE, player.playbackState)
            // The FFmpeg renderer is added after the MediaCodec one, so an audio renderer count of two shows it loaded.
            val audioRenderers = (0 until player.rendererCount).count { player.getRendererType(it) == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            assertEquals(2, audioRenderers)
            assertEquals("FfmpegAudioRenderer", (0 until player.rendererCount)
                .map { player.getRenderer(it).name }
                .first { it.startsWith("Ffmpeg") })

            // A preset set before prepare() is picked up by the player's own load control.
            parts.loadControl.setPreset(BufferPreset.LARGE)
            player.setMediaSource(FakeMediaSource())
            player.prepare()
            RobolectricUtil.runMainLooperUntil { parts.loadControl.activePreset == BufferPreset.LARGE }
        } finally {
            player.release()
        }
    }
}
