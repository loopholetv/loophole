package io.github.loopholetv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.theme.Tokens

private val ButtonShape = RoundedCornerShape(10.dp)
private val MarkerWidth = 3.dp

/**
 * Seek bar for an archive or paused-live stream: programme title and times, the bar with the
 * played part, the live edge and any pending seek, elapsed / total time, and six transport
 * buttons. [focus] moves focus to that button whenever it changes; Left/Right then walk the row.
 */
@Composable
fun CatchupBar(
    ui: CatchupBarUi,
    focus: TransportButton?,
    onButton: (TransportButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { TransportButton.entries.associateWith { FocusRequester() } }
    LaunchedEffect(focus) { focus?.let { requesters.getValue(it).requestFocus() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(ui.times, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, maxLines = 1,
                modifier = Modifier.padding(start = 16.dp))
        }
        SeekTrack(ui)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.elapsed, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnBackground, maxLines = 1)
            Text(ui.pendingLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentLight,
                maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(ui.total, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, maxLines = 1)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            TransportButton.entries.forEach { button ->
                TransportTile(button, ui, onClick = { onButton(button) }, Modifier.focusRequester(requesters.getValue(button)))
            }
        }
    }
}

/** [CatchupBar] reading its state here, so each tick recomposes only the bar. Nothing when [ui] gives null. */
@Composable
internal fun TickingCatchupBar(
    ui: () -> CatchupBarUi?,
    focus: TransportButton?,
    onButton: (TransportButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bar = ui() ?: return
    CatchupBar(bar, focus, onButton, modifier)
}

@Composable
private fun SeekTrack(ui: CatchupBarUi) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).drawBehind {
            drawRect(Tokens.Outline, size = size)
            drawRect(Tokens.Accent, size = Size(size.width * ui.fraction, size.height))
            val marker = MarkerWidth.toPx()
            fun mark(fraction: Float, color: Color) = drawRect(
                color, Offset((size.width * fraction - marker / 2).coerceIn(0f, size.width - marker), 0f), Size(marker, size.height),
            )
            ui.liveFraction?.let { mark(it, Tokens.AccentLight) }
            ui.pendingFraction?.let { mark(it, Color.White) }
        },
    )
}

@Composable
private fun TransportTile(button: TransportButton, ui: CatchupBarUi, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val liveOn = button == TransportButton.LIVE && ui.behindLive
    val fill = when {
        focused -> Tokens.FocusFill
        liveOn -> Tokens.Accent
        else -> Tokens.Surface.copy(alpha = 0.92f)
    }
    val content = if (focused || liveOn) Tokens.OnFocus else Tokens.OnBackground
    Row(
        modifier
            .semantics { contentDescription = button.label; role = Role.Button }
            .okPress(onClick, null)
            .focusable(interactionSource = interaction)
            .background(fill, ButtonShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(transportIcon(button, ui.paused), contentDescription = null, modifier = Modifier.size(28.dp),
            colorFilter = ColorFilter.tint(content))
        if (button == TransportButton.LIVE) {
            Text("Live", style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1)
        }
    }
}

// 24x24 path data from Material Symbols (Apache License 2.0).
private fun pathFor(button: TransportButton, paused: Boolean): String = when (button) {
    TransportButton.PREVIOUS -> "M6 6h2v12H6zm3.5 6l8.5 6V6z"
    TransportButton.REWIND -> "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"
    TransportButton.PLAY_PAUSE -> if (paused) "M8 5v14l11-7z" else "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
    TransportButton.FAST_FORWARD -> "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"
    TransportButton.NEXT -> "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"
    TransportButton.LIVE -> "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"
}

private val icons: Map<Pair<TransportButton, Boolean>, ImageVector> by lazy {
    TransportButton.entries.flatMap { b -> listOf(false, true).map { p -> b to p } }.associateWith { (b, paused) ->
        ImageVector.Builder(b.label, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(pathFor(b, paused)), fill = SolidColor(Color.White))
            .build()
    }
}

private fun transportIcon(button: TransportButton, paused: Boolean): ImageVector = icons.getValue(button to paused)
