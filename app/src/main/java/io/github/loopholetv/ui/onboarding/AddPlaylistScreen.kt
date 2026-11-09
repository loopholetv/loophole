package io.github.loopholetv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import android.os.SystemClock
import io.github.loopholetv.ui.common.FieldGuard
import io.github.loopholetv.ui.files.FileBrowser
import io.github.loopholetv.ui.files.rememberPlaylistFilePicker
import io.github.loopholetv.ui.theme.Tokens

const val PLAYER_ONLY_NOTICE =
    "Loophole is a player only. It contains no channels — add the playlist from your own IPTV subscription."

/**
 * The full-screen playlist form: first start, Settings → "Add playlist", and — with [editId] —
 * "Change address or login". Back calls [onBack] when given; without it (first start) Back keeps
 * its usual behaviour.
 */
@Composable
fun AddPlaylistScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    editId: Long? = null,
    // Keyed so an add form and an edit form never share one ViewModel, even in one back-stack entry.
    vm: AddPlaylistViewModel = viewModel(key = "playlist-form-${editId ?: "new"}", factory = AddPlaylistViewModel.factory(editId)),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.phase) { if (ui.phase == Phase.Done) onDone() }
    if (onBack != null) BackHandler(onBack = onBack)
    // The TV's own picker when it has one, else Loophole's browser (its Back handler wins while shown).
    var browsing by rememberSaveable { mutableStateOf(false) }
    // The availability check (a stub "picker" counts as none) lives in FilePicker.kt.
    val chooseFile = rememberPlaylistFilePicker(onPicked = vm::setFile, onUnavailable = { browsing = true })
    if (browsing) {
        FileBrowser(onPick = { vm.setFile(it); browsing = false }, onCancel = { browsing = false })
    } else {
        AddPlaylistContent(ui, vm, chooseFile)
    }
}

/** Shared "typing" wiring for every field of the form. */
private class FieldCtl(
    val typing: Boolean,
    val imeVisible: Boolean,
    val busy: Boolean,
    val start: () -> Unit,
    val submit: () -> Unit,
    val stop: () -> Unit,
)

