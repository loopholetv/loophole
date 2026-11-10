package io.github.loopholetv.ui.manage

import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.player.ASK_EACH_TIME
import io.github.loopholetv.player.ExternalPlayerApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEditDialogsTest {
    private val vlc = ExternalPlayerApp("org.videolan.vlc", "VLC")
    private val kodi = ExternalPlayerApp("org.xbmc.kodi", "Kodi")

    private fun labels(playback: ChannelPlaybackPrefs) =
        channelEditOptions(testChannel(), hidden = false, canMove = false, playback = playback) {}.map { it.label }

    @Test fun playbackOptionsAppendedWithCurrentValues() {
        val l = labels(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE, audio = DecoderChoice.HARDWARE, externalPlayer = vlc.packageName))
        assertEquals(
            listOf("Video decoder (Software)", "Audio decoder (Hardware)", "External player (VLC)"),
            l.takeLast(3),
        )
        assertEquals("Guide time shift (Off)", l[l.size - 4])
    }

    @Test fun defaultPrefsShowSameAsSettingsAndOff() {
        assertEquals(
            listOf("Video decoder (Same as Settings)", "Audio decoder (Same as Settings)", "External player (Off)"),
            labels(ChannelPlaybackPrefs.NONE).takeLast(3),
        )
        assertEquals("External player (Ask each time)", labels(ChannelPlaybackPrefs(externalPlayer = ASK_EACH_TIME)).last())
    }

    @Test fun optionsReportTheirEdit() {
        val picked = mutableListOf<ChannelEdit>()
        channelEditOptions(testChannel(), hidden = false, canMove = false) { picked += it }.takeLast(3).forEach { it.onClick() }
        assertEquals(listOf(ChannelEdit.VideoDecoder, ChannelEdit.AudioDecoder, ChannelEdit.ExternalPlayer), picked)
    }

    @Test fun decoderTextNames() {
        assertEquals("Same as Settings", decoderText(null))
        assertEquals("Hardware", decoderText(DecoderChoice.HARDWARE))
        assertEquals("Software", decoderText(DecoderChoice.SOFTWARE))
        assertEquals(listOf("Same as Settings", "Hardware", "Software"), DECODER_CHOICES.map(::decoderText))
    }

    @Test fun decoderPickCallsEdits() = runTest {
        val edits = FakeEditActions()
        var done = 0
        val actions = ChannelEditActions(edits, this) {}
        val ch = testChannel()
        actions.setVideoDecoder(ch, DECODER_CHOICES[0]) { done++ }
        actions.setVideoDecoder(ch, DECODER_CHOICES[2]) { done++ }
        actions.setAudioDecoder(ch, DECODER_CHOICES[1]) { done++ }
        advanceUntilIdle()
        assertEquals(
            listOf("setVideoDecoder(1:k1, null)", "setVideoDecoder(1:k1, SOFTWARE)", "setAudioDecoder(1:k1, HARDWARE)"),
            edits.calls,
        )
        assertEquals(3, done)
    }

    @Test fun externalChoicesListInstalledThenAsk() {
        val values = externalChoiceValues(listOf(vlc, kodi))
        assertEquals(listOf(null, "org.videolan.vlc", "org.xbmc.kodi", ASK_EACH_TIME), values)
        assertEquals(listOf("Off", "VLC", "Kodi", "Ask each time"), externalChoiceLabels(listOf(vlc, kodi)))
        assertEquals(listOf("Off", "Ask each time"), externalChoiceLabels(emptyList()))
    }

    @Test fun savedPlayerNoLongerInstalledStaysListedAndSelected() {
        val values = externalChoiceValues(listOf(vlc), "com.old.player")
        assertEquals(listOf(null, "org.videolan.vlc", "com.old.player", ASK_EACH_TIME), values)
        assertEquals("com.old.player (not installed)", externalChoiceLabels(listOf(vlc), "com.old.player")[2])
        assertEquals(2, externalChoiceIndex(values, "com.old.player"))
        assertEquals(listOf("Off", "VLC", "Ask each time"), externalChoiceLabels(listOf(vlc), ASK_EACH_TIME))
    }

    @Test fun noteOnlyWhenNothingInstalled() {
        assertEquals("No other video player is installed.", externalNote(emptyList()))
        assertNull(externalNote(listOf(vlc)))
    }

    @Test fun initialSelectionIsTheSavedValue() {
        assertEquals(2, decoderChoiceIndex(DecoderChoice.SOFTWARE))
        assertEquals(1, decoderChoiceIndex(DecoderChoice.HARDWARE))
        assertEquals(0, decoderChoiceIndex(null))
        val saved = ChannelPlaybackPrefs(externalPlayer = kodi.packageName)
        val installed = listOf(vlc, kodi)
        assertEquals(2, externalChoiceIndex(externalChoiceValues(installed, saved.externalPlayer), saved.externalPlayer))
    }

    @Test fun playbackDialogsWaitForSavedPrefs() {
        for (e in listOf(ChannelEdit.VideoDecoder, ChannelEdit.AudioDecoder, ChannelEdit.ExternalPlayer)) {
            assertEquals(false, playbackEditReady(e, null))
            assertEquals(true, playbackEditReady(e, ChannelPlaybackPrefs.NONE))
        }
        assertEquals(true, playbackEditReady(ChannelEdit.Rename, null))
        assertEquals(true, playbackEditReady(null, null))
    }

    @Test fun externalCurrentIsFocusedFirst() {
        val values = externalChoiceValues(listOf(vlc))
        assertEquals(1, externalChoiceIndex(values, vlc.packageName))
        assertEquals(0, externalChoiceIndex(values, null))
        assertEquals(2, externalChoiceIndex(values, ASK_EACH_TIME))
        assertEquals(0, externalChoiceIndex(values, "gone.package"))
    }

    @Test fun externalOffStoresNull() = runTest {
        val edits = FakeEditActions()
        val actions = ChannelEditActions(edits, this) {}
        val values = externalChoiceValues(listOf(vlc))
        actions.setExternalPlayer(testChannel(), values[0]) {}
        actions.setExternalPlayer(testChannel(), values[1]) {}
        actions.setExternalPlayer(testChannel(), values[2]) {}
        advanceUntilIdle()
        assertEquals(
            listOf("setExternalPlayer(1:k1, null)", "setExternalPlayer(1:k1, org.videolan.vlc)", "setExternalPlayer(1:k1, ask)"),
            edits.calls,
        )
        assertNull(values[0])
    }
}
