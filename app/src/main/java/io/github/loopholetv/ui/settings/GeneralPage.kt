package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.settings.MAX_TV_NAME_LENGTH
import io.github.loopholetv.ui.manage.TextEntryDialog
import io.github.loopholetv.update.AboutCheck
import io.github.loopholetv.update.aboutCheckText

private const val LAST_CHANNEL_KEY = "lastChannel"
private const val UA_KEY = "userAgent"
private const val PROXY_KEY = "udpProxy"
private const val TV_NAME_KEY = "tvName"

/** Longest user agent or proxy address typed (both are short in practice). */
private const val MAX_VALUE_LENGTH = 300

@Composable
fun GeneralPage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit) {
    val host = rememberChoiceHost()
    // Which text dialog is open (UA_KEY / PROXY_KEY), and the row to refocus once it closes.
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var refocus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: LAST_CHANNEL_KEY))
        onFocusConsumed()
    }
    LaunchedEffect(dialog, refocus) {
        val key = refocus
        if (dialog == null && key != null) { focusSoon(host.requester(key)); refocus = null }
    }
    val close: () -> Unit = { refocus = dialog; dialog = null }
    val o = ui.options
    val context = LocalContext.current
    val updater = remember(context) { (context.applicationContext as LoopholeApp).container.updater }
    val check by updater.about.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleRow(
            "Turn on last channel on app start",
            o.lastChannelOnStart,
            { vm.setOption { it.copy(lastChannelOnStart = !it.lastChannelOnStart) } },
            Modifier.focusRequester(host.requester(LAST_CHANNEL_KEY)),
        )
        ToggleRow("Confirm exit", o.confirmExit, { vm.setOption { it.copy(confirmExit = !it.confirmExit) } })
        ValueRow(
            "TV name",
            ui.tvName,
            onClick = { dialog = TV_NAME_KEY },
            modifier = Modifier.focusRequester(host.requester(TV_NAME_KEY)),
        )
        ToggleRow(
            "Allow other TVs to cast to this TV",
            o.castAllowJoin,
            { vm.setOption { it.copy(castAllowJoin = !it.castAllowJoin) } },
            supporting = "Join a cast automatically when nothing is playing",
        )
        ToggleRow(
            "Check for updates automatically",
            o.checkForUpdates,
            { vm.setOption { it.copy(checkForUpdates = !it.checkForUpdates) } },
            supporting = "Once a day, when Loophole starts",
        )
        ActionRow(
            "Check for updates",
            onClick = vm::checkForUpdatesNow,
            supporting = aboutCheckText(check),
            supportingIsError = check == AboutCheck.Failed,
        )
        // The offered update's next step (Update, Install, ...), as in About.
        ui.updateCard?.let { card ->
            val action = card.buttons.firstOrNull()
            ActionRow(
                action?.first ?: card.message,
                onClick = { action?.second?.let(vm::performUpdate) },
                supporting = if (action != null) card.message else null,
            )
        }
        ValueRow(
            "User agent",
            generalUserAgentText(o.userAgent, BuildConfig.VERSION_NAME),
            onClick = { dialog = UA_KEY },
            modifier = Modifier.focusRequester(host.requester(UA_KEY)),
        )
        ValueRow(
            "UDP proxy",
            // After an invalid entry: what is still in force, then the hint.
            listOfNotNull(o.udpProxy ?: "Off", ui.udpProxyError).joinToString(" · "),
            onClick = { vm.clearUdpProxyError(); dialog = PROXY_KEY },
            modifier = Modifier.focusRequester(host.requester(PROXY_KEY)),
            valueIsError = ui.udpProxyError != null,
        )
    }
    when (dialog) {
        TV_NAME_KEY -> TextEntryDialog(
            title = "TV name",
            label = "TV name",
            initial = ui.tvName,
            keyboardType = KeyboardType.Text,
            onSave = { vm.setTvName(it); close() },
            onDismiss = close,
            maxLength = MAX_TV_NAME_LENGTH,
        )
        UA_KEY -> TextEntryDialog(
            title = "User agent",
            label = "User agent",
            initial = o.userAgent.orEmpty(),
            keyboardType = KeyboardType.Text,
            onSave = { vm.setGeneralUserAgent(it); close() },
            onDismiss = close,
            accept = io.github.loopholetv.data.net.UserAgents::isAcceptable,
            maxLength = MAX_VALUE_LENGTH,
        )
        PROXY_KEY -> TextEntryDialog(
            title = "UDP proxy",
            label = UDP_PROXY_LABEL,
            initial = o.udpProxy.orEmpty(),
            keyboardType = KeyboardType.Uri,
            onSave = { vm.setUdpProxy(it); close() },
            onDismiss = close,
            maxLength = MAX_VALUE_LENGTH,
        )
    }
}
