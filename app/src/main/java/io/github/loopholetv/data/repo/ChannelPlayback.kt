package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.ChannelPrefsDao
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One channel's playback overrides; each null follows the playback settings (aspect: Fit). */
data class ChannelPlaybackPrefs(
    val video: DecoderChoice? = null,
    val audio: DecoderChoice? = null,
    /** External player package name, or `"ask"` for the system chooser. */
    val externalPlayer: String? = null,
    val aspect: AspectMode? = null,
) {
    companion object {
        val NONE = ChannelPlaybackPrefs()
    }
}

/** Reads a channel's playback overrides at play time. A channel without a pref row has none. */
class ChannelPlayback(private val dao: ChannelPrefsDao) {
    suspend fun get(ref: ChannelRef): ChannelPlaybackPrefs = dao.get(ref.playlistId, ref.key).toPlaybackPrefs()

    fun observe(ref: ChannelRef): Flow<ChannelPlaybackPrefs> =
        dao.observe(ref.playlistId, ref.key).map { it.toPlaybackPrefs() }.distinctUntilChanged()

    private fun ChannelPrefsEntity?.toPlaybackPrefs(): ChannelPlaybackPrefs =
        if (this == null) ChannelPlaybackPrefs.NONE else ChannelPlaybackPrefs(videoDecoder, audioDecoder, externalPlayer, aspectMode)
}
