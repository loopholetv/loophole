package io.github.loopholetv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private val UNREACHABLE_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
)

/**
 * A user-facing message for a playback failure. Built only from [channelName] and fixed text, so it
 * never contains the stream URL (Media3 exception messages often do).
 */
fun errorMessage(channelName: String, error: PlaybackException): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val httpCode = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    val reason = when {
        httpCode == 401 || httpCode == 403 -> "access denied by provider"
        httpCode == 404 -> "stream not found"
        httpCode == 429 || httpCode == 458 || httpCode == 509 ->
            "provider connection limit reached (another device may be watching)"
        httpCode != null -> GENERIC
        error.errorCode in UNREACHABLE_ERROR_CODES || causes.any(::isUnreachable) -> "can't reach server"
        else -> GENERIC
    }
    return "$channelName: $reason"
}

/** The message for a stream that failed for no particular reason. */
fun cantPlayMessage(name: String): String = "$name: $GENERIC"

private fun isUnreachable(t: Throwable): Boolean =
    t is UnknownHostException || t is SocketTimeoutException || t is ConnectException || t is NoRouteToHostException

private const val GENERIC = "can't play this stream"
private const val MAX_CAUSE_DEPTH = 16
