package io.github.loopholetv.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.player.ExternalPlayers
import io.github.loopholetv.player.enterPictureInPicture
import io.github.loopholetv.player.rememberInPictureInPicture
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
import io.github.loopholetv.ui.manage.rememberChannelPlayback
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.time.Instant

private const val CLOCK_TICK_MS = 5_000L

/** The casting label's height plus a gap: what a typed number moves down by under it. */
private val CASTING_LABEL_SPACE = 48.dp

/** The same, while the label has shrunk to its dot. */
private val CASTING_DOT_SPACE = 40.dp

/**
 * Full-screen playback. Bare video or the slim banner a channel key shows: D-pad Up, Down and OK
 * open the full overlay, holding OK (or Menu) opens the quick menu, Back hides the banner /
 * returns to the guide. Full overlay: group and clock on top, info bar and channel row at the
 * bottom; the D-pad moves along the row, OK on a tile changes channel (held: its options), Back
 * hides it. Channel keys and digits work everywhere. In picture-in-picture only the video shows.
 */
@Composable
fun PlayerScreen(
    channelId: Long,
    scope: ChannelScope,
    onExit: () -> Unit,
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** Replay the archive from this shown instant instead of playing live. */
    archiveFrom: Instant? = null,
) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(channelId, scope, archiveFrom))
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
    val quickMenu by vm.quickMenu.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val subtitleStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val autoFrameRate by vm.autoFrameRate.collectAsStateWithLifecycle()
    // Not the countdown itself: that is read only by the card, so the screen doesn't recompose each second.
    val sleepWarningShown by vm.sleepWarningShown.collectAsStateWithLifecycle()
    val tracks by playback.tracks.collectAsStateWithLifecycle()
    // The bar changes every second while it shows: only the bar and the info bar read it (through
    // a lambda), so the ticks never recompose this screen.
    val catchupBar = vm.catchupBar.collectAsStateWithLifecycle()
    val replaying by remember { derivedStateOf { catchupBar.value != null } }
    val barVisible by vm.barVisible.collectAsStateWithLifecycle()
    val archiveFailed by vm.archiveFailed.collectAsStateWithLifecycle()
    val castingFollowers by vm.castingFollowers.collectAsStateWithLifecycle()
    var castingMarkerExpanded by remember { mutableStateOf(true) }
    val inPip = rememberInPictureInPicture()
    LaunchedEffect(inPip) { vm.onPipChanged(inPip) }
    // Read when the user presses Home; the view model clears it as soon as an exit starts.
    DisposableEffect(Unit) {
        container.pipGate.playerShowing = true
        onDispose { container.pipGate.playerShowing = false }
    }

    // Back to the guide (hidden channel, sleep timer, other player) only while this entry is
    // resumed: after Settings closes, or once the user is back from the other player.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    var awaitingReturn by remember { mutableStateOf(false) }
    var returned by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingReturn, lifeState) {
        if (awaitingReturn && !lifeState.isAtLeast(Lifecycle.State.RESUMED)) returned = true
    }
    val resumed = lifeState.isAtLeast(Lifecycle.State.RESUMED) && (!awaitingReturn || returned)
    LaunchedEffect(exitToGuide, resumed) { if (shouldExit(exitToGuide, resumed)) onExit() }

    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val search by rememberUpdatedState(onSearch)
    val settings by rememberUpdatedState(onSettings)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is PlayerEvent.LaunchExternal -> {
                    // Never logged: the intent carries the stream link and its headers.
                    val intent = ExternalPlayers.intentFor(event.choice, event.handoff, event.title)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    try {
                        launcher.launch(intent)
                        awaitingReturn = true
                        vm.onExternalLaunched()
                    } catch (_: ActivityNotFoundException) {
                        vm.onExternalFailed()
                    }
                }
                PlayerEvent.EnterPip -> activity?.let { enterPictureInPicture(it) }
                PlayerEvent.OpenSearch -> {
                    // Leaving the player: settle it (no picture-in-picture on the way out) first.
                    vm.leave()
                    search()
                }
                PlayerEvent.OpenSettings -> settings()
            }
        }
    }

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
    // Keys taken by the sleep timer warning ("press any button"): their key-up does nothing either.
    val keptAwake = remember { mutableSetOf<Int>() }
    // The OK press the root follows (see rootOkRoute): only presses that begin here click or hold.
    var rootPress by remember { mutableStateOf(RootPress.None) }
    // The archive's error card: OK is taken at the root, so it acts on the button that has focus.
    var watchLiveFocused by remember { mutableStateOf(false) }
    val onClick: () -> Unit = { vm.onRootClick(watchLiveFocused) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                when {
                    // A seek key held into the sleep warning still commits its seek when released.
                    e.type == KeyEventType.KeyUp && keptAwake.remove(code) -> { vm.onKeyUp(code); true }
                    e.type == KeyEventType.KeyDown && vm.consumeSleepWarning() -> { keptAwake += code; true }
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
                    rootOkAction(vm, code, e, rootPress, onClick) { rootPress = it } -> true
                    e.type == KeyEventType.KeyDown ->
                        vm.onKey(code, e.nativeKeyEvent.repeatCount).also { if (it) consumed += code }
                    e.type == KeyEventType.KeyUp -> {
                        // A held seek key commits its seek when released.
                        vm.onKeyUp(code)
                        consumed.remove(code)
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // One surface for full screen and picture-in-picture: it is never recreated, only the
        // overlays come and go. MPEG-TS tracks often carry no frame rate; the decoded format may.
        val player = playback.player
        val frameRate = decodedFrameRate(player) ?: tracks.frameRate
        VideoSurface(
            player = player,
            aspect = aspect,
            subtitleStyle = subtitleStyle,
            autoFrameRate = autoFrameRate,
            frameRate = frameRate,
            inPip = inPip,
            onSurfaceAttached = playback::setFullScreenSurface,
            subtitlesShown = subtitlesShown(quickMenuOpen = quickMenu != null && !inPip),
        )
        val u = ui ?: return@Box
        if (inPip) return@Box
        // While replaying, only the archive's own card shows (the live one would offer the wrong retry).
        val showError = archiveFailed || (u.error != null && !replaying)

        // Bare video: focus sits on the error card's button if there is one, else on the screen
        // (also once a dialog closes).
        val dialogOpen = optionsFor != null || editing != null || quickMenu != null
        LaunchedEffect(u.overlayVisible, showError, dialogOpen) {
            if (u.overlayVisible || dialogOpen) return@LaunchedEffect
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
        val showBar = barVisible && replaying
        if (u.overlayVisible || u.bannerVisible || showBar) {
            Overlay(
                ui = u,
                showBar = showBar,
                catchup = { catchupBar.value },
                onTransport = vm::onTransport,
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
                ) + channelEditOptions(
                    c, hidden = false, canMove = false, playback = rememberChannelPlayback(c) ?: ChannelPlaybackPrefs.NONE,
                ) { edit -> vm.pickEdit(c, edit) },
                onDismiss = vm::dismissOptions,
            )
        }
        editing?.let { e ->
            ChannelEditHost(
                edit = e.edit,
                channel = e.channel,
                edits = container.channelEdits,
                playback = rememberChannelPlayback(e.channel),
                onDone = vm::onEditDone,
            )
        }
        quickMenu?.let { menu ->
            QuickMenu(
                ui = menu,
                onPick = vm::pickQuickItem,
                onChoice = vm::pickChoice,
                onStep = vm::stepAudioDelay,
                onClosePanel = vm::closePanel,
                onDismiss = vm::dismissQuickMenu,
            )
        }
        if (sleepWarningShown) SleepWarningHost(vm.sleep, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        if (showError) {
            // Centred on bare video; above the info bar while the overlay is up.
            val place = if (u.overlayVisible || u.bannerVisible) Modifier.align(Alignment.TopCenter).padding(top = 110.dp) else Modifier.align(Alignment.Center)
            if (archiveFailed) {
                ErrorCard(
                    ARCHIVE_FAILED, onRetry = vm::retryArchive, focus = retryFocus, modifier = place,
                    detail = ARCHIVE_FAILED_DETAIL, onWatchLive = vm::watchLive,
                    onWatchLiveFocused = { watchLiveFocused = it },
                )
            } else {
                u.error?.let { ErrorCard(it, onRetry = vm::retry, focus = retryFocus, modifier = place) }
            }
        }
        // Below the clock while the top bar shows; a number being typed goes under it.
        val topBar = u.overlayVisible || u.bannerVisible
        val labelTop = if (topBar) 72.dp else 24.dp
        castingFollowers?.let {
            CastingMarker(
                it,
                Modifier.align(Alignment.TopEnd).padding(top = labelTop, end = 48.dp),
                onExpandedChange = { expanded -> castingMarkerExpanded = expanded },
            )
        }
        val castingLabelSpace = if (castingMarkerExpanded) CASTING_LABEL_SPACE else CASTING_DOT_SPACE
        u.numberEntry?.let {
            Text(
                text = it,
                fontSize = 64.sp,
                color = Tokens.OnBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (castingFollowers != null && topBar) labelTop + castingLabelSpace else 72.dp, end = 48.dp)
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
                    .padding(top = if (sleepWarningShown) 150.dp else 72.dp)
                    .background(Tokens.SurfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
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
    click: () -> Unit,
    setPress: (RootPress) -> Unit,
): Boolean {
    val ui = vm.ui.value ?: return false
    val phase = when (e.type) {
        KeyEventType.KeyDown -> KeyPhase.Down
        KeyEventType.KeyUp -> KeyPhase.Up
        else -> return false
    }
    val state = RootKeyState(ui.overlayVisible, ui.numberEntry != null, vm.dialogOpen())
    val result = rootOkRoute(state, code, phase, e.nativeKeyEvent.repeatCount, press)
    setPress(result.press)
    when (result.action) {
        RootOkAction.PassThrough -> return false
        RootOkAction.Consume -> Unit
        RootOkAction.Click -> click()
        RootOkAction.LongClick -> vm.onLongOk()
        RootOkAction.Commit -> vm.onKey(code)
    }
    return true
}

@Composable
internal fun Overlay(
    ui: PlayerUi,
    /** Whether the catch-up bar shows. */
    showBar: Boolean,
    /**
     * What is replaying, for the bar and the info bar (also while the bar is hidden). Read only
     * inside them, so its once-a-second changes recompose nothing else.
     */
    catchup: () -> CatchupBarUi?,
    onTransport: (TransportButton) -> Unit,
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
    val info = ui.overlayVisible || ui.bannerVisible
    Box(Modifier.fillMaxSize()) {
        if (info) {
            OverlayTopBar(
                group = ui.groupLabel,
                clock = ui.clock,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 40.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 24.dp),
        ) {
            if (showBar) {
                TickingCatchupBar(
                    ui = catchup,
                    // Changes only when OK opens the overlay on the bar, so it refocuses each time.
                    focus = if (ui.barFocused) TransportButton.PLAY_PAUSE else null,
                    onButton = onTransport,
                    // Nothing above the bar: Up stays on it. Down goes on to the channel row.
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    },
                )
            }
            if (info) InfoBar(ui, format, catchup = catchup)
            // The slim banner (after a channel key) stops here; D-pad/OK adds the row and moves focus into it.
            if (ui.overlayVisible) {
                ChannelRow(
                    autoFocus = !ui.barFocused,
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
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier,
    detail: String? = null,
    onWatchLive: (() -> Unit)? = null,
    onWatchLiveFocused: (Boolean) -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .width(520.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(28.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground, textAlign = TextAlign.Center)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
            onWatchLive?.let {
                Button(onClick = it, modifier = Modifier.onFocusChanged { f -> onWatchLiveFocused(f.isFocused) }) {
                    Text("Watch live")
                }
            }
        }
    }
}

/**
 * The frame rate of the video format being decoded, when known. Read during composition: the
 * screen recomposes when the stream goes from buffering to playing, after the format is known.
 */
@OptIn(UnstableApi::class)
private fun decodedFrameRate(player: Player): Float? =
    (player as? ExoPlayer)?.videoFormat?.frameRate?.takeIf { it > 0f }
