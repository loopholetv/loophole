package io.github.loopholetv.record

import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.catchup.PlayMode

/**
 * The provider streams this TV holds right now: the player's (or the guide preview's, or the cast relay's
 * while casting), and one per running recording, each on its provider's host. Following another TV's cast
 * holds no provider stream, and a player that failed holds none either.
 */
class StreamUses(
    private val playback: Playback,
    private val cast: CastControls,
    private val runner: RecordingRuns,
    private val hostOfChannel: suspend (channelId: Long) -> String?,
    /** The host of a film or episode's playlist; null when unknown (it is then not counted). */
    private val hostOfVod: suspend (ref: VodRef) -> String? = { null },
) {
    @Volatile private var previewVisible = false

    /** The guide reports whether its preview is on screen: what plays then is the preview, not the player. */
    fun setPreviewVisible(v: Boolean) {
        previewVisible = v
    }

    suspend fun current(): List<StreamUse> = listOfNotNull(player()) +
        runner.active.value.values.map { StreamUse(it.host, Use.RECORDING, it.channelId) }

    private suspend fun player(): StreamUse? {
        val casting = cast.state.value
        val mode = playback.mode.value
        if (casting is CastState.Following || mode is PlayMode.Cast) return null
        val channelId = when (val s = playback.state.value) {
            is PlaybackState.Buffering -> s.channelId
            is PlaybackState.Playing -> s.channelId
            is PlaybackState.Error, PlaybackState.Idle -> return null
        }
        // A recording plays from storage: it holds no provider stream.
        if (mode is PlayMode.Vod && mode.ref.kind == VodKind.RECORDING) return null
        val host = if (mode is PlayMode.Vod) hostOfVod(mode.ref) else hostOfChannel(channelId)
        val what = when {
            casting is CastState.Casting -> Use.CAST
            previewVisible -> Use.PREVIEW
            else -> Use.PLAYER
        }
        return host?.let { StreamUse(it, what, channelId.takeIf { mode !is PlayMode.Vod }) }
    }
}
