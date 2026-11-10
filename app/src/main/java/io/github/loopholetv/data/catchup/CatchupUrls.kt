package io.github.loopholetv.data.catchup

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** The pieces of an Xtream-style live URL needed to address the same channel's archive. */
data class XtreamArchive(
    val server: String,
    val user: String,
    val pass: String,
    val streamId: String,
    /** Time zone the server reads archive start times in. */
    val zone: ZoneId,
    /** `ts` or `m3u8`. */
    val ext: String,
) {
    override fun toString() =
        "XtreamArchive(server=$server, streamId=$streamId, zone=$zone, ext=$ext, user=***, pass=***)"
}

// scheme://host[:port]/[live/]user/pass/id[.ts|.m3u8] with no query string.
private val XC_LIVE_URL = Regex(
    """^(https?://[^/?#]+)/(?:live/(?=[^/?#]+/[^/?#]+/[^/?#.]+(?:\.(?:m3u8|ts))?$)|(?!live/))([^/?#]+)/([^/?#]+)/([^/?#.]+)(?:\.(m3u8|ts))?$""",
    RegexOption.IGNORE_CASE,
)

// scheme://host/<path prefix>/<last segment>[?query]
private val STREAM_URL_PARTS = Regex("""^(https?://[^/?#]+)((?:/[^/?#]+)*)/([^/?#]*)(\?.*)?$""", RegexOption.IGNORE_CASE)

private val PLACEHOLDER = Regex("""\$?\{([A-Za-z]+)(?::([^{}]*))?\}""")
private val XTREAM_START: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT)
private val FILTER_START_NAMES = setOf("utc", "start")
private val FILTER_END_NAMES = setOf("utcend", "end")
private val FILTER_NOW_NAMES = setOf("lutc", "now", "timestamp")

/** Builds archive URLs. These carry the playlist's credentials: never log them unredacted. */
object CatchupUrls {
    /**
     * The archive URL for a playlist-driven channel, or null when [spec] and [streamUrl] cannot
     * produce one. [correction] is added to [from] and [until] before they are formatted, so a
     * playlist correction of +H hours must be passed as a negative duration of H hours.
     */
    fun m3u(
        spec: CatchupSpec,
        streamUrl: String,
        from: Instant,
        until: Instant,
        now: Instant,
        zone: ZoneId,
        correction: Duration = Duration.ZERO,
    ): String? {
        val start = from.plus(correction)
        val end = until.plus(correction)
        val template = spec.source?.takeIf { it.isNotBlank() }
        return when (spec.mode) {
            CatchupMode.DEFAULT -> template?.let { fill(it, start, end, now, zone) }
            CatchupMode.APPEND -> template?.let { streamUrl + fill(it, start, end, now, zone) }
            CatchupMode.SHIFT -> {
                val separator = if ('?' in streamUrl) '&' else '?'
                "$streamUrl${separator}utc=${start.epochSecond}&lutc=${now.epochSecond}"
            }
            CatchupMode.FLUSSONIC_HLS, CatchupMode.FLUSSONIC_TS ->
                flussonic(streamUrl, spec.mode == CatchupMode.FLUSSONIC_TS, start, end, now, zone)
            CatchupMode.XC -> xcFromStreamUrl(streamUrl, zone)?.let { xtream(it, start, end, now) }
        }
    }

    /**
     * `server/timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ext`. The start is floored to the
     * minute and written in the server's zone; the length runs from that floored start to the
     * earlier of [until] and [now], rounded up to whole minutes (at least one).
     */
    fun xtream(a: XtreamArchive, from: Instant, until: Instant, now: Instant): String {
        val start = from.truncatedTo(ChronoUnit.MINUTES)
        val seconds = Duration.between(start, minOf(until, now)).seconds.coerceAtLeast(0)
        val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
        val builder: HttpUrl.Builder = a.server.toHttpUrl().newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(a.user)
            .addPathSegment(a.pass)
            .addPathSegment(minutes.toString())
            .addPathSegment(XTREAM_START.format(start.atZone(a.zone)))
            .addPathSegment("${a.streamId}.${a.ext}")
        return builder.build().toString()
    }

