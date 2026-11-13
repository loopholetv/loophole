package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.VodProgressEntity
import java.time.Instant

/**
 * The progress row to store after playback reports [positionMs], or null when there is nothing worth
 * keeping (no earlier row and not past the not-started threshold). Reaching [WATCHED_FRACTION] of a
 * known duration marks the item watched and resets the position; watched stays set on a rewatch.
 */
fun progressAfter(
    old: VodProgressEntity?,
    ref: VodRef,
    positionMs: Long,
    durationMs: Long?,
    now: Instant,
): VodProgressEntity? {
    val reachedEnd = durationMs != null && durationMs > 0 && positionMs >= WATCHED_FRACTION * durationMs
    val watched = old?.watched == true || reachedEnd
    val pos = if (reachedEnd || positionMs < NOT_STARTED_MS) 0L else positionMs
    if (old == null && !watched && pos == 0L) return null
    return VodProgressEntity(
        kind = ref.kind,
        playlistId = ref.playlistId,
        key = ref.key,
        positionMs = pos,
        durationMs = durationMs ?: old?.durationMs,
        watched = watched,
        lastWatchedAt = if (pos > 0 || reachedEnd) now else old?.lastWatchedAt ?: now,
    )
}

/** Where to resume playback, or null when the saved position is too early to be worth resuming. */
fun resumeFrom(p: VodProgressEntity?): Long? = p?.positionMs?.takeIf { it >= NOT_STARTED_MS }

data class EpisodeView(
    val key: String,
    val season: Int?,
    val episode: Int?,
    val name: String,
    val progress: VodProgressEntity?,
)

data class ContinueTarget(
    val key: String,
    val season: Int?,
    val episode: Int?,
    val name: String,
    val started: Boolean,
)

/** The episode a show's primary button should play, given its episodes in playing order. */
fun continueTarget(episodesInOrder: List<EpisodeView>): ContinueTarget? {
    if (episodesInOrder.isEmpty()) return null
    val latestIndex = episodesInOrder.indices
        .filter { episodesInOrder[it].progress != null }
        .maxByOrNull { episodesInOrder[it].progress!!.lastWatchedAt }
        ?: return episodesInOrder.first().toTarget(started = false)
    val latest = episodesInOrder[latestIndex]
    val p = latest.progress!!
    if (!p.watched || p.positionMs > 0) return latest.toTarget(started = true)
    return (episodesInOrder.getOrNull(latestIndex + 1) ?: latest).toTarget(started = true)
}

private fun EpisodeView.toTarget(started: Boolean) = ContinueTarget(key, season, episode, name, started)
