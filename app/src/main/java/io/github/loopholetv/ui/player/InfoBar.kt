package io.github.loopholetv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.guide.GuideFormat
import io.github.loopholetv.ui.guide.isAiring
import io.github.loopholetv.ui.guide.minutesLeft
import io.github.loopholetv.ui.guide.progress
import io.github.loopholetv.ui.theme.Tokens

private val ChipShape = RoundedCornerShape(4.dp)

/** Group name (left) and date/time (right) across the top while the overlay is up. */
@Composable
fun OverlayTopBar(group: String, clock: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(group, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(clock, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground, maxLines = 1)
    }
}

/**
 * Logo, programme airing now (title, time, progress, minutes left), the next programme, channel
 * number and name, and the stream tags as outlined chips.
 */
@Composable
fun InfoBar(ui: PlayerUi, format: GuideFormat, modifier: Modifier = Modifier) {
    val now = ui.now
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ChannelBadge(ui.channel.displayLogo, ui.channel.guideNumber, width = 132.dp, height = 74.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = now?.title ?: "No information",
                style = MaterialTheme.typography.headlineSmall,
                color = Tokens.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(format.range(now), style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
                    if (now.isAiring(ui.at)) {
                        ProgressBar(now.progress(ui.at), Modifier.padding(horizontal = 14.dp).width(260.dp))
                        Text("${now.minutesLeft(ui.at)} min left", style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Muted)
                    }
                }
            }
            val next = ui.next
            Text(
                text = if (next != null) "${format.time(next.start)}  ${next.title}" else "No information",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${ui.channel.guideNumber}  ${ui.channel.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Tokens.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ui.favourite) FavouriteStar(MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(4.dp))
                ui.tags.chips().forEach { TagChip(it) }
            }
        }
    }
}

private fun StreamTags.chips(): List<String> = listOfNotNull(resolution, fps, audio)

@Composable
private fun TagChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.OnBackground,
        modifier = Modifier.border(1.dp, Tokens.Muted, ChipShape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier) {
    Box(modifier.height(4.dp).background(Tokens.Outline)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Tokens.Accent))
    }
}

/** ★ (Accent) marking a favourite channel. */
@Composable
fun FavouriteStar(style: TextStyle, modifier: Modifier = Modifier) {
    Text("★", style = style, color = Tokens.Accent, maxLines = 1, modifier = modifier)
}

/** The channel's logo at a fixed size, or its number on a plain badge when it has none. */
@Composable
fun ChannelBadge(logo: String?, number: Int, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    Box(
        modifier.size(width, height).background(Tokens.SurfaceRaised, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            val request = remember(logo) {
                // Decode at about the on-screen size (2x for 1080p panels), never the source size.
                ImageRequest.Builder(context).data(logo).size((width.value * 2).toInt(), (height.value * 2).toInt())
                    .crossfade(false).build()
            }
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize().padding(6.dp))
        } else {
            Text(number.toString(), style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground)
        }
    }
}
