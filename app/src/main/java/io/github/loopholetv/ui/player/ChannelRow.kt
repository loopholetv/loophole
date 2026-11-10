package io.github.loopholetv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.guide.tryFocus
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

private val TileWidth = 210.dp
private val TileHeight = 104.dp
private val TileShape = RoundedCornerShape(12.dp)

/** Within this many tiles of either end of the window, more channels are loaded. */
private const val EDGE_MARGIN = 2

/** Tiles before the channels: TV guide and History (Channels in History mode). */
private const val LEADING_TILES = 2

/**
 * "TV guide" and "History" tiles, then the channels around the current one ([ChannelRowUi], a
 * window of the group, never all of it). In [RowMode.HISTORY] the second tile reads "Channels"
 * and the recently watched channels follow (or a "No recently watched channels" tile). Whenever
 * the row moves to a new current channel it scrolls that tile to the centre and focuses it.
 * OK on a channel tile plays it; holding OK (or Menu) opens its options ([onLongPress]).
 */
@Composable
fun ChannelRow(
    row: ChannelRowUi,
    favourites: Set<ChannelRef>,
    onGuide: () -> Unit,
    onHistory: () -> Unit,
    onChannels: () -> Unit,
    onPlay: (ChannelEntity) -> Unit,
    onLongPress: (ChannelEntity) -> Unit,
    /** Focus reached the first (false) or last (true) channel tile: load more on that side. */
    onReachEdge: (atEnd: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** False while another control (the catch-up bar) takes the focus when the row appears. */
    autoFocus: Boolean = true,
) {
    val focusOnShow by rememberUpdatedState(autoFocus)
    val listState = rememberLazyListState()
    val current = remember { FocusRequester() }
    val tilePx = with(LocalDensity.current) { TileWidth.toPx() }
    val history = row.mode == RowMode.HISTORY
    val emptyHistory = history && row.channels.isEmpty()
    LaunchedEffect(row.mode, row.currentId, emptyHistory) {
        val target = if (emptyHistory) 0 else row.currentIndex.takeIf { it >= 0 && row.currentId != null }
        target ?: return@LaunchedEffect
        val index = LEADING_TILES + target
        listState.scrollToItem(index)
        // Centre it from where it actually landed: near the end of a short row (History) the
        // scroll above stops early, and a fixed half-viewport step back would push it off screen.
        val info = listState.layoutInfo
        val offset = info.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0
        listState.scrollBy(offset - (info.viewportSize.width - tilePx) / 2f)
        if (!focusOnShow) return@LaunchedEffect
        // The tile attaches once laid out; retry briefly instead of crashing on an unattached requester.
        repeat(10) {
            if (current.tryFocus()) return@LaunchedEffect
            delay(30)
        }
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item(key = "guide") { LabelTile("TV guide", onGuide) }
        if (history) {
            item(key = "channels") { LabelTile("Channels", onChannels) }
        } else {
            item(key = "history") { LabelTile("History", onHistory) }
        }
        if (emptyHistory) {
            item(key = "empty") { LabelTile("No recently watched channels", onClick = {}, Modifier.focusRequester(current)) }
        }
        items(count = row.channels.size, key = { row.channels[it].id }) { i ->
            val channel = row.channels[i]
            ChannelTile(
                channel = channel,
                title = row.titles[channel.id],
                favourite = channel.ref in favourites,
                onClick = { onPlay(channel) },
                onLongClick = { onLongPress(channel) },
                modifier = (if (i == row.currentIndex) Modifier.focusRequester(current) else Modifier)
                    .onFocusChanged { f ->
                        if (f.isFocused && i <= EDGE_MARGIN) onReachEdge(false)
                        if (f.isFocused && i >= row.channels.size - 1 - EDGE_MARGIN) onReachEdge(true)
                    },
            )
        }
    }
}

@Composable
private fun Tile(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .size(TileWidth, TileHeight)
            // OK clicks on release; held (or Menu) it long-clicks instead.
            .okPress(onClick, onLongClick)
            .focusable(interactionSource = interaction)
            .background(if (focused) Tokens.FocusFill else Tokens.Surface.copy(alpha = 0.92f), TileShape)
            .padding(12.dp),
    ) { content(focused) }
}

@Composable
private fun LabelTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick, onLongClick = null, modifier) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Tokens.OnFocus else Tokens.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        )
    }
}

@Composable
private fun ChannelTile(
    channel: ChannelEntity,
    title: String?,
    favourite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    Tile(onClick, onLongClick, modifier) { focused ->
        val primary = if (focused) Tokens.OnFocus else Tokens.OnBackground
        val secondary = if (focused) Tokens.OnFocus.copy(alpha = 0.7f) else Tokens.Muted
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelBadge(channel.displayLogo, channel.guideNumber, width = 64.dp, height = 36.dp)
            Text(
                // Without a logo the badge already shows the number.
                text = if (channel.displayLogo != null) "${channel.guideNumber}  ${channel.displayName}" else channel.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
            if (favourite) FavouriteStar(MaterialTheme.typography.labelLarge, Modifier.padding(start = 4.dp))
        }
        Text(
            text = title ?: "No information",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
