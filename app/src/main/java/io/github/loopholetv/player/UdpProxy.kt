package io.github.loopholetv.player

const val DEFAULT_UDP_PROXY_PORT = 4022

/** A udpxy-style HTTP proxy. [host] keeps its brackets for IPv6 (`[fe80::1]`). */
data class UdpProxy(val host: String, val port: Int)

/** The normalised form stored in settings, e.g. `192.168.1.1:4022` or `[fe80::1]:99`. */
fun UdpProxy.asSetting(): String = "${bracketed(host)}:$port"

/** IPv6 literals need brackets in a URL authority; already-bracketed hosts are kept. */
private fun bracketed(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

private val SCHEME_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)
private val MULTICAST_URL = Regex("^(udp|rtp|igmp)://(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * Parses user input such as `192.168.1.1`, `http://router.lan:4000/` or `[fe80::1]:99`.
 * Missing port gives [DEFAULT_UDP_PROXY_PORT]; an empty host or a port outside 1..65535 gives null.
 */
fun parseUdpProxy(input: String?): UdpProxy? {
    val text = input?.trim()?.replace(SCHEME_PREFIX, "")?.trimEnd('/')?.trim()
    if (text.isNullOrEmpty() || text.any { it.isWhitespace() || it == '/' }) return null
    val host: String
    val portText: String?
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close < 2) return null
        host = text.substring(0, close + 1)
        val rest = text.substring(close + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
    } else {
        val colon = text.indexOf(':')
        host = if (colon < 0) text else text.substring(0, colon)
        portText = if (colon < 0) null else text.substring(colon + 1)
    }
    if (host.isEmpty() || (!host.startsWith("[") && ':' in host)) return null
    if (host.any { it == '@' || it == '?' || it == '#' }) return null
    if (portText != null && (portText.isEmpty() || portText.any { !it.isDigit() })) return null
    val port = if (portText == null) DEFAULT_UDP_PROXY_PORT else portText.toIntOrNull() ?: return null
    return if (port in 1..65535) UdpProxy(host, port) else null
}

/**
 * Rewrites `udp://`, `rtp://` and `igmp://` multicast URLs to the proxy's HTTP form
 * (`http://host:port/udp/group:port`); `rtp` maps to `/rtp/`. Anything else, or a null proxy,
 * is returned unchanged.
 */
fun rewriteForProxy(url: String, proxy: UdpProxy?): String {
    if (proxy == null) return url
    val match = MULTICAST_URL.matchEntire(url.trim()) ?: return url
    val kind = if (match.groupValues[1].equals("rtp", ignoreCase = true)) "rtp" else "udp"
    val target = match.groupValues[2].substringBefore('#').substringBefore('?').removePrefix("@")
    if (target.isEmpty()) return url
    return "http://${bracketed(proxy.host)}:${proxy.port}/$kind/$target"
}
