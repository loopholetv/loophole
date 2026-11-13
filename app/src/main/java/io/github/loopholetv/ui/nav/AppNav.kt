package io.github.loopholetv.ui.nav

import android.os.Bundle
import java.net.URLEncoder
import java.time.Instant
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.ui.cast.CastFollowScreen
import io.github.loopholetv.ui.cast.CastInviteCard
import io.github.loopholetv.ui.cast.InviteKeys
import io.github.loopholetv.ui.cast.TvsPanel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.player.rememberInPictureInPicture
import io.github.loopholetv.shouldCloseAbovePlayerForPip
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.channelScopeFromArg
import io.github.loopholetv.data.db.toArg
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.navOptions
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.navArgument
import io.github.loopholetv.ui.guide.GuideScreen
import io.github.loopholetv.ui.onboarding.AddPlaylistScreen
import io.github.loopholetv.ui.player.KeepScreenOnWhilePlaying
import io.github.loopholetv.ui.player.PlayerScreen
import io.github.loopholetv.ui.vod.VodPlayerScreen
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.ui.search.SearchScreen
import io.github.loopholetv.ui.settings.SettingsPanel
import io.github.loopholetv.setup.SetupState
import io.github.loopholetv.ui.setup.SetupOverlay
import io.github.loopholetv.ui.theme.Tokens
import io.github.loopholetv.ui.vod.FilmScreen
import io.github.loopholetv.ui.vod.MoviesScreen
import io.github.loopholetv.ui.vod.ShowScreen
import io.github.loopholetv.ui.vod.SeriesScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val GUIDE = "guide"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val MOVIES = "movies"
    const val SERIES = "series"
    /** A film's page; the key (which may hold slashes) is URL-encoded in the concrete route. */
    const val FILM = "film/{playlistId}/{key}"
    /** A show's page; the key is encoded like a film's. */
    const val SHOW = "show/{playlistId}/{key}"
    const val PLAYER = "player/{channelId}?scope={scope}&archiveFrom={archiveFrom}"
    /** The full-screen add/edit playlist form opened from Settings; `edit` is a playlist id. */
    const val PLAYLIST_FORM = "playlist-form?edit={edit}"
    /** The other TVs on the home network: a right-side panel like Settings. */
    const val TVS = "tvs"
    /** Full-screen video of another TV's cast. */
    const val CAST_FOLLOW = "cast/follow"
    /** Copying another TV's setup onto this one, from the first-start screen. */
    const val SETUP_COPY_FROM = "setup/copy-from"
    /** Full-screen playback of a film or episode; `start` is where it begins, in ms. */
    const val VOD_PLAYER = "vod/play/{kind}/{playlistId}/{key}?start={start}"

    const val ARG_CHANNEL_ID = "channelId"
    const val ARG_SCOPE = "scope"
    const val ARG_ARCHIVE_FROM = "archiveFrom"
    const val ARG_EDIT = "edit"
    const val ARG_KIND = "kind"
    const val ARG_PLAYLIST_ID = "playlistId"
    const val ARG_KEY = "key"
    const val ARG_START = "start"

    val vodPlayerArguments = listOf(
        navArgument(ARG_KIND) { type = NavType.StringType },
        navArgument(ARG_PLAYLIST_ID) { type = NavType.LongType },
        navArgument(ARG_KEY) { type = NavType.StringType },
        navArgument(ARG_START) { type = NavType.LongType; defaultValue = 0L },
    )

    val vodPageArguments = listOf(
        navArgument(ARG_PLAYLIST_ID) { type = NavType.LongType },
        navArgument(ARG_KEY) { type = NavType.StringType },
    )

    fun film(playlistId: Long, key: String): String = "film/$playlistId/${encodeKey(key)}"

    fun show(playlistId: Long, key: String): String = "show/$playlistId/${encodeKey(key)}"

    /** Encodes a route segment or query value (spaces as %20, which navigation decodes back). */
    private fun encodeKey(key: String): String = URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    /** Concrete film player route; the key (which may hold slashes) is URL-encoded. */
    fun vodPlayer(ref: VodRef, startMs: Long): String =
        "vod/play/${ref.kind.name}/${ref.playlistId}/${encodeKey(ref.key)}?start=$startMs"

    /** The player destination's arguments; `archiveFrom` is epoch seconds, [NO_ARCHIVE] for live. */
    val playerArguments = listOf(
        navArgument(ARG_CHANNEL_ID) { type = NavType.LongType },
        navArgument(ARG_SCOPE) { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument(ARG_ARCHIVE_FROM) { type = NavType.LongType; defaultValue = NO_ARCHIVE },
    )

    /** "Add playlist" without [editId]; editing that playlist with it. */
    fun playlistForm(editId: Long? = null): String =
        "playlist-form" + if (editId != null) "?edit=$editId" else ""

    /**
     * Concrete player route; the scope ([toArg]) is URL-encoded and omitted for all channels,
     * and [archiveFrom] (replay from that instant instead of live) is omitted when null.
     */
    fun player(channelId: Long, scope: ChannelScope, archiveFrom: Instant? = null): String {
        val query = buildList {
            if (scope != ChannelScope.All) {
                add("scope=${encodeKey(scope.toArg())}")
            }
            if (archiveFrom != null) add("archiveFrom=${archiveFrom.epochSecond}")
        }
        return "player/$channelId" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }
}

