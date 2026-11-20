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

/**
 * Like [progressAfter], for a stream that played to its natural end. With no known duration there is
 * no telling how far through it was, so the position resets (watched is left as it was) instead of
 * resuming at the very end; a known duration behaves exactly as [progressAfter].
 */
fun progressAfterNaturalEnd(
    old: VodProgressEntity?,
    ref: VodRef,
    positionMs: Long,
    durationMs: Long?,
    now: Instant,
): VodProgressEntity? {
    if (durationMs != null && durationMs > 0) return progressAfter(old, ref, positionMs, durationMs, now)
    return old?.copy(positionMs = 0L)
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

/**
 * The episode a show's Continue watching entry should open, or null when the show does not belong in
 * the row: nothing watched yet, or the final episode finished. [ContinueTarget.started] is true only
 * when the target has a saved position to resume from.
 */
fun continueRowTarget(episodesInOrder: List<EpisodeView>): ContinueTarget? {
    val latestIndex = episodesInOrder.indices
        .filter { episodesInOrder[it].progress != null }
        .maxByOrNull { episodesInOrder[it].progress!!.lastWatchedAt }
        ?: return null
    val p = episodesInOrder[latestIndex].progress!!
    if (p.watched && p.positionMs == 0L && latestIndex == episodesInOrder.lastIndex) return null
    val target = continueTarget(episodesInOrder) ?: return null
    val started = episodesInOrder.first { it.key == target.key }.progress?.let { it.positionMs > 0 } == true
    return target.copy(started = started)
}

/**
 * The numbered episode that follows [currentKey] in show-page order, or null when [currentKey] is
 * absent, in the "Other" group (no season or episode number), or the last numbered episode.
 */
fun nextEpisode(episodesInOrder: List<EpisodeView>, currentKey: String): EpisodeView? {
    val index = episodesInOrder.indexOfFirst { it.key == currentKey }
    if (index < 0) return null
    val current = episodesInOrder[index]
    if (current.season == null || current.episode == null) return null
    return episodesInOrder.getOrNull(index + 1)?.takeIf { it.season != null && it.episode != null }
}

/** Playback position from which the "Next episode" button shows, for an episode [durationMs] long. */
fun nextWindowStartMs(durationMs: Long): Long =
    if (durationMs >= SHORT_EPISODE_MS) durationMs - NEXT_WINDOW_MS
    else (durationMs * (1 - SHORT_EPISODE_WINDOW_FRACTION)).toLong()
