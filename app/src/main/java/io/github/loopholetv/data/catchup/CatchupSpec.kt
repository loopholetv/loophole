package io.github.loopholetv.data.catchup

import io.github.loopholetv.data.db.ChannelEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** How a provider serves archived programmes. */
enum class CatchupMode { DEFAULT, APPEND, SHIFT, FLUSSONIC_HLS, FLUSSONIC_TS, XC }

/** Archive window assumed when a playlist declares catch-up without a length. */
const val DEFAULT_CATCHUP_DAYS = 1

private val LAG_MINUTE_GRANULAR: Duration = Duration.ofSeconds(120)
private val LAG_SECOND_GRANULAR: Duration = Duration.ofSeconds(60)

// Placeholders that only carry minute (or coarser) precision: date parts and a duration in minutes.
private val MINUTE_PLACEHOLDER = Regex("""\{[YmdHM]\}|\{duration:60\}""")
private val FORMATTED_PLACEHOLDER =
    Regex("""\{(?:utc|utcend|lutc|start|end|now|timestamp):([^{}]*)\}""")

/**
 * What a channel's archive offers: how URLs are built ([mode], [source]), how far back it reaches
 * ([days]) and whether the provider only addresses it by the minute ([minuteGranular]).
 */
data class CatchupSpec(
    val mode: CatchupMode,
    val days: Int,
    val source: String?,
    val minuteGranular: Boolean,
) {
    override fun toString() = "CatchupSpec(mode=$mode, days=$days, source=***, minuteGranular=$minuteGranular)"

    /** Oldest start time still inside the archive window. */
    fun earliest(now: Instant): Instant = now.minus(Duration.ofDays(days.toLong()))

    /** How long a programme must have been running before its archive is expected to exist. */
    val liveLag: Duration get() = if (minuteGranular) LAG_MINUTE_GRANULAR else LAG_SECOND_GRANULAR

    /** Latest start time (exclusive) whose archive is expected to exist yet. */
    fun playableUntil(now: Instant): Instant = now.minus(liveLag)

    fun canPlay(start: Instant, now: Instant): Boolean =
        start < playableUntil(now) && start >= earliest(now)
}

/**
 * Works out a channel's catch-up support from its playlist attributes, or null when it has none
 * (unknown mode, non-positive window, a template mode without a template, or an Xtream-style mode
 * whose stream URL does not have the live shape).
 */
fun catchupSpec(type: String?, days: Int?, source: String?, streamUrl: String): CatchupSpec? {
    val mode = parseMode(type) ?: return null
    val window = days ?: DEFAULT_CATCHUP_DAYS
    if (window <= 0) return null
    val template = source?.takeIf { it.isNotBlank() }
    return when (mode) {
        CatchupMode.DEFAULT, CatchupMode.APPEND ->
            template?.let { CatchupSpec(mode, window, it, isMinuteGranular(it)) }
        CatchupMode.XC ->
            if (CatchupUrls.xcFromStreamUrl(streamUrl, ZoneOffset.UTC) == null) null
            else CatchupSpec(mode, window, template, true)
        CatchupMode.SHIFT, CatchupMode.FLUSSONIC_HLS, CatchupMode.FLUSSONIC_TS ->
            CatchupSpec(mode, window, template, false)
    }
}

fun ChannelEntity.catchup(): CatchupSpec? = catchupSpec(catchupType, catchupDays, catchupSource, streamUrl)

private fun parseMode(type: String?): CatchupMode? = when (type?.trim()?.lowercase()) {
    "default" -> CatchupMode.DEFAULT
    "append" -> CatchupMode.APPEND
    "shift", "timeshift" -> CatchupMode.SHIFT
    "flussonic", "flussonic-hls" -> CatchupMode.FLUSSONIC_HLS
    "flussonic-ts", "fs" -> CatchupMode.FLUSSONIC_TS
    "xc" -> CatchupMode.XC
    else -> null
}

private fun isMinuteGranular(template: String): Boolean =
    MINUTE_PLACEHOLDER.containsMatchIn(template) ||
        FORMATTED_PLACEHOLDER.findAll(template).any { 'S' !in it.groupValues[1] }
