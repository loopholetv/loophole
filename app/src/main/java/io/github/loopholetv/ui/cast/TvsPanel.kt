package io.github.loopholetv.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.ui.theme.Tokens
import io.github.loopholetv.ui.settings.focusSoon

/**
 * The TVs panel (right side, opened from the side menu): this TV first, then the other TVs by
 * name, each with what it does. OK on a TV that casts joins it and closes the panel through
 * [onClose]; OK on this TV while it casts stops the cast. Back is handled by the dialog.
 */
@Composable
fun TvsPanel(
    onClose: () -> Unit,
    vm: TvsViewModel = viewModel(factory = TvsViewModel.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selfFocus = remember { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focusSoon(selfFocus) }
    // The focused TV dropped out of the list: focus goes back to this TV's row.
    val keys = ui.rows.map { it.key }
    LaunchedEffect(keys) {
        if (focusedKey != null && focusedKey !in keys) focusSoon(selfFocus)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(
            "TVs",
            style = MaterialTheme.typography.headlineSmall,
            color = Tokens.OnBackground,
            maxLines = 1,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in ui.rows) {
                // Keyed by TV, so focus stays on the same TV when others appear or go above it.
                key(row.key) {
                    TvRowItem(
                        row = row,
                        modifier = Modifier
                            .then(if (row is TvRow.ThisTv) Modifier.focusRequester(selfFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = row.key },
                        onClick = { if (vm.onOk(row)) onClose() },
                    )
                }
            }
            ui.emptyText?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.Muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TvRowItem(row: TvRow, modifier: Modifier, onClick: () -> Unit) {
    val (title, status) = when (row) {
        is TvRow.ThisTv -> THIS_TV_LABEL to row.status
        is TvRow.Peer -> row.name to row.status
    }
    val stop = row is TvRow.ThisTv && row.canStop
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        // While this TV casts, OK on its row stops the cast.
        supportingContent = if (stop) {
            { Pill(STOP_CASTING_LABEL, Tokens.Accent, Tokens.OnFocus, Modifier.padding(top = 6.dp)) }
        } else {
            null
        },
        trailingContent = { StatusPill(status) },
        modifier = modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(
            containerColor = Tokens.SurfaceRaised,
            contentColor = Tokens.OnBackground,
            focusedContainerColor = Tokens.FocusFill,
            focusedContentColor = Tokens.OnFocus,
        ),
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun StatusPill(status: CastStatus) = when (status) {
    CastStatus.CASTING -> Pill(statusLabel(status), Tokens.Accent, Tokens.OnFocus)
    CastStatus.WATCHING -> Pill(statusLabel(status), Tokens.Outline, Tokens.OnBackground)
    CastStatus.IDLE -> Pill(statusLabel(status), Tokens.Surface, Tokens.Muted)
}

@Composable
private fun Pill(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}
