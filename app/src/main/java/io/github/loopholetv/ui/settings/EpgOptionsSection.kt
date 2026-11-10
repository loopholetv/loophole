package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.ui.theme.Tokens

/** Global EPG options. Shown once, below the per-playlist blocks, even with no playlists. */
@Composable
fun EpgOptionsSection(ui: SettingsUi, vm: SettingsViewModel, host: ChoiceHost, clearFocus: FocusRequester) {
    val o = ui.options
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Update options", style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        host.Row("Update interval", AppSettings.INTERVAL_HOURS, o.epgUpdateHours, ::intervalText) { h ->
            vm.setOption { it.copy(epgUpdateHours = h) }
        }
        ToggleRow("Update on app start", o.epgUpdateOnStart, { vm.setOption { it.copy(epgUpdateOnStart = !it.epgUpdateOnStart) } })
        ToggleRow(
            "Update EPG on playlist change",
            o.epgUpdateOnPlaylistChange,
            { vm.setOption { it.copy(epgUpdateOnPlaylistChange = !it.epgUpdateOnPlaylistChange) } },
        )
        host.Row("Past days of EPG to keep", AppSettings.PAST_DAYS, o.epgPastDays, { pastDaysText(it, ui.automaticPastDays) }) { d ->
            vm.setOption { it.copy(epgPastDays = d) }
        }
        ToggleRow("Store programme descriptions", o.epgStoreDescriptions, { vm.setOption { it.copy(epgStoreDescriptions = !it.epgStoreDescriptions) } })
        ActionRow("Clear EPG", vm::requestClearEpg, Modifier.focusRequester(clearFocus))
        if (ui.epgCleared) Text("TV guide cleared.", style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentLight)
    }
}
