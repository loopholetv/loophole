package io.github.loopholetv.ui.nav

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
import io.github.loopholetv.ui.search.SearchScreen
import io.github.loopholetv.ui.settings.SettingsPanel
import io.github.loopholetv.ui.theme.Tokens

object Routes {
    const val ONBOARDING = "onboarding"
    const val GUIDE = "guide"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val PLAYER = "player/{channelId}?scope={scope}&archiveFrom={archiveFrom}"
    /** The full-screen add/edit playlist form opened from Settings; `edit` is a playlist id. */
    const val PLAYLIST_FORM = "playlist-form?edit={edit}"

    const val ARG_CHANNEL_ID = "channelId"
    const val ARG_SCOPE = "scope"
    const val ARG_ARCHIVE_FROM = "archiveFrom"
    const val ARG_EDIT = "edit"

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
                add("scope=${URLEncoder.encode(scope.toArg(), "UTF-8").replace("+", "%20")}")
            }
            if (archiveFrom != null) add("archiveFrom=${archiveFrom.epochSecond}")
        }
        return "player/$channelId" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }
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
 * Every playlist counts, turned on or off: with all of them off the guide stays and says so,
 * so [hasPlaylist] comes from all playlists, never only the enabled ones.
 */
fun startDestination(hasPlaylist: Boolean): String =
    if (hasPlaylist) Routes.GUIDE else Routes.ONBOARDING

/**
 * App-level rule: with no playlist left, only onboarding makes sense. [currentRoute] null means
 * no destination yet (the graph is still being set up), which never navigates.
 */
fun shouldGoToOnboarding(playlistCount: Int, currentRoute: String?): Boolean =
    playlistCount == 0 && currentRoute != null && currentRoute != Routes.ONBOARDING

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
            if (shouldGoToOnboarding(count, navController.currentDestination?.route)) {
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
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize().background(Tokens.Background),
    ) {
        composable(Routes.ONBOARDING) {
            AddPlaylistScreen(onDone = {
                navController.navigate(Routes.GUIDE) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
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
            )
        }
        // Full screen (not a dialog): the player opened from a result pops back here.
        composable(Routes.SEARCH) {
            SearchScreen(
                onPlayChannel = { navController.navigate(Routes.player(it, ChannelScope.All)) },
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
