package io.github.loopholetv.ui.guide

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.repo.GuideKey
import io.github.loopholetv.data.repo.guideKey
import io.github.loopholetv.ui.theme.Tokens
import java.time.Instant
import kotlin.math.roundToInt

/** Thinner than 0.3.0's 48 dp so 8 rows fit under the header at 1080p; see guidePreviewHeight. */
val ROW_HEIGHT = 37.dp
val CHANNEL_WIDTH = 210.dp
private val CellShape = RoundedCornerShape(4.dp)

/** Shown in the grid area while the Favourites group is selected and empty. */
const val FAVOURITES_EMPTY_HINT = "No favourites yet. Hold OK on a channel to add it."

class GridActions(
    val onFocus: (ChannelEntity, ProgrammeEntity?) -> Unit,
    /** OK on a programme block, or an empty strip (programme null). */
    val onOk: (ChannelEntity, ProgrammeEntity?) -> Unit,
    /** Hold OK (or Menu) on a row: the options for that row's channel, whichever block is selected. */
    val onLongPress: (ChannelEntity) -> Unit,
    /** The guide keys (guide id + time shift) of the rows around the visible ones. */
    val onVisibleIds: (List<GuideKey>) -> Unit,
    val keys: StripKeys,
)

@Composable
private fun rememberStripStyle(anchor: MutableState<Instant?>): StripStyle {
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize
    return remember(density, fontSize, anchor) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = with(density) { fontSize.toPx() }
            typeface = Typeface.DEFAULT
        }
        StripStyle(
            paint = paint,
            textColor = Tokens.OnBackground.toArgb(),
            focusedTextColor = Tokens.OnFocus.toArgb(),
            dimTextColor = Tokens.Muted.toArgb(),
            anchor = anchor,
            rewind = with(density) {
                RewindGlyph(REWIND_GLYPH_WIDTH.toPx(), REWIND_GLYPH_HEIGHT.toPx(), REWIND_GLYPH_GAP.toPx())
            },
        )
    }
}

