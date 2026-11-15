package io.github.loopholetv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.phone.PhoneAddress
import io.github.loopholetv.phone.PhoneJob
import io.github.loopholetv.phone.PhoneJobPhase
import io.github.loopholetv.phone.PhonePairing
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.onboarding.guideProgressText
import io.github.loopholetv.ui.settings.focusSoon
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.flow.StateFlow

private const val NO_NETWORK_TEXT = "Connect this TV to your home network to use the phone page."
private const val WAITING_TEXT = "Waiting for a phone…"
private const val CONNECTED_TEXT = "Phone connected"

internal fun codeDigitTag(index: Int) = "phoneCode$index"

/**
 * "Set up from your phone": the QR code and address of the phone page, and the code that pairs a
 * phone. The code exists only while this screen shows. Done and Back call [onDone].
 */
@Composable
fun PhoneSetupScreen(onDone: () -> Unit) {
    val container = (LocalContext.current.applicationContext as LoopholeApp).container
    PhoneSetupScreen(onDone, container.phoneServer.address, container.phonePairing, container.phoneJobs.current)
}

@Composable
internal fun PhoneSetupScreen(
    onDone: () -> Unit,
    address: StateFlow<PhoneAddress?>,
    pairing: PhonePairing,
    job: StateFlow<PhoneJob?>,
) {
    DisposableEffect(pairing) {
        pairing.open()
        onDispose { pairing.close() }
    }
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(pairing) { pairing.connected.collect { connected = true } }
    val where by address.collectAsStateWithLifecycle()
    val code by pairing.code.collectAsStateWithLifecycle()
    val current by job.collectAsStateWithLifecycle()
    // A job's end shows only if it ran while this screen was open (not a stale one from before).
    var watched by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current) { current?.takeIf { it.phase.isRunning() }?.let { watched = it.id } }
    val shown = current?.takeIf { it.phase.isRunning() || it.id == watched }?.phase

    BackHandler(onBack = onDone)
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(done) }

    Box(
        Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up from your phone", style = MaterialTheme.typography.headlineMedium, color = Tokens.OnBackground)
            Text(
                "Your phone or laptop must be on the same Wi‑Fi as this TV.",
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.Muted,
            )
            Spacer(Modifier.height(16.dp))
            val at = where
            if (at == null) {
                Text(NO_NETWORK_TEXT, style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground)
            } else {
                Steps(at, code)
            }
            Spacer(Modifier.height(16.dp))
            StatusLine(shown, connected, hasAddress = at != null)
            Spacer(Modifier.height(8.dp))
            PillButton("Done", selected = false, modifier = Modifier.focusRequester(done), onClick = onDone)
        }
    }
}

private fun PhoneJobPhase.isRunning() = this is PhoneJobPhase.LoadingChannels || this is PhoneJobPhase.LoadingGuide

/** The QR code on the left; on the right, the two numbered steps. */
@Composable
private fun Steps(address: PhoneAddress, code: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
        QrImage(address.url, Modifier.size(240.dp))
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Step(1) {
                Text("Scan the code with your phone's camera", style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
                Text("or type this into a browser:", style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted)
                Text(address.short, style = MaterialTheme.typography.headlineSmall, color = Tokens.AccentLight)
            }
            Step(2) {
                Text("Enter this code on the page", style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
                Spacer(Modifier.height(8.dp))
                CodeBoxes(code.orEmpty())
            }
        }
    }
}

@Composable
private fun Step(number: Int, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(32.dp).background(Tokens.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = Tokens.Background)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** The pairing code, one digit per box, as the copy-setup screen shows its code. */
@Composable
private fun CodeBoxes(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        code.forEachIndexed { i, digit ->
            Box(
                Modifier
                    .size(width = 64.dp, height = 80.dp)
                    .background(Tokens.Surface, RoundedCornerShape(12.dp))
                    .border(2.dp, Tokens.Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    digit.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Tokens.Accent,
                    modifier = Modifier.testTag(codeDigitTag(i)),
                )
            }
        }
    }
}

/** The phone's load when there is one to show, else whether a phone has paired. */
@Composable
private fun StatusLine(phase: PhoneJobPhase?, connected: Boolean, hasAddress: Boolean) {
    val style = MaterialTheme.typography.bodyLarge
    when (phase) {
        is PhoneJobPhase.LoadingChannels -> Text("Loading channels… ${phase.count}", style = style, color = Tokens.AccentLight)
        is PhoneJobPhase.LoadingGuide -> Text(guideProgressText(phase.programmes), style = style, color = Tokens.AccentLight)
        is PhoneJobPhase.Failed -> Text(phase.message, style = style, color = Tokens.Error)
        is PhoneJobPhase.Done, null -> when {
            connected -> Text(CONNECTED_TEXT, style = style, color = Tokens.AccentLight)
            // Without a network no phone can reach the TV: nothing to wait for.
            hasAddress -> Text(WAITING_TEXT, style = style, color = Tokens.AccentLight)
        }
    }
}
