package io.github.loopholetv.data.xtream

import java.time.Instant

/** Xtream Codes login. [server] is normalised to scheme+host+port with no trailing slash or path. */
class XtreamCredentials(server: String, val username: String, val password: String) {
    val server: String = normalizeServer(server)

    override fun equals(other: Any?) = other is XtreamCredentials &&
        server == other.server && username == other.username && password == other.password

    override fun hashCode() = listOf(server, username, password).hashCode()

    /** Never includes the password. */
    override fun toString() = "XtreamCredentials(server=$server, username=$username, password=***)"

    companion object {
        private val SERVER_REGEX = Regex("""^(?:([A-Za-z][A-Za-z0-9+.-]*)://)?([^/?#\s]*)""")

        fun normalizeServer(raw: String): String {
            val m = SERVER_REGEX.find(raw.trim()) ?: return raw.trim()
            val scheme = m.groupValues[1].lowercase().ifEmpty { "http" }
            return "$scheme://${m.groupValues[2]}"
        }
    }
}

data class XtreamCategory(val id: String, val name: String)

data class XtreamLiveStream(
    val streamId: Long,
    val name: String,
    val num: Int?,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val tvArchive: Boolean,
    val tvArchiveDays: Int?,
)

data class XtreamAccount(val status: String, val maxConnections: Int?, val expiresAt: Instant?)

/** Credentials rejected (`auth` 0 or HTTP 401/403). */
class XtreamAuthException(message: String) : Exception(message)

/** The server answered, but not with the JSON we expect (HTML page, wrong shape, unexpected HTTP status). */
class XtreamProtocolException(message: String) : Exception(message)

/** Transport failure. Message is redacted. */
class XtreamNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
