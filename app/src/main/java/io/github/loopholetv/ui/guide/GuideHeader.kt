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

val HEADER_HEIGHT = 28.dp

/**
 * The timeline header: date and time (accent) over the channel column, half-hour marks over the
 * timeline. Display only: it is not focusable (the window moves from the strips' edges).
 */
@Composable
fun GuideHeader(window: TimeWindow, now: Instant, format: GuideFormat) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
    ) {
        Box(Modifier.width(CHANNEL_WIDTH + 3.dp)) {
            Text(
                text = "${format.date(window.start)}  ${format.time(now)}",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.Accent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Half-hour marks are drawn, not composed: they change on every window shift.
        val labels = remember(window, format) {
            Array(4) { format.time(window.start.plus(WINDOW_STEP.multipliedBy(it.toLong()))) }
        }
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
