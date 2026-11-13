package io.github.loopholetv.ui.player

import android.os.Build
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.player.DecoderKinds
import io.github.loopholetv.player.DisplaySnapshot
import io.github.loopholetv.player.InfoSection
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.StreamInfo
import io.github.loopholetv.player.streamInfo
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often the stream info card reads the player again. */
const val STREAM_INFO_REFRESH_MS = 1_000L

private val ColumnWidth = 290.dp
private val LabelWidth = 112.dp

/** Video and colour on the left; display, audio and playback on the right. */
private val LEFT_SECTIONS = setOf("Video", "Colour")

/**
 * The stream info card: what [playback] decodes and what the display reports, read again every
 * [STREAM_INFO_REFRESH_MS]. It takes no focus; Back hides it through the screen.
 */
@Composable
fun StreamInfoCard(playback: Playback, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val kinds = remember { DecoderKinds() }
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    LaunchedEffect(playback, view) {
        while (true) {
            val stream = playback.streamSnapshot()
            val display = view.display?.let(::displaySnapshot)
            // The first lookup reads the platform codec list: off the main thread.
            val names = listOfNotNull(stream?.videoDecoder, stream?.audioDecoder)
            val hardware = withContext(Dispatchers.Default) { names.associateWith(kinds::isHardware) }
            info = streamInfo(stream, display) { hardware[it] }
            delay(STREAM_INFO_REFRESH_MS)
        }
    }
    val content = info ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val (left, right) = content.sections.partition { it.title in LEFT_SECTIONS }
        SectionColumn(left)
        SectionColumn(right)
    }
}

@Composable
private fun SectionColumn(sections: List<InfoSection>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(ColumnWidth)) {
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(section.title, style = MaterialTheme.typography.labelLarge, color = Tokens.Accent)
                section.rows.forEach { row ->
                    Row {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Muted,
                            modifier = Modifier.width(LabelWidth),
                        )
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.OnBackground,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The display's current mode and the HDR types it reports. */
private fun displaySnapshot(display: Display): DisplaySnapshot {
    val mode = display.mode
    val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        mode.supportedHdrTypes
    } else {
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes
    }
    return DisplaySnapshot(mode.physicalWidth, mode.physicalHeight, mode.refreshRate, hdr?.toList().orEmpty())
}
