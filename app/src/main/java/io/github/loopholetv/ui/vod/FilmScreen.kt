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
import androidx.compose.foundation.layout.widthIn
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
import io.github.loopholetv.ui.parental.LocalLockMarks
import io.github.loopholetv.ui.parental.LocalPinPrompter
import io.github.loopholetv.ui.theme.Tokens

private val DETAIL_POSTER_WIDTH = 200.dp
private const val BACKDROP_ALPHA = 0.35f
private const val FILM_OVERVIEW_LINES = 4

/** The button that drops a wrong TMDB match. */
internal const val WRONG_DETAILS_LABEL = "Wrong details?"

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
    val marks = LocalLockMarks.current
    val prompter = LocalPinPrompter.current
    // The page is composed afresh when the player closes; the button used last takes focus again.
    var lastButton by rememberSaveable { mutableStateOf<Int?>(null) }
    ui?.let { film ->
        FilmContent(
            film,
            lastUsedButton = lastButton,
            onUsed = { lastButton = it },
            onToggleMyList = vm::toggleMyList,
            onWrongDetails = vm::rejectDetails,
            offerLock = marks.unlocked,
            locked = marks.channel(playlistId, key),
            onToggleLock = { vm.setLocked(!marks.channel(playlistId, key), prompter::ensurePinThen) },
        ) { ms ->
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
    onToggleMyList: () -> Unit = {},
    onWrongDetails: () -> Unit = {},
    /** The lock pill is offered (a PIN exists and is entered); [locked] says which way it reads. */
    offerLock: Boolean = false,
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    onPlay: (startMs: Long) -> Unit,
) {
    val requesters = remember { List(2) { FocusRequester() } }
    // Focus goes to the button used last (when the page is entered again), else to Play (or
    // Resume). The primary button keeps its place in the row when progress turns Play into
    // Resume, so focus stays on it.
    val openOn = remember { lastUsedButton?.takeIf { it in filmButtons(ui).indices } ?: 0 }
    RequestFocusOnOpen(requesters[openOn])
    DetailBackground(ui.details?.backdropUrl) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            DetailPoster(ui.logo, ui.title, ui.details?.badge)
            Column(Modifier.padding(start = 32.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(ui.title, style = MaterialTheme.typography.headlineLarge, color = Tokens.OnBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                // A matched film's meta line takes the place of the playlist category.
                Text(
                    ui.details?.let(::formatMeta) ?: ui.category,
                    style = MaterialTheme.typography.titleMedium,
                    color = Tokens.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ui.details?.let { DetailsText(it) }
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
                    // The label changes with the state but the pill stays put, so focus stays on it.
                    PillButton(label = myListLabel(ui.inMyList), selected = false, onClick = onToggleMyList)
                    if (offerLock) PillButton(label = filmLockLabel(locked), selected = false, onClick = onToggleLock)
                    if (ui.details?.fromTmdb == true) {
                        // The button goes away with the details, so focus moves to Play first.
                        WrongDetailsButton {
                            requesters[0].tryFocus()
                            onWrongDetails()
                        }
                    }
                }
            }
        }
    }
}

/** The page background: [Tokens.Background], with the TMDB backdrop dimmed over it when there is one. */
@Composable
internal fun DetailBackground(backdropUrl: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tokens.Background)) {
        if (backdropUrl != null) {
            val context = LocalPlatformContext.current
            val request = remember(backdropUrl) { ImageRequest.Builder(context).data(backdropUrl).crossfade(false).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = BACKDROP_ALPHA,
                modifier = Modifier.fillMaxSize(),
            )
        }
        content()
    }
}

/** A matched title's plot (up to [overviewLines] lines) and its top cast. */
@Composable
internal fun DetailsText(d: TitleDetailsUi, overviewLines: Int = FILM_OVERVIEW_LINES) {
    d.overview?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnBackground,
            maxLines = overviewLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 640.dp),
        )
    }
    if (d.cast.isNotEmpty()) {
        Text(
            d.cast.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** "Wrong details?": drops the TMDB match without asking. Not shown for the provider's own details. */
@Composable
internal fun WrongDetailsButton(onClick: () -> Unit) {
    PillButton(label = WRONG_DETAILS_LABEL, selected = false, onClick = onClick)
}

internal fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/** A 2:3 poster that is not focusable; the title stands in when there is no image or it fails to load. */
@Composable
internal fun DetailPoster(logo: String?, title: String, badge: String? = null) {
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
        if (badge != null) QualityBadge(badge, Modifier.align(Alignment.TopEnd))
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
