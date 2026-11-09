package io.github.loopholetv.ui.player

import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnTest {
    @Test fun keepsScreenOnOnlyWhileBufferingOrPlaying() {
        assertTrue(shouldKeepScreenOn(PlaybackState.Buffering(1)))
        assertTrue(shouldKeepScreenOn(PlaybackState.Playing(1, StreamTags(null, null, null))))
        assertFalse(shouldKeepScreenOn(PlaybackState.Idle))
        assertFalse(shouldKeepScreenOn(PlaybackState.Error(1, "x")))
    }
}
