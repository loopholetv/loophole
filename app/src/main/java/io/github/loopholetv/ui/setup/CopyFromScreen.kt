package io.github.loopholetv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.ui.settings.focusSoon
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

const val COPY_FROM_TITLE = "Copy from another TV"
const val LOOKING_FOR_TVS = "Looking for TVs…"
const val NO_SETUP_TVS = "No other Loophole TVs found on this network."

/** How long an empty list says it is looking before it says nothing was found. */
const val SEARCH_MS = 10_000L

/**
 * First start, "Copy from another TV": the Loophole TVs on the home network by name. OK on one
 * calls [onPick] with its id; a TV on an older version says so and can't be picked. Back is the
 * navigation's (back to the first-start screen).
 */
@Composable
fun CopyFromScreen(peers: List<CastPeer>, onPick: (String) -> Unit) {
    val sorted = remember(peers) { peers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CastPeer::name)) }
    val first = remember { FocusRequester() }
    val empty = sorted.isEmpty()
    var waited by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(empty) {
        if (empty) {
            waited = false
            delay(SEARCH_MS)
            waited = true
        }
    }
    // The first TV has focus when TVs appear, and again whenever the focused TV leaves.
    val ids = sorted.map { it.id }
    LaunchedEffect(ids) {
        if (ids.isNotEmpty() && (focusedId == null || focusedId !in ids)) focusSoon(first)
    }
    Column(
        Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(COPY_FROM_TITLE, style = MaterialTheme.typography.headlineMedium, color = Tokens.OnBackground, maxLines = 1)
        Spacer(Modifier.height(24.dp))
        if (empty) {
            Text(
                if (waited) NO_SETUP_TVS else LOOKING_FOR_TVS,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.Muted,
            )
            return@Column
        }
        Column(
            // Room above and below, so the focused row (drawn larger) keeps its rounded corners.
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sorted.forEachIndexed { index, peer ->
                key(peer.id) {
                    val older = if (peer.setup < 1) SetupController.olderVersion(peer.name) else null
                    ListItem(
                        selected = false,
                        onClick = { if (older == null) onPick(peer.id) },
                        headlineContent = {
                            Text(
                                peer.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = older?.let {
                            { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedId = peer.id },
                        colors = if (older != null) {
                            ListItemDefaults.colors(
                                containerColor = Tokens.Surface,
                                contentColor = Tokens.Muted,
                                focusedContainerColor = Tokens.Outline,
                                focusedContentColor = Tokens.OnBackground,
                            )
                        } else {
                            ListItemDefaults.colors(
                                containerColor = Tokens.SurfaceRaised,
                                contentColor = Tokens.OnBackground,
                                focusedContainerColor = Tokens.FocusFill,
                                focusedContentColor = Tokens.OnFocus,
                            )
                        },
                        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}
