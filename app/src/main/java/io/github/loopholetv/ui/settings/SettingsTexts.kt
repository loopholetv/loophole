package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.data.tmdb.KeyStatus
import io.github.loopholetv.data.repo.COPY_NOTICE_PREFIX
import io.github.loopholetv.data.repo.PORTAL_ERROR_COPY
import io.github.loopholetv.data.xtream.XTREAM_FORMAT_HLS
import io.github.loopholetv.data.settings.AUDIO_DELAY_RANGE_MS
import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.AUDIO_DELAY_STEP_MS
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.ColourMode
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.keyName
import io.github.loopholetv.data.settings.PLAYBACK_LANGUAGES
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.record.storage.StorageKind
import io.github.loopholetv.ui.recordings.formatSize
import java.text.NumberFormat
import java.util.Locale

fun intervalText(hours: Int): String = if (hours == 0) "Off" else "Every $hours hours"

/** [automaticDays] is what "Automatic" currently resolves to; without it the label is just "Automatic". */
fun pastDaysText(days: Int, automaticDays: Int? = null): String = when (days) {
    AUTO_PAST_DAYS -> if (automaticDays == null) "Automatic" else "Automatic (${dayCount(automaticDays)})"
    0 -> "None"
    1 -> "1 day"
    else -> "$days days"
}

private fun dayCount(n: Int) = if (n == 1) "1 day" else "$n days"

/** "Red button · Add/remove favourite". */
fun bindingRowText(keyCode: Int, action: PlayerAction): String = "${keyName(keyCode)} · ${action.label}"

fun bindingRowText(keyCode: Int, action: GuideAction): String = "${keyName(keyCode)} · ${action.label}"

fun onOff(b: Boolean): String = if (b) "On" else "Off"


/** "1,360 channels" (grouped for [locale]). */
internal fun channelsGrouped(n: Int, locale: Locale): String =
    if (n == 1) "1 channel" else "${NumberFormat.getIntegerInstance(locale).format(n)} channels"

/**
 * "120 channels · 850 films · 40 shows": zero parts are left out, so a playlist of films only never
 * reads "0 channels". An empty playlist still reads "0 channels".
 */
internal fun contentCounts(channels: Int, films: Int, shows: Int, locale: Locale): String {
    if (channels == 0 && films == 0 && shows == 0) return channelsGrouped(0, locale)
    val nf = NumberFormat.getIntegerInstance(locale)
    return buildList {
        if (channels > 0) add(channelsGrouped(channels, locale))
        if (films > 0) add(if (films == 1) "1 film" else "${nf.format(films)} films")
        if (shows > 0) add(if (shows == 1) "1 show" else "${nf.format(shows)} shows")
    }.joinToString(" · ")
}

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

/** A playlist's stored status line: [isError] false for the last-good-copy notice, which reports a success. */
internal data class StoredError(val text: String, val isError: Boolean)

/**
 * What a playlist's stored error may show, wherever it is shown (Settings, the phone page); null when
 * there is none. Stored errors are raw (redacted) import messages, so only fixed copy goes out: the
 * last-good-copy notice, the portal's fixed messages, else [LAST_UPDATE_FAILED].
 */
internal fun storedErrorCopy(lastError: String?): StoredError? {
    val stored = lastError?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        stored.startsWith(COPY_NOTICE_PREFIX) -> StoredError(stored, isError = false)
        stored in PORTAL_ERROR_COPY -> StoredError(stored, isError = true)
        else -> StoredError(LAST_UPDATE_FAILED, isError = true)
    }
}

/** The text of [value] in [choices], or [fallback] for a value the list doesn't have (never throws). */
fun <T> choiceText(choices: List<Pair<T, String>>, value: T, fallback: (T) -> String): String =
    choices.firstOrNull { it.first == value }?.second ?: fallback(value)

/** The Settings section for the phone and laptop setup page. */
internal const val SECTION_COMPANION = "Companion"

// --- Film and show details page ---

