package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.settings.AppSettings
import java.time.Duration
import java.time.Instant

enum class RefreshKind { PLAYLIST, EPG }

/** An app start within this gap of the last success does not update again. */
val START_GAP: Duration = Duration.ofMinutes(15)

/**
 * Scheduled runs drift by a few minutes; a run this close to the interval counts as due, so a 12 h
 * job firing at 11 h 55 m doesn't wait a whole extra period.
 */
val DUE_SLACK: Duration = Duration.ofMinutes(10)

/**
 * Whether a scheduled update is due. [intervalHours] `0` is Off (never due); a source that has never
 * succeeded ([lastSuccess] null) is due at once.
 */
fun isDue(lastSuccess: Instant?, intervalHours: Int, now: Instant): Boolean {
    if (intervalHours <= 0) return false
    if (lastSuccess == null) return true
    return !now.isBefore(lastSuccess.plus(Duration.ofHours(intervalHours.toLong())).minus(DUE_SLACK))
}

/** Whether an app start should update: "update on start" unless within [START_GAP], or simply due. */
fun startRefreshWanted(onStart: Boolean, lastSuccess: Instant?, intervalHours: Int, now: Instant): Boolean =
    (onStart && (lastSuccess == null || !now.isBefore(lastSuccess.plus(START_GAP)))) ||
        isDue(lastSuccess, intervalHours, now)

/** The playlist's own update interval, or the general one when it is "Same as default" (null). */
fun effectiveHours(p: PlaylistEntity, s: AppSettings): Int = p.updateHours ?: s.playlistUpdateHours

/** The playlist's own "Update on app start", or the general one when it is "Same as default" (null). */
fun effectiveOnStart(p: PlaylistEntity, s: AppSettings): Boolean = p.updateOnStart ?: s.playlistUpdateOnStart
