package io.github.loopholetv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.keyName
import kotlinx.coroutines.delay

private const val RC_MEDIA_KEYS = "RW/FF/Pause seek or pause in catch-up"
private const val RC_RW_LIVE = "RW rewinds live via catch-up"
private const val RC_LEFT_RIGHT = "Left/Right seek in catch-up"
private const val RC_LEFT_LIVE = "Left rewinds live via catch-up"
private const val RC_RESET = "Reset buttons"
private const val RC_RESET_PROMPT = "Reset all buttons to how they started?"
private const val RC_ADD = "Add a button"
private const val ADD_KEY = "rb:add"
private const val REJECTED_MS = 2_000L

/** Focus-return key of a Remote control row. */
internal fun remoteRowKey(label: String) = "rc:$label"

private fun bindingKey(code: Int) = "rb:$code"

/** Remote control: the catch-up switches, then the button lists and "Reset buttons". */
@Composable
fun RemotePage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit, onOpen: (BindingContext) -> Unit) {
    val host = rememberChoiceHost()
    val asking by vm.resetPrompt.collectAsStateWithLifecycle()
    var wasAsking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            wasAsking = true
        } else {
            focusSoon(host.requester(if (wasAsking) remoteRowKey(RC_RESET) else focusKey ?: remoteRowKey(RC_MEDIA_KEYS)))
            wasAsking = false
            onFocusConsumed()
        }
    }
    BackHandler(enabled = asking) { vm.cancelResetBindings() }
    // Leaving the page (or closing the panel) never leaves the question waiting.
    DisposableEffect(Unit) { onDispose { vm.cancelResetBindings() } }

    if (asking) {
        Confirm(RC_RESET_PROMPT, "Reset", onAction = vm::confirmResetBindings, onCancel = vm::cancelResetBindings)
        return
    }

    val r = ui.options.remote
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fun key(label: String) = Modifier.focusRequester(host.requester(remoteRowKey(label)))
        ToggleRow(RC_MEDIA_KEYS, r.mediaKeysSeek, { vm.setRemote { it.copy(mediaKeysSeek = !it.mediaKeysSeek) } }, key(RC_MEDIA_KEYS))
        ToggleRow(RC_RW_LIVE, r.rwRewindsLive, { vm.setRemote { it.copy(rwRewindsLive = !it.rwRewindsLive) } }, key(RC_RW_LIVE))
        ToggleRow(RC_LEFT_RIGHT, r.leftRightSeek, { vm.setRemote { it.copy(leftRightSeek = !it.leftRightSeek) } }, key(RC_LEFT_RIGHT))
        ToggleRow(RC_LEFT_LIVE, r.leftRewindsLive, { vm.setRemote { it.copy(leftRewindsLive = !it.leftRewindsLive) } }, key(RC_LEFT_LIVE))
        BindingContext.entries.forEach { c -> ActionRow(c.title, { onOpen(c) }, key(c.title)) }
        ActionRow(RC_RESET, vm::requestResetBindings, key(RC_RESET))
    }
}

/** One row per button of [context], sorted by button name, after "Add a button". */
@Composable
fun RemoteButtonsPage(context: BindingContext, ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit) {
    val host = rememberChoiceHost()
    var capturing by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    var rejectedCount by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: ADD_KEY))
        onFocusConsumed()
        started = true
    }
    // The capture view is gone after a cancel: put focus back on "Add a button".
    LaunchedEffect(capturing) {
        if (!capturing && started) focusSoon(host.requester(ADD_KEY))
    }
    LaunchedEffect(rejectedCount) {
        if (rejectedCount == 0) return@LaunchedEffect
        rejected = true
        delay(REJECTED_MS)
        rejected = false
    }
    host.Effects()
    BackHandler(enabled = capturing) { capturing = false }
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }

    if (capturing) {
        KeyCaptureDialog(
            onKey = { code ->
                if (vm.captureKey(context, code)) {
                    capturing = false
                    rejected = false
                    host.open(ADD_KEY, host.actionSpec(context, code, ui, vm, newKey = true))
                } else {
                    rejectedCount++
                }
            },
            onCancel = { capturing = false; rejected = false },
            message = if (rejected) CAPTURE_REJECTED else null,
        )
        return
    }

    val b = ui.options.remote.bindings
    val rows: List<Pair<Int, String>> = when (context) {
        BindingContext.PLAYER -> b.player.map { (code, a) -> code to bindingRowText(code, a) }
        BindingContext.GUIDE -> b.guide.map { (code, a) -> code to bindingRowText(code, a) }
    }.sortedWith(compareBy({ keyName(it.first) }, { it.first }))
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow(RC_ADD, { capturing = true }, Modifier.focusRequester(host.requester(ADD_KEY)))
        rows.forEach { (code, text) ->
            ActionRow(
                text,
                { host.open(bindingKey(code), host.actionSpec(context, code, ui, vm)) },
                Modifier.focusRequester(host.requester(bindingKey(code))),
            )
        }
    }
}

/**
 * The list of actions for button [code]; the current one (if any) is ticked. Picking applies it and
 * closes the list; for a [newKey] focus then goes to its new row.
 */
private fun ChoiceHost.actionSpec(context: BindingContext, code: Int, ui: SettingsUi, vm: SettingsViewModel, newKey: Boolean = false): ChoiceSpec {
    val b = ui.options.remote.bindings
    return when (context) {
        BindingContext.PLAYER -> {
            val current = b.player(code)
            ChoiceSpec(keyName(code), PlayerAction.entries.map { it.label to (it == current) }) { i ->
                val action = PlayerAction.entries[i]
                vm.setBinding(code, action)
                if (newKey && action != PlayerAction.NONE) returnTo(bindingKey(code))
                close()
            }
        }
        BindingContext.GUIDE -> {
            val current = b.guide(code)
            ChoiceSpec(keyName(code), GuideAction.entries.map { it.label to (it == current) }) { i ->
                val action = GuideAction.entries[i]
                vm.setBinding(code, action)
                if (newKey && action != GuideAction.NONE) returnTo(bindingKey(code))
                close()
            }
        }
    }
}
