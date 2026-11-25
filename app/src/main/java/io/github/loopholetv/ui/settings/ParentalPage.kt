package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.loopholetv.LoopholeApp
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.data.settings.UNLOCK_CHOICES
import io.github.loopholetv.ui.parental.LocalPinPrompter
import io.github.loopholetv.ui.parental.PinPurpose
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.launch

internal const val PIN_KEY = "pin"
internal const val TURN_OFF_KEY = "turnOff"

internal const val NO_PIN_HINT = "Lock groups and channels from their options menu."
internal const val TURN_OFF_TITLE = "Turn off PIN and remove every lock?"

/**
 * Whether opening [page] asks for the PIN. Never without a PIN. Parental controls always asks; the
 * Settings lock adds every page ([SettingsLock.ALL]) or the playlist and guide pages ([SettingsLock.PLAYLISTS_EPG]).
 */
fun needsPin(page: SettingsPage, lock: SettingsLock, hasPin: Boolean): Boolean {
    if (!hasPin) return false
    return when (page) {
        SettingsPage.Parental -> true
        is SettingsPage.Playlist, is SettingsPage.Editor, SettingsPage.Playlists, SettingsPage.Epg ->
            lock != SettingsLock.OFF
        else -> lock == SettingsLock.ALL
    }
}

/** Sending a setup to another TV and the empty guide's "Copy from another TV" ask only under [SettingsLock.ALL]. */
fun allSettingsGuardsOutside(parental: ParentalSettings): Boolean =
    parental.hasPin && parental.settingsLock == SettingsLock.ALL

/** The PIN screen the "Set PIN" / "Change PIN" row opens. */
internal fun pinRowPurpose(hasPin: Boolean): PinPurpose = if (hasPin) PinPurpose.CHANGE else PinPurpose.SET

/** True at once when there is no PIN; otherwise whatever [ask] says (false when cancelled). */
suspend fun askWhenPinSet(hasPin: suspend () -> Boolean, ask: suspend () -> Boolean): Boolean = !hasPin() || ask()

/**
 * Settings → Parental controls. With no PIN only "Set PIN" shows; with one: change it, how long
 * it stays entered, which Settings pages ask for it, what is locked, and "Turn off PIN" (whose
 * confirmation is the panel's, through [onTurnOff]).
 */
@Composable
fun ParentalPage(
    ui: SettingsUi,
    vm: SettingsViewModel,
    focusKey: String?,
    onFocusConsumed: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val host = rememberChoiceHost()
    val prompter = LocalPinPrompter.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: PIN_KEY))
        onFocusConsumed()
    }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }

    val parental = ui.options.parental
    val hasPin = parental.hasPin
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow(
            if (hasPin) "Change PIN" else "Set PIN",
            onClick = {
                scope.launch {
                    prompter.ask(pinRowPurpose(hasPin))
                    focusSoon(host.requester(PIN_KEY))
                }
            },
            modifier = Modifier.focusRequester(host.requester(PIN_KEY)),
        )
        if (!hasPin) {
            Text(
                NO_PIN_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@Column
        }
        host.Row(
            "Don't ask again for",
            UNLOCK_CHOICES,
            parental.unlockMinutes,
            ::unlockText,
        ) { m -> vm.setOption { it.copy(parental = it.parental.copy(unlockMinutes = m)) } }
        host.Row(
            "Ask for PIN to open",
            SettingsLock.entries,
            parental.settingsLock,
            ::settingsLockText,
        ) { l -> vm.setOption { it.copy(parental = it.parental.copy(settingsLock = l)) } }
        // Informational: what is locked, read here and not changed.
        ValueRow("Locked", lockSummary(ui.lockCounts), {})
        ActionRow(
            "Turn off PIN",
            onClick = {
                scope.launch {
                    if (prompter.ask()) onTurnOff() else focusSoon(host.requester(TURN_OFF_KEY))
                }
            },
            modifier = Modifier.focusRequester(host.requester(TURN_OFF_KEY)),
            supporting = "Removes the PIN and every lock",
        )
    }
}

/**
 * Shows [content] once opening Settings (on [startPage], or the section list) is allowed: at once
 * unless the Settings lock asks for the PIN. Cancelling calls [onDenied]. Nothing shows while the
 * PIN prompt is up, and the answer is not kept across recreation, so a locked app asks again.
 */
@Composable
fun SettingsLockGate(page: SettingsPage, onDenied: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prompter = LocalPinPrompter.current
    PinGate(
        page = page,
        needed = { p ->
            val settings = (context.applicationContext as LoopholeApp).container.settingsStore
            // A failed read asks anyway: with no PIN or an open lock that answers at once.
            try {
                settings.current().parental.let { needsPin(p, it.settingsLock, it.hasPin) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        },
        ask = { prompter.ask() },
        onDenied = onDenied,
        content = content,
    )
}

/** [SettingsLockGate] with its questions passed in. [page] is the one [content] will show, restored or not. */
@Composable
internal fun PinGate(
    page: SettingsPage,
    needed: suspend (SettingsPage) -> Boolean,
    ask: suspend () -> Boolean,
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    var allowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!needed(page) || ask()) allowed = true else onDenied()
    }
    if (allowed) content()
}
