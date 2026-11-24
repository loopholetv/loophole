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

data class XtreamFilm(
    val streamId: Long,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val container: String?,
    val tmdbId: Long?,
    val year: Int?,
    val rating: Double?,
)

data class XtreamShow(
    val seriesId: Long,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val rating: Double?,
    val year: Int?,
    val backdrop: String?,
    val tmdbId: Long?,
    val lastModified: String?,
)

data class XtreamEpisode(
    val id: Long,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val container: String?,
    val cover: String?,
)

data class XtreamFilmInfo(
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val rating: Double?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val backdrop: String?,
)

/**
 * Stored as an account's expiry when the provider reports none (no `exp_date`, or 0): the account
 * never expires. A null expiry means the provider did not say. The settings page shows "Never".
 */
val NEVER_EXPIRES: Instant = Instant.EPOCH

/**
 * [expiresAt] is [NEVER_EXPIRES] for an unlimited account, null when the login has no status or date.
 * [serverTimeZone] is the zone of the provider's archive timestamps as resolved by [xtreamZone]
 * (an IANA id or a `+HH:MM` offset), null when the login doesn't say. [archiveExt] is `ts` or `m3u8`.
 */
data class XtreamAccount(
    val status: String,
    val maxConnections: Int?,
    val expiresAt: Instant?,
    val serverTimeZone: String? = null,
    val archiveExt: String = "ts",
)

/** Credentials rejected (`auth` 0 or HTTP 401/403). */
class XtreamAuthException(message: String) : Exception(message)

/** The server answered, but not with the JSON we expect (HTML page, wrong shape, unexpected HTTP status). */
class XtreamProtocolException(message: String) : Exception(message)

/** Transport failure. Message is redacted. */
class XtreamNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
