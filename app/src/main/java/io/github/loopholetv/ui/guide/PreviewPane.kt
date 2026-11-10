package io.github.loopholetv.ui.guide

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.ui.player.applySubtitleStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.R
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.theme.Tokens
import java.time.Instant

/**
 * Subtitles in the preview start this far up the pane (a fraction of its height), above the
 * channel label along its bottom edge.
 */
const val PREVIEW_SUBTITLE_BOTTOM_FRACTION = 0.18f

/** The preview is small: subtitles keep the chosen style but are never drawn larger than Normal. */
fun previewSubtitleStyle(style: SubtitleStyle): SubtitleStyle =
    if (style.size.scale > SubtitleSize.NORMAL.scale) style.copy(size = SubtitleSize.NORMAL) else style

/**
 * The live preview (with sound) bound to the app's single player. Focusable (Up from the grid's top
 * row or the groups' first item): an accent border, and a "Full screen" pill while something is
 * previewing. OK calls [onOk] (full screen on the previewing channel), holding it (or Menu) [onLongPress]; Down calls [onDown] (back into
 * the grid); Left is ordinary focus search (the rail); Up goes nowhere, Right only to [rightTo]
 * (the update card) when given.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPane(
    playback: Playback,
    previewing: ChannelEntity?,
    focusRequester: FocusRequester,
    onOk: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    /** Held OK or Menu: the previewing channel's options (nothing while nothing previews). */
    onLongPress: (ChannelEntity) -> Unit = {},
    /** How subtitles are drawn when a channel previews with them on. */
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    rightTo: FocusRequester? = null,
) {
    val state by playback.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(previewing)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .focusRequester(focusRequester)
            .focusProperties {
                up = FocusRequester.Cancel
                right = rightTo ?: FocusRequester.Cancel
            }
            .onKeyEvent { e ->
                if (e.key != Key.DirectionDown) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) onDown()
                true
            }
            .okPress(onClick = onOk, onLongClick = { current?.let(onLongPress) })
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(Color.Black)
            .then(if (focused) Modifier.border(3.dp, Tokens.Accent) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.preview_player_view, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = playback.player
                }
            },
            update = { view ->
                if (view.tag != subtitleStyle) {
                    view.subtitleView?.applySubtitleStyle(previewSubtitleStyle(subtitleStyle))
                    view.subtitleView?.setBottomPaddingFraction(PREVIEW_SUBTITLE_BOTTOM_FRACTION)
                    view.tag = subtitleStyle
                }
            },
            // Detaching only unbinds the surface: playback carries on (e.g. into the full-screen player).
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        val message = when (val s = state) {
            is PlaybackState.Buffering -> "Loading…"
            is PlaybackState.Error -> s.message
            is PlaybackState.Idle -> if (previewing == null) "Press OK to preview" else null
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (previewing != null) {
            Text(
                text = channelLabel(previewing),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (focused) {
                Text(
                    text = "OK  Full screen",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.OnFocus,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Tokens.Accent, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Title, time range with progress and minutes left, channel and (room permitting) the start of the
 * description of the focused slot, compact beside the preview.
 */
@Composable
fun ProgrammeInfo(
    focused: FocusedProgramme?,
    now: Instant,
    format: GuideFormat,
    modifier: Modifier = Modifier,
    /** Move mode: what is moved (a channel's or group's name) and how to move it, instead of the programme. */
    moving: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (moving != null) {
            Text(
                text = moving,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(MOVE_HINT, style = MaterialTheme.typography.bodyMedium, color = Tokens.Accent, maxLines = 1)
            return@Column
        }
        if (focused == null) return@Column
        val p = focused.programme
        Text(
            text = p?.title ?: "No information",
            style = MaterialTheme.typography.titleLarge,
            color = Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(format.range(p), style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, maxLines = 1)
                if (p.isAiring(now)) {
                    ProgressBar(p.progress(now), Modifier.padding(start = 12.dp).width(140.dp))
                    Text(
                        text = "${p.minutesLeft(now)} min left",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.Muted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            val catchup = remember(focused.channel) { focused.channel.catchup() }
            if (p.stop <= now && catchup?.canPlay(p.start, now) == true) {
                Text(CATCHUP_HINT, style = MaterialTheme.typography.bodyMedium, color = Tokens.Accent, maxLines = 1)
            }
        }
        Text(
            text = channelLabel(focused.channel),
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val description = p?.description
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The info area's line in Move mode. */
const val MOVE_HINT = "Up/Down to move \u00b7 OK to finish"

/** The info area's line for a programme that has ended and can be watched from the archive. */
const val CATCHUP_HINT = "Catch-up \u00b7 Press OK to watch"

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier) {
    Box(modifier.height(4.dp).background(Tokens.Outline)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Tokens.Accent))
    }
}
