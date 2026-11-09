package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.data.settings.AppSettings
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
