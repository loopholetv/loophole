package io.github.loopholetv.player

/**
 * One-shot flag. [hold] arms it; the next [consume] returns true and disarms it, so a
 * screen coming back to the foreground can tell whether it should keep its current preview.
 */
class PreviewHold {
    @Volatile private var held = false

    fun hold() { held = true }

    /** True once after [hold]; false otherwise. */
    fun consume(): Boolean {
        val was = held
        held = false
        return was
    }
}
