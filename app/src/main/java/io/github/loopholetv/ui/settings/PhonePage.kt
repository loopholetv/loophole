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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val PHONE_FORGET_PROMPT = "Forget all paired phones? They'll need the code again."

/**
 * Settings → Phone page: "Show QR code" opens "Set up from your phone"; "Forget paired phones"
 * unpairs every phone at once (after asking), and is off while none is paired.
 */
@Composable
fun PhonePage(ui: SettingsUi, vm: SettingsViewModel, onShowQr: () -> Unit) {
    val asking by vm.forgetPhonesPrompt.collectAsStateWithLifecycle()
    val showQr = remember { FocusRequester() }
    val forget = remember { FocusRequester() }
    var wasAsking by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            wasAsking = true
        } else {
            // Cancel lands back on the row; after forgetting, the row is off, so "Show QR code".
            focusSoon(if (wasAsking && !forgot) forget else showQr)
            wasAsking = false
        }
    }
    BackHandler(enabled = asking) { vm.cancelForgetPhones() }
    // Leaving the page (or closing the panel) never leaves the question waiting.
    DisposableEffect(Unit) { onDispose { vm.cancelForgetPhones() } }

    if (asking) {
        Confirm(PHONE_FORGET_PROMPT, "Forget", onAction = { forgot = true; vm.confirmForgetPhones() }, onCancel = vm::cancelForgetPhones)
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow("Show QR code", onShowQr, Modifier.focusRequester(showQr))
        ActionRow(
            "Forget paired phones",
            vm::requestForgetPhones,
            Modifier.focusRequester(forget),
            supporting = ui.forgetPhonesError ?: pairedPhonesText(ui.pairedPhones),
            supportingIsError = ui.forgetPhonesError != null,
            enabled = ui.pairedPhones > 0,
        )
    }
}
