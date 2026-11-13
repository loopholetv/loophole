package io.github.loopholetv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens
import androidx.compose.ui.graphics.Color

private val MarkerWidth = 3.dp

/** Seek bar for a film or episode: title, the bar with the played part and any pending seek, elapsed and total time. No buttons: the remote's keys drive it. */
@Composable
fun VodBar(ui: VodBarUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ui.paused) {
                Text("Paused", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, maxLines = 1,
                    modifier = Modifier.padding(start = 16.dp))
            }
        }
        SeekTrack(ui)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.elapsed, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnBackground, maxLines = 1)
            Text(ui.pendingLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentLight,
                maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(ui.total.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun SeekTrack(ui: VodBarUi) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).drawBehind {
            drawRect(Tokens.Outline, size = size)
            drawRect(Tokens.Accent, size = Size(size.width * (ui.fraction ?: 0f), size.height))
            ui.pendingFraction?.let {
                val marker = MarkerWidth.toPx()
                drawRect(Color.White, Offset((size.width * it - marker / 2).coerceIn(0f, size.width - marker), 0f), Size(marker, size.height))
            }
        },
    )
}
