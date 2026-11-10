package io.github.loopholetv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.TrackNameProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerTracksTest {

    private val names = TrackNameProvider { f -> "name:${f.language ?: "?"}:${f.channelCount}" }

    private fun video(height: Int, bitrate: Int = Format.NO_VALUE, fps: Float = Format.NO_VALUE.toFloat()) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(height * 16 / 9)
            .setHeight(height)
            .setPeakBitrate(bitrate)
            .setFrameRate(fps)
            .build()

    private fun audio(lang: String, channels: Int = 2) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setLanguage(lang)
        .setChannelCount(channels)
        .build()

    private fun text(lang: String) = Format.Builder()
        .setSampleMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(lang)
        .build()

    private fun group(
        id: String,
        vararg formats: Format,
        adaptive: Boolean = formats.size > 1,
        supported: List<Boolean> = formats.map { true },
        selected: Int? = null,
        allSelected: Boolean = false,
    ) = Tracks.Group(
        TrackGroup(id, *formats),
        adaptive,
        IntArray(formats.size) { if (supported[it]) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE },
        BooleanArray(formats.size) { allSelected || it == selected },
    )

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())

    private val params: TrackSelectionParameters = DefaultTrackSelector.Parameters.DEFAULT

    @Test
    fun hlsVariantsListedByHeight() {
        // An adaptive selection reports every variant as selected.
        val t = tracks(group("v", video(720), video(1080), video(480), allSelected = true))
        val out = playerTracks(t, params, names)
        assertEquals(listOf("1080p", "720p", "480p"), out.video.map { it.label })
        assertEquals(listOf(TrackId("v", 1), TrackId("v", 0), TrackId("v", 2)), out.video.map { it.id })
        assertEquals(listOf(false, false, false), out.video.map { it.selected })
        assertTrue(out.videoAuto)
    }

    @Test
    fun manualQualityPickMarksOnlyThatRow() {
        val all = group("v", video(720), video(1080), video(480), allSelected = true)
        val picked = params.withChoice(tracks(all), TrackType.VIDEO, TrackChoice.Pick(TrackId("v", 0)))
        val after = tracks(group("v", video(720), video(1080), video(480), selected = 0))
        val out = playerTracks(after, picked, names)
        assertFalse(out.videoAuto)
        assertEquals(listOf(false, true, false), out.video.map { it.selected })
        assertEquals("720p", out.video.single { it.selected }.label)
    }

    @Test
    fun equalHeightsWithoutBitrateGetDistinctLabels() {
        val t = tracks(group("v", video(720), video(720), video(1080)))
        assertEquals(listOf("1080p", "720p", "720p (2)"), playerTracks(t, params, names).video.map { it.label })
    }

    @Test
    fun singleVideoTrackListsNothing() {
        val t = tracks(group("0", video(1080), selected = 0), group("1", audio("en"), selected = 0))
        val out = playerTracks(t, params, names)
        assertTrue(out.video.isEmpty())
        assertTrue(out.videoAuto)
    }

    @Test
    fun nonAdaptiveVideoGroupListsNothing() {
        val t = tracks(group("v", video(1080), video(720), adaptive = false, selected = 0))
        assertTrue(playerTracks(t, params, names).video.isEmpty())
    }

    @Test
    fun equalHeightsShowBitrate() {
        val t = tracks(group("v", video(720, 2_500_000), video(720, 4_000_000), video(1080, 6_000_000)))
        val out = playerTracks(t, params, names)
        assertEquals(listOf("1080p", "720p (4.0 Mbit/s)", "720p (2.5 Mbit/s)"), out.video.map { it.label })
    }

    @Test
    fun audioAndTextUseNameProvider() {
        val provider = TrackNameProvider { f ->
            if (f.sampleMimeType == MimeTypes.AUDIO_AAC) "English, 5.1 surround" else "German"
        }
        val t = tracks(
            group("a", audio("en", 6), selected = 0),
            group("s", text("de")),
        )
        val out = playerTracks(t, params, provider)
        assertEquals(listOf(TrackOption(TrackId("a", 0), "English, 5.1 surround", true)), out.audio)
        assertEquals(listOf(TrackOption(TrackId("s", 0), "German", false)), out.text)
        assertTrue(out.textOff)
    }

    @Test
    fun unsupportedTracksHidden() {
        val t = tracks(
            group("v", video(1080), video(720), video(480), supported = listOf(true, false, true)),
            group("a1", audio("en"), supported = listOf(false)),
            group("a2", audio("de")),
            group("s", text("fr"), supported = listOf(false)),
        )
        val out = playerTracks(t, params, names)
        assertEquals(listOf("1080p", "480p"), out.video.map { it.label })
        assertEquals(listOf(TrackId("a2", 0)), out.audio.map { it.id })
        assertTrue(out.text.isEmpty())
    }

    @Test
    fun frameRateNullWhenUnknown() {
        assertNull(playerTracks(tracks(group("v", video(1080), selected = 0)), params, names).frameRate)
        assertEquals(25f, playerTracks(tracks(group("v", video(1080, fps = 25f), selected = 0)), params, names).frameRate)
        assertNull(playerTracks(tracks(group("v", video(1080, fps = 25f))), params, names).frameRate)
    }

    @Test
    fun textSelectedReportsTextOn() {
        val t = tracks(group("s", text("de"), selected = 0))
        val out = playerTracks(t, params, names)
        assertFalse(out.textOff)
        assertTrue(out.text.single().selected)
        val disabled = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        assertTrue(playerTracks(t, disabled, names).textOff)
    }

    @Test
    fun pickSetsOverrideAndAutoClears() {
        val g = group("v", video(1080), video(720), selected = 0)
        val t = tracks(g, group("a", audio("en"), audio("de"), adaptive = false, selected = 0))
        val picked = params.withChoice(t, TrackType.VIDEO, TrackChoice.Pick(TrackId("v", 1)))
        assertEquals(TrackSelectionOverride(g.mediaTrackGroup, 1), picked.overrides[g.mediaTrackGroup])
        assertFalse(playerTracks(t, picked, names).videoAuto)

        val withAudio = picked.withChoice(t, TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
        assertEquals(2, withAudio.overrides.size)

        val auto = withAudio.withChoice(t, TrackType.VIDEO, TrackChoice.Auto)
        assertTrue(auto.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO })
        assertEquals(1, auto.overrides.size)
        assertTrue(playerTracks(t, auto, names).videoAuto)
    }

    @Test
    fun pickUnknownGroupIsNoOp() {
        val t = tracks(group("a", audio("en")))
        assertSame(params, params.withChoice(t, TrackType.AUDIO, TrackChoice.Pick(TrackId("gone", 0))))
        assertSame(params, params.withChoice(t, TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 5))))
        assertSame(params, params.withChoice(t, TrackType.TEXT, TrackChoice.Pick(TrackId("a", 0))))
    }

    @Test
    fun pickSurvivesGroupsArrivingLate() {
        val early = tracks(group("1", video(576), selected = 0), group("2", audio("de"), selected = 0))
        val id = playerTracks(early, params, names).audio.single().id
        val late = tracks(
            group("1", video(576), selected = 0),
            group("3", audio("en"), selected = 0),
            group("2", audio("de")),
        )
        val picked = params.withChoice(late, TrackType.AUDIO, TrackChoice.Pick(id))
        val override = picked.overrides.values.single()
        assertEquals("2", override.mediaTrackGroup.id)
        assertEquals(listOf(0), override.trackIndices)
    }

    @Test
    fun textOffDisablesType() {
        val g = group("s", text("de"), selected = 0)
        val t = tracks(g)
        val picked = params.withChoice(t, TrackType.TEXT, TrackChoice.Pick(TrackId("s", 0)))
        val off = picked.withChoice(t, TrackType.TEXT, TrackChoice.Off)
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertTrue(off.overrides.isEmpty())

        val back = off.withChoice(t, TrackType.TEXT, TrackChoice.Pick(TrackId("s", 0)))
        assertFalse(C.TRACK_TYPE_TEXT in back.disabledTrackTypes)
        assertEquals(TrackSelectionOverride(g.mediaTrackGroup, 0), back.overrides[g.mediaTrackGroup])
    }

    @Test
    fun offIgnoredForVideoAndAudio() {
        val t = tracks(group("a", audio("en")))
        assertSame(params, params.withChoice(t, TrackType.AUDIO, TrackChoice.Off))
        assertSame(params, params.withChoice(t, TrackType.VIDEO, TrackChoice.Off))
    }

    @Test
    fun newChannelParamsClearOverridesKeepPreferences() {
        val g = group("a", audio("en"), audio("de"), adaptive = false)
        val start = params.withChoice(tracks(g), TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
            .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        assertEquals(1, start.overrides.size)

        val next = start.forNewChannel("de", "de", true)
        assertTrue(next.overrides.isEmpty())
        assertEquals(listOf("de"), next.preferredAudioLanguages)
        assertFalse(C.TRACK_TYPE_TEXT in next.disabledTrackTypes)
        assertEquals(listOf("de"), next.preferredTextLanguages)
        assertFalse(next.selectUndeterminedTextLanguage)
        assertEquals(Int.MAX_VALUE, next.maxAudioChannelCount)
    }

    @Test
    fun noAudioLanguageClearsPreference() {
        val next = params.forNewChannel("de", null, true).forNewChannel(null, null, true)
        assertTrue(next.preferredAudioLanguages.isEmpty())
    }

    @Test
    fun noSubtitleLanguageDisablesText() {
        val next = params.forNewChannel("de", "de", true).forNewChannel(null, null, true)
        assertTrue(C.TRACK_TYPE_TEXT in next.disabledTrackTypes)
        assertTrue(next.preferredTextLanguages.isEmpty())
    }

    @Test
    fun subtitleLanguageIgnoresDefaultFlag() {
        val next = params.forNewChannel(null, "fr", true)
        assertEquals(C.SELECTION_FLAG_DEFAULT, next.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT)
    }

    @Test
    fun surroundOffCapsChannelsAtTwo() {
        assertEquals(2, params.forNewChannel(null, null, false).maxAudioChannelCount)
        assertEquals(Int.MAX_VALUE, params.forNewChannel(null, null, false).forNewChannel(null, null, true).maxAudioChannelCount)
    }

    @Test
    fun newChannelKeepsTrackSelectorParametersType() {
        val tunnelled = DefaultTrackSelector.Parameters.DEFAULT.buildUpon().setTunnelingEnabled(true).build()
        val next = tunnelled.forNewChannel("en", "en", false)
        assertTrue(next is DefaultTrackSelector.Parameters)
        assertTrue((next as DefaultTrackSelector.Parameters).tunnelingEnabled)
        val picked = next.withChoice(tracks(group("a", audio("en"))), TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 0)))
        assertTrue((picked as DefaultTrackSelector.Parameters).tunnelingEnabled)
    }

    @Test
    fun withTunnelingSetsFlagOnDefaultTrackSelectorParams() {
        val on = params.withTunneling(true)
        assertTrue((on as DefaultTrackSelector.Parameters).tunnelingEnabled)
        assertFalse((on.withTunneling(false) as DefaultTrackSelector.Parameters).tunnelingEnabled)

        val plain = TrackSelectionParameters.DEFAULT
        assertSame(plain, plain.withTunneling(true))
    }

    @Test
    fun noneIsEmpty() {
        val none = PlayerTracks.NONE
        assertTrue(none.video.isEmpty() && none.audio.isEmpty() && none.text.isEmpty())
        assertTrue(none.videoAuto)
        assertTrue(none.textOff)
        assertNull(none.frameRate)
        assertEquals(none, playerTracks(Tracks.EMPTY, params, names))
    }

    @Test
    fun trackTypeMapsToMediaConstants() {
        assertEquals(C.TRACK_TYPE_VIDEO, TrackType.VIDEO.c)
        assertEquals(C.TRACK_TYPE_AUDIO, TrackType.AUDIO.c)
        assertEquals(C.TRACK_TYPE_TEXT, TrackType.TEXT.c)
    }
}
