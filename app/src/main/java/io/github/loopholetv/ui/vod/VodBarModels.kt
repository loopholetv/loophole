package io.github.loopholetv.ui.vod

import io.github.loopholetv.ui.player.durationText
import io.github.loopholetv.ui.player.signedDurationText
import java.time.Duration

/** Everything the film seek bar draws, already formatted. [total], [fraction] and [pendingFraction] are null while the length is unknown. */
data class VodBarUi(
    val title: String,
    val elapsed: String,
    val total: String?,
    val fraction: Float?,
    /** Where a pending seek will land; null when no seek is waiting. */
    val pendingFraction: Float?,
    /** The pending seek relative to the current position, e.g. "−0:30" or "+5:00". */
    val pendingLabel: String?,
    val paused: Boolean,
)

/** [pendingMs] is the offset a held seek key has built up, not a position. */
fun vodBarUi(title: String, positionMs: Long, durationMs: Long?, pendingMs: Long?, paused: Boolean): VodBarUi {
    val length = durationMs?.takeIf { it > 0 }
    fun fractionAt(ms: Long) = (ms.toFloat() / length!!).coerceIn(0f, 1f)
    return VodBarUi(
        title = title,
        elapsed = durationText(Duration.ofMillis(positionMs)),
        total = length?.let { durationText(Duration.ofMillis(it)) },
        fraction = length?.let { fractionAt(positionMs) },
        pendingFraction = if (length != null && pendingMs != null) fractionAt(positionMs + pendingMs) else null,
        pendingLabel = pendingMs?.let { signedDurationText(Duration.ofMillis(it)) },
        paused = paused,
    )
}
