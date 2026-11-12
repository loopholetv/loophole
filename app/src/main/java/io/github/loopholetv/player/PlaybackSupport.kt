package io.github.loopholetv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.data.settings.PlaybackSettings

/** The engine choices that need a stream restart to change. */
internal data class EngineConfig(
    val buffer: BufferPreset,
    val video: DecoderChoice,
    val audio: DecoderChoice,
    val passthrough: Passthrough,
    val decodeDtsToPcm: Boolean,
)

internal fun engineConfig(s: PlaybackSettings, p: ChannelPlaybackPrefs) = EngineConfig(
    buffer = s.bufferPreset,
    video = p.video ?: s.videoDecoder,
    audio = p.audio ?: s.audioDecoder,
    passthrough = s.passthrough,
    decodeDtsToPcm = s.decodeDtsToPcm,
)

/** The track preferences that apply live, without a restart. */
internal data class TrackPrefs(val audioLanguage: String?, val subtitleLanguage: String?, val preferSurround: Boolean) {
    constructor(s: PlaybackSettings) : this(s.audioLanguage, s.subtitleLanguage, s.preferSurround)
}

private val HLS_QUERY_HINTS = mapOf("output" to setOf("m3u8", "hls"), "extension" to setOf("m3u8"))

/**
 * HLS by URL shape: the path ends in `.m3u8`, has a `/hls/` segment, or the query says
 * `output=m3u8|hls` / `extension=m3u8` (Xtream style). All case-insensitive.
 */
internal fun isHlsUrl(uri: Uri): Boolean {
    val path = uri.path.orEmpty().lowercase()
    if (path.endsWith(".m3u8") || "/hls/" in path) return true
    if (!uri.isHierarchical) return false
    return uri.queryParameterNames.any { name ->
        val accepted = HLS_QUERY_HINTS[name.lowercase()] ?: return@any false
        uri.getQueryParameters(name).any { it.lowercase() in accepted }
    }
}

internal fun PlaybackException.httpCode(): Int? =
    generateSequence<Throwable>(this) { it.cause }.take(16)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

@OptIn(UnstableApi::class)
internal fun PlaybackException.isAudioRendererError(): Boolean =
    this is ExoPlaybackException && type == ExoPlaybackException.TYPE_RENDERER &&
        MimeTypes.isAudio(rendererFormat?.sampleMimeType)

internal inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is T }
