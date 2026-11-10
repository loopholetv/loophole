package io.github.loopholetv.data.settings

/** Remote-control behaviour while playing. */
data class RemoteSettings(
    /** Rewind and Fast-forward seek the programme. */
    val mediaKeysSeek: Boolean = true,
    /** Rewind on a live channel goes back in time instead of being ignored. */
    val rwRewindsLive: Boolean = true,
    /** Left and Right seek while the controls are hidden. */
    val leftRightSeek: Boolean = true,
    /** Left on a live channel goes back in time. */
    val leftRewindsLive: Boolean = false,
    val bindings: KeyBindings = KeyBindings.DEFAULT,
)
