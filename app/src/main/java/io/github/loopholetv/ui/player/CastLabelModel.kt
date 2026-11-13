package io.github.loopholetv.ui.player

/** What a casting label shows: the full [text] while [expanded], a small marker with [count] after. */
data class CastLabelState(val text: String, val count: Int?, val expanded: Boolean)

/**
 * Timing of a label that steps out of the way: it is expanded for [showMs] after it first appears
 * and after every change of [update]'s text, count or key, then collapses. Pure; the caller owns
 * the single delayed wake-up that calls [tick].
 */
class CastLabelTimer(private val clock: () -> Long, private val showMs: Long) {
    private var text: String? = null
    private var count: Int? = null
    private var key: Any? = null
    private var expandedUntilMs = 0L

    /** Null [text] hides the label; any change of text, count or [key] expands it again. */
    fun update(text: String?, count: Int?, key: Any? = null): CastLabelState? {
        if (text == null) {
            this.text = null
            this.count = null
            this.key = null
            return null
        }
        if (text != this.text || count != this.count || key != this.key) {
            this.text = text
            this.count = count
            this.key = key
            expandedUntilMs = clock() + showMs
        }
        return tick()
    }

    fun tick(): CastLabelState? {
        val t = text ?: return null
        return CastLabelState(t, count, clock() < expandedUntilMs)
    }

    /** Milliseconds until the label collapses; 0 when it already has or nothing shows. */
    fun remainingMs(): Long = if (text == null) 0L else (expandedUntilMs - clock()).coerceAtLeast(0L)
}
