package io.github.loopholetv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.SubtitleBackground
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.data.settings.audioDelayText

/** Focus-return key of a Playback row. */
private fun rowKey(label: String) = "pb:$label"

/**
 * Player settings: buffer, decoders, sound, audio delay, languages, subtitle style, then the
 * device-dependent options. Rows follow [playbackRowLabels]; the picture-in-picture row appears
 * only when [pipSupported].
 */
@Composable
fun PlaybackPage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit, pipSupported: Boolean) {
    val host = rememberChoiceHost()
    val labels = playbackRowLabels(pipSupported)
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: rowKey(labels.first())))
        onFocusConsumed()
    }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }

    val p = ui.options.playback
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { PlaybackRow(it, p, host, vm::setPlayback) }
    }
}

@Composable
private fun PlaybackRow(
    label: String,
    p: PlaybackSettings,
    host: ChoiceHost,
    set: ((PlaybackSettings) -> PlaybackSettings) -> Unit,
) {
    val key = rowKey(label)
    val style = p.subtitleStyle
    val setStyle: ((SubtitleStyle) -> SubtitleStyle) -> Unit = { t -> set { it.copy(subtitleStyle = t(it.subtitleStyle)) } }
    @Composable
    fun toggle(value: Boolean, supporting: String?, flip: (PlaybackSettings) -> PlaybackSettings) =
        ToggleRow(label, value, { set(flip) }, Modifier.focusRequester(host.requester(key)), supporting)

    when (label) {
        PB_BUFFER -> host.Row(label, BufferPreset.entries, p.bufferPreset, ::bufferPresetText, key) { v ->
            set { it.copy(bufferPreset = v) }
        }
        PB_VIDEO_DECODER -> host.Row(
            label, DecoderChoice.entries, p.videoDecoder, ::decoderText, key, supporting = PB_VIDEO_DECODER_HINT,
        ) { v -> set { it.copy(videoDecoder = v) } }
        PB_AUDIO_DECODER -> host.Row(label, DecoderChoice.entries, p.audioDecoder, ::decoderText, key) { v ->
            set { it.copy(audioDecoder = v) }
        }
        PB_AUTO_FRAME_RATE -> toggle(p.autoFrameRate, PB_AUTO_FRAME_RATE_HINT) { it.copy(autoFrameRate = !it.autoFrameRate) }
        PB_SURROUND -> toggle(p.preferSurround, PB_SURROUND_HINT) { it.copy(preferSurround = !it.preferSurround) }
        PB_PASSTHROUGH -> host.Row(
            label, Passthrough.entries, p.passthrough, ::passthroughText, key, supporting = PB_PASSTHROUGH_HINT,
        ) { v -> set { it.copy(passthrough = v) } }
        PB_DTS -> toggle(p.decodeDtsToPcm, PB_DTS_HINT) { it.copy(decodeDtsToPcm = !it.decodeDtsToPcm) }
        PB_AUDIO_DELAY -> host.Row(label, audioDelayChoices(), p.audioDelayMs, ::audioDelayText, key) { v ->
            set { it.copy(audioDelayMs = v) }
        }
        PB_AUDIO_LANGUAGE -> {
            val choices = audioLanguageChoices()
            host.Row(label, choices.map { it.first }, p.audioLanguage, { languageText(choices, it) }, key) { v ->
                set { it.copy(audioLanguage = v) }
            }
        }
        PB_SUBTITLE_LANGUAGE -> {
            val choices = subtitleLanguageChoices()
            host.Row(
                label, choices.map { it.first }, p.subtitleLanguage, { languageText(choices, it) }, key,
                supporting = PB_SUBTITLE_LANGUAGE_HINT,
            ) { v -> set { it.copy(subtitleLanguage = v) } }
        }
        PB_SUBTITLE_SIZE -> host.Row(label, SubtitleSize.entries, style.size, { it.label }, key) { v ->
            setStyle { it.copy(size = v) }
        }
        PB_SUBTITLE_COLOUR -> host.Row(label, SubtitleColour.entries, style.colour, { it.label }, key) { v ->
            setStyle { it.copy(colour = v) }
        }
        PB_SUBTITLE_BACKGROUND -> host.Row(label, SubtitleBackground.entries, style.background, { it.label }, key) { v ->
            setStyle { it.copy(background = v) }
        }
        PB_SUBTITLE_EDGE -> host.Row(label, SubtitleEdge.entries, style.edge, { it.label }, key) { v ->
            setStyle { it.copy(edge = v) }
        }
        PB_PIP_ON_HOME -> toggle(p.pipOnHome, null) { it.copy(pipOnHome = !it.pipOnHome) }
        PB_TUNNELING -> toggle(p.tunneling, PB_TUNNELING_HINT) { it.copy(tunneling = !it.tunneling) }
    }
}
