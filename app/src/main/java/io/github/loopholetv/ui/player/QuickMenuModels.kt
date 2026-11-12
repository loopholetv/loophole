package io.github.loopholetv.ui.player

import io.github.loopholetv.data.settings.AUDIO_DELAY_STEP_MS
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.audioDelayText
import io.github.loopholetv.data.settings.normaliseAudioDelay
import io.github.loopholetv.player.Handoff
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlayerTracks
import io.github.loopholetv.player.SLEEP_PRESETS_MIN
import io.github.loopholetv.player.SleepState
import io.github.loopholetv.player.SleepTimer
import io.github.loopholetv.player.TrackChoice
import io.github.loopholetv.player.TrackId
import io.github.loopholetv.player.TrackOption
import io.github.loopholetv.player.TrackType
import io.github.loopholetv.player.sleepPresetText
import io.github.loopholetv.player.sleepRemainingText
import io.github.loopholetv.ui.common.OPTION_ADD_FAVOURITE
import io.github.loopholetv.ui.common.OPTION_REMOVE_FAVOURITE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sign

/** A stepped audio delay is saved to the settings once no step has come for this long. */
const val AUDIO_DELAY_WRITE_MS = 500L

/** The quick menu's tiles, in display order. */
enum class QuickItem(val label: String) {
    SEARCH("Search"),
    CHANNELS("Channels"),
    START_OVER("Watch from start"),
    VIDEO("Video"),
    AUDIO("Audio"),
    AUDIO_DELAY("Audio delay"),
    SUBTITLES("Subtitles"),
    ASPECT("Aspect ratio"),
    SLEEP("Sleep timer"),
    FAVOURITE("Add to favourites"),

    /** Labelled [CAST_STOP_LABEL] while this TV casts. */
    CAST("Cast to all TVs"),
    OPTIONS("Channel options"),
    PIP("Picture-in-picture"),
    SETTINGS("Settings"),
}

/** The cast tile's label while this TV casts. */
const val CAST_STOP_LABEL = "Stop casting"

/** The small label over the video while this TV casts to [followers] other TVs. */
fun castingLabel(followers: Int): String = when {
    followers <= 0 -> "Casting"
    followers == 1 -> "Casting to 1 TV"
    else -> "Casting to $followers TVs"
}

/**
 * The tiles shown; picture-in-picture only where the device supports it, Watch from start only
 * while a channel with catch-up plays live ([startOver]).
 */
fun quickItems(pipSupported: Boolean, startOver: Boolean = false): List<QuickItem> =
    QuickItem.entries.filter {
        when (it) {
            QuickItem.PIP -> pipSupported
            QuickItem.START_OVER -> startOver
            else -> true
        }
    }

/** One tile: its name and the current value shown under it (null for actions). */
data class QuickEntry(val item: QuickItem, val label: String, val value: String?)

/** The list opened above a tile. */
sealed interface QuickPanel {
    data class Choices(val item: QuickItem, val options: List<QuickChoice>) : QuickPanel
    data class Delay(val ms: Int) : QuickPanel
}

data class QuickChoice(val key: String, val label: String, val selected: Boolean)

/** The open quick menu: its tiles, the tile to focus, and the list above it, if any. */
data class QuickMenuUi(val entries: List<QuickEntry>, val focus: QuickItem, val panel: QuickPanel?)

/** One-off actions the player screen carries out (they need the activity or navigation). */
sealed interface PlayerEvent {
    data class LaunchExternal(val choice: String, val handoff: Handoff, val title: String) : PlayerEvent
    data object EnterPip : PlayerEvent
    data object OpenSearch : PlayerEvent
    data object OpenSettings : PlayerEvent
}

/** What the tiles and lists show. */
data class QuickValues(
    val tracks: PlayerTracks = PlayerTracks.NONE,
    val delayMs: Int = 0,
    val aspect: AspectMode = AspectMode.FIT,
    val sleep: SleepState = SleepState.Off,
    val favourite: Boolean = false,
    /** This TV is casting (the cast tile offers to stop). */
    val casting: Boolean = false,
)

