package io.github.loopholetv.ui.search

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeHit
import io.github.loopholetv.data.db.displayLogo
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.ui.guide.GuideFormat
import io.github.loopholetv.ui.guide.rememberGuideFormat
import io.github.loopholetv.ui.onboarding.Field
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Search showing (opened from the rail over a guide preview, or back from a player opened here)
 * never has a stream playing behind it. Back to the guide resumes its preview
 * (GuideViewModel.onScreenStarted, Idle branch).
 */
internal fun stopsPlaybackOnSearchResume(state: PlaybackState): Boolean = state !is PlaybackState.Idle

private fun channelKey(id: Long) = "c:$id"
private fun programmeKey(hit: ProgrammeHit) = "p:${hit.programme.id}"

/**
 * Search: the field is read-only until OK opens the keyboard; Down enters the results,
 * Back from the results returns to the field, Back on the field leaves. OK on a channel or an
 * airing programme plays it full screen; on a later programme it shows its details. Coming back
 * from a player opened here stops playback and refocuses the result that was opened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onPlayChannel: (channelId: Long) -> Unit,
    onBack: () -> Unit,
    vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
    playback: Playback = rememberAppPlayback(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.results.collectAsStateWithLifecycle()
    val format = rememberGuideFormat()
    val field = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val requester: (String) -> FocusRequester = { requesters.getOrPut(it) { FocusRequester() } }
    var lastOpenedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<ProgrammeHit?>(null) }
    var refocus by remember { mutableStateOf<String?>(null) }
    var resultsFocused by remember { mutableStateOf(false) }

    // Same TV text-field behaviour as onboarding: read-only until OK opens the keyboard.
    val keyboard = LocalSoftwareKeyboardController.current
    var typing by remember { mutableStateOf(false) }
    val stopTyping: () -> Unit = { typing = false; keyboard?.hide() }
    val startTyping: () -> Unit = { typing = true; keyboard?.show() }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) typing = false
        imeWasVisible = imeVisible
    }

    // First entry focuses the field; back from the player, the result that was opened.
    LaunchedEffect(Unit) {
        val opened = lastOpenedKey?.let { requesters[it] }
        if (opened?.tryFocus() != true) field.tryFocus()
    }
    LaunchedEffect(refocus) {
        refocus?.let { if (!requester(it).tryFocus()) field.tryFocus() }
        refocus = null
    }
    // No stream behind Search, whether the guide preview (opened from the rail) or a player
    // opened from here (results reappearing).
    LifecycleResumeEffect(playback) {
        if (stopsPlaybackOnSearchResume(playback.state.value)) playback.stop()
        onPauseOrDispose { }
    }
    BackHandler(enabled = resultsFocused) { field.tryFocus() }
    BackHandler(enabled = !resultsFocused) { onBack() }

    val now by produceState(Instant.now()) {
        while (true) { delay(30_000); value = Instant.now() }
    }
    val open: (ProgrammeHit) -> Unit = { hit ->
        if (hit.isOnNow(Instant.now())) {
            lastOpenedKey = programmeKey(hit)
            onPlayChannel(hit.channelId)
        } else {
            details = hit
        }
    }

    Column(
        Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, color = Tokens.OnBackground)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(
                label = "Search channels and programmes", value = query, onChange = vm::setQuery,
                modifier = Modifier.weight(1f).focusRequester(field), ime = ImeAction.Search,
                keyboardType = KeyboardType.Text, typing = typing, imeVisible = imeVisible,
                onActivate = startTyping, busy = false, onDone = stopTyping,
            )
            VoiceSearchButton(onFocus = stopTyping, onResult = vm::setQuery)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged {
                    resultsFocused = it.hasFocus
                    if (it.hasFocus) stopTyping()
                }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ResultsContent(
                ui = ui, now = now, format = format, requester = requester,
                onChannel = { c -> lastOpenedKey = channelKey(c.id); onPlayChannel(c.id) },
                onProgramme = open,
            )
        }
    }

    details?.let { hit ->
        ProgrammeDetailsDialog(hit, format, onClose = { details = null; refocus = programmeKey(hit) })
    }
}

@Composable
private fun rememberAppPlayback(): Playback {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as LoopholeApp).container.playback }
}

/** Mic button, only when a speech recogniser exists (hidden on most Fire TVs). */
@Composable
private fun VoiceSearchButton(onFocus: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val available = remember(context) { isVoiceSearchAvailable(context.packageManager) }
    if (!available) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(onResult)
    }
    PillButton("Voice search", selected = false, onFocus = onFocus) {
        runCatching { launcher.launch(voiceSearchIntent()) } // recogniser may vanish after the check
    }
}

