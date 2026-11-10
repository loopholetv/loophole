package io.github.loopholetv.ui.guide

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.loopholetv.data.db.ProgrammeEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Guide date/time formatting in the device zone, honouring its 12/24 h setting. */
class GuideFormat(is24Hour: Boolean, val zone: ZoneId, locale: Locale = Locale.getDefault()) {
    private val time = DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm a", locale).withZone(zone)
    private val date = DateTimeFormatter.ofPattern("EEE d MMM", locale).withZone(zone)
    private val dayDate = DateTimeFormatter.ofPattern("EEE d MMM", locale)

    fun time(t: Instant): String = time.format(t)
    fun date(t: Instant): String = date.format(t)
    fun range(p: ProgrammeEntity): String = "${time(p.start)} – ${time(p.stop)}"

    /** [day] in words relative to [today]: "Yesterday", "Today", "Tomorrow", else e.g. "Mon 9 Nov". */
    fun dayWord(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        else -> headerDay(day, today)
    }

    /** The header's day: "Yesterday" or "Tomorrow" next to today, else its date (e.g. "Wed 11 Nov"). */
    fun headerDay(day: LocalDate, today: LocalDate): String = when (day) {
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> dayDate.format(day)
    }
}

@Composable
fun rememberGuideFormat(): GuideFormat {
    val context = LocalContext.current
    return remember(context) { GuideFormat(DateFormat.is24HourFormat(context), ZoneId.systemDefault()) }
}

fun ProgrammeEntity.isAiring(now: Instant): Boolean = start <= now && stop > now

/** Whole minutes until [ProgrammeEntity.stop], rounded up. */
fun ProgrammeEntity.minutesLeft(now: Instant): Long =
    (Duration.between(now, stop).toSeconds() + 59).coerceAtLeast(0) / 60

/** Share of the programme already aired (0..1). */
fun ProgrammeEntity.progress(now: Instant): Float {
    val total = Duration.between(start, stop).toMillis().coerceAtLeast(1)
    return (Duration.between(start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
}
