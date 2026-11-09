package io.github.loopholetv.ui.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.R
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.player.Playback
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.ChannelOptionsDialog
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.OPTION_ADD_FAVOURITE
import io.github.loopholetv.ui.common.OPTION_REMOVE_FAVOURITE
import io.github.loopholetv.ui.common.channelOptionsTitle
import io.github.loopholetv.ui.guide.GuideFormat
import io.github.loopholetv.ui.guide.rememberGuideFormat
import io.github.loopholetv.ui.guide.tryFocus
import io.github.loopholetv.ui.manage.ChannelEditHost
import io.github.loopholetv.ui.manage.channelEditOptions
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

private const val CLOCK_TICK_MS = 5_000L

/**
 * Full-screen playback. Bare video or the slim banner a channel key shows: D-pad Up, Down and OK
 * open the full overlay, holding OK (or Menu) opens the current channel's options, Back hides the
 * banner / returns to the guide. Full overlay: group and clock on top, info bar and channel row at
 * the bottom; the D-pad moves along the row, OK on a tile changes channel (held: its options),
 * Back hides it. Channel keys and digits work everywhere.
 */
@Composable
fun PlayerScreen(channelId: Long, scope: ChannelScope, onExit: () -> Unit) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(channelId, scope))
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LoopholeApp).container }
    val playback = container.playback
    val format = rememberGuideFormat()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val row by vm.row.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val optionsFor by vm.optionsFor.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val exitToGuide by vm.exitToGuide.collectAsStateWithLifecycle()

    // The current channel was hidden and nothing is left in the scope: back to the guide.
    LaunchedEffect(exitToGuide) { if (exitToGuide) onExit() }

    LaunchedEffect(vm) {
        while (true) {
            vm.tick()
            delay(CLOCK_TICK_MS)
        }
    }
    // Leaving the app stops playback (MainActivity); coming back resumes this channel.
    LifecycleStartEffect(vm) {
        vm.onScreenStarted()
        // Drop a zap still settling; coming back plays the channel shown (see onScreenStarted).
        onStopOrDispose { vm.onScreenStopped() }
    }
    fun back() {
        if (!vm.onBack()) onExit()
    }
    // Back arrives through the key handler below; this covers any other source (e.g. a gesture).
    BackHandler(onBack = ::back)

    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // Key codes whose key-down was consumed, so their key-up is swallowed too (no stray clicks).
    val consumed = remember { mutableSetOf<Int>() }
    // The OK press the root follows (see rootOkRoute): only presses that begin here click or hold.
    var rootPress by remember { mutableStateOf(RootPress.None) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                when {
                    // Handled here, not via the back dispatcher: with focus on a row tile Compose
                    // could swallow the first Back press (seen on the Shield).
                    code == KeyEvent.KEYCODE_BACK -> {
                        if (e.type == KeyEventType.KeyDown) {
                            vm.onUserInput()
                            consumed += code
                        } else if (e.type == KeyEventType.KeyUp && consumed.remove(code)) {
                            back()
                        }
                        true
                    }
                    rootOkAction(vm, code, e, rootPress) { rootPress = it } -> true
                    e.type == KeyEventType.KeyDown -> vm.onKey(code).also { if (it) consumed += code }
                    e.type == KeyEventType.KeyUp -> consumed.remove(code)
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        FullScreenVideo(playback)
        val u = ui ?: return@Box
        val showError = u.error != null

        // Bare video: focus sits on the error card's button if there is one, else on the screen
        // (also once the options dialog closes).
        val optionsOpen = optionsFor != null || editing != null
        LaunchedEffect(u.overlayVisible, showError, optionsOpen) {
            if (u.overlayVisible || optionsOpen) return@LaunchedEffect
            repeat(10) {
                if ((if (showError) retryFocus else rootFocus).tryFocus()) return@LaunchedEffect
                delay(30)
            }
        }

        if (u.buffering && !showError) {
            CircularProgressIndicator(
                color = Tokens.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
        if (u.overlayVisible || u.bannerVisible) {
            Overlay(
                ui = u,
                row = row,
                format = format,
                onGuide = {
                    vm.leave()
                    onExit()
                },
                favourites = favourites,
                onHistory = vm::showHistory,
                onChannels = vm::showChannels,
                onPlay = if (row.mode == RowMode.HISTORY) vm::playFromHistory else vm::playFromRow,
                onLongPress = vm::onTileLongPress,
                onRowEdge = vm::onRowEdge,
            )
        }
        optionsFor?.let { c ->
            val favourite = c.ref in favourites
            ChannelOptionsDialog(
                title = channelOptionsTitle(c),
                options = listOf(
                    ChannelOption(if (favourite) OPTION_REMOVE_FAVOURITE else OPTION_ADD_FAVOURITE) {
                        vm.dismissOptions()
                        vm.toggleFavourite(c)
                    },
                // The player shows only visible channels, and has no move mode.
                ) + channelEditOptions(c, hidden = false, canMove = false) { edit -> vm.pickEdit(c, edit) },
                onDismiss = vm::dismissOptions,
            )
        }
        editing?.let { e ->
            ChannelEditHost(edit = e.edit, channel = e.channel, edits = container.channelEdits, onDone = vm::onEditDone)
        }
        u.error?.let {
            // Centred on bare video; above the info bar while the overlay is up.
            val place = if (u.overlayVisible || u.bannerVisible) Modifier.align(Alignment.TopCenter).padding(top = 110.dp) else Modifier.align(Alignment.Center)
            ErrorCard(it, onRetry = vm::retry, focus = retryFocus, modifier = place)
        }
        u.numberEntry?.let {
            Text(
                text = it,
                fontSize = 64.sp,
                color = Tokens.OnBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        u.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleLarge,
                color = Tokens.OnBackground,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Tokens.SurfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/** Binds the shared player to a full-screen SurfaceView; detaching only unbinds it. */
@OptIn(UnstableApi::class)
@Composable
private fun FullScreenVideo(playback: Playback) {
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.fullscreen_player_view, null) as PlayerView).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // The guide preview may still hold the player during the transition; the player
                // moves to this surface, and the preview's later unbind leaves it alone.
                player = playback.player
            }
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * OK/Menu at the root through [rootOkRoute]; true = handled here (consumed), false = the usual
 * key path (tiles, other keys).
 */
private inline fun rootOkAction(
    vm: PlayerViewModel,
    code: Int,
    e: androidx.compose.ui.input.key.KeyEvent,
    press: RootPress,
    setPress: (RootPress) -> Unit,
): Boolean {
    val ui = vm.ui.value ?: return false
    val phase = when (e.type) {
        KeyEventType.KeyDown -> KeyPhase.Down
        KeyEventType.KeyUp -> KeyPhase.Up
        else -> return false
    }
    val state = RootKeyState(ui.overlayVisible, ui.numberEntry != null, vm.optionsFor.value != null || vm.editing.value != null)
    val result = rootOkRoute(state, code, phase, e.nativeKeyEvent.repeatCount, press)
    setPress(result.press)
    when (result.action) {
        RootOkAction.PassThrough -> return false
        RootOkAction.Consume -> Unit
        RootOkAction.Click -> vm.onKey(KeyEvent.KEYCODE_DPAD_CENTER)
        RootOkAction.LongClick -> vm.onLongOk()
        RootOkAction.Commit -> vm.onKey(code)
    }
    return true
}

@Composable
private fun Overlay(
    ui: PlayerUi,
    row: ChannelRowUi,
    format: GuideFormat,
    onGuide: () -> Unit,
    favourites: Set<ChannelRef>,
    onHistory: () -> Unit,
    onChannels: () -> Unit,
    onPlay: (ChannelEntity) -> Unit,
    onLongPress: (ChannelEntity) -> Unit,
    onRowEdge: (atEnd: Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        OverlayTopBar(
            group = ui.groupLabel,
            clock = ui.clock,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 40.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 24.dp),
        ) {
            InfoBar(ui, format)
            // The slim banner (after a channel key) stops here; D-pad/OK adds the row and moves focus into it.
            if (ui.overlayVisible) {
                ChannelRow(
                    row = row,
                    favourites = favourites,
                    onGuide = onGuide,
                    onHistory = onHistory,
                    onChannels = onChannels,
                    onPlay = onPlay,
                    onLongPress = onLongPress,
                    onReachEdge = onRowEdge,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, focus: FocusRequester, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .width(520.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(28.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
    }
}