@Composable
private fun ColumnScope.ResultsContent(
    ui: SearchUi,
    now: Instant,
    format: GuideFormat,
    requester: (String) -> FocusRequester,
    onChannel: (ChannelEntity) -> Unit,
    onProgramme: (ProgrammeHit) -> Unit,
) {
    when (ui) {
        SearchUi.Hint -> Message("Type at least 2 letters")
        SearchUi.Searching -> Message("Searching…")
        is SearchUi.Empty -> Message("Nothing found for “${ui.query}”")
        is SearchUi.Results -> {
            if (ui.channels.isNotEmpty()) {
                SectionTitle("Channels")
                ui.channels.forEach { c ->
                    key(channelKey(c.id)) {
                        ChannelResult(c, Modifier.focusRequester(requester(channelKey(c.id)))) { onChannel(c) }
                    }
                }
            }
            if (ui.programmes.isNotEmpty() || ui.programmesLoading) SectionTitle("Programmes")
            ui.programmes.forEach { hit ->
                key(programmeKey(hit)) {
                    ProgrammeResult(hit, hit.isOnNow(now), format, Modifier.focusRequester(requester(programmeKey(hit)))) {
                        onProgramme(hit)
                    }
                }
            }
            if (ui.programmesLoading) Message("Searching…")
        }
    }
}

@Composable
private fun Message(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted, modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

private val resultColors
    @Composable get() = ListItemDefaults.colors(
        containerColor = Tokens.Surface,
        contentColor = Tokens.OnBackground,
        focusedContainerColor = Tokens.FocusFill,
        focusedContentColor = Tokens.OnFocus,
    )

@Composable
private fun ChannelResult(channel: ChannelEntity, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.guideNumber.toString(), style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.width(48.dp))
                ChannelLogo(channel.displayLogo)
            }
        },
        headlineContent = { Text(channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.fillMaxWidth(),
        colors = resultColors,
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun ProgrammeResult(hit: ProgrammeHit, onNow: Boolean, format: GuideFormat, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(hit.programme.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${hit.channelNumber}  ${hit.channelName} · ${format.range(hit.shown)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = if (onNow) ({ NowBadge() }) else null,
        modifier = modifier.fillMaxWidth(),
        colors = resultColors,
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}

/** "Now" on an Accent pill, legible both on the dark row and the light focused row. */
@Composable
private fun NowBadge() = Text(
    "Now",
    style = MaterialTheme.typography.labelLarge,
    color = Tokens.OnFocus,
    modifier = Modifier.background(Tokens.Accent, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
)

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

/** Copy of the guide's (private) details dialog, which `ui/guide` keeps to itself. */
@Composable
private fun ProgrammeDetailsDialog(hit: ProgrammeHit, format: GuideFormat, onClose: () -> Unit) {
    val p = hit.shown
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { close.tryFocus() }
    Dialog(onDismissRequest = onClose) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(520.dp)
                .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
                .padding(28.dp),
        ) {
            Text(p.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground)
            Text(format.range(p), style = MaterialTheme.typography.bodyMedium, color = Tokens.Accent)
            Text(
                text = p.description?.takeIf { it.isNotBlank() } ?: "No description",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Muted,
            )
            Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp).focusRequester(close)) { Text("Close") }
        }
    }
}

/** False (not a crash) when the target isn't attached yet. */
private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)
