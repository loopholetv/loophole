package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.common.TypingField
import io.github.loopholetv.ui.common.TypingState
import io.github.loopholetv.ui.common.rememberTypingState
import io.github.loopholetv.ui.theme.Tokens

/** Longest guide link typed. */
private const val MAX_URL_LENGTH = 2048

internal fun fieldKey(playlistId: Long) = "epgField:$playlistId"
internal fun epgSourceKey(sourceId: Long) = "epgSource:$sourceId"
private fun ownGuideKey(playlistId: Long) = "ownGuide:$playlistId"

/**
 * Settings → EPG: per playlist, its own guide (on/off), its added guide links (each with
 * "Remove"), "Add EPG source" + "Save source", "Update EPG" and the status line; then the global
 * EPG options.
 */
@Composable
fun EpgPage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit) {
    val host = rememberChoiceHost()
    val typing = rememberTypingState()
    val drafts = remember { mutableStateMapOf<Long, String>() }
    val saved = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(Unit) {
        val first = ui.epg.firstOrNull()?.let { if (it.showOwnGuide) ownGuideKey(it.playlistId) else fieldKey(it.playlistId) }
        focusSoon(host.requester(focusKey ?: first ?: CLEAR_EPG_KEY))
        onFocusConsumed()
    }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.epg.isEmpty()) Text("No playlists.", color = Tokens.Muted)
        ui.epg.forEach { epg ->
            EpgSourcesSection(
                epg = epg,
                showName = ui.epg.size > 1,
                host = host,
                typing = typing,
                draft = drafts[epg.playlistId].orEmpty(),
                onDraft = { drafts[epg.playlistId] = it },
                savedText = saved[epg.playlistId],
                onSave = {
                    val value = drafts[epg.playlistId].orEmpty()
                    // A blank field never removes anything (the migrated link stays).
                    if (value.isNotBlank()) {
                        vm.addEpgSource(epg.playlistId, value)
                        saved[epg.playlistId] = "Saved. Choose Update EPG to load it."
                        drafts[epg.playlistId] = ""
                    } else {
                        saved.remove(epg.playlistId)
                    }
                },
                onRemove = { sourceId -> saved.remove(epg.playlistId); vm.requestRemoveEpgSource(sourceId) },
                onUpdate = { saved.remove(epg.playlistId); vm.updateEpg(epg.playlistId) },
                onOwnGuide = { vm.setUseOwnGuide(epg.playlistId, !epg.useOwnGuide) },
            )
            Spacer(Modifier.height(12.dp))
        }
        EpgOptionsSection(ui, vm, host, host.requester(CLEAR_EPG_KEY))
    }
}

/** One playlist's guide sources block. */
@Composable
fun EpgSourcesSection(
    epg: EpgUi,
    showName: Boolean,
    host: ChoiceHost,
    typing: TypingState,
    draft: String,
    onDraft: (String) -> Unit,
    savedText: String?,
    onSave: () -> Unit,
    onRemove: (sourceId: Long) -> Unit,
    onUpdate: () -> Unit,
    onOwnGuide: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showName) Text(epg.playlistName, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
        if (epg.showOwnGuide) {
            ToggleRow(
                "Playlist's own guide",
                epg.useOwnGuide,
                onOwnGuide,
                Modifier.focusRequester(host.requester(ownGuideKey(epg.playlistId))),
                supporting = epg.ownGuide.joinToString("\n").ifEmpty { null },
            )
        }
        epg.sources.forEach { source ->
            ActionRow(
                source.url,
                { onRemove(source.id) },
                Modifier.focusRequester(host.requester(epgSourceKey(source.id))),
                trailing = "Remove",
            )
        }
        Spacer(Modifier.height(4.dp))
        TypingField(
            state = typing,
            label = "Add EPG source",
            value = draft,
            onChange = { onDraft(it.take(MAX_URL_LENGTH)) },
            modifier = Modifier.focusRequester(host.requester(fieldKey(epg.playlistId))),
            keyboardType = KeyboardType.Uri,
            onDone = onSave,
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Save source", onFocus = typing::stop, onClick = { typing.stop(); onSave() })
            PillButton("Update EPG", onFocus = typing::stop, onClick = onUpdate)
        }
        savedText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentLight) }
        epg.statusText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    epg.updating -> Tokens.AccentLight
                    epg.statusIsError -> Tokens.Error
                    else -> Tokens.OnBackground
                },
            )
        }
    }
}
