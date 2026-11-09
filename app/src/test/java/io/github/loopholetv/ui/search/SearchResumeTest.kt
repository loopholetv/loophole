package io.github.loopholetv.ui.search

import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResumeTest {
    @Test fun guidePreviewStopsWhenSearchOpens() {
        // Opened from the rail with the guide preview playing: nothing opened from Search yet,
        // but the stream must not keep playing (sound) behind the results.
        assertTrue(stopsPlaybackOnSearchResume(PlaybackState.Playing(3, StreamTags(null, null, null))))
        assertTrue(stopsPlaybackOnSearchResume(PlaybackState.Buffering(3)))
        assertTrue(stopsPlaybackOnSearchResume(PlaybackState.Error(3, "x")))
    }

    @Test fun idleIsLeftAlone() = assertFalse(stopsPlaybackOnSearchResume(PlaybackState.Idle))
}
