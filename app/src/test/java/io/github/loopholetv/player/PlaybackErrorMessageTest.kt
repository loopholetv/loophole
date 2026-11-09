package io.github.loopholetv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorMessageTest {

    private val url = "http://provider.example.test/live/someuser/somepass/123.ts"
    private val spec = DataSpec(android.net.Uri.parse(url))

    private fun httpCode(code: Int): PlaybackException {
        val cause = HttpDataSource.InvalidResponseCodeException(
            code, "Status $code for $url", null, emptyMap(), spec, ByteArray(0),
        )
        return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    private fun httpIo(cause: IOException, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED): PlaybackException {
        val wrapped = HttpDataSource.HttpDataSourceException(
            "Unable to connect to $url", cause, spec, code, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
        return ExoPlaybackException.createForSource(wrapped, code)
    }

    @Test
    fun http509MapsToConnectionLimitMessage() {
        assertEquals(
            "BBC One: provider connection limit reached (another device may be watching)",
            errorMessage("BBC One", httpCode(509)),
        )
    }

    @Test
    fun httpStatusTable() {
        val cases = mapOf(
            401 to "Ch: access denied by provider",
            403 to "Ch: access denied by provider",
            404 to "Ch: stream not found",
            429 to "Ch: provider connection limit reached (another device may be watching)",
            458 to "Ch: provider connection limit reached (another device may be watching)",
            509 to "Ch: provider connection limit reached (another device may be watching)",
            500 to "Ch: can't play this stream",
        )
        cases.forEach { (code, expected) -> assertEquals("HTTP $code", expected, errorMessage("Ch", httpCode(code))) }
    }

    @Test
    fun unreachableServerCauses() {
        val expected = "Ch: can't reach server"
        assertEquals(expected, errorMessage("Ch", httpIo(UnknownHostException("provider.example.test"))))
        assertEquals(expected, errorMessage("Ch", httpIo(SocketTimeoutException("timeout"))))
        assertEquals(expected, errorMessage("Ch", httpIo(ConnectException("refused"))))
    }

    @Test
    fun networkErrorCodesMapToUnreachable() {
        val expected = "Ch: can't reach server"
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ).forEach { code ->
            assertEquals(expected, errorMessage("Ch", PlaybackException("io $url", IOException(url), code)))
        }
    }

    @Test
    fun otherErrorsAreGeneric() {
        val error = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals("Ch: can't play this stream", errorMessage("Ch", error))
    }

    @Test
    fun errorMessageHasNoUrl() {
        val errors = listOf(httpCode(509), httpCode(404), httpCode(500), httpIo(UnknownHostException(url)))
        errors.forEach { error ->
            val message = errorMessage("Ch", error)
            assertFalse(message, message.contains("://"))
            assertFalse(message, message.contains("provider.example.test"))
            assertFalse(message, message.contains("somepass"))
        }
    }
}
