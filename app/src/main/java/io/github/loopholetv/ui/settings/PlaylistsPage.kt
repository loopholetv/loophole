package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.ui.theme.Tokens

private const val ADD_KEY = "add"
private const val UPDATE_ALL_KEY = "updateAll"
private const val DEFAULT_INTERVAL_KEY = "defaultInterval"

/**
 * Settings → Playlists: "Add playlist", "Update all playlists", one row per playlist (OK opens
 * its page), then the default update options every playlist follows unless it has its own.
 */
@Composable
fun PlaylistsPage(
    ui: SettingsUi,
    vm: SettingsViewModel,
    focusKey: String?,
    onFocusConsumed: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (playlistId: Long) -> Unit,
) {
    val host = rememberChoiceHost()
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: ui.playlists.firstOrNull()?.let { playlistRowKey(it.id) } ?: ADD_KEY))
        onFocusConsumed()
    }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow("Add playlist", onAdd, Modifier.focusRequester(host.requester(ADD_KEY)))
        // Stays enabled while running (disabling the focused row would drop focus); the VM ignores repeats.
        ActionRow(
            "Update all playlists",
            vm::updateAll,
            Modifier.focusRequester(host.requester(UPDATE_ALL_KEY)),
            supporting = ui.updateAllText,
        )
        Spacer(Modifier.height(8.dp))
        if (ui.playlists.isEmpty()) Text("No playlists.", color = Tokens.Muted)
        ui.playlists.forEach { row ->
            ActionRow(
                row.name,
                onClick = { onOpen(row.id) },
                modifier = Modifier.focusRequester(host.requester(playlistRowKey(row.id))),
                supporting = row.secondaryText,
                supportingIsError = row.statusIsError,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Update options", style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        val o = ui.options
        host.Row("Default update interval", AppSettings.INTERVAL_HOURS, o.playlistUpdateHours, ::intervalText, key = DEFAULT_INTERVAL_KEY) { h ->
            vm.setOption { it.copy(playlistUpdateHours = h) }
        }
        ToggleRow(
            "Default: update on app start",
            o.playlistUpdateOnStart,
            { vm.setOption { it.copy(playlistUpdateOnStart = !it.playlistUpdateOnStart) } },
        )
    }
}
