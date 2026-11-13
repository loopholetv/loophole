package io.github.loopholetv.ui.nav

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.setup.SetupView
import io.github.loopholetv.ui.setup.CopyFromScreen
import kotlinx.coroutines.flow.StateFlow

/** "Copy from another TV" (first start, or the guide with no playlist) opens the TV picker. */
fun NavController.openCopyFrom() {
    navigate(Routes.SETUP_COPY_FROM) { launchSingleTop = true }
}

/** The TV picker: the TVs in [peers]; picking one copies its setup here through [setup]. */
fun NavGraphBuilder.copyFromDestination(peers: StateFlow<List<CastPeer>>, setup: SetupView) {
    composable(Routes.SETUP_COPY_FROM) {
        val list by peers.collectAsStateWithLifecycle()
        CopyFromScreen(list, onPick = setup::copyFrom)
    }
}

/**
 * This TV's setup was replaced: back to the guide, with everything above it closed. From first
 * start (no guide yet) the guide replaces the whole back stack, onboarding included.
 */
fun NavController.returnToGuide() {
    val route = currentDestination?.route ?: return
    if (route == Routes.GUIDE) return
    if (popBackStack(Routes.GUIDE, inclusive = false)) return
    navigate(Routes.GUIDE) { popUpTo(graph.id) { inclusive = true } }
}
