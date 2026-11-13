package io.github.loopholetv.ui.search

import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.common.stopsPlaybackOnResume
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResumeTest {
    @Test fun guidePreviewStopsWhenSearchOpens() {
        // Opened from the rail with the guide preview playing: nothing opened from Search yet,
        // but the stream must not keep playing (sound) behind the results.
        assertTrue(stopsPlaybackOnResume(PlaybackState.Playing(3, StreamTags(null, null, null))))
        assertTrue(stopsPlaybackOnResume(PlaybackState.Buffering(3)))
        assertTrue(stopsPlaybackOnResume(PlaybackState.Error(3, "x")))
    }

    @Test fun idleIsLeftAlone() = assertFalse(stopsPlaybackOnResume(PlaybackState.Idle))
}
