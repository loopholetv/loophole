package io.github.loopholetv.data.xtream

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val XTREAM_FILM_PREFIX = "xm:"
const val XTREAM_SHOW_PREFIX = "xs:"
const val XTREAM_EPISODE_PREFIX = "xe:"

fun xtreamFilmKey(id: Long): String = "$XTREAM_FILM_PREFIX$id"
fun xtreamShowKey(id: Long): String = "$XTREAM_SHOW_PREFIX$id"
fun xtreamEpisodeKey(id: Long): String = "$XTREAM_EPISODE_PREFIX$id"

/** The numeric id in [key], or null when [key] doesn't start with [prefix] or the rest isn't a positive number. */
fun xtreamIdOf(key: String, prefix: String): Long? {
    if (!key.startsWith(prefix)) return null
    val rest = key.substring(prefix.length)
    if (rest.isEmpty() || !rest.all { it in '0'..'9' }) return null
    return rest.toLongOrNull()?.takeIf { it > 0 }
}

enum class XtreamVodKind(val path: String) { MOVIE("movie"), SERIES("series") }

/** Stream URL for a film or episode. It carries the password: never log or store it. */
fun xtreamVodUrl(c: XtreamCredentials, kind: XtreamVodKind, id: Long, container: String?): String {
    val base = c.server.toHttpUrlOrNull() ?: throw XtreamProtocolException("Invalid server address")
    return base.newBuilder()
        .addPathSegment(kind.path)
        .addPathSegment(c.username)
        .addPathSegment(c.password)
        .addPathSegment("$id.${xtreamExtension(container)}")
        .build().toString()
}

private val EXTENSION = Regex("[a-z0-9]{1,8}")

/** The container as a safe file extension; anything unusual falls back to `mp4`. */
fun xtreamExtension(container: String?): String {
    val ext = container?.trim()?.removePrefix(".")?.lowercase().orEmpty()
    return if (EXTENSION.matches(ext)) ext else "mp4"
}
