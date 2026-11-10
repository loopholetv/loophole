package io.github.loopholetv.data.settings

import android.view.KeyEvent

/** What a remote button does while a video is playing full screen. */
enum class PlayerAction(val label: String) {
    NONE("Nothing"),
    CHANNEL_UP("Channel up"),
    CHANNEL_DOWN("Channel down"),
    CHANNEL_LIST("Channel list"),
    GUIDE("TV guide"),
    QUICK_MENU("Quick menu"),
    FAVOURITE("Add/remove favourite"),
    SUBTITLES("Subtitles"),
    AUDIO_TRACK("Audio track"),
    SLEEP_TIMER("Sleep timer"),
    SEARCH("Search"),
    PLAY_PAUSE("Play/Pause"),
    REWIND("Rewind"),
    FAST_FORWARD("Fast-forward"),
    LIVE("Live"),
    WATCH_FROM_START("Watch from start"),
}

/** What a remote button does in the TV guide. */
enum class GuideAction(val label: String) {
    NONE("Nothing"),
    PAGE_UP("Page up"),
    PAGE_DOWN("Page down"),
    NOW("Back to now"),
    DAY_BACK("Previous day"),
    DAY_FORWARD("Next day"),
    FAVOURITE("Add/remove favourite"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

/**
 * Remote button bindings, keyed by Android key code. An entry whose action is `NONE` is kept:
 * it switches a default binding off.
 */
data class KeyBindings(
    val player: Map<Int, PlayerAction>,
    val guide: Map<Int, GuideAction>,
) {
    fun player(code: Int): PlayerAction? = player[code]
    fun guide(code: Int): GuideAction? = guide[code]

    companion object {
        val DEFAULT = KeyBindings(
            player = mapOf(
                KeyEvent.KEYCODE_CHANNEL_UP to PlayerAction.CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_UP to PlayerAction.CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN to PlayerAction.CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN to PlayerAction.CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_REWIND to PlayerAction.REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to PlayerAction.REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to PlayerAction.FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to PlayerAction.FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to PlayerAction.PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY to PlayerAction.PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PAUSE to PlayerAction.PLAY_PAUSE,
                KeyEvent.KEYCODE_GUIDE to PlayerAction.GUIDE,
                KeyEvent.KEYCODE_INFO to PlayerAction.CHANNEL_LIST,
                KeyEvent.KEYCODE_CAPTIONS to PlayerAction.SUBTITLES,
            ),
            guide = mapOf(
                KeyEvent.KEYCODE_CHANNEL_UP to GuideAction.PAGE_UP,
                KeyEvent.KEYCODE_PAGE_UP to GuideAction.PAGE_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN to GuideAction.PAGE_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN to GuideAction.PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_REWIND to GuideAction.DAY_BACK,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to GuideAction.DAY_FORWARD,
            ),
        )
    }
}

private val FIXED_KEYS: Set<Int> = buildSet {
    add(KeyEvent.KEYCODE_DPAD_UP)
    add(KeyEvent.KEYCODE_DPAD_DOWN)
    add(KeyEvent.KEYCODE_DPAD_LEFT)
    add(KeyEvent.KEYCODE_DPAD_RIGHT)
    add(KeyEvent.KEYCODE_DPAD_CENTER)
    add(KeyEvent.KEYCODE_DPAD_UP_LEFT)
    add(KeyEvent.KEYCODE_DPAD_UP_RIGHT)
    add(KeyEvent.KEYCODE_DPAD_DOWN_LEFT)
    add(KeyEvent.KEYCODE_DPAD_DOWN_RIGHT)
    add(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP)
    add(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)
    add(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT)
    add(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT)
    add(KeyEvent.KEYCODE_BUTTON_A)
    add(KeyEvent.KEYCODE_BUTTON_B)
    add(KeyEvent.KEYCODE_ENTER)
    add(KeyEvent.KEYCODE_NUMPAD_ENTER)
    add(KeyEvent.KEYCODE_BACK)
    add(KeyEvent.KEYCODE_ESCAPE)
    add(KeyEvent.KEYCODE_HOME)
    add(KeyEvent.KEYCODE_MENU)
    addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
    addAll(KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)
    add(KeyEvent.KEYCODE_VOLUME_UP)
    add(KeyEvent.KEYCODE_VOLUME_DOWN)
    add(KeyEvent.KEYCODE_VOLUME_MUTE)
    add(KeyEvent.KEYCODE_MUTE)
    add(KeyEvent.KEYCODE_POWER)
    add(KeyEvent.KEYCODE_SEARCH)
    add(KeyEvent.KEYCODE_ASSIST)
    add(KeyEvent.KEYCODE_VOICE_ASSIST)
}

/** False for keys that must keep their fixed job: arrows, OK, Back, Home, Menu, digits, volume, power, search. */
fun isRemappable(keyCode: Int): Boolean = keyCode > 0 && keyCode !in FIXED_KEYS

/** A short name for a remote button, for the settings screen. */
fun keyName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind"
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast-forward"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
    KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
    KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
    KeyEvent.KEYCODE_CHANNEL_UP -> "Channel up"
    KeyEvent.KEYCODE_CHANNEL_DOWN -> "Channel down"
    KeyEvent.KEYCODE_PAGE_UP -> "Page up"
    KeyEvent.KEYCODE_PAGE_DOWN -> "Page down"
    KeyEvent.KEYCODE_PROG_RED -> "Red button"
    KeyEvent.KEYCODE_PROG_GREEN -> "Green button"
    KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow button"
    KeyEvent.KEYCODE_PROG_BLUE -> "Blue button"
    KeyEvent.KEYCODE_INFO -> "Info"
    KeyEvent.KEYCODE_GUIDE -> "Guide"
    KeyEvent.KEYCODE_CAPTIONS -> "Subtitles"
    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "Skip back"
    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "Skip forward"
    KeyEvent.KEYCODE_MEDIA_STOP -> "Stop"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "Next"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous"
    else -> "Button $keyCode"
}

/** Stored form, e.g. `p:89=REWIND;g:92=PAGE_UP`. */
fun encodeBindings(b: KeyBindings): String =
    (b.player.map { (code, a) -> "p:$code=${a.name}" } + b.guide.map { (code, a) -> "g:$code=${a.name}" })
        .joinToString(";")

/** Reads [encodeBindings] output. Null or blank gives the defaults; bad or non-remappable entries are dropped. */
fun decodeBindings(s: String?): KeyBindings {
    if (s.isNullOrBlank()) return KeyBindings.DEFAULT
    val player = linkedMapOf<Int, PlayerAction>()
    val guide = linkedMapOf<Int, GuideAction>()
    for (entry in s.split(';')) {
        val scope = entry.substringBefore(':', "")
        val rest = entry.substringAfter(':', "")
        val code = rest.substringBefore('=', "").trim().toIntOrNull() ?: continue
        if (!isRemappable(code)) continue
        val name = rest.substringAfter('=', "").trim()
        when (scope.trim()) {
            "p" -> PlayerAction.entries.firstOrNull { it.name == name }?.let { player[code] = it }
            "g" -> GuideAction.entries.firstOrNull { it.name == name }?.let { guide[code] = it }
        }
    }
    // Real tables keep their NONE entries, so an empty result means the text was unusable.
    return if (player.isEmpty() && guide.isEmpty()) KeyBindings.DEFAULT else KeyBindings(player, guide)
}
