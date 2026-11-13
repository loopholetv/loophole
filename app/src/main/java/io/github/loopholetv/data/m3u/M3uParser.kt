package io.github.loopholetv.data.m3u

import java.io.InputStream

/** Streaming, lenient M3U parser. Entries are handed to [onEntry] one by one and never accumulated. */
object M3uParser {
    private val ATTR = Regex("""([\w-]+)=("([^"]*)"|[^\s,]+)""")
    private const val BOM = '\uFEFF'

    private class Pending(val attrs: Map<String, String>, val name: String)

    fun parse(input: InputStream, onEntry: (M3uEntry) -> Unit): M3uHeader {
        var epgUrls: List<String> = emptyList()
        var pending: Pending? = null
        var userAgent: String? = null
        var referrer: String? = null
        var extGrp: String? = null
        var first = true
        var defaults: Map<String, String> = emptyMap()

        input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            var line = raw
            if (first) {
                line = line.trimStart(BOM)
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) return@forEachLine

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttrs(line.substring(7))
                    defaults = attrs
                    epgUrls = listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"])
                        .flatMap { it.split(',') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pending != null) { // previous EXTINF never got a URL
                        userAgent = null; referrer = null; extGrp = null
                    }
                    val body = line.substringAfter(':', "")
                    val comma = firstUnquotedComma(body)
                    val head = if (comma >= 0) body.substring(0, comma) else body
                    val name = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    pending = Pending(parseAttrs(head), name)
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val kv = line.substringAfter(':', "")
                    val key = kv.substringBefore('=').trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value.ifEmpty { null }
                        "http-referrer", "http-referer" -> referrer = value.ifEmpty { null }
                    }
                }
                line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    val kv = line.substringAfter(':', "")
                    if (kv.substringBefore('=').trim().equals("inputstream.adaptive.stream_headers", true)) {
                        kv.substringAfter('=', "").split('&').forEach { pair ->
                            val k = pair.substringBefore('=').trim().lowercase()
                            val v = pair.substringAfter('=', "").trim()
                            when (k) {
                                "user-agent" -> userAgent = v.ifEmpty { null }
                                "referer", "referrer" -> referrer = v.ifEmpty { null }
                            }
                        }
                    }
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    extGrp = line.substringAfter(':', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    if (p != null) {
                        val a = p.attrs
                        val timeshift = positiveInt(a["timeshift"]) ?: positiveInt(a["tvg-rec"])
                        val days = leadingInt(a["catchup-days"]) ?: leadingInt(defaults["catchup-days"]) ?: timeshift
                        val mode = nonBlank(a["catchup"]) ?: nonBlank(a["catchup-type"])
                            ?: nonBlank(defaults["catchup"]) ?: nonBlank(defaults["catchup-type"])
                            ?: if (timeshift != null) "shift" else null
                        onEntry(
                            M3uEntry(
                                name = p.name,
                                url = line,
                                tvgId = a["tvg-id"],
                                tvgName = a["tvg-name"],
                                logo = a["tvg-logo"],
                                group = a["group-title"]?.takeIf { it.isNotEmpty() } ?: extGrp,
                                chno = a["tvg-chno"]?.trim()?.toIntOrNull(),
                                userAgent = userAgent,
                                referrer = referrer,
                                catchup = mode,
                                catchupDays = days,
                                catchupSource = nonBlank(a["catchup-source"]) ?: nonBlank(defaults["catchup-source"]),
                                catchupCorrectionMinutes = correctionMinutes(a["catchup-correction"])
                                    ?: correctionMinutes(defaults["catchup-correction"]) ?: 0,
                                type = a["tvg-type"],
                            )
                        )
                    }
                    pending = null; userAgent = null; referrer = null; extGrp = null
                }
            }
        }
        return M3uHeader(epgUrls)
    }

    /** Leading digits of [s] (`"7d"` gives 7); null when it doesn't start with a digit. */
    private fun leadingInt(s: String?): Int? = s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

    private fun positiveInt(s: String?): Int? = leadingInt(s)?.takeIf { it > 0 }

    private fun nonBlank(s: String?): String? = s?.takeIf { it.isNotBlank() }

    /** Largest archive time correction accepted, in minutes (one day either way). */
    private const val MAX_CORRECTION_MINUTES = 24 * 60

    /** Decimal hours to whole minutes (`"-1.5"` gives -90), limited to a day either way; null when not a number. */
    private fun correctionMinutes(s: String?): Int? {
        val hours = s?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Math.round(hours * 60).coerceIn(-MAX_CORRECTION_MINUTES.toLong(), MAX_CORRECTION_MINUTES.toLong()).toInt()
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            val key = m.groupValues[1].lowercase()
            val value = if (m.groupValues[2].startsWith("\"")) m.groupValues[3] else m.groupValues[2]
            map.putIfAbsent(key, value)
        }
        return map
    }

    private fun firstUnquotedComma(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
