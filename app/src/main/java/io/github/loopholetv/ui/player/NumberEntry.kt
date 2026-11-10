package io.github.loopholetv.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Digits typed on the player: shown through [show] as they come, then handed to [jump]
 * [NUMBER_ENTRY_IDLE_MS] after the last digit, at [MAX_NUMBER_DIGITS] digits, or on [commit].
 */
internal class NumberEntry(
    private val scope: CoroutineScope,
    private val typed: () -> String?,
    private val show: (String?) -> Unit,
    private val jump: (String) -> Unit,
) {
    private var job: Job? = null

    fun digit(d: Int) {
        require(d in 0..9) { "digit $d" }
        val entry = typed().orEmpty() + d
        show(entry)
        job?.cancel()
        job = null
        if (entry.length >= MAX_NUMBER_DIGITS) {
            jump(entry)
        } else {
            job = scope.launch {
                delay(NUMBER_ENTRY_IDLE_MS)
                job = null
                jump(entry)
            }
        }
    }

    /** OK while typing a number: jump now instead of waiting. */
    fun commit() {
        val entry = typed() ?: return
        job?.cancel()
        job = null
        jump(entry)
    }

    /** Drops the number being typed. */
    fun cancel() {
        job?.cancel()
        job = null
        if (typed() != null) show(null)
    }
}