internal const val SECTION_DETAILS = "Film and show details"
internal const val DT_KEY = "TMDB key"
internal const val DT_REMOVE = "Remove key"
internal const val DT_NOT_SET = "Not set"
internal const val DT_KEY_HINT = "Get a free key at themoviedb.org → Settings → API"
internal const val TMDB_CREDIT = "This product uses the TMDB API but is not endorsed or certified by TMDB."

/** The status line under the key; none when no key is set. */
internal fun keyStatusText(status: KeyStatus): String? = when (status) {
    KeyStatus.NONE -> null
    KeyStatus.CHECKING -> "Checking…"
    KeyStatus.WORKING -> "Working"
    KeyStatus.BAD_KEY -> "Key not accepted"
    KeyStatus.UNREACHABLE -> "Couldn't reach TMDB"
}

/** `••••` and the last four characters; a key too short to hide part of shows only the dots. */
internal fun maskedTmdbKey(key: String): String {
    val k = key.trim()
    return when {
        k.isEmpty() -> DT_NOT_SET
        k.length <= 8 -> "••••"
        else -> "••••" + k.takeLast(4)
    }
}

// --- Playback page ---

internal const val PB_BUFFER = "Buffer size"
internal const val PB_VIDEO_DECODER = "Video decoder"
internal const val PB_COLOUR = "Picture colour"
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
internal const val PB_AUTO_PLAY_NEXT = "Auto-play next episode"
internal const val PB_TUNNELING = "Tunneled playback (advanced)"

internal const val PB_VIDEO_DECODER_HINT = "Software can help a channel that won't play. It is slower."
internal const val PB_COLOUR_HINT = "Try Standard if the picture looks pale or washed out on this TV. Standard turns HDR off."
internal const val PB_AUTO_FRAME_RATE_HINT =
    "Switches the TV to the channel's frame rate in full screen. The screen goes black for 1–5 seconds each time it switches."
internal const val PB_SURROUND_HINT = "Picks a 5.1 sound track when the channel has one."
internal const val PB_PASSTHROUGH_HINT = "Auto sends Dolby sound to your TV or sound system when it supports it."
internal const val PB_DTS_HINT = "Turn on if DTS channels have no sound."
internal const val PB_SUBTITLE_LANGUAGE_HINT = "Subtitles turn on by themselves when a channel has this language."
internal const val PB_AUTO_PLAY_NEXT_HINT = "Plays the next episode when one ends. Asks if you're still watching after 3 in a row."
internal const val PB_TUNNELING_HINT = "Can make 4K smoother on some TVs. May do nothing on Fire TV. Full screen only."

/** The Playback page's rows, top to bottom. The picture-in-picture row exists only where the device supports it. */
internal fun playbackRowLabels(pipSupported: Boolean): List<String> = listOfNotNull(
    PB_BUFFER,
    PB_VIDEO_DECODER,
    PB_COLOUR,
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
    PB_AUTO_PLAY_NEXT,
    PB_PIP_ON_HOME.takeIf { pipSupported },
    PB_TUNNELING,
)

fun bufferPresetText(p: BufferPreset): String = when (p) {
    BufferPreset.SMALL -> "Small (fastest channel change)"
    BufferPreset.NORMAL -> "Normal"
    BufferPreset.LARGE -> "Large (for unsteady Wi-Fi)"
}

