package io.github.loopholetv.data.tmdb

import io.github.loopholetv.data.vod.TitleKind
import java.text.Normalizer
import java.time.Year

/** A playlist name reduced to what a metadata lookup needs: the title, a release year and a quality badge. */
data class CleanTitle(
    val query: String,
    val year: Int?,
    val badge: String?,
    /** True when the year was written in brackets, which makes it a firmer match than a bare trailing number. */
    val yearBracketed: Boolean = false,
)

private val LANGUAGE_CODES = setOf(
    "EN", "UK", "US", "GB", "FR", "DE", "ES", "IT", "PT", "BR", "NL", "BE", "PL", "RO", "TR", "AR", "RU", "GR",
    "SE", "NO", "DK", "FI", "IN", "PK", "CA", "AU", "IE", "LAT", "VO", "NZ", "CH", "AT", "CZ", "HU", "BG", "HR",
    "RS", "AL", "IL", "IR", "CN", "JP", "KR", "TH", "VN", "MX", "AF", "KU",
)
private val LANGUAGE_PREFIX = Regex("""^\s*(?:\[([A-Z]{2,3})]|([A-Z]{2,3})\s*[|:-])\s*""")
private val UHD_TAGS = setOf("4k", "uhd", "2160p")
private val HD_TAGS = setOf("fhd", "hd", "1080p", "720p")
private val NOISE_TAGS = listOf(
    "sd", "multi", "vostfr", "hevc", "x265", "x264", "h265", "h264", "hdr", "hdr10", "sdr", "dv", "bluray", "bdrip", "webrip",
    "web-dl", "hdrip", "dvdrip", "remux",
)
private val TAG = Regex(
    """(?<![\w])(${(UHD_TAGS + HD_TAGS + NOISE_TAGS).joinToString("|") { Regex.escape(it) }})(?![\w])""",
    RegexOption.IGNORE_CASE,
)
private val EMPTY_BRACKETS = Regex("""[(\[]\s*[)\]]""")
private val BRACKETED_YEAR = Regex("""^(.*\S)\s+[(\[]((?:19|20)\d{2})[)\]]$""")
private val BARE_YEAR = Regex("""^(.*\S)\s+((?:19|20)\d{2})$""")
private val EDGE_SEPARATORS = " -:|.,/\\".toCharArray()
private val WHITESPACE = Regex("\\s+")
private val MARKS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val LEADING_ARTICLE = Regex("^(the|a|an) ")

private fun String.trimEdges() = trim { it.isWhitespace() || it in EDGE_SEPARATORS }

private fun stripLanguagePrefix(raw: String): String {
    val m = LANGUAGE_PREFIX.find(raw) ?: return raw
    val code = m.groupValues[1].ifEmpty { m.groupValues[2] }
    return if (code in LANGUAGE_CODES) raw.substring(m.range.last + 1) else raw
}

/** A bare trailing number is only a year when it is not in the far future ("Blade Runner 2049"). */
fun cleanTitle(raw: String, maxYear: Int = Year.now().value + 1): CleanTitle {
    val tags = TAG.findAll(raw).map { it.value.lowercase() }.toSet()
    val badge = when {
        tags.any { it in UHD_TAGS } -> "4K"
        tags.any { it in HD_TAGS } -> "HD"
        else -> null
    }
    var text = TAG.replace(stripLanguagePrefix(raw), " ")
    text = EMPTY_BRACKETS.replace(text, " ").replace(WHITESPACE, " ").trimEdges()
    for ((regex, bracketed) in listOf(BRACKETED_YEAR to true, BARE_YEAR to false)) {
        val match = regex.matchEntire(text) ?: continue
        val year = match.groupValues[2].toInt()
        val before = match.groupValues[1].trimEdges()
        if (before.isEmpty() || (!bracketed && year > maxYear)) continue
        return CleanTitle(before, year, badge, bracketed)
    }
    return CleanTitle(text, null, badge)
}

/** Lower case, accents folded, `&` read as "and", punctuation dropped, leading article removed. */
fun normaliseTitle(s: String): String {
    val folded = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
    val spaced = NON_ALNUM.replace(folded.lowercase().replace("&", " and "), " ").trim()
    return LEADING_ARTICLE.replace(spaced, "").replace(WHITESPACE, " ").trim()
}

/** Stable identity of a playlist title: the provider's id when it has one, else the cleaned name and year. */
fun fingerprint(kind: TitleKind, tvgId: String?, raw: String): String {
    val prefix = if (kind == TitleKind.FILM) "FILM|" else "SHOW|"
    if (!tvgId.isNullOrBlank()) return "${prefix}id:$tvgId"
    val clean = cleanTitle(raw)
    val name = normaliseTitle(clean.query).ifEmpty { normaliseTitle(raw) }
    return "${prefix}n:$name|${clean.year ?: ""}"
}