    /** Replaces every supported `{placeholder}` / `${placeholder}` in [template]; others are kept. */
    fun fill(template: String, from: Instant, until: Instant, now: Instant, zone: ZoneId): String {
        val durationSeconds = Duration.between(from, minOf(until, now)).seconds.coerceAtLeast(0)
        val offsetSeconds = Duration.between(from, now).seconds.coerceAtLeast(0)
        // The end never runs past now, matching the duration.
        val cappedEnd = from.plusSeconds(durationSeconds)
        return PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            val arg = match.groups[2]?.value
            resolve(name, arg, from, cappedEnd, now, zone, durationSeconds, offsetSeconds) ?: match.value
        }
    }

    /** Splits an Xtream live URL (`…/[live/]user/pass/id[.ts|.m3u8]`) into its parts. */
    fun xcFromStreamUrl(streamUrl: String, zone: ZoneId): XtreamArchive? {
        val m = XC_LIVE_URL.matchEntire(streamUrl) ?: return null
        val (server, rawUser, rawPass, rawId) = m.destructured
        if (server.toHttpUrlOrNull() == null) return null
        val user = percentDecode(rawUser)
        val pass = percentDecode(rawPass)
        val id = percentDecode(rawId)
        val ext = if (m.groupValues[5].equals("m3u8", ignoreCase = true)) "m3u8" else "ts"
        return XtreamArchive(server, user, pass, id, zone, ext)
    }

    /** Decodes `%XX` escapes only; a literal `+` stays a plus. */
    private fun percentDecode(segment: String): String =
        try {
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            segment
        }

    private fun flussonic(
        streamUrl: String,
        transportStream: Boolean,
        start: Instant,
        end: Instant,
        now: Instant,
        zone: ZoneId,
    ): String? {
        val m = STREAM_URL_PARTS.matchEntire(streamUrl) ?: return null
        val (host, path, last) = m.destructured
        val query = m.groupValues[4]
        val absolute = "timeshift_abs-\${start}.ts"
        val relative = "timeshift_rel-{offset:1}.m3u8"
        val replacement = when (last.lowercase()) {
            "mpegts" -> absolute
            "index.m3u8" -> relative
            "mono.m3u8" -> "mono-$relative"
            else -> if (transportStream) absolute else relative
        }
        return host + path + "/" + fill(replacement, start, end, now, zone) + query
    }

    @Suppress("LongParameterList")
    private fun resolve(
        name: String,
        arg: String?,
        from: Instant,
        end: Instant,
        now: Instant,
        zone: ZoneId,
        durationSeconds: Long,
        offsetSeconds: Long,
    ): String? = when {
        name == "duration" -> divided(durationSeconds, arg)
        name == "offset" -> divided(offsetSeconds, arg)
        name in FILTER_START_NAMES -> instant(from, arg, zone)
        name in FILTER_END_NAMES -> instant(end, arg, zone)
        name in FILTER_NOW_NAMES -> instant(now, arg, zone)
        arg == null && name.length == 1 -> datePart(name[0], from.atZone(zone))
        else -> null
    }

    private fun divided(value: Long, divisor: String?): String? {
        if (divisor == null) return value.toString()
        val n = divisor.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (value / n).coerceAtLeast(0).toString()
    }

    private fun instant(at: Instant, format: String?, zone: ZoneId): String =
        if (format == null) at.epochSecond.toString() else format(at.atZone(zone), format)

    private fun format(at: ZonedDateTime, format: String): String = buildString {
        for (c in format) append(datePart(c, at) ?: c.toString())
    }

    private fun datePart(letter: Char, at: ZonedDateTime): String? = when (letter) {
        'Y' -> "%04d".format(Locale.ROOT, at.year)
        'm' -> "%02d".format(Locale.ROOT, at.monthValue)
        'd' -> "%02d".format(Locale.ROOT, at.dayOfMonth)
        'H' -> "%02d".format(Locale.ROOT, at.hour)
        'M' -> "%02d".format(Locale.ROOT, at.minute)
        'S' -> "%02d".format(Locale.ROOT, at.second)
        else -> null
    }
}
