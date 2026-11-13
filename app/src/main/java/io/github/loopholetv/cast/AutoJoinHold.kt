package io.github.loopholetv.cast

/**
 * Keeps this TV from joining a cast on its own while held (the first-start screens, and before
 * the first screen is known). Releasing it calls [recheck], so a cast already on the network
 * can be joined then. Starts held.
 */
class AutoJoinHold(private val recheck: () -> Unit) {
    @Volatile private var held = true

    /** Whether this TV may join a cast or be invited on its own now. */
    fun allows(): Boolean = !held

    fun set(held: Boolean) {
        if (held == this.held) return
        this.held = held
        if (!held) recheck()
    }
}
