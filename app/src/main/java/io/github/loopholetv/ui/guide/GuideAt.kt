package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ProgrammeEntity

/**
 * A showing to open in the guide: its channel (playlist id and key) and start, in epoch milliseconds as the
 * guide shows it (with the channel's time shift). It travels between screens as a string.
 */
data class GuideAt(val playlistId: Long, val channelKey: String, val startMs: Long) {
    fun encode(): String = "$playlistId/$channelKey/$startMs"

    companion object {
        /** Null for anything that is not an encoded [GuideAt]. The key may hold slashes. */
        fun parse(text: String?): GuideAt? {
            if (text == null) return null
            val first = text.indexOf('/')
            val last = text.lastIndexOf('/')
            if (first <= 0 || last <= first + 1) return null
            val playlist = text.substring(0, first).toLongOrNull() ?: return null
            val start = text.substring(last + 1).toLongOrNull() ?: return null
            return GuideAt(playlist, text.substring(first + 1, last), start)
        }
    }
}

/** The programme of [programmes] that starts at [startMs], else the one on air then; null when the guide has none. */
fun programmeAt(programmes: List<ProgrammeEntity>, startMs: Long): ProgrammeEntity? =
    programmes.firstOrNull { it.start.toEpochMilli() == startMs }
        ?: programmes.firstOrNull { it.start.toEpochMilli() <= startMs && startMs < it.stop.toEpochMilli() }
