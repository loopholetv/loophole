package io.github.loopholetv.data.repo

sealed interface ImportError {
    /** Network failure: refused, unknown host, timeout, dropped mid-download, invalid address. */
    data object Unreachable : ImportError

    /** Xtream login rejected, or HTTP 401/403 for an M3U URL. */
    data object AuthFailed : ImportError

    /** Download/login succeeded but there were no usable channels. */
    data object EmptyPlaylist : ImportError

    /** The response is not a playlist (e.g. an HTML page) or not the Xtream JSON we expect. */
    data object NotAPlaylist : ImportError

    /** A bug or unexpected condition (not a network/source problem). */
    data object Unexpected : ImportError

    /** Any other non-2xx HTTP status. */
    data class Http(val code: Int) : ImportError
}

/** User-facing copy for an import failure. Never includes the raw (URL-bearing) failure message. */
fun errorCopy(error: ImportError): String = when (error) {
    ImportError.Unreachable -> "Couldn't reach the server. Check the address and your internet connection."
    ImportError.AuthFailed -> "The server rejected the username or password."
    ImportError.EmptyPlaylist -> "The playlist loaded but contained no channels."
    ImportError.NotAPlaylist -> "That address didn't return a playlist."
    ImportError.Unexpected -> "Something went wrong while loading the playlist."
    is ImportError.Http -> "The server returned an error (${error.code})."
}

/** Trims [raw] and prepends "http://" unless it already starts with http:// or https:// (any case). */
fun withHttpScheme(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) t else "http://$t"
}

sealed interface ImportResult {
    data class Success(val playlistId: Long, val channelCount: Int) : ImportResult

    /** [message] is redacted and safe to show or store. */
    data class Failure(val error: ImportError, val message: String) : ImportResult
}

/** Thrown inside an import to abort (and roll back) with a specific [error]. Message is not yet redacted. */
internal class ImportFailure(val error: ImportError, message: String) : Exception(message)