/** Channels column + timeline in one vertical list, so both scroll together. */
@Composable
fun GuideGrid(
    channels: LazyPagingItems<ChannelEntity>,
    slice: ProgrammeSlice,
    window: TimeWindow,
    now: Instant,
    format: GuideFormat,
    listState: LazyListState,
    focusRequester: FocusRequester,
    /** Channel whose strip carries [focusRequester] when on screen; else the first visible row. */
    focusTargetId: Long?,
    /** True while the screen wants to move focus into the grid ([focusRequester] is attached only then). */
    focusPending: Boolean,
    /** Time column the strips keep to (the last selected block's start). */
    anchor: MutableState<Instant?>,
    /** Up from the top row goes here (the preview). */
    upFromTop: FocusRequester,
    actions: GridActions,
    modifier: Modifier = Modifier,
    /** Centred in the grid area (below the header) while the list is empty; null draws nothing. */
    emptyHint: String? = null,
    /** Focuses the "Copy from another TV" button shown with the no-playlist hint. */
    emptyButtonFocus: FocusRequester = remember { FocusRequester() },
    /** OK on the "Copy from another TV" button: the TV picker. */
    onCopyFromAnotherTv: () -> Unit = {},
    /** Channels drawn with the ★ marker. */
    favourites: Set<ChannelRef> = emptySet(),
    /** The row being reordered (Move mode), outlined. */
    movingId: Long? = null,
    /** The window starts before "now" (see [GuideViewModel.pastWindow]). */
    pastWindow: Boolean = false,
) {
    val visibleIds by remember(channels, listState) {
        derivedStateOf {
            // Rows around the visible ones (see guideKeyRange): one programme query per range.
            val range = guideKeyRange(listState.firstVisibleItemIndex, channels.itemCount)
            buildList { for (i in range) channels.peek(i)?.guideKey?.let(::add) }.distinct()
        }
    }
    LaunchedEffect(visibleIds) { actions.onVisibleIds(visibleIds) }
    // While a focus request is pending, the requester sits on the target channel's strip if that row
    // is on screen, else on the first visible row, so the request always lands on a composed cell.
    // Otherwise no row carries it, so scrolling never recomposes cells just to move the requester.
    val targetId by rememberUpdatedState(focusTargetId)
    val pending by rememberUpdatedState(focusPending)
    val requesterIndex by remember(listState) {
        derivedStateOf {
            if (!pending) return@derivedStateOf -1
            val visible = listState.layoutInfo.visibleItemsInfo
            when {
                // Not laid out yet: no requester, so the caller retries instead of landing on a wrong row.
                visible.isEmpty() -> -1
                else -> visible.firstOrNull { targetId != null && it.key == targetId }?.index
                    ?: listState.firstVisibleItemIndex
            }
        }
    }
    val stripStyle = rememberStripStyle(anchor)

    Column(modifier) {
        GuideHeader(window, now, format)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                modifier = Modifier.fillMaxSize()
                    // No time line through the empty-state hint.
                    .then(if (emptyHint != null && channels.itemCount == 0) Modifier else Modifier.nowLine(window, now)),
            ) {
                items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                    val channel = channels[index]
                    if (channel == null) {
                        Spacer(Modifier.fillMaxWidth().height(ROW_HEIGHT))
                    } else {
                        GuideRow(
                            channel = channel,
                            slice = slice,
                            window = window,
                            now = now,
                            style = stripStyle,
                            actions = actions,
                            favourite = channel.ref in favourites,
                            moving = channel.id == movingId,
                            pastWindow = pastWindow,
                            stripModifier = Modifier
                                .then(if (index == requesterIndex) Modifier.focusRequester(focusRequester) else Modifier)
                                // Nothing focusable above but the preview (the header is display only).
                                .then(if (index == 0) Modifier.focusProperties { up = upFromTop } else Modifier),
                        )
                    }
                }
            }
            if (emptyHint != null && channels.itemCount == 0) {
                GuideEmptyState(
                    emptyHint,
                    onCopyFromAnotherTv = onCopyFromAnotherTv,
                    buttonFocus = emptyButtonFocus,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** The "Copy from another TV" button goes with the no-playlist hint only. */
fun showsCopyFromAnotherTv(hint: String?): Boolean = hint == NO_PLAYLIST_HINT

const val COPY_FROM_ANOTHER_TV = "Copy from another TV"

/**
 * The grid area's message for an empty list. With the no-playlist hint it also offers
 * [COPY_FROM_ANOTHER_TV] beneath it, which [buttonFocus] can focus.
 */
@Composable
fun GuideEmptyState(
    hint: String,
    onCopyFromAnotherTv: () -> Unit,
    buttonFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = hint, style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted)
        if (showsCopyFromAnotherTv(hint)) {
            io.github.loopholetv.ui.onboarding.PillButton(
                COPY_FROM_ANOTHER_TV,
                selected = false,
                modifier = Modifier.focusRequester(buttonFocus),
                onClick = onCopyFromAnotherTv,
            )
            // Focus moves to it each time it appears (also when the playlists are known only after
            // the list loaded empty), so OK works at once; it isn't there once channels exist.
            LaunchedEffect(buttonFocus) {
                repeat(20) {
                    if (runCatching { buttonFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    slice: ProgrammeSlice,
    window: TimeWindow,
    now: Instant,
    style: StripStyle,
    actions: GridActions,
    favourite: Boolean,
    moving: Boolean,
    pastWindow: Boolean,
    stripModifier: Modifier,
) {
    // The guide id actually used (manual or automatic) at the channel's time shift.
    val key = channel.guideKey
    val loaded = if (key == null) emptyList() else slice.byKey[key]
    val known = key == null || key in slice.keys
    val visible = remember(loaded, window) { loaded?.filter(window::overlaps) ?: emptyList() }
    // Worked out once per channel (and its limit once per clock tick), not on every draw.
    val catchup = remember(channel) { channel.catchup() }
    val playable = remember(catchup, now) { playableRange(catchup, now) }
    // Own render layer per row: scrolling only moves layers, and a change in one row (selection,
    // focus) re-records that row alone instead of the whole list.
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT).graphicsLayer()
            .then(if (moving) Modifier.border(2.dp, Tokens.Accent, CellShape) else Modifier),
    ) {
        // Display only, not a focus stop: the strip is the row's one focusable node.
        // Own layer: a window shift re-records the strip next to it, not the cell (only a ★
        // change re-records the cell).
        ChannelCell(
            channel = channel,
            favourite = favourite,
            catchup = catchup != null,
            modifier = Modifier.graphicsLayer(),
        )
        Spacer(Modifier.width(3.dp))
        // Always composed so every row has its focus stop; blank until its programmes are loaded.
        ProgrammeStrip(
            programmes = visible,
            window = window,
            now = now,
            style = style,
            keys = actions.keys,
            onFocused = { actions.onFocus(channel, it) },
            onClick = { actions.onOk(channel, it) },
            onLongPress = { actions.onLongPress(channel) },
            modifier = Modifier.weight(1f).fillMaxHeight().then(stripModifier),
            loading = !known,
            playable = playable,
            pastWindow = pastWindow,
        )
    }
}

@Composable
private fun ChannelCell(channel: ChannelEntity, favourite: Boolean, catchup: Boolean, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(CHANNEL_WIDTH)
            .fillMaxHeight()
            .background(Tokens.Surface, CellShape)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = channel.guideNumber.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = Tokens.Muted,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        ChannelLogo(channel.displayLogo)
        Spacer(Modifier.width(8.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The name gives way to the markers, never the other way round.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (catchup) RewindMark(Modifier.padding(start = 6.dp))
        if (favourite) {
            Text(
                text = FAVOURITE_MARK,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Accent,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** After a favourite channel's name. */
const val FAVOURITE_MARK = "\u2605"

/** The catch-up mark (as on the strip's archived programmes), after a channel's name. */
@Composable
private fun RewindMark(modifier: Modifier = Modifier) {
    Spacer(
        modifier.size(REWIND_GLYPH_WIDTH, REWIND_GLYPH_HEIGHT).drawWithCache {
            val path = rewindGlyphPath(size.width, size.height)
            onDrawBehind { drawPath(path, Tokens.Accent) }
        },
    )
}

@Composable
private fun ChannelLogo(url: String?) {
    val context = LocalPlatformContext.current
    Box(Modifier.size(width = 44.dp, height = 28.dp), contentAlignment = Alignment.Center) {
        if (url != null) {
            val request = remember(url) {
                ImageRequest.Builder(context).data(url).size(88, 56).crossfade(false).build()
            }
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The vertical "now" line across the timeline (drawn, not composed, so it costs no layout). */
private fun Modifier.nowLine(window: TimeWindow, now: Instant): Modifier = drawWithContent {
    drawContent()
    if (!window.contains(now)) return@drawWithContent
    val stripStart = (CHANNEL_WIDTH + 3.dp).toPx()
    val x = stripStart + fractionOf(now, window) * (size.width - stripStart)
    val stroke = 2.dp.toPx()
    drawLine(Tokens.Accent, Offset(x.roundToInt().toFloat(), 0f), Offset(x.roundToInt().toFloat(), size.height), stroke)
}