private const val KEY_AUTO = "auto"
private const val KEY_OFF = "off"
private const val KEY_DEFAULT = "default"

/** Track choices are keyed by their group id and index; never logged (ids can hold URL text). */
private fun keyOf(id: TrackId) = "t:${id.groupId}#${id.trackIndex}"

/**
 * Repeated labels (two "English, Stereo" tracks) get a running number from the second one on,
 * so every row of a list reads differently.
 */
internal fun distinctLabels(options: List<TrackOption>): List<TrackOption> {
    val seen = HashMap<String, Int>()
    return options.map { o ->
        val n = (seen[o.label] ?: 0) + 1
        seen[o.label] = n
        if (n == 1) o else o.copy(label = "${o.label} ($n)")
    }
}

private fun PlayerTracks.subtitlesOff() = textOff || text.none { it.selected }

fun quickEntries(items: List<QuickItem>, v: QuickValues): List<QuickEntry> = items.map { item ->
    when (item) {
        QuickItem.FAVOURITE -> QuickEntry(item, if (v.favourite) OPTION_REMOVE_FAVOURITE else OPTION_ADD_FAVOURITE, null)
        QuickItem.CAST -> QuickEntry(item, if (v.casting) CAST_STOP_LABEL else item.label, null)
        else -> QuickEntry(item, item.label, quickValue(item, v))
    }
}

private fun quickValue(item: QuickItem, v: QuickValues): String? = when (item) {
    QuickItem.VIDEO -> if (v.tracks.videoAuto) "Auto" else v.tracks.video.firstOrNull { it.selected }?.label ?: "Auto"
    QuickItem.AUDIO -> distinctLabels(v.tracks.audio).firstOrNull { it.selected }?.label ?: "Default"
    QuickItem.AUDIO_DELAY -> audioDelayText(v.delayMs)
    QuickItem.SUBTITLES ->
        if (v.tracks.subtitlesOff()) "Off" else distinctLabels(v.tracks.text).firstOrNull { it.selected }?.label ?: "Off"
    QuickItem.ASPECT -> v.aspect.label
    QuickItem.SLEEP -> when (val s = v.sleep) {
        SleepState.Off -> "Off"
        is SleepState.Running -> sleepRemainingText(s.remainingMs)
        is SleepState.Warning -> sleepRemainingText(s.secondsLeft * 1_000L)
    }
    else -> null
}

private fun SleepState.minutes(): Int? = when (this) {
    SleepState.Off -> null
    is SleepState.Running -> minutes
    is SleepState.Warning -> minutes
}

/** The list above [item]. Subtitles offer Off and the stream's languages (no Auto). */
fun quickChoices(item: QuickItem, v: QuickValues): List<QuickChoice> = when (item) {
    QuickItem.VIDEO -> listOf(QuickChoice(KEY_AUTO, "Auto", v.tracks.videoAuto)) +
        v.tracks.video.map { QuickChoice(keyOf(it.id), it.label, !v.tracks.videoAuto && it.selected) }
    QuickItem.AUDIO -> distinctLabels(v.tracks.audio).map { QuickChoice(keyOf(it.id), it.label, it.selected) }
        .ifEmpty { listOf(QuickChoice(KEY_DEFAULT, "Default", true)) }
    QuickItem.SUBTITLES -> {
        val off = v.tracks.subtitlesOff()
        listOf(QuickChoice(KEY_OFF, "Off", off)) +
            distinctLabels(v.tracks.text).map { QuickChoice(keyOf(it.id), it.label, !off && it.selected) }
    }
    QuickItem.ASPECT -> AspectMode.entries.map { QuickChoice(it.name, it.label, it == v.aspect) }
    QuickItem.SLEEP -> listOf(QuickChoice(KEY_OFF, "Off", v.sleep == SleepState.Off)) +
        SLEEP_PRESETS_MIN.map { QuickChoice(it.toString(), sleepPresetText(it), v.sleep.minutes() == it) }
    else -> emptyList()
}