/** The film or episode a [Routes.VOD_PLAYER] entry plays; null when its arguments are missing or unknown. */
fun vodRefFromArgs(args: Bundle?): VodRef? {
    val kind = args?.getString(Routes.ARG_KIND)?.let { name -> VodKind.entries.firstOrNull { it.name == name } } ?: return null
    val key = args.getString(Routes.ARG_KEY) ?: return null
    return VodRef(kind, args.getLong(Routes.ARG_PLAYLIST_ID), key)
}

/** The player's `archiveFrom` argument: [NO_ARCHIVE] (Navigation can't make a Long nullable) means live. */
const val NO_ARCHIVE = -1L

fun archiveFromArg(arg: Long?): Instant? = arg?.takeIf { it >= 0 }?.let(Instant::ofEpochSecond)

/** The form's `edit` argument: [NO_EDIT] (Navigation can't make a Long nullable) means "add". */
const val NO_EDIT = -1L

fun playlistFormEditId(arg: Long?): Long? = arg?.takeIf { it != NO_EDIT }

/** Search opened from the player takes its place, so Back from Search returns to the guide. */
fun searchFromPlayerOptions(): NavOptions = navOptions {
    popUpTo(Routes.PLAYER) { inclusive = true }
    launchSingleTop = true
}

/**
 * Into the follow screen: everything above the guide goes first, so a player left underneath
 * can't play a channel over the cast.
 */
fun castFollowOptions(): NavOptions = navOptions {
    popUpTo(Routes.GUIDE) { inclusive = false }
    launchSingleTop = true
}

/** Whether the follow screen opens ([ENTER]) or closes ([LEAVE]) for the cast state. */
enum class FollowNav { NONE, ENTER, LEAVE }

/**
 * Following a cast always shows the follow screen, and the follow screen shows only while
 * following. [currentRoute] null means no destination yet, which never navigates.
 */
fun followNavFor(following: Boolean, currentRoute: String?): FollowNav = when {
    currentRoute == null -> FollowNav.NONE
    following && currentRoute != Routes.CAST_FOLLOW -> FollowNav.ENTER
    !following && currentRoute == Routes.CAST_FOLLOW -> FollowNav.LEAVE
    else -> FollowNav.NONE
}

/** From the follow screen back to the guide (or whatever is under it when there is no guide). */
private fun NavHostController.leaveFollow() {
    if (currentDestination?.route != Routes.CAST_FOLLOW) return
    if (!popBackStack(Routes.GUIDE, inclusive = false)) popBackStack()
}

/**
 * Every playlist counts, turned on or off: with all of them off the guide stays and says so,
 * so [hasPlaylist] comes from all playlists, never only the enabled ones.
 */
fun startDestination(hasPlaylist: Boolean, skipped: Boolean): String =
    if (hasPlaylist || skipped) Routes.GUIDE else Routes.ONBOARDING

/**
 * App-level rule: with no playlist left, only onboarding makes sense, unless setup was [skipped]
 * or a setup copy is [copying] (it replaces the playlists, and returns to the guide itself).
 * [currentRoute] null means no destination yet (the graph is still being set up), which never
 * navigates.
 */
fun shouldGoToOnboarding(playlistCount: Int, currentRoute: String?, skipped: Boolean = false, copying: Boolean = false): Boolean =
    !skipped && !copying && playlistCount == 0 && currentRoute != null && currentRoute != Routes.ONBOARDING

