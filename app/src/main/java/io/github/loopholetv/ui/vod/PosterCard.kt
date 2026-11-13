package io.github.loopholetv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.github.loopholetv.ui.theme.Tokens

val POSTER_WIDTH = 120.dp
private val POSTER_SHAPE = RoundedCornerShape(8.dp)
private const val FOCUSED_SCALE = 1.05f

/**
 * A 2:3 poster. Without a [logo], or when it fails to load, the card shows the [title] instead.
 * [subtitle] (the playlist a show comes from) sits at the foot. OK opens, a long OK or Menu calls [onLongClick].
 */
@Composable
fun PosterCard(
    title: String,
    logo: String?,
    subtitle: String?,
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
        if (subtitle != null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
