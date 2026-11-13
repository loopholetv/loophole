package io.github.loopholetv.ui.vod

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.common.StopPlaybackOnResume
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens

private val DETAIL_POSTER_WIDTH = 200.dp

/** One film: its poster, title and the buttons that start it. Closes itself when the film goes away. */
@Composable
fun FilmScreen(
    playlistId: Long,
    key: String,
    onPlay: (VodRef, Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FilmViewModel = viewModel(key = "film/$playlistId/$key", factory = filmViewModelFactory(playlistId, key))
    val ui by vm.ui.collectAsStateWithLifecycle()
    StopPlaybackOnResume()
    val starter = rememberVodStarter(rememberCast()) { ref, ms -> onPlay(ref, ms) }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    CloseWhenGone(present = ui != null, loaded = loaded, onBack = onBack)
    // The page is composed afresh when the player closes; the button used last takes focus again.
    var lastButton by rememberSaveable { mutableStateOf<Int?>(null) }
    ui?.let { film ->
        FilmContent(film, lastUsedButton = lastButton, onUsed = { lastButton = it }) { ms ->
            starter.start(VodRef(VodKind.MOVIE, playlistId, key), ms)
        }
    }
}

/** The film page body; [onPlay] gets the position to start from, in ms. */
@Composable
internal fun FilmContent(
    ui: FilmUi,
    lastUsedButton: Int? = null,
    onUsed: (Int) -> Unit = {},
    onPlay: (startMs: Long) -> Unit,
) {
    val requesters = remember { List(2) { FocusRequester() } }
    // Focus goes to the button used last (when the page is entered again), else to Play (or
    // Resume). The primary button keeps its place in the row when progress turns Play into
    // Resume, so focus stays on it.
    val openOn = remember { lastUsedButton?.takeIf { it in filmButtons(ui).indices } ?: 0 }
    RequestFocusOnOpen(requesters[openOn])
    Row(Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        DetailPoster(ui.logo, ui.title)
        Column(Modifier.padding(start = 32.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(ui.title, style = MaterialTheme.typography.headlineLarge, color = Tokens.OnBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(ui.category, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted, modifier = Modifier.padding(top = 4.dp))
            ui.fraction?.let { ProgressBar(it, Modifier.padding(top = 20.dp).width(320.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
                filmButtons(ui).forEachIndexed { i, button ->
                    PillButton(
                        label = button.label,
                        selected = i == 0,
                        modifier = Modifier.focusRequester(requesters[i]),
                        onClick = {
                            onUsed(i)
                            onPlay(button.startMs)
                        },
                    )
                }
            }
        }
    }
}

/** A 2:3 poster that is not focusable; the title stands in when there is no image or it fails to load. */
@Composable
internal fun DetailPoster(logo: String?, title: String) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        Modifier.width(DETAIL_POSTER_WIDTH).aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp)).background(Tokens.Surface),
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
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.OnBackground,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
        }
    }
}

/** A thin bar filled to [fraction] (0..1). */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.Outline)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Tokens.Accent))
    }
}

@Composable
internal fun rememberCast() = LocalContext.current.let { context ->
    remember(context) { (context.applicationContext as LoopholeApp).container.castController }
}

/**
 * Leaves the page when the item it shows is gone: [loaded] (the view model has read the database)
 * and not [present]. Before it is loaded, a missing item only means loading.
 */
@Composable
internal fun CloseWhenGone(present: Boolean, loaded: Boolean, onBack: () -> Unit) {
    LaunchedEffect(present, loaded) {
        if (loaded && !present) onBack()
    }
}
