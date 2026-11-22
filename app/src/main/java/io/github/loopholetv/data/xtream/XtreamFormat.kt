package io.github.loopholetv.data.xtream

/** The stored value for HLS; MPEG-TS is the absence of a value. */
const val XTREAM_FORMAT_HLS = "hls"

/**
 * A live URL as the playlist's stream format asks for it: with [format] "hls" a path ending `.ts`
 * becomes `.m3u8` (query kept); any other URL or format is returned as it is.
 */
fun applyXtreamFormat(url: String, format: String?): String {
    if (format != XTREAM_FORMAT_HLS) return url
    val cut = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
    val path = url.substring(0, cut)
    if (!path.endsWith(".ts", ignoreCase = true)) return url
    return path.dropLast(3) + ".m3u8" + url.substring(cut)
}
