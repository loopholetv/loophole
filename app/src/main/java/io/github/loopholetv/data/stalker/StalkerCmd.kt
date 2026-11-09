package io.github.loopholetv.data.stalker

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Schemes a resolved link may have (`file://` and pseudo-schemes are rejected). */
internal val PLAYABLE_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "udp", "rtp")

private val SCHEME_REGEX = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")
private val SOLUTION_SHAPE = Regex("""^\w+\s+\S+$""")
private val BASE64_SHAPE = Regex("""^[A-Za-z0-9+/_-]+={0,2}$""")

/**
 * `<solution> <url>` → `<url>` (the text after the last space). A value that is not
 * `<word> <url>`-shaped is base64-decoded only when it decodes to that shape (or to a bare URL).
 */
fun stripSolution(cmd: String): String {
    val v = cmd.trim()
    if (v.isEmpty()) return ""
    if (v.any { it.isWhitespace() }) return v.substringAfterLast(' ').trim().substringAfterLast('\t')
    decodeBase64Cmd(v)?.let { return stripSolution(it) }
    return v
}

private fun decodeBase64Cmd(v: String): String? {
    if (SCHEME_REGEX.containsMatchIn(v) || v.length < 8 || !BASE64_SHAPE.matches(v)) return null
    val decoded = try {
        val bytes = if (v.contains('-') || v.contains('_')) Base64.getUrlDecoder().decode(v) else Base64.getDecoder().decode(v)
        bytes.decodeToString().trim()
    } catch (_: IllegalArgumentException) {
        return null
    }
    val url = if (SOLUTION_SHAPE.matches(decoded)) decoded.substringAfterLast(' ') else decoded
    return decoded.takeIf { SCHEME_REGEX.containsMatchIn(url) && url.none { it.isWhitespace() || it.isISOControl() } }
}

/** Resolves a relative path (`/media/…`) or query-only (`?token=`) [url] against [base]. */
fun resolveAgainst(base: String, url: String): String {
    val u = url.trim()
    if (SCHEME_REGEX.containsMatchIn(u)) return u
    return base.toHttpUrlOrNull()?.resolve(u)?.toString() ?: u
}

/**
 * Whether playing [cmd] needs `create_link`: either flag, or a `cmd` that is empty,
 * relative, on localhost/127.0.0.0/8, or uses a non-playable pseudo-scheme such as `ffrt4://`.
 */
fun needsCreateLink(useTmpLink: Boolean, useLoadBalancing: Boolean, cmd: String): Boolean {
    if (useTmpLink || useLoadBalancing) return true
    val url = stripSolution(cmd)
    if (url.isEmpty()) return true
    val scheme = SCHEME_REGEX.find(url)?.groupValues?.get(1)?.lowercase() ?: return true
    if (scheme !in PLAYABLE_SCHEMES) return true
    val host = hostOf(url) ?: return true
    return host == "localhost" || host.startsWith("127.")
}

private fun hostOf(url: String): String? {
    val afterScheme = url.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    return host.lowercase().takeIf { it.isNotEmpty() }
}

/** The guide id of a portal channel: its `xmltv_id` when set, else `stalker-<id>`. */
fun stalkerTvgId(id: String, xmltvId: String?): String =
    xmltvId?.trim()?.takeIf { it.isNotEmpty() } ?: "stalker-$id"

/** Every value of [cfg] (and [token]) that must never reach a log or an error, in all its MAC forms. */
fun stalkerSecrets(cfg: PortalConfig, token: String?): List<String> {
    val macForms = listOfNotNull(cfg.mac, normaliseMac(cfg.mac)).flatMap { m ->
        listOf(m, m.lowercase(), m.uppercase(), m.replace(":", "").replace("-", ""), m.replace(':', '-'))
    }
    val host = cfg.portalUrl.toHttpUrlOrNull()?.host
        ?: cfg.portalUrl.substringAfter("://").substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }
    return (macForms + listOfNotNull(
        token, cfg.login, cfg.password, cfg.serial, cfg.deviceId, cfg.deviceId2, cfg.signature, cfg.portalUrl, host,
    )).filter { it.isNotBlank() }.distinct()
}

/**
 * The portal root that relative links and logos resolve against: the parent of `server/load.php`,
 * or the directory of any other endpoint (`…/portal.php`). Always ends with `/`.
 */
fun portalRoot(endpoint: String): String {
    val noQuery = endpoint.substringBefore('?').substringBefore('#')
    val stripped = when {
        noQuery.endsWith("/server/load.php") -> noQuery.removeSuffix("server/load.php")
        else -> noQuery.substringBeforeLast('/') + "/"
    }
    return if (stripped.endsWith("/")) stripped else "$stripped/"
}

/**
 * Logo URL: absolute as given; a bare file name lives in `<root>misc/logos/320/`;
 * any other relative path resolves against [root]. Blank → null.
 */
fun resolveLogo(root: String, logo: String?): String? {
    val l = logo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (SCHEME_REGEX.containsMatchIn(l)) return l
    if (!l.contains('/')) return resolveAgainst(root, "misc/logos/320/$l")
    return resolveAgainst(root, l)
}

/** A `cmd` (or create_link answer) → a playable URL: stripped, resolved against [root], scheme-checked. */
fun playableUrl(root: String, cmd: String): String? {
    val stripped = stripSolution(cmd)
    if (stripped.isEmpty()) return null
    val url = resolveAgainst(root, stripped)
    val scheme = SCHEME_REGEX.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
    return url.takeIf { scheme in PLAYABLE_SCHEMES }
}

/** `cmd` as a real STB sends it: only `&`, `#` and space are encoded; `%` passes through. */
fun encodeCmd(cmd: String): String = cmd.replace("&", "%26").replace("#", "%23").replace(" ", "%20")
