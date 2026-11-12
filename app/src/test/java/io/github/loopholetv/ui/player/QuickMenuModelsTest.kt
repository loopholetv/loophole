package io.github.loopholetv.ui.player

import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.player.PlayerTracks
import io.github.loopholetv.player.SleepState
import io.github.loopholetv.player.TrackId
import io.github.loopholetv.player.TrackOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuModelsTest {
    private fun opt(group: String, i: Int, label: String, selected: Boolean = false) =
        TrackOption(TrackId(group, i), label, selected)

    private fun List<QuickEntry>.value(item: QuickItem) = first { it.item == item }.value

    @Test fun quickItemsInOrder() {
        val base = listOf(
            QuickItem.SEARCH, QuickItem.CHANNELS, QuickItem.VIDEO, QuickItem.AUDIO, QuickItem.AUDIO_DELAY,
            QuickItem.SUBTITLES, QuickItem.ASPECT, QuickItem.SLEEP, QuickItem.FAVOURITE, QuickItem.CAST,
            QuickItem.OPTIONS, QuickItem.SETTINGS,
        )
        assertEquals(base, quickItems(pipSupported = false))
        assertEquals(base.dropLast(1) + QuickItem.PIP + QuickItem.SETTINGS, quickItems(pipSupported = true))
    }

    @Test fun entryValues() {
        val v = QuickValues(delayMs = 150, aspect = AspectMode.RATIO_4_3, sleep = SleepState.Running(30, 1_500_000))
        val entries = quickEntries(quickItems(false), v)
        assertEquals("+150 ms", entries.value(QuickItem.AUDIO_DELAY))
        assertEquals("4:3", entries.value(QuickItem.ASPECT))
        assertEquals("25 min left", entries.value(QuickItem.SLEEP))
        assertEquals("Auto", entries.value(QuickItem.VIDEO))
        assertEquals("Off", entries.value(QuickItem.SUBTITLES))
        assertEquals("Default", entries.value(QuickItem.AUDIO))
        assertEquals(null, entries.value(QuickItem.SEARCH))
        assertEquals("Add to favourites", entries.first { it.item == QuickItem.FAVOURITE }.label)
        assertEquals(
            "Remove from favourites",
            quickEntries(quickItems(false), v.copy(favourite = true)).first { it.item == QuickItem.FAVOURITE }.label,
        )
        assertEquals("Off", quickEntries(quickItems(false), QuickValues()).value(QuickItem.SLEEP))
        assertEquals("Off", quickEntries(quickItems(false), QuickValues()).value(QuickItem.AUDIO_DELAY))
    }

    @Test fun trackValuesShowTheSelection() {
        val tracks = PlayerTracks(
            video = listOf(opt("v", 0, "1080p"), opt("v", 1, "720p", selected = true)), videoAuto = false,
            audio = listOf(opt("a1", 0, "English"), opt("a2", 0, "German", selected = true)),
            text = listOf(opt("t", 0, "English", selected = true)), textOff = false, frameRate = null,
        )
        val entries = quickEntries(quickItems(false), QuickValues(tracks = tracks))
        assertEquals("720p", entries.value(QuickItem.VIDEO))
        assertEquals("German", entries.value(QuickItem.AUDIO))
        assertEquals("English", entries.value(QuickItem.SUBTITLES))
    }

    @Test fun videoChoicesStartWithAutoAndMarkItWhileAuto() {
        val tracks = PlayerTracks.NONE.copy(video = listOf(opt("v", 0, "1080p"), opt("v", 1, "720p")), videoAuto = true)
        val choices = quickChoices(QuickItem.VIDEO, QuickValues(tracks = tracks))
        assertEquals(listOf("Auto", "1080p", "720p"), choices.map { it.label })
        assertEquals(listOf(true, false, false), choices.map { it.selected })
    }

    @Test fun subtitleChoicesAreOffThenLanguagesWithoutAuto() {
        val tracks = PlayerTracks.NONE.copy(text = listOf(opt("t1", 0, "English"), opt("t2", 0, "French")), textOff = true)
        val choices = quickChoices(QuickItem.SUBTITLES, QuickValues(tracks = tracks))
        assertEquals(listOf("Off", "English", "French"), choices.map { it.label })
        assertTrue(choices[0].selected)
        assertFalse(choices.any { it.label == "Auto" })
    }

    @Test fun identicalAudioNamesAreNumbered() {
        val tracks = PlayerTracks.NONE.copy(
            audio = listOf(opt("a1", 0, "English, Stereo"), opt("a2", 0, "English, Stereo", selected = true), opt("a3", 0, "German")),
        )
        val v = QuickValues(tracks = tracks)
        assertEquals(listOf("English, Stereo", "English, Stereo (2)", "German"), quickChoices(QuickItem.AUDIO, v).map { it.label })
        assertEquals("English, Stereo (2)", quickEntries(quickItems(false), v).value(QuickItem.AUDIO))
        assertEquals(3, quickChoices(QuickItem.AUDIO, v).map { it.key }.distinct().size)
    }

    @Test fun aspectAndSleepChoices() {
        val aspect = quickChoices(QuickItem.ASPECT, QuickValues(aspect = AspectMode.ZOOM))
        assertEquals(listOf("Fit", "Stretch", "Zoom", "16:9", "4:3"), aspect.map { it.label })
        assertEquals(listOf(false, false, true, false, false), aspect.map { it.selected })
        val sleep = quickChoices(QuickItem.SLEEP, QuickValues(sleep = SleepState.Running(45, 10_000)))
        assertEquals(listOf("Off", "15 min", "30 min", "45 min", "1 hour", "1 hour 30 min", "2 hours"), sleep.map { it.label })
        assertEquals("45 min", sleep.single { it.selected }.label)
        assertEquals("Off", quickChoices(QuickItem.SLEEP, QuickValues()).single { it.selected }.label)
    }

    @Test fun sleepWarningTextFormatsMinute() {
        assertEquals("Sleep timer: stopping in 1:00", sleepWarningText(60))
        assertEquals("Sleep timer: stopping in 0:59", sleepWarningText(59))
        assertEquals("Sleep timer: stopping in 0:05", sleepWarningText(5))
    }

    @Test fun exitWaitsForResumed() {
        assertFalse(shouldExit(exitRequested = true, resumed = false))
        assertFalse(shouldExit(exitRequested = false, resumed = true))
        assertTrue(shouldExit(exitRequested = true, resumed = true))
    }

    @Test fun everyItemHasAnIcon() {
        QuickItem.entries.forEach { assertEquals(it.label, quickIcon(it).name) }
    }

    @Test fun startOverPlacedAfterChannels() {
        val items = quickItems(pipSupported = false, startOver = true)
        assertEquals(QuickItem.START_OVER, items[items.indexOf(QuickItem.CHANNELS) + 1])
        assertEquals("Watch from start", QuickItem.START_OVER.label)
        assertEquals(items - QuickItem.START_OVER, quickItems(pipSupported = false))
        assertFalse(QuickItem.START_OVER in quickItems(pipSupported = true))
    }

    @Test fun castTileLabelFollowsState() {
        fun cast(v: QuickValues) = quickEntries(quickItems(false), v).first { it.item == QuickItem.CAST }
        assertEquals("Cast to all TVs", cast(QuickValues()).label)
        assertEquals("Stop casting", cast(QuickValues(casting = true)).label)
        assertEquals(null, cast(QuickValues(casting = true)).value)
        assertEquals("Cast to all TVs", QuickItem.CAST.label)
    }

    @Test fun castLabelText() {
        assertEquals("Casting", castingLabel(0))
        assertEquals("Casting to 1 TV", castingLabel(1))
        assertEquals("Casting to 3 TVs", castingLabel(3))
    }
}
