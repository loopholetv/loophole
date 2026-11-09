package io.github.loopholetv.util

import java.net.URLDecoder
import java.net.URLEncoder

private const val MASK = "***"
private const val SCHEMES = "https?|rtsp|rtmps?|udp|rtp|igmp"

// A URL runs to the next whitespace/quote; trailing sentence punctuation is not part of it.
private val URL_REGEX = Regex("""(?:$SCHEMES)://[^\s"'<>]*[^\s"'<>.,;:!?)\]}]""", RegexOption.IGNORE_CASE)
private val CONTAINS_URL_REGEX = Regex("""(?:$SCHEMES)://""", RegexOption.IGNORE_CASE)

private val SECRET_KEY_PARTS = listOf("user", "pass", "pwd", "token", "key", "auth", "secret", "sig")
private val KEY_TOKEN_SPLIT = Regex("[_.\\-]")

/** True when [key] has `mac` as a whole token (`mac`, `stb_mac`, `mac_address`), not `macro`. */
private fun isMacKey(key: String) = key.split(KEY_TOKEN_SPLIT).any { it == "mac" }
// A MAC address, with plain or percent-encoded (%3A) colons or with dashes.
private val MAC_REGEX =
    Regex("""(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}(?:[:-]|%3[Aa])){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""")
private val CREDENTIAL_PATH_PREFIXES =
    setOf("live", "movie", "series", "timeshift", "hls", "hlsr", "play", "streaming")
private val TOKEN_STEM = Regex("""[A-Za-z0-9_-]{6,}""")

/** Same as `redact(text, emptyList())`. */
fun redact(text: String): String = redact(text, emptyList())

/**
 * Makes [text] safe to log or show: every literal occurrence of each non-blank entry of [secrets]
 * (plain and percent-encoded) becomes `***`, then credentials inside http(s), rtsp, rtmp(s), udp
 * and rtp URLs are masked structurally (userinfo, secret-looking query/fragment keys, credential
 * path segments and token-shaped segments).
 */
fun redact(text: String, secrets: Collection<String>): String {
    val withoutSecrets = secrets
        .filter { it.isNotBlank() }
        .flatMap { secretForms(it) }
        .distinct()
        .sortedByDescending { it.length }
        .fold(text) { acc, form -> acc.replace(form, MASK) }
    val withoutMacs = MAC_REGEX.replace(withoutSecrets, MASK)
    return URL_REGEX.replace(withoutMacs) { redactUrl(it.value) }
}

private fun secretForms(secret: String): List<String> {
    val encoded = URLEncoder.encode(secret, "UTF-8")
    return listOf(secret, encoded, encoded.replace("+", "%20"))
}

private fun redactUrl(url: String): String {
    val schemeEnd = url.indexOf("://") + 3
    val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
    val queryStart = url.indexOf('?').takeIf { it in 0 until fragmentStart } ?: fragmentStart
    val pathStart = url.indexOf('/', schemeEnd).takeIf { it in 0 until queryStart } ?: queryStart

    val authority = redactAuthority(url.substring(schemeEnd, pathStart))
    val path = redactPath(url.substring(pathStart, queryStart))
    val query = if (queryStart < fragmentStart) "?" + redactParams(url.substring(queryStart + 1, fragmentStart)) else ""
    val fragment = if (fragmentStart < url.length) "#" + redactParams(url.substring(fragmentStart + 1)) else ""
    return url.substring(0, schemeEnd) + authority + path + query + fragment
}

private fun redactAuthority(authority: String): String {
    val at = authority.lastIndexOf('@')
    return if (at < 0) authority else "$MASK@" + authority.substring(at + 1)
}

private fun redactPath(path: String): String {
    val segments = path.split('/')
    val out = segments.toMutableList()
    var i = 0
    while (i < segments.size) {
        if (segments[i].lowercase() in CREDENTIAL_PATH_PREFIXES) {
            // Xtream style: /live/<user>/<pass>/<stream file>; both credential segments are always masked.
            for (j in i + 1..i + 2) if (j < segments.size) out[j] = MASK
            i += 3
        } else {
            out[i] = maskTokenSegment(segments[i])
            i++
        }
    }
    return out.joinToString("/")
}

/** Masks a token-shaped segment (letters and digits, 6+ chars); a file extension is kept. */
private fun maskTokenSegment(segment: String): String {
    val dot = segment.lastIndexOf('.')
    val stem = if (dot > 0) segment.substring(0, dot) else segment
    val extension = if (dot > 0) segment.substring(dot) else ""
    val isToken = TOKEN_STEM.matches(stem) && stem.any { it.isLetter() } && stem.any { it.isDigit() }
    return if (isToken) MASK + extension else segment
}

/** Redacts `a=b&c=d` pairs (used for both the query and the fragment). */
private fun redactParams(params: String): String = params.split('&').joinToString("&") { param ->
    val eq = param.indexOf('=')
    if (eq < 0) return@joinToString param
    val key = param.substring(0, eq)
    val value = param.substring(eq + 1)
    val decodedKey = decode(key).lowercase()
    when {
        SECRET_KEY_PARTS.any { it in decodedKey } || isMacKey(decodedKey) -> "$key=$MASK"
        else -> {
            val decodedValue = decode(value)
            if (CONTAINS_URL_REGEX.containsMatchIn(decodedValue)) "$key=" + redact(decodedValue) else param
        }
    }
}

private fun decode(s: String): String =
    try { URLDecoder.decode(s, "UTF-8") } catch (_: IllegalArgumentException) { s }
