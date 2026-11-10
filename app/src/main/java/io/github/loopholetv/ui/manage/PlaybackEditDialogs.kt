package io.github.loopholetv.ui.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.player.ASK_EACH_TIME
import io.github.loopholetv.player.ExternalPlayerApp
import io.github.loopholetv.player.ExternalPlayers

/** A per-channel decoder choice as shown: null follows the playback settings. */
fun decoderText(choice: DecoderChoice?): String = when (choice) {
    null -> "Same as Settings"
    DecoderChoice.HARDWARE -> "Hardware"
    DecoderChoice.SOFTWARE -> "Software"
}

/** The decoder choices, in the order listed; null follows the playback settings. */
val DECODER_CHOICES: List<DecoderChoice?> = listOf(null, DecoderChoice.HARDWARE, DecoderChoice.SOFTWARE)

/**
 * Stored values of the external player choices: off (null), each of [installed], a saved [current]
 * player that is no longer installed (so the list still shows what is stored), then ask each time.
 */
fun externalChoiceValues(installed: List<ExternalPlayerApp>, current: String? = null): List<String?> {
    val missing = current?.takeIf { it != ASK_EACH_TIME && installed.none { app -> app.packageName == it } }
    return listOf<String?>(null) + installed.map { it.packageName } + listOfNotNull(missing) + ASK_EACH_TIME
}

fun externalChoiceLabels(installed: List<ExternalPlayerApp>, current: String? = null): List<String> =
    externalChoiceValues(installed, current).map { value ->
        val label = ExternalPlayers.choiceLabel(value)
        if (value != null && value != ASK_EACH_TIME && installed.none { it.packageName == value }) "$label (not installed)" else label
    }

/** Position of [current] in [values]. */
fun externalChoiceIndex(values: List<String?>, current: String?): Int =
    values.indexOf(current).takeIf { it >= 0 } ?: 0

/** The line under the external player list: only when no known player is installed. */
fun externalNote(installed: List<ExternalPlayerApp>): String? = if (installed.isEmpty()) NO_PLAYER_NOTE else null

/** Position of [current] in [DECODER_CHOICES]. */
fun decoderChoiceIndex(current: DecoderChoice?): Int = DECODER_CHOICES.indexOf(current).coerceAtLeast(0)

/**
 * Whether [edit] can be shown. The playback dialogs focus the saved value when they open, so they
 * wait for [playback] (null until the first read); every other edit needs nothing.
 */
fun playbackEditReady(edit: ChannelEdit?, playback: ChannelPlaybackPrefs?): Boolean = when (edit) {
    ChannelEdit.VideoDecoder, ChannelEdit.AudioDecoder, ChannelEdit.ExternalPlayer -> playback != null
    else -> true
}

/** Pick a video or audio decoder for one channel; focus starts on the current choice. */
@Composable
fun DecoderDialog(title: String, current: DecoderChoice?, onPick: (DecoderChoice?) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = title,
        options = DECODER_CHOICES.map(::decoderText),
        selected = decoderChoiceIndex(current),
        onPick = { i -> onPick(DECODER_CHOICES[i]) },
        onDismiss = onDismiss,
    )
}

/**
 * Pick the external player for one channel: Off, the installed known players, or "Ask each time".
 * With none installed, a note says so. Focus starts on the current choice.
 */
@Composable
fun ExternalPlayerDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val pm = LocalContext.current.packageManager
    val installed = remember { ExternalPlayers.installed(pm) }
    val values = externalChoiceValues(installed, current)
    ChoiceDialog(
        title = "External player",
        options = externalChoiceLabels(installed, current),
        selected = externalChoiceIndex(values, current),
        onPick = { i -> onPick(values[i]) },
        onDismiss = onDismiss,
        note = externalNote(installed),
    )
}

const val NO_PLAYER_NOTE = "No other video player is installed."

/** [channel]'s playback overrides, kept current while the caller is composed; null until first read. */
@Composable
fun rememberChannelPlayback(channel: ChannelEntity): ChannelPlaybackPrefs? {
    val app = LocalContext.current.applicationContext as LoopholeApp
    val flow = remember(channel.playlistId, channel.key) { app.container.channelPlayback.observe(channel.ref) }
    return flow.collectAsState(null).value
}