@Composable
private fun FormField(
    ctl: FieldCtl,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    last: Boolean = false,
) = Field(
    label, value, onChange, modifier,
    ime = if (last) ImeAction.Done else ImeAction.Next,
    keyboardType = keyboardType,
    typing = ctl.typing,
    imeVisible = ctl.imeVisible,
    onActivate = ctl.start,
    busy = ctl.busy,
    masked = masked,
    onDone = if (last) ctl.submit else ({}),
    // Back may hide the keyboard without the insets noticing: a D-pad move always ends typing,
    // so the next field stays read-only until OK (IME "Next" keeps typing on purpose).
    onMove = ctl.stop,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPlaylistContent(ui: AddPlaylistUi, vm: AddPlaylistViewModel, chooseFile: () -> Unit) {
    val firstField = remember { FocusRequester() }
    // Also re-runs when an edited playlist has loaded (its mode decides the first field).
    LaunchedEffect(ui.loaded) { runCatching { firstField.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current
    // Fields are read-only (no IME) until the user presses OK/Enter on one; leaving the fields
    // (to a toggle/button) or IME Done closes the keyboard again.
    var typing by remember { mutableStateOf(false) }
    val stopTyping: () -> Unit = { typing = false; keyboard?.hide() }
    val submitIme: () -> Unit = { stopTyping(); vm.submit() }
    val startTyping: () -> Unit = { typing = true; keyboard?.show() }
    // Back (or anything else) hiding the IME returns the fields to read-only so the next OK reopens it.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) typing = false
        imeWasVisible = imeVisible
    }

    Box(Modifier.fillMaxSize().background(Tokens.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (ui.editing) "Change address or login" else "Add playlist",
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.OnBackground,
            )
            if (!ui.editing) Text(PLAYER_ONLY_NOTICE, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            // Coming up from the fields lands on the chosen kind (the pills span the full-width field).
            val modePills = remember { Mode.entries.associateWith { FocusRequester() } }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusRestorer(modePills.getValue(ui.mode)).focusGroup(),
            ) {
                Mode.entries.forEach { m ->
                    PillButton(
                        m.label,
                        selected = ui.mode == m,
                        modifier = Modifier.focusRequester(modePills.getValue(m)),
                        onFocus = stopTyping,
                    ) { vm.setMode(m) }
                }
            }
            val ctl = FieldCtl(typing, imeVisible, ui.busy, startTyping, submitIme, stopTyping)
            val first = Modifier.focusRequester(firstField)
            // The name is changed in Settings → (playlist) → Name, so the edit form has none.
            val named = !ui.editing
            when (ui.mode) {
                Mode.M3U -> {
                    FormField(ctl, "Playlist URL", ui.url, vm::setUrl, first, KeyboardType.Uri, last = !named)
                }
                Mode.FILE -> {
                    PillButton("Choose file", selected = false, enabled = !ui.busy, modifier = first, onFocus = stopTyping, onClick = chooseFile)
                    if (ui.fileName.isNotBlank()) {
                        Text(ui.fileName, style = MaterialTheme.typography.bodyLarge, color = Tokens.OnBackground)
                    }
                }
                Mode.XTREAM -> {
                    FormField(ctl, "Server address", ui.server, vm::setServer, first, KeyboardType.Uri)
                    FormField(ctl, "Username", ui.user, vm::setUser)
                    FormField(ctl, "Password", ui.pass, vm::setPass, keyboardType = KeyboardType.Password, masked = true, last = !named)
                }
                Mode.PORTAL -> PortalFields(ui, vm, ctl, first, last = !named, onToggleFocus = stopTyping)
            }
            if (named) FormField(ctl, "Playlist name", ui.name, vm::setName, last = true)
            Spacer(Modifier.height(4.dp))
            PillButton(if (ui.editing) "Save" else "Add playlist", selected = false, enabled = !ui.busy, onFocus = stopTyping) { vm.submit() }
            // On a long form (MAC portal) the status sits below the fold: scroll it into view.
            val status = remember { BringIntoViewRequester() }
            LaunchedEffect(ui.phase) { if (ui.phase != Phase.Editing) runCatching { status.bringIntoView() } }
            Box(Modifier.bringIntoViewRequester(status)) { StatusLine(ui.phase, ui.editing) }
        }
    }
}

@Composable
private fun PortalFields(
    ui: AddPlaylistUi,
    vm: AddPlaylistViewModel,
    ctl: FieldCtl,
    first: Modifier,
    last: Boolean,
    onToggleFocus: () -> Unit,
) {
    FormField(ctl, "Portal address", ui.portalUrl, vm::setPortalUrl, first, KeyboardType.Uri)
    // An invalid MAC blocks "Add": bring the field (and its message) into view, since the button may be far below.
    val macField = remember { FocusRequester() }
    // Keyed on the count of blocked submits: each one refocuses, and re-entering the kind doesn't.
    LaunchedEffect(ui.macErrorShown) { if (ui.macErrorShown > 0 && ui.macError != null) runCatching { macField.requestFocus() } }
    FormField(
        ctl, "MAC address", ui.mac, vm::setMac, Modifier.focusRequester(macField),
        keyboardType = KeyboardType.Ascii, last = last && !ui.moreOptions,
    )
    ui.macError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Error) }
    ui.macWarning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.AccentLight) }
    PillButton("More options", selected = ui.moreOptions, enabled = !ui.busy, onFocus = onToggleFocus) { vm.toggleMoreOptions() }
    if (ui.moreOptions) {
        FormField(ctl, "Login (optional)", ui.portalLogin, vm::setPortalLogin)
        FormField(ctl, "Password (optional)", ui.portalPass, vm::setPortalPass, keyboardType = KeyboardType.Password, masked = true)
        FormField(ctl, "Serial number (optional)", ui.serial, vm::setSerial, keyboardType = KeyboardType.Ascii)
        FormField(ctl, "Device ID (optional)", ui.deviceId, vm::setDeviceId, keyboardType = KeyboardType.Ascii)
        FormField(ctl, "Device ID 2 (optional)", ui.deviceId2, vm::setDeviceId2, keyboardType = KeyboardType.Ascii)
        FormField(ctl, "Signature (optional)", ui.signature, vm::setSignature, keyboardType = KeyboardType.Ascii, last = last)
    }
}

@Composable
private fun StatusLine(phase: Phase, editing: Boolean) {
    // An edit has no guide step of its own (the coordinator runs it): nothing to say once done.
    if (editing && phase == Phase.Done) return
    when (phase) {
        is Phase.LoadingChannels -> Text("Loading channels… ${phase.count}", color = Tokens.AccentLight, style = MaterialTheme.typography.bodyLarge)
        is Phase.LoadingGuide -> Text(guideProgressText(phase.programmes), color = Tokens.AccentLight, style = MaterialTheme.typography.bodyLarge)
        is Phase.Failed -> Text(phase.message, color = Tokens.Error, style = MaterialTheme.typography.bodyLarge)
        Phase.Done -> Text(guideProgressText(0), color = Tokens.AccentLight, style = MaterialTheme.typography.bodyLarge)
        Phase.Editing -> Unit
    }
}

