package io.github.loopholetv.data.xtream

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val TIME_NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val SNAP_SECONDS = 15 * 60L
private const val MAX_OFFSET_SECONDS = 18 * 3600L

/**
 * The zone a provider's archive timestamps are written in. A usable IANA id from the login wins;
 * otherwise the offset is derived from the provider's wall clock ([timeNow], `yyyy-MM-dd HH:mm:ss`)
 * against its epoch clock ([timestampNow]), snapped to 15 minutes and returned as `+HH:MM` / `-HH:MM`.
 * Null when neither is usable.
 */
fun xtreamZone(timezone: String?, timeNow: String?, timestampNow: Long?): String? {
    val id = timezone?.trim().orEmpty()
    if (id.isNotEmpty()) {
        val zone = try { ZoneId.of(id) } catch (_: DateTimeException) { null }
        if (zone != null && zone !is ZoneOffset && !id.startsWith("+") && !id.startsWith("-")) {
            // "UTC+2" style ids are offsets in disguise; keep real region ids (and plain UTC / GMT) as they are.
            return if (PREFIXED_OFFSET.containsMatchIn(id)) formatOffset(zone.normalized()) else id
        }
    }
    if (timeNow == null || timestampNow == null) return null
    val local = try { LocalDateTime.parse(timeNow.trim(), TIME_NOW) } catch (_: DateTimeException) { return null }
    val diff = local.toEpochSecond(ZoneOffset.UTC) - timestampNow
    val snapped = (diff.toDouble() / SNAP_SECONDS).roundToLong() * SNAP_SECONDS
    if (abs(snapped) > MAX_OFFSET_SECONDS) return null
    return formatOffsetSeconds(snapped)
}

private val PREFIXED_OFFSET = Regex("^(UTC|GMT|UT)[+-]")

private fun formatOffset(zone: ZoneId): String = formatOffsetSeconds((zone as ZoneOffset).totalSeconds.toLong())

private fun formatOffsetSeconds(seconds: Long): String {
    val sign = if (seconds < 0) '-' else '+'
    val minutes = abs(seconds) / 60
    return String.format(Locale.ROOT, "%c%02d:%02d", sign, minutes / 60, minutes % 60)
}

/** Archive streams use `ts` unless the provider only offers `m3u8`. */
fun archiveExt(formats: List<String>): String {
    val lower = formats.map { it.trim().lowercase() }
    return if ("ts" !in lower && "m3u8" in lower) "m3u8" else "ts"
}
