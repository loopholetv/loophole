package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.loopholetv.ui.manage.TextEntryDialog

private const val NAME_KEY = "name"
private const val ENABLED_KEY = "enabled"
private const val UA_KEY = "userAgent"

/** Longest user agent typed. */
private const val MAX_UA_LENGTH = 300

/**
 * One playlist's page: name, on/off, its source (the full-screen form), update, its own
 * update options, user agent, "Groups and channels" and delete.
 */
@Composable
fun PlaylistPage(
    row: PlaylistRowUi,
    ui: SettingsUi,
    vm: SettingsViewModel,
    focusKey: String?,
    onFocusConsumed: () -> Unit,
    onEditSource: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val id = row.id
    val host = rememberChoiceHost()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var refocus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: NAME_KEY))
        onFocusConsumed()
    }
    LaunchedEffect(dialog, refocus) {
        val key = refocus
        if (dialog == null && key != null) { focusSoon(host.requester(key)); refocus = null }
    }
    val close: () -> Unit = { refocus = dialog; dialog = null }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }

    val intervals = playlistIntervalChoices(ui.options)
    val onStarts = playlistOnStartChoices(ui.options)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ValueRow("Name", row.name, { dialog = NAME_KEY }, Modifier.focusRequester(host.requester(NAME_KEY)))
        ToggleRow("Turned on", row.enabled, { vm.setEnabled(id, !row.enabled) }, Modifier.focusRequester(host.requester(ENABLED_KEY)))
        ActionRow("Change address or login", onEditSource)
        ActionRow(
            "Update playlist",
            { vm.updatePlaylist(id) },
            supporting = row.statusText,
            supportingIsError = row.statusIsError,
        )
        host.Row(
            "Update interval",
            intervals.map { it.first },
            row.updateHours,
            { h -> choiceText(intervals, h) { intervalText(it ?: 0) } },
            key = "p$id:interval",
        ) { vm.setPlaylistInterval(id, it) }
        host.Row(
            "Update on app start",
            onStarts.map { it.first },
            row.updateOnStart,
            { s -> choiceText(onStarts, s) { onOff(it ?: false) } },
            key = "p$id:onStart",
        ) { vm.setPlaylistOnStart(id, it) }
        ValueRow("User agent", userAgentText(row.userAgent), { dialog = UA_KEY }, Modifier.focusRequester(host.requester(UA_KEY)))
        ActionRow("Groups and channels", onOpenEditor, Modifier.focusRequester(host.requester(EDITOR_KEY)))
        ActionRow("Delete playlist", { vm.requestDelete(id) }, Modifier.focusRequester(host.requester(DELETE_KEY)))
    }
    when (dialog) {
        NAME_KEY -> TextEntryDialog(
            title = "Playlist name",
            label = "Name",
            initial = row.name,
            keyboardType = KeyboardType.Text,
            onSave = { vm.rename(id, it); close() },
            onDismiss = close,
        )
        UA_KEY -> TextEntryDialog(
            title = "User agent",
            label = "User agent",
            initial = row.userAgent.orEmpty(),
            keyboardType = KeyboardType.Text,
            onSave = { vm.setPlaylistUserAgent(id, it); close() },
            onDismiss = close,
            accept = io.github.loopholetv.data.net.UserAgents::isAcceptable,
            maxLength = MAX_UA_LENGTH,
        )
    }
}