@Composable
internal fun PillButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    containerColor: Color = Tokens.SurfaceRaised,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Tokens.Accent else containerColor,
            contentColor = if (selected) Tokens.OnFocus else Tokens.OnBackground,
            focusedContainerColor = Tokens.FocusFill,
            focusedContentColor = Tokens.OnFocus,
            disabledContainerColor = Tokens.Surface,
            disabledContentColor = Tokens.Muted,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    ime: ImeAction,
    keyboardType: KeyboardType,
    typing: Boolean,
    imeVisible: Boolean,
    onActivate: () -> Unit,
    busy: Boolean,
    masked: Boolean = false,
    onDone: () -> Unit = {},
    /**
     * Typing ends: D-pad Up/Down leaving this field (the keyboard is hidden then, or it would take
     * the key), or focus put back on it after a stray loss (so it comes back read-only).
     */
    onMove: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // Keeps focus on this field when hiding the keyboard (Back) makes it jump away (Fire TV).
    val guard = remember { FieldGuard() }
    val self = remember { FocusRequester() }
    LaunchedEffect(imeVisible) {
        guard.onImeVisible(imeVisible, SystemClock.uptimeMillis())
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Tokens.Muted) },
        singleLine = true,
        readOnly = !typing || busy,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ime, keyboardType = keyboardType, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(
            onNext = { guard.onUserMove(SystemClock.uptimeMillis()); focus.moveFocus(FocusDirection.Down) },
            // IME Done/Search is the user's own way on: a focus move it causes is never stray.
            onDone = { guard.onUserMove(SystemClock.uptimeMillis()); onDone() },
            onSearch = { guard.onUserMove(SystemClock.uptimeMillis()); onDone() },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Tokens.OnBackground,
            unfocusedTextColor = Tokens.OnBackground,
            focusedContainerColor = Tokens.SurfaceRaised,
            unfocusedContainerColor = Tokens.Surface,
            focusedBorderColor = Tokens.AccentLight,
            unfocusedBorderColor = Tokens.Outline,
            focusedLabelColor = Tokens.AccentLight,
            unfocusedLabelColor = Tokens.Muted,
            cursorColor = Tokens.AccentLight,
        ),
        modifier = modifier.fillMaxWidth().focusRequester(self).onFocusChanged { state ->
            if (!guard.onFocusChanged(state.isFocused, SystemClock.uptimeMillis(), typing)) return@onFocusChanged
            scope.launch {
                // Read-only first (as for a D-pad move), or regaining focus would reopen the keyboard.
                onMove()
                repeat(2) { withFrameNanos { } }
                while (guard.restoring(SystemClock.uptimeMillis())) {
                    if (!guard.focused) runCatching { self.requestFocus() }
                    withFrameNanos { }
                }
                guard.onRestoreEnded()
            }
        }.onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            val moveKey = e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
                e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.Tab
            // The user's own way out of the field (see isStrayFieldFocusLoss); ends any restoring.
            if (moveKey && e.type == KeyEventType.KeyDown) guard.onUserMove(SystemClock.uptimeMillis())
            val dir = when (e.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            val ch = e.utf16CodePoint
            val printable = ch >= 32 && ch != 127 && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed
            when {
                // The single-line text field swallows D-pad Up/Down, so move focus ourselves.
                dir != null -> {
                    if (e.type == KeyEventType.KeyDown) {
                        if (typing) {
                            // Let the read-only state compose first, or the next field opens the keyboard.
                            onMove()
                            scope.launch { repeat(2) { withFrameNanos { } }; focus.moveFocus(dir) }
                        } else {
                            focus.moveFocus(dir)
                        }
                    }
                    true
                }
                // OK always (re)opens the keyboard unless it is already up and handling the key.
                ok && !(typing && imeVisible) -> { if (e.type == KeyEventType.KeyUp) onActivate(); true }
                // Hardware keyboard: a printable key on a not-yet-activated field starts typing.
                !typing && printable && !ok -> {
                    if (e.type == KeyEventType.KeyDown) {
                        onActivate()
                        onChange(value + String(Character.toChars(ch)))
                    }
                    true
                }
                else -> false
            }
        },
    )
}