/** The track selection a list row stands for; null when it no longer matches a track. */
private fun trackChoice(item: QuickItem, key: String, tracks: PlayerTracks): Pair<TrackType, TrackChoice>? {
    fun pick(type: TrackType, options: List<TrackOption>) =
        options.firstOrNull { keyOf(it.id) == key }?.let { type to TrackChoice.Pick(it.id) }
    return when (item) {
        QuickItem.VIDEO -> if (key == KEY_AUTO) TrackType.VIDEO to TrackChoice.Auto else pick(TrackType.VIDEO, tracks.video)
        QuickItem.AUDIO -> pick(TrackType.AUDIO, tracks.audio)
        QuickItem.SUBTITLES -> if (key == KEY_OFF) TrackType.TEXT to TrackChoice.Off else pick(TrackType.TEXT, tracks.text)
        else -> null
    }
}

/** True once an exit to the guide was asked for and the screen is resumed (it can pop then). */
fun shouldExit(exitRequested: Boolean, resumed: Boolean): Boolean = exitRequested && resumed

/** What the quick menu needs from the player screen's state. */
internal interface QuickMenuHost {
    /** Current values; the menu fills in the audio delay itself. */
    fun values(): QuickValues
    fun showChannels()
    fun watchFromStart()
    fun toggleFavourite()

    /** Starts casting to the other TVs, or stops the cast running. */
    fun toggleCasting()
    fun openOptions()
    fun saveAspect(mode: AspectMode)
    fun send(event: PlayerEvent)

    /** Any use of the menu counts as the viewer still watching (sleep timer warning). */
    fun stayAwake()
}

/**
 * The quick menu's state: open or not, the focused tile, the list above it and the audio delay
 * being stepped. [refresh] rebuilds [ui] from the host's current values (call it when they change).
 */
