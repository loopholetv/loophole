package io.github.loopholetv.ui.guide

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens
import java.time.Instant
import java.time.LocalDate

val HEADER_HEIGHT = 28.dp

/**
 * The marks over the timeline: one per half hour of [w]. When they cross midnight, the first mark
 * of the new day is led by that day ("Today  0:00"); the window's own day is in the header's date.
 */
internal fun markLabels(w: TimeWindow, format: GuideFormat, today: LocalDate): Array<String> {
    val starts = Array(4) { w.start.plus(WINDOW_STEP.multipliedBy(it.toLong())) }
    val days = Array(4) { starts[it].atZone(format.zone).toLocalDate() }
    val firstOfNewDay = (1 until 4).firstOrNull { days[it] != days[it - 1] }
    return Array(4) {
        val time = format.time(starts[it])
        if (it == firstOfNewDay) "${format.dayWord(days[it], today)}  $time" else time
    }
}

/**
 * The text over the channel column: on today's window its date and the current time; on any other
 * day only the day ("Yesterday", "Tomorrow", else e.g. "Mon 9 Nov"), as a clock next to another
 * day would read like a time on that day.
 */
internal fun headerText(window: TimeWindow, now: Instant, format: GuideFormat): String {
    val today = now.atZone(format.zone).toLocalDate()
    val day = window.start.atZone(format.zone).toLocalDate()
    val word = format.headerDay(day, today)
    return if (day == today) "$word  ${format.time(now)}" else word
}

/**
 * The timeline header: the window's day and the time (accent) over the channel column, half-hour
 * marks over the timeline. Display only: it is not focusable (the window moves from the strips'
 * edges).
 */
@Composable
fun GuideHeader(window: TimeWindow, now: Instant, format: GuideFormat) {
    val today = now.atZone(format.zone).toLocalDate()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
    ) {
        Box(Modifier.width(CHANNEL_WIDTH + 3.dp)) {
            Text(
                text = headerText(window, now, format),
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.Accent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Half-hour marks are drawn, not composed: they change on every window shift.
        val labels = remember(window, format, today) { markLabels(window, format, today) }
        val paint = rememberMarkPaint()
        Spacer(
            Modifier.weight(1f).fillMaxHeight().drawBehind {
                val step = size.width / labels.size
                val inset = 6.dp.toPx()
                val baseline = (size.height - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
                val canvas = drawContext.canvas.nativeCanvas
                for (i in labels.indices) canvas.drawText(labels[i], i * step + inset, baseline, paint)
            },
        )
    }
}

@Composable
private fun rememberMarkPaint(): Paint {
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.labelMedium.fontSize
    return remember(density, fontSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { fontSize.toPx() }
            color = Tokens.Muted.toArgb()
        }
    }
}
