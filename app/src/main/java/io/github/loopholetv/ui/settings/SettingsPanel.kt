package io.github.loopholetv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.update.AboutCheck
import io.github.loopholetv.update.aboutCheckText
import io.github.loopholetv.update.cardFor
import androidx.compose.ui.platform.LocalContext
import io.github.loopholetv.ui.manage.GroupsAndChannelsEditor
import io.github.loopholetv.ui.onboarding.PLAYER_ONLY_NOTICE
import io.github.loopholetv.ui.onboarding.PillButton as OnboardingPillButton
import io.github.loopholetv.ui.theme.Tokens

/** The panel's pages. Saved as [key] (e.g. `"playlist:7"`) so a page with an id survives recreation. */
sealed interface SettingsPage {
    val key: String

    data object Sections : SettingsPage { override val key = "sections" }
    data object General : SettingsPage { override val key = "general" }
    data object Playlists : SettingsPage { override val key = "playlists" }
    data class Playlist(val id: Long) : SettingsPage { override val key = "playlist:$id" }
    /** "Groups and channels" of playlist [id]. */
    data class Editor(val id: Long) : SettingsPage { override val key = "editor:$id" }
    data object Epg : SettingsPage { override val key = "epg" }
    data object Playback : SettingsPage { override val key = "playback" }
    data object About : SettingsPage { override val key = "about" }

    companion object {
        private val fixed = listOf(Sections, General, Playlists, Epg, Playback, About).associateBy { it.key }

        /** Unknown or malformed keys give [Sections]. */
        fun fromKey(key: String): SettingsPage {
            fixed[key]?.let { return it }
            val id = key.substringAfter(':', "").toLongOrNull() ?: return Sections
            return when (key.substringBefore(':')) {
                "playlist" -> Playlist(id)
                "editor" -> Editor(id)
                else -> Sections
            }
        }

        val Saver: Saver<SettingsPage, String> = Saver(save = { it.key }, restore = { fromKey(it) })
    }
}

private fun SettingsPage.title(ui: SettingsUi): String = when (this) {
    SettingsPage.Sections -> "Settings"
    SettingsPage.General -> "General"
    SettingsPage.Playlists -> "Playlists"
    is SettingsPage.Playlist -> ui.playlists.firstOrNull { it.id == id }?.name ?: "Playlists"
    is SettingsPage.Editor -> ui.playlists.firstOrNull { it.id == id }?.name ?: "Playlists"
    SettingsPage.Epg -> "EPG"
    SettingsPage.Playback -> "Playback"
    SettingsPage.About -> "About"
}

internal val DISABLED_SECTIONS = listOf("Appearance", "Remote control", "Parental controls", "Other")
private const val COMING_LATER = "Coming in a later update"

/**
 * Settings content for the right-side panel. Each section opens a page in the same panel; a
 * playlist opens its own page, and its "Groups and channels" the editor. Back goes up one page
 * (Back on the section list closes the panel, handled by the dialog). "Add playlist" and
 * "Change address or login" leave the panel for the full-screen form.
 */
