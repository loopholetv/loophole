package io.github.loopholetv.ui.settings

/** Which set of buttons a page or action applies to. */
enum class BindingContext(val key: String, val title: String) {
    PLAYER("player", "Player buttons"),
    GUIDE("guide", "Guide buttons"),
}
