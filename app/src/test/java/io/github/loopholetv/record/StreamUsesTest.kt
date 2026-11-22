package io.github.loopholetv.record

import androidx.media3.common.Player
import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.player.VOD_CHANNEL_ID
import io.github.loopholetv.player.catchup.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUsesTest {
    private class Vod(ref: VodRef) : Playback {
        override val player: Player get() = error("not used")
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Playing(VOD_CHANNEL_ID, StreamTags.NONE))
        override val mode: StateFlow<PlayMode> = MutableStateFlow(PlayMode.Vod(ref, durationMs = null, ended = false))
        override fun play(channel: ChannelEntity) = Unit
        override fun stop() = Unit
    }

    private val cast = object : CastControls {
        override val state: StateFlow<CastState> = MutableStateFlow(CastState.Off)
        override fun startCasting() = Unit
        override fun stopCasting(reason: StopReason) = Unit
    }

    private val noRuns = object : RecordingRuns {
        override val active = MutableStateFlow<Map<Long, ActiveRecording>>(emptyMap())
        override var runGate: suspend (id: Long, fromArchive: Boolean) -> Unit = { _, _ -> }
        override suspend fun begin(id: Long, fromArchive: Boolean) = Unit
        override suspend fun stop(id: Long, reason: io.github.loopholetv.data.db.RecordingReason) = Unit
        override suspend fun recover() = Unit
    }

    private fun uses(ref: VodRef) =
        StreamUses(Vod(ref), cast, noRuns, hostOfChannel = { null }, hostOfVod = { "provider.example.test" })

    @Test fun aFilmCountsOnItsProvider() = runTest {
        val now = uses(VodRef(VodKind.MOVIE, 1, "f")).current()
        assertEquals(listOf("provider.example.test"), now.map { it.host })
    }

    @Test fun aRecordingBeingPlayedIsNotCounted() = runTest {
        assertEquals(emptyList<StreamUse>(), uses(VodRef(VodKind.RECORDING, -1, "rec:1")).current())
    }
}