@Composable
fun SettingsPanel(
    onAddPlaylist: () -> Unit = {},
    onEditPlaylist: (playlistId: Long) -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val ui by vm.state.collectAsStateWithLifecycle()

    var page by rememberSaveable(stateSaver = SettingsPage.Saver) { mutableStateOf<SettingsPage>(SettingsPage.Sections) }
    var lastSection by rememberSaveable(stateSaver = SettingsPage.Saver) { mutableStateOf<SettingsPage>(SettingsPage.Playlists) }
    // The row to focus when a page (re)opens: after Back from a sub-page, a dialog or a confirmation.
    var focusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val confirm = ui.confirmDelete
    val confirmClear = ui.confirmClearEpg
    val confirmRemove = ui.confirmRemoveSource
    // After a removal the row is gone: focus goes to that playlist's "Add EPG source" field.
    val removeFieldKey = confirmRemove?.let { s -> ui.epg.firstOrNull { e -> e.sources.any { it.id == s.id } } }
        ?.let { fieldKey(it.playlistId) }
    val go: (SettingsPage, String?) -> Unit = { to, key -> focusKey = key; page = to }

    // A deleted (or vanished) playlist's pages go back to the list.
    val current = page
    val playlistId = (current as? SettingsPage.Playlist)?.id ?: (current as? SettingsPage.Editor)?.id
    LaunchedEffect(ui.loaded, playlistId, ui.playlists) {
        if (ui.loaded && playlistId != null && ui.playlists.none { it.id == playlistId }) go(SettingsPage.Playlists, null)
    }

    // The editor handles Back itself (its group page, move mode), so the page-level handler is off there.
    val confirming = confirm != null || confirmClear || confirmRemove != null
    BackHandler(enabled = !confirming && page != SettingsPage.Sections && page !is SettingsPage.Editor) {
        when (val p = page) {
            is SettingsPage.Playlist -> go(SettingsPage.Playlists, playlistRowKey(p.id))
            else -> go(SettingsPage.Sections, null)
        }
    }
    BackHandler(enabled = confirm != null) { focusKey = DELETE_KEY; vm.cancelDelete() }
    BackHandler(enabled = confirmClear) { focusKey = CLEAR_EPG_KEY; vm.cancelClearEpg() }
    BackHandler(enabled = confirmRemove != null) {
        focusKey = confirmRemove?.let { epgSourceKey(it.id) }
        vm.cancelRemoveEpgSource()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(
            when {
                confirm != null -> "Delete playlist"
                confirmClear -> "Clear EPG"
                confirmRemove != null -> "Remove this guide link?"
                else -> page.title(ui)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        val consumeFocus = { focusKey = null }
        when {
            confirm != null -> ConfirmDelete(
                name = confirm.name,
                onDelete = vm::confirmDelete,
                onCancel = { focusKey = DELETE_KEY; vm.cancelDelete() },
            )
            confirmClear -> ConfirmClearEpg(
                onClear = { focusKey = CLEAR_EPG_KEY; vm.confirmClearEpg() },
                onCancel = { focusKey = CLEAR_EPG_KEY; vm.cancelClearEpg() },
            )
            confirmRemove != null -> Confirm(
                text = confirmRemove.url,
                action = "Remove",
                onAction = { focusKey = removeFieldKey; vm.confirmRemoveEpgSource() },
                onCancel = { focusKey = epgSourceKey(confirmRemove.id); vm.cancelRemoveEpgSource() },
            )
            else -> when (val p = page) {
                SettingsPage.Sections -> SectionList(focus = lastSection) { lastSection = it; go(it, null) }
                SettingsPage.General -> GeneralPage(ui, vm, focusKey, consumeFocus)
                SettingsPage.Playlists -> PlaylistsPage(
                    ui, vm, focusKey, consumeFocus,
                    onAdd = onAddPlaylist,
                    onOpen = { id -> go(SettingsPage.Playlist(id), null) },
                )
                is SettingsPage.Playlist -> ui.playlists.firstOrNull { it.id == p.id }?.let { row ->
                    PlaylistPage(
                        row, ui, vm, focusKey, consumeFocus,
                        onEditSource = { onEditPlaylist(p.id) },
                        onOpenEditor = { go(SettingsPage.Editor(p.id), null) },
                    )
                }
                is SettingsPage.Editor -> GroupsAndChannelsEditor(
                    playlistId = p.id,
                    onBack = { go(SettingsPage.Playlist(p.id), EDITOR_KEY) },
                )
                SettingsPage.Epg -> EpgPage(ui, vm, focusKey, consumeFocus)
                SettingsPage.Playback -> PlaybackPage(ui, vm, focusKey, consumeFocus, vm.pipSupported)
                SettingsPage.About -> AboutPage()
            }
        }
    }
}

internal const val DELETE_KEY = "delete"
internal const val CLEAR_EPG_KEY = "clearEpg"
internal const val EDITOR_KEY = "editor"
internal fun playlistRowKey(id: Long) = "row:$id"

/** Onboarding's pill button, darker than the raised panel so unfocused buttons stay visible. */
@Composable
internal fun PillButton(label: String, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) =
    OnboardingPillButton(label, selected = false, modifier = modifier, containerColor = Tokens.Surface, onFocus = onFocus, onClick = onClick)

private val SECTIONS = listOf(SettingsPage.General, SettingsPage.Playlists, SettingsPage.Epg, SettingsPage.Playback, SettingsPage.About)

@Composable
private fun SectionList(focus: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    val requesters = remember { SECTIONS.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { focusSoon(requesters[focus] ?: requesters.getValue(SettingsPage.Playlists)) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionItem("General", requesters.getValue(SettingsPage.General)) { onOpen(SettingsPage.General) }
        SectionItem("Playlists", requesters.getValue(SettingsPage.Playlists)) { onOpen(SettingsPage.Playlists) }
        SectionItem("EPG", requesters.getValue(SettingsPage.Epg)) { onOpen(SettingsPage.Epg) }
        SectionItem("Playback", requesters.getValue(SettingsPage.Playback)) { onOpen(SettingsPage.Playback) }
        // Not focusable: D-pad moves straight from Playback to About.
        DISABLED_SECTIONS.forEach { DisabledSection(it) }
        SectionItem("About", requesters.getValue(SettingsPage.About)) { onOpen(SettingsPage.About) }
    }
}

@Composable
private fun SectionItem(label: String, focus: FocusRequester, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
private fun DisabledSection(label: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Tokens.Muted)
        Text(COMING_LATER, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
    }
}

@Composable
private fun ConfirmDelete(name: String, onDelete: () -> Unit, onCancel: () -> Unit) =
    Confirm("Delete $name? This removes its channels and guide.", "Delete", onDelete, onCancel)

@Composable
private fun ConfirmClearEpg(onClear: () -> Unit, onCancel: () -> Unit) =
    Confirm("Clear the TV guide? Programmes stay empty until the next EPG update.", "Clear", onClear, onCancel)

/** A confirmation in the panel: [text], then [action] and "Cancel"; focus starts on "Cancel". */
@Composable
private fun Confirm(text: String, action: String, onAction: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(cancel) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.OnBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(action, onClick = onAction)
            PillButton("Cancel", modifier = Modifier.focusRequester(cancel), onClick = onCancel)
        }
    }
}

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val updater = remember(context) { (context.applicationContext as LoopholeApp).container.updater }
    val check by updater.about.collectAsStateWithLifecycle()
    val status by updater.status.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(focus) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Loophole", style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, color = Tokens.OnBackground)
        Text("Licence: GPL-3.0", style = MaterialTheme.typography.bodyLarge, color = Tokens.OnBackground)
        ActionRow(
            "Check for updates",
            onClick = { updater.checkNow() },
            modifier = Modifier.focusRequester(focus),
            supporting = aboutCheckText(check),
            supportingIsError = check == AboutCheck.Failed,
        )
        // The offered update's next step (Update, Cancel, Install...), as on the guide's card.
        cardFor(status)?.let { card ->
            val action = card.buttons.firstOrNull()
            ActionRow(
                action?.first ?: card.message,
                onClick = { action?.second?.let(updater::perform) },
                supporting = if (action != null) card.message else null,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(PLAYER_ONLY_NOTICE, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
    }
}