/**
 * The first-start screens, where this TV must not join a cast on its own. No destination yet
 * (null) counts too, since the first screen may be one of them.
 */
fun isFirstStartRoute(route: String?): Boolean =
    route == null || route == Routes.ONBOARDING || route == Routes.SETUP_COPY_FROM

/**
 * Where this TV must not join a cast on its own: the first-start screens, and a film or episode
 * playing (a stream from the provider is never shared, and following a cast would cut it off).
 */
fun holdsAutoJoin(route: String?): Boolean = isFirstStartRoute(route) || route == Routes.VOD_PLAYER

/**
 * Holds joining a cast on its own ([hold] true) while [routes] is on a screen [holdsOn] names, or
 * while a setup copy is [copying] on any screen (a following TV would cover the copy's screens).
 */
suspend fun holdAutoJoin(
    routes: kotlinx.coroutines.flow.Flow<String?>,
    copying: kotlinx.coroutines.flow.Flow<Boolean>,
    holdsOn: (String?) -> Boolean = ::isFirstStartRoute,
    hold: (Boolean) -> Unit,
) {
    kotlinx.coroutines.flow.combine(routes.map(holdsOn), copying) { firstStart, copy -> firstStart || copy }
        .distinctUntilChanged()
        .collect(hold)
}

@Composable
fun AppNav(
    navController: NavHostController,
    startDestination: String,
    startPlayback: StartSlot = StartSlot(null),
    confirmExit: Boolean = false,
) {
    val container = (LocalContext.current.applicationContext as LoopholeApp).container
    LaunchedEffect(navController, container) {
        container.playlistRepository.playlists().map { it.size }.distinctUntilChanged().collect { count ->
            val skipped = runCatching { container.settingsStore.current().onboardingSkipped }.getOrDefault(false)
            val copying = container.setup.state.value !is SetupState.Idle
            if (shouldGoToOnboarding(count, navController.currentDestination?.route, skipped, copying)) {
                container.playback.stop()
                navController.navigate(Routes.ONBOARDING) { popUpTo(navController.graph.id) { inclusive = true } }
            }
        }
    }
    // One holder for the whole app: per-screen holders fight over the single ComposeView flag.
    KeepScreenOnWhilePlaying(container.playback)
    // Only the player enters picture-in-picture; whatever was open above it (Settings over the
    // video) closes so the window shows the video alone.
    val inPip = rememberInPictureInPicture()
    LaunchedEffect(inPip, navController) {
        if (shouldCloseAbovePlayerForPip(inPip, navController.currentDestination?.route)) {
            navController.popBackStack(Routes.PLAYER, inclusive = false)
        }
    }
    val cast = container.castController
    // Discovery runs on every screen (copying a setup needs it), but on the first-start screens
    // and during a setup copy this TV doesn't join a cast on its own.
    LaunchedEffect(navController, container) {
        holdAutoJoin(
            navController.currentBackStackEntryFlow.map { it.destination.route },
            container.setup.state.map { it !is SetupState.Idle },
            ::holdsAutoJoin,
        ) { container.autoJoinHold.set(it) }
    }
    val toastContext = LocalContext.current
    // Messages about casts (ended, lost, can't play...), wherever the app is.
    LaunchedEffect(cast, toastContext) {
        cast.messages.collect { Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show() }
    }
    // Following a cast shows the follow screen; when the cast ends the guide comes back, with
    // nothing replaying in its preview.
    LaunchedEffect(navController, cast) {
        combine(
            cast.state.map { it is CastState.Following }.distinctUntilChanged(),
            navController.currentBackStackEntryFlow.map { it.destination.route },
            ::followNavFor,
        ).collect { nav ->
            when (nav) {
                FollowNav.ENTER -> navController.navigate(Routes.CAST_FOLLOW, castFollowOptions())
                FollowNav.LEAVE -> {
                    container.previewHold.hold()
                    navController.leaveFollow()
                }
                FollowNav.NONE -> Unit
            }
        }
    }
    // Not in picture-in-picture, which shows the video alone.
    val invite = cast.invite.collectAsStateWithLifecycle().value?.takeIf { !inPip }
    // While the invitation shows it has the remote; the screen underneath keeps its focus.
    val inviteKeys = remember(cast) {
        InviteKeys(onWatch = { cast.invite.value?.let { cast.join(it.peer.id) } }, onIgnore = cast::ignoreInvite)
    }
    LaunchedEffect(invite?.peer?.castId) { inviteKeys.reset() }
    val inviteShowing by rememberUpdatedState(invite != null)
    // Whether a control on the screens below has focus, for the setup screens' hand-back.
    val screenFocused = remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().onFocusChanged { screenFocused.value = it.hasFocus }.onPreviewKeyEvent { e ->
            val down = e.type == KeyEventType.KeyDown
            if (!down && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            inviteKeys.onKey(e.nativeKeyEvent.keyCode, down, e.nativeKeyEvent.repeatCount, inviteShowing)
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize().background(Tokens.Background),
        ) {
            composable(Routes.ONBOARDING) {
                val scope = rememberCoroutineScope()
                AddPlaylistScreen(
                    onDone = {
                        navController.navigate(Routes.GUIDE) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    },
                    onSkip = {
                        scope.launch {
                            // If the flag can't be saved the guide still opens; it just won't be remembered.
                            runCatching { container.settingsStore.update { it.copy(onboardingSkipped = true) } }
                            navController.navigate(Routes.GUIDE) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                        }
                    },
                    onCopyFromAnotherTv = { navController.openCopyFrom() },
                )
            }
            // Back returns to where it opened (first start, or the guide with no playlist); the copy
            // itself shows in the setup screens.
            copyFromDestination(cast.peers, container.setup)
            composable(Routes.GUIDE) {
                // Last channel on start: at most once per process start (the slot empties on the
                // first look, wherever the app is); the guide stays underneath for Back.
                LaunchedEffect(startPlayback) {
                    val start = startPlayback.take(navController.currentDestination?.route) ?: return@LaunchedEffect
                    navController.navigate(Routes.player(start.channelId, start.scope)) { launchSingleTop = true }
                }
                // Composed before GuideScreen so the guide's own Back handlers (shifted window, move
                // mode) win while enabled (guide only).
                val context = LocalContext.current
                val exit = remember { ExitConfirm(clock = SystemClock::uptimeMillis) }
                BackHandler(enabled = confirmExit) {
                    when (exit.onBack()) {
                        ExitDecision.SHOW_HINT ->
                            Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                        ExitDecision.EXIT -> context.findActivity()?.finish()
                    }
                }
                GuideScreen(
                    onPlay = { id, scope -> navController.navigate(Routes.player(id, scope)) },
                    onPlayArchive = { id, scope, from -> navController.navigate(Routes.player(id, scope, from)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
                    onTvs = { navController.navigate(Routes.TVS) { launchSingleTop = true } },
                    onMovies = { navController.navigate(Routes.MOVIES) { launchSingleTop = true } },
                    onSeries = { navController.navigate(Routes.SERIES) { launchSingleTop = true } },
                    onCopyFromAnotherTv = { navController.openCopyFrom() },
                )
            }
            // Only Back does anything here; the cast plays through the shared player.
            composable(Routes.CAST_FOLLOW) {
                CastFollowScreen(onExit = { navController.leaveFollow() })
            }
            // Full screen (not a dialog): the player opened from a result pops back here.
            composable(Routes.SEARCH) { entry ->
                SearchScreen(
                    onPlayChannel = { navController.navigate(Routes.player(it, ChannelScope.All)) },
                    // Only while this entry is resumed: a quick double OK must not push two pages.
                    onOpenFilm = { p, key ->
                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navController.navigate(Routes.film(p, key)) { launchSingleTop = true }
                        }
                    },
                    onOpenShow = { p, key ->
                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navController.navigate(Routes.show(p, key)) { launchSingleTop = true }
                        }
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }
            composable(Routes.MOVIES) {
                MoviesScreen(
                    onOpenFilm = { p, key -> navController.navigate(Routes.film(p, key)) { launchSingleTop = true } },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }
            composable(Routes.SERIES) {
                SeriesScreen(
                    onOpenShow = { p, key -> navController.navigate(Routes.show(p, key)) { launchSingleTop = true } },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }
            composable(Routes.FILM, arguments = Routes.vodPageArguments) { entry ->
                val args = entry.arguments
                FilmScreen(
                    playlistId = args?.getLong(Routes.ARG_PLAYLIST_ID) ?: 0L,
                    key = args?.getString(Routes.ARG_KEY).orEmpty(),
                    // Only while this entry is resumed: a quick double OK must not push two players.
                    onPlay = { ref, ms ->
                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navController.navigate(Routes.vodPlayer(ref, ms))
                        }
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }
            composable(Routes.SHOW, arguments = Routes.vodPageArguments) { entry ->
                val args = entry.arguments
                ShowScreen(
                    playlistId = args?.getLong(Routes.ARG_PLAYLIST_ID) ?: 0L,
                    key = args?.getString(Routes.ARG_KEY).orEmpty(),
                    // Only while this entry is resumed: a quick double OK must not push two players.
                    onPlay = { ref, ms ->
                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navController.navigate(Routes.vodPlayer(ref, ms))
                        }
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }
            composable(Routes.PLAYER, arguments = Routes.playerArguments) { entry ->
                val args = entry.arguments
                PlayerScreen(
                    channelId = args?.getLong(Routes.ARG_CHANNEL_ID) ?: 0L,
                    scope = channelScopeFromArg(args?.getString(Routes.ARG_SCOPE)),
                    archiveFrom = archiveFromArg(args?.getLong(Routes.ARG_ARCHIVE_FROM, NO_ARCHIVE)),
                    // Only while this entry is resumed: a second Back during the exit fade must not
                    // pop the guide too.
                    onExit = dropUnlessResumed { navController.popBackStack() },
                    onSearch = dropUnlessResumed { navController.navigate(Routes.SEARCH, searchFromPlayerOptions()) },
                    // A dialog destination: the video keeps playing underneath.
                    onSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(Routes.VOD_PLAYER, arguments = Routes.vodPlayerArguments) { entry ->
                val ref = vodRefFromArgs(entry.arguments)
                if (ref == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    VodPlayerScreen(
                        ref = ref,
                        startMs = entry.arguments?.getLong(Routes.ARG_START) ?: 0L,
                        // Not only while resumed: a film that ends in the picture-in-picture window leaves too.
                        onExit = { if (navController.currentDestination?.route == Routes.VOD_PLAYER) navController.popBackStack() },
                    )
                }
            }
            // A dialog destination keeps the previous screen composed underneath; Back pops it.
            dialog(
                Routes.SETTINGS,
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                SettingsPanelHost {
                    // The same full-screen form as first start; Done and Back return to Settings.
                    SettingsPanel(
                        onAddPlaylist = { navController.navigate(Routes.playlistForm()) { launchSingleTop = true } },
                        onEditPlaylist = { navController.navigate(Routes.playlistForm(it)) { launchSingleTop = true } },
                    )
                }
            }
            dialog(
                Routes.TVS,
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                SettingsPanelHost {
                    TvsPanel(onClose = dropUnlessResumed { navController.popBackStack() })
                }
            }
            // A full-screen dialog over Settings (not a composable: navigating to one pops every dialog),
            // so Settings stays open underneath, on the page it was on.
            dialog(
                Routes.PLAYLIST_FORM,
                arguments = listOf(navArgument(Routes.ARG_EDIT) { type = NavType.LongType; defaultValue = NO_EDIT }),
                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) { entry ->
                val editId = playlistFormEditId(entry.arguments?.getLong(Routes.ARG_EDIT, NO_EDIT))
                Box(Modifier.fillMaxSize().background(Tokens.Background)) {
                    AddPlaylistScreen(
                        editId = editId,
                        onDone = dropUnlessResumed { navController.popBackStack() },
                        onBack = dropUnlessResumed { navController.popBackStack() },
                    )
                }
            }
        }
        // Over every screen, top-right: another TV started casting while this one plays something.
        invite?.let { offer ->
            CastInviteCard(
                name = offer.peer.name,
                selected = inviteKeys.selected,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
        }
    }
    // Copying a setup between TVs: a window of its own above every screen, panel and the card.
    SetupOverlay(
        setup = container.setup,
        hidden = inPip,
        onReturnToGuide = { navController.returnToGuide() },
        screenHasFocus = { screenFocused.value },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Right-side panel (about 35% width, SurfaceRaised) over a dimmed backdrop. Back is handled by the dialog. */
@Composable
fun SettingsPanelHost(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
        Spacer(Modifier.weight(0.65f).fillMaxHeight())
        Box(
            Modifier.weight(0.35f).fillMaxHeight().background(Tokens.SurfaceRaised),
            contentAlignment = Alignment.TopStart,
        ) { content() }
    }
}