internal class QuickMenuController(
    private val scope: CoroutineScope,
    private val playback: Playback,
    /** The tiles to show, read each time the menu is built (some depend on what plays). */
    private val itemsNow: () -> List<QuickItem>,
    private val sleepTimer: SleepTimer?,
    private val updatePlayback: suspend ((PlaybackSettings) -> PlaybackSettings) -> Unit,
    private val host: QuickMenuHost,
) {
    private data class Open(val focus: QuickItem, val panel: QuickItem?)

    private var open: Open? = null

    /** The last tile used, focused when the menu opens again. */
    private var last: QuickItem? = null
    private var storedDelay = 0

    /** A stepped delay not yet saved; null when the stored value applies. */
    private var steppedDelay: Int? = null
    private var writeJob: Job? = null

    private val _ui = MutableStateFlow<QuickMenuUi?>(null)
    val ui: StateFlow<QuickMenuUi?> = _ui.asStateFlow()

    val isOpen: Boolean get() = open != null

    private val delayMs: Int get() = steppedDelay ?: storedDelay

    fun onStoredDelay(ms: Int) {
        storedDelay = ms
        refresh()
    }

    fun open() {
        host.stayAwake()
        if (open != null) return
        val items = itemsNow()
        open = Open(last?.takeIf { it in items } ?: items.first(), null)
        refresh()
    }

    /** Opens the menu straight on [item]'s list (a remote button bound to it). */
    fun openPanel(item: QuickItem) {
        host.stayAwake()
        if (open == null) open()
        if (open?.panel == item) return
        pick(item)
    }

    fun dismiss() {
        if (open == null) return
        open = null
        refresh()
    }

    fun pick(item: QuickItem) {
        host.stayAwake()
        val o = open ?: return
        last = item
        when (item) {
            QuickItem.SEARCH -> close { host.send(PlayerEvent.OpenSearch) }
            QuickItem.SETTINGS -> close { host.send(PlayerEvent.OpenSettings) }
            QuickItem.PIP -> close { host.send(PlayerEvent.EnterPip) }
            QuickItem.CHANNELS -> close(host::showChannels)
            QuickItem.START_OVER -> close(host::watchFromStart)
            QuickItem.OPTIONS -> close(host::openOptions)
            QuickItem.FAVOURITE -> {
                open = o.copy(focus = item, panel = null)
                host.toggleFavourite()
                refresh()
            }
            // Stays open: the tile's label follows the cast as it starts or stops.
            QuickItem.CAST -> {
                open = o.copy(focus = item, panel = null)
                host.toggleCasting()
                refresh()
            }
            else -> {
                open = Open(item, item)
                refresh()
            }
        }
    }

    private inline fun close(then: () -> Unit) {
        dismiss()
        then()
    }

    fun choose(item: QuickItem, key: String) {
        host.stayAwake()
        if (open?.panel != item) return
        when (item) {
            QuickItem.VIDEO, QuickItem.AUDIO, QuickItem.SUBTITLES ->
                trackChoice(item, key, playback.tracks.value)?.let { (type, choice) -> playback.selectTrack(type, choice) }
            QuickItem.ASPECT -> AspectMode.entries.firstOrNull { it.name == key }?.let(host::saveAspect)
            QuickItem.SLEEP -> if (key == KEY_OFF) {
                sleepTimer?.cancel()
            } else {
                key.toIntOrNull()?.takeIf { it in SLEEP_PRESETS_MIN }?.let { sleepTimer?.start(it) }
            }
            else -> Unit
        }
        closePanel()
    }

    /** One step of the audio delay, applied at once; saved [AUDIO_DELAY_WRITE_MS] after the last step. */
    fun step(direction: Int) {
        host.stayAwake()
        if (open?.panel != QuickItem.AUDIO_DELAY || direction == 0) return
        val next = normaliseAudioDelay(delayMs + direction.sign * AUDIO_DELAY_STEP_MS)
        steppedDelay = next
        playback.setAudioDelay(next)
        writeJob?.cancel()
        writeJob = scope.launch {
            delay(AUDIO_DELAY_WRITE_MS)
            saveDelay(next)
        }
        refresh()
    }

    /**
     * Saves a stepped delay still waiting for its write now, outside [scope], so leaving the screen
     * (which cancels [scope]) never loses it.
     */
    fun flushDelay() {
        // Still set until a save finishes, even when [scope] has already cancelled the timed write.
        val next = steppedDelay ?: return
        if (flushing) return
        flushing = true
        writeJob?.cancel()
        // ATOMIC starts even when [scope] is already cancelled; NonCancellable lets the write finish.
        writeJob = scope.launch(start = CoroutineStart.ATOMIC) { withContext(NonCancellable) { saveDelay(next) } }
    }

    /** Set once [flushDelay] has started the final save; the screen is going away. */
    private var flushing = false

    private suspend fun saveDelay(next: Int) {
        val saved = try {
            updatePlayback { it.copy(audioDelayMs = next) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (saved) storedDelay = next else playback.setAudioDelay(storedDelay)
        steppedDelay = null
        refresh()
    }

    /** Back to the tiles, focus on the tile whose list was open. */
    fun closePanel() {
        host.stayAwake()
        val o = open ?: return
        if (o.panel == null) return
        open = Open(o.panel, null)
        refresh()
    }

    fun refresh() {
        val o = open
        if (o == null) {
            _ui.value = null
            return
        }
        val v = host.values().copy(delayMs = delayMs)
        val panel = when (o.panel) {
            null -> null
            QuickItem.AUDIO_DELAY -> QuickPanel.Delay(delayMs)
            else -> QuickPanel.Choices(o.panel, quickChoices(o.panel, v))
        }
        val items = itemsNow()
        // A tile that went away (Watch from start once replaying) hands the focus to the first one.
        val focus = o.focus.takeIf { it in items } ?: items.first()
        _ui.value = QuickMenuUi(quickEntries(items, v), focus, panel)
    }
}