fun colourModeText(m: ColourMode): String = when (m) {
    ColourMode.AUTO -> "Auto"
    ColourMode.STANDARD -> "Standard (ignore stream colour tags)"
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

// --- Recordings & reminders page ---

internal const val SECTION_RECORDINGS = "Recordings & reminders"

internal const val RC_WHERE = "Where to record"
internal const val RC_SMB = "Network folder (SMB)"
internal const val RC_NFS = "Network folder (NFS)"
internal const val RC_USB = "USB drive"
internal const val RC_INTERNAL = "Internal storage (small on most TVs)"
internal const val RC_TEST = "Test"
internal const val RC_FREE = "Free space"
internal const val RC_REMIND = "Remind me before"
internal const val RC_AUTO_SWITCH = "Switch automatically"
internal const val RC_PAD_BEFORE = "Start early"
internal const val RC_PAD_AFTER = "End late"
internal const val RC_POPUPS = "Pop-ups over other apps"
internal const val RC_STANDBY = "Keep recording in standby"
internal const val RC_CONNECTIONS = "Connections allowed"
internal const val RC_STREAM_FORMAT = "Stream format"
internal const val RC_FILMS_AND_SERIES = "Films and series"
internal const val UPDATE_NOW = "Update now"

internal const val RC_NOT_SET = "Not set"
internal const val RC_NOT_SET_HINT = "Pick a network folder or a USB drive"
internal const val RC_TEST_WORKS = "Works"
internal const val RC_TEST_FAILED = "Couldn't reach that storage."
internal const val RC_TESTING = "Testing…"
internal const val RC_AUTO_SWITCH_HINT = "Outside Loophole this needs Pop-ups over other apps."
internal const val RC_NO_USB_PICKER = "This TV can't pick a USB drive. Use a network folder."
internal const val RC_NO_SCREEN = "This TV has no screen for that setting."
internal const val RC_ALLOWED = "Allowed"
internal const val RC_NOT_ALLOWED = "Not allowed"
internal const val RC_UNKNOWN = "Unknown"
internal const val RC_SAVE = "Save"

internal val RC_SMB_FIELDS = listOf("Host", "Share", "Folder", "User", "Password", "Domain")
internal val RC_NFS_FIELDS = listOf("Host", "Export", "Folder")

/** The kinds on offer, internal storage last. */
internal val RC_WHERE_CHOICES = listOf(StorageKind.SMB, StorageKind.NFS, StorageKind.USB, StorageKind.INTERNAL)

/** "Auto" first (null), then one to five streams. */
internal val RC_CONNECTION_CHOICES: List<Int?> = listOf(null, 1, 2, 3, 4, 5)

/** MPEG-TS (null) first, then HLS. */
internal val RC_STREAM_FORMAT_CHOICES: List<String?> = listOf(null, XTREAM_FORMAT_HLS)

internal fun streamFormatText(format: String?): String = if (format == XTREAM_FORMAT_HLS) "HLS" else "MPEG-TS"

/** Row value of "Where to record"; internal storage adds its [internalFree] space when known. */
internal fun storageKindText(kind: StorageKind, internalFree: Long?): String = when (kind) {
    StorageKind.NONE -> RC_NOT_SET
    StorageKind.SMB -> RC_SMB
    StorageKind.NFS -> RC_NFS
    StorageKind.USB -> RC_USB
    StorageKind.INTERNAL -> if (internalFree == null) RC_INTERNAL else "$RC_INTERNAL · ${formatSize(internalFree)} free"
}

/** The rows of the page, top to bottom; the chosen network folder's form follows "Where to record". */
internal fun recordingsRowLabels(kind: StorageKind): List<String> = buildList {
    add(RC_WHERE)
    when (kind) {
        StorageKind.SMB -> addAll(RC_SMB_FIELDS)
        StorageKind.NFS -> addAll(RC_NFS_FIELDS)
        else -> Unit
    }
    addAll(listOf(RC_TEST, RC_FREE, RC_REMIND, RC_AUTO_SWITCH, RC_PAD_BEFORE, RC_PAD_AFTER, RC_POPUPS, RC_STANDBY))
}

/** "At the start", "1 min before", ... for one of the reminder lead choices. */
internal fun reminderLeadText(min: Int): String = if (min == 0) "At the start" else "$min min"

/** "None", "1 min", ... for a recording's start-early or end-late choice. */
internal fun padText(min: Int): String = if (min == 0) "None" else "$min min"

internal fun freeSpaceText(bytes: Long?): String = bytes?.let(::formatSize) ?: RC_UNKNOWN

/** "Auto (3)" for no setting, else the number. [auto] is the account's limit, or one. */
internal fun connectionsText(allowed: Int?, auto: Int): String = allowed?.toString() ?: "Auto ($auto)"

internal fun allowedText(granted: Boolean): String = if (granted) RC_ALLOWED else RC_NOT_ALLOWED
