package io.github.loopholetv.ui.nav

enum class ExitDecision { SHOW_HINT, EXIT }

/** "Press Back again to exit": a second Back within [windowMs] of the first exits. */
class ExitConfirm(private val windowMs: Long = 2_000, private val clock: () -> Long) {
    private var lastHintAt: Long? = null

    fun onBack(): ExitDecision {
        val now = clock()
        val last = lastHintAt
        return if (last != null && now - last <= windowMs) {
            lastHintAt = null
            ExitDecision.EXIT
        } else {
            lastHintAt = now
            ExitDecision.SHOW_HINT
        }
    }
}
