package io.github.loopholetv.data.net

/**
 * User-Agent precedence. Each function returns the first candidate that is non-blank once cleaned
 * ([clean]), else [default].
 */
object UserAgents {
    fun default(versionName: String): String = "Loophole/$versionName"

    fun forDownload(playlistUa: String?, generalUa: String?, default: String): String =
        firstUsable(playlistUa, generalUa) ?: default

    fun forStream(channelUa: String?, playlistUa: String?, generalUa: String?, default: String): String =
        firstUsable(channelUa, playlistUa, generalUa) ?: default

    /**
     * Whether Settings may save [text] as a user agent: blank (the default) or only characters an
     * HTTP header allows. OkHttp rejects anything else, which would break every download and stream.
     */
    fun isAcceptable(text: String): Boolean = text.isBlank() || text.trim().all(::isHeaderChar)

    /** [ua] without the characters a header can't carry (e.g. from a playlist tag), trimmed; null when blank. */
    private fun clean(ua: String?): String? = ua?.filter(::isHeaderChar)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isHeaderChar(c: Char): Boolean = c == '\t' || c in ' '..'~'

    private fun firstUsable(vararg candidates: String?): String? = candidates.firstNotNullOfOrNull(::clean)
}
