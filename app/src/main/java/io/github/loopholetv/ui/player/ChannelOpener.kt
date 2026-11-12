package io.github.loopholetv.ui.player

import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.NoCast
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.player.Handoff
import io.github.loopholetv.player.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/** How opening a channel full screen went. */
internal sealed interface Opened {
    /** Playing (or resumed) here. */
    data object Here : Opened

    /** Our stream stopped; [handoff] is ready for the external player [choice]. */
    data class External(val choice: String, val handoff: Handoff) : Opened

    /** Set to an external player, but no link could be resolved; nothing plays. */
    data object ExternalFailed : Opened

    /** No longer wanted by the time its settings were read (the user moved on). */
    data object Dropped : Opened
}

/**
 * Starts channels for the full-screen player, honouring each channel's playback overrides: a
 * channel set to an external player is handed off instead of played. Handing off ends a cast this
 * TV is running first (the other TVs can't follow another app).
 */
internal class ChannelOpener(
    private val playback: Playback,
    private val channelPlayback: (ChannelEntity) -> Flow<ChannelPlaybackPrefs>,
    private val cast: CastControls = NoCast,
) {
    /** Opens [channel] unless [wanted] turns false while its overrides or link are read. */
    suspend fun open(channel: ChannelEntity, wanted: () -> Boolean): Opened {
        val external = guard { channelPlayback(channel).first().externalPlayer }
        if (!wanted()) return Opened.Dropped
        if (external == null) {
            playback.play(channel)
            return Opened.Here
        }
        if (cast.state.value is CastState.Casting) cast.stopCasting(StopReason.EXTERNAL_PLAYER)
        val handoff = guard { playback.handOff(channel) }
        return when {
            !wanted() -> Opened.Dropped
            handoff == null -> Opened.ExternalFailed
            else -> Opened.External(external, handoff)
        }
    }

    /**
     * Reports the overrides of the current channel from [channels] to [onPrefs]. A decoder change
     * restarts the stream in [Playback] itself, whichever screen saved it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun follow(channels: Flow<ChannelEntity>, onPrefs: (ChannelPlaybackPrefs) -> Unit) {
        channels.distinctUntilChangedBy { it.id }
            .flatMapLatest { c -> channelPlayback(c).catch { } }
            .collect(onPrefs)
    }

    /** A failed read counts as no override (the channel plays here). */
    private inline fun <T> guard(read: () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
