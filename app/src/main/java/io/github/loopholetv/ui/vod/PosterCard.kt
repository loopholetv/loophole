package io.github.loopholetv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.parental.Padlock
import io.github.loopholetv.ui.theme.Tokens

val POSTER_WIDTH = 120.dp
val PROGRAMME_CARD_WIDTH = 220.dp
private val POSTER_SHAPE = RoundedCornerShape(8.dp)
private const val FOCUSED_SCALE = 1.05f
internal const val POSTER_PROGRESS_TAG = "poster-progress"
internal const val POSTER_BADGE_TAG = "poster-badge"
private val BADGE_SHAPE = RoundedCornerShape(4.dp)

/**
 * A 2:3 poster. Without a [logo], or when it fails to load, the card shows the [title] instead.
 * [subtitle] (the playlist a show comes from) sits at the foot, with a progress bar under it when
 * [fraction] is set. [badge] (a quality tag such as "4K") sits in a chip at the top right.
 * OK calls [onClick], a long OK or Menu calls [onLongClick].
 */
@Composable
fun PosterCard(
    title: String,
    logo: String?,
    subtitle: String?,
    fraction: Float? = null,
    badge: String? = null,
    /** The title has its own lock (and the PIN is entered): a padlock marks the card. */
    locked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(POSTER_WIDTH)
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                val scale = if (focused) FOCUSED_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(POSTER_SHAPE)
            .background(Tokens.Surface)
            .border(if (focused) 3.dp else 0.dp, if (focused) Tokens.Accent else Color.Transparent, POSTER_SHAPE)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { }
            .okPress(onClick, onLongClick)
            .focusable(),
    ) {
        if (logo != null && !failed) {
            val context = LocalPlatformContext.current
            val request = remember(logo) { ImageRequest.Builder(context).data(logo).crossfade(false).build() }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.OnBackground,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        if (subtitle != null || fraction != null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                if (subtitle != null) {
                    Column(Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (fraction != null) {
                    ProgressBar(fraction.coerceIn(0f, 1f), Modifier.fillMaxWidth().testTag(POSTER_PROGRESS_TAG))
                }
            }
        }
        if (badge != null) QualityBadge(badge, Modifier.align(Alignment.TopEnd))
        if (locked) {
            Padlock(Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0xB3000000), BADGE_SHAPE).padding(2.dp))
        }
    }
}

/** A quality tag such as "4K" in a small chip, for the top right of a poster. */
@Composable
internal fun QualityBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Tokens.OnBackground,
        maxLines = 1,
        modifier = modifier.padding(4.dp).testTag(POSTER_BADGE_TAG)
            .background(Color(0xB3000000), BADGE_SHAPE).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * A landscape card for a booked programme: its channel's [channelLogo] (when it has one and it loads),
 * the [title] and [timeText] (when it airs). OK calls [onClick], a long OK or Menu calls [onLongClick].
 */
@Composable
fun ProgrammeCard(
    channelLogo: String?,
    title: String,
    timeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(channelLogo) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(PROGRAMME_CARD_WIDTH)
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                val scale = if (focused) FOCUSED_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(POSTER_SHAPE)
            .background(Tokens.Surface)
            .border(if (focused) 3.dp else 0.dp, if (focused) Tokens.Accent else Color.Transparent, POSTER_SHAPE)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) { }
            .okPress(onClick, onLongClick)
            .focusable()
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channelLogo != null && !failed) {
                val context = LocalPlatformContext.current
                val request = remember(channelLogo) { ImageRequest.Builder(context).data(channelLogo).crossfade(false).build() }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (channelLogo != null && !failed) 8.dp else 0.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.OnBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
