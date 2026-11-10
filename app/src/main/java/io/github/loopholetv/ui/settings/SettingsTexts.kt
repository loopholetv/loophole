package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.data.settings.AUDIO_DELAY_RANGE_MS
import io.github.loopholetv.data.settings.AUDIO_DELAY_STEP_MS
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.PLAYBACK_LANGUAGES
import io.github.loopholetv.data.settings.Passthrough
import java.text.NumberFormat
import java.util.Locale

fun intervalText(hours: Int): String = if (hours == 0) "Off" else "Every $hours hours"

fun pastDaysText(days: Int): String = when (days) {
    0 -> "None"
    1 -> "1 day"
    else -> "$days days"
}

fun onOff(b: Boolean): String = if (b) "On" else "Off"


/** "1,360 channels" (grouped for [locale]). */
internal fun channelsGrouped(n: Int, locale: Locale): String =
    if (n == 1) "1 channel" else "${NumberFormat.getIntegerInstance(locale).format(n)} channels"

/** Per-playlist "Update interval" choices; `null` = "Same as default". */
fun playlistIntervalChoices(general: AppSettings): List<Pair<Int?, String>> =
    listOf<Pair<Int?, String>>(null to "Same as default (${intervalText(general.playlistUpdateHours)})") +
        AppSettings.INTERVAL_HOURS.map { it to intervalText(it) }

/** Per-playlist "Update on app start" choices; `null` = "Same as default". */
fun playlistOnStartChoices(general: AppSettings): List<Pair<Boolean?, String>> = listOf(
    null to "Same as default (${onOff(general.playlistUpdateOnStart)})",
    true to "On",
    false to "Off",
)

/** A playlist's user agent row value. */
fun userAgentText(ua: String?): String = ua?.trim()?.takeIf { it.isNotEmpty() } ?: "Default"

/** The general user agent row value. */
fun generalUserAgentText(ua: String?, versionName: String): String =
    ua?.trim()?.takeIf { it.isNotEmpty() } ?: "Default (${UserAgents.default(versionName)})"

const val UDP_PROXY_LABEL = "Address:port, e.g. 192.168.1.1:4022"
const val UDP_PROXY_INVALID = "Use the form 192.168.1.1:4022"

internal const val LAST_UPDATE_FAILED = "Last update failed"
internal const val NOT_UPDATED_YET = "Not updated yet"

/** The text of [value] in [choices], or [fallback] for a value the list doesn't have (never throws). */
fun <T> choiceText(choices: List<Pair<T, String>>, value: T, fallback: (T) -> String): String =
    choices.firstOrNull { it.first == value }?.second ?: fallback(value)

// --- Playback page ---

internal const val PB_BUFFER = "Buffer size"
internal const val PB_VIDEO_DECODER = "Video decoder"
internal const val PB_AUDIO_DECODER = "Audio decoder"
internal const val PB_AUTO_FRAME_RATE = "Auto frame rate"
internal const val PB_SURROUND = "Surround sound by default"
internal const val PB_PASSTHROUGH = "Audio passthrough"
internal const val PB_DTS = "Decode DTS to PCM"
internal const val PB_AUDIO_DELAY = "Audio delay"
internal const val PB_AUDIO_LANGUAGE = "Audio language"
internal const val PB_SUBTITLE_LANGUAGE = "Subtitle language"
internal const val PB_SUBTITLE_SIZE = "Subtitle size"
internal const val PB_SUBTITLE_COLOUR = "Subtitle colour"
internal const val PB_SUBTITLE_BACKGROUND = "Subtitle background"
internal const val PB_SUBTITLE_EDGE = "Subtitle edge"
internal const val PB_PIP_ON_HOME = "Home button opens picture-in-picture"
internal const val PB_TUNNELING = "Tunneled playback (advanced)"

internal const val PB_VIDEO_DECODER_HINT = "Software can help a channel that won't play. It is slower."
internal const val PB_AUTO_FRAME_RATE_HINT =
    "Switches the TV to the channel's frame rate in full screen. The screen goes black for 1–5 seconds each time it switches."
internal const val PB_SURROUND_HINT = "Picks a 5.1 sound track when the channel has one."
internal const val PB_PASSTHROUGH_HINT = "Auto sends Dolby sound to your TV or sound system when it supports it."
internal const val PB_DTS_HINT = "Turn on if DTS channels have no sound."
internal const val PB_SUBTITLE_LANGUAGE_HINT = "Subtitles turn on by themselves when a channel has this language."
internal const val PB_TUNNELING_HINT = "Can make 4K smoother on some TVs. May do nothing on Fire TV. Full screen only."

/** The Playback page's rows, top to bottom. The picture-in-picture row exists only where the device supports it. */
internal fun playbackRowLabels(pipSupported: Boolean): List<String> = listOfNotNull(
    PB_BUFFER,
    PB_VIDEO_DECODER,
    PB_AUDIO_DECODER,
    PB_AUTO_FRAME_RATE,
    PB_SURROUND,
    PB_PASSTHROUGH,
    PB_DTS,
    PB_AUDIO_DELAY,
    PB_AUDIO_LANGUAGE,
    PB_SUBTITLE_LANGUAGE,
    PB_SUBTITLE_SIZE,
    PB_SUBTITLE_COLOUR,
    PB_SUBTITLE_BACKGROUND,
    PB_SUBTITLE_EDGE,
    PB_PIP_ON_HOME.takeIf { pipSupported },
    PB_TUNNELING,
)

fun bufferPresetText(p: BufferPreset): String = when (p) {
    BufferPreset.SMALL -> "Small (fastest channel change)"
    BufferPreset.NORMAL -> "Normal"
    BufferPreset.LARGE -> "Large (for unsteady Wi-Fi)"
}

fun decoderText(d: DecoderChoice): String = when (d) {
    DecoderChoice.HARDWARE -> "Hardware"
    DecoderChoice.SOFTWARE -> "Software"
}

fun passthroughText(p: Passthrough): String = when (p) {
    Passthrough.AUTO -> "Auto"
    Passthrough.OFF -> "Off"
}

/** Every audio delay step from the most negative to the most positive value. */
fun audioDelayChoices(): List<Int> =
    (AUDIO_DELAY_RANGE_MS.first..AUDIO_DELAY_RANGE_MS.last step AUDIO_DELAY_STEP_MS).toList()

/** "Stream default" (null), then each offered language. */
fun audioLanguageChoices(): List<Pair<String?, String>> =
    listOf<Pair<String?, String>>(null to "Stream default") + PLAYBACK_LANGUAGES.map { it.code to it.name }

/** "Off" (null), then each offered language. */
fun subtitleLanguageChoices(): List<Pair<String?, String>> =
    listOf<Pair<String?, String>>(null to "Off") + PLAYBACK_LANGUAGES.map { it.code to it.name }

/** The name of [code] in [choices]; an unknown code shows as itself. */
fun languageText(choices: List<Pair<String?, String>>, code: String?): String = choiceText(choices, code) { it.orEmpty() }
