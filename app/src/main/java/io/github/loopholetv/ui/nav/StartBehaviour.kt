package io.github.loopholetv.ui.nav

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope

/** Channel (and zapping scope) to open full screen at app start. */
data class StartPlayback(val channelId: Long, val scope: ChannelScope)

/**
 * Null when the setting is off or there is no (surviving, visible) history; otherwise the
 * latest channel in the remembered scope, or in All channels when that scope no longer contains it.
 * [resolveScope] turns a saved scope into a usable one first (a legacy `"g:<title>"` group is
 * found by title), since `contains` is false for an unresolved group.
 */
suspend fun resolveStartPlayback(
    enabled: Boolean,
    latest: suspend () -> ChannelEntity?,
    lastScope: ChannelScope,
    contains: suspend (ChannelEntity, ChannelScope) -> Boolean,
    resolveScope: suspend (ChannelScope) -> ChannelScope = { it },
): StartPlayback? {
    if (!enabled) return null
    val channel = latest() ?: return null
    val resolved = resolveScope(lastScope)
    val scope = if (contains(channel, resolved)) resolved else ChannelScope.All
    return StartPlayback(channel.id, scope)
}

/**
 * The start channel, used at most once: the first [take] empties it whatever the route. Taken
 * when the guide first composes; if the no-playlist fallback has already left the guide it is
 * dropped, so a playlist added later can never open a stale channel.
 */
class StartSlot(initial: StartPlayback?) {
    private var pending: StartPlayback? = initial

    fun take(currentRoute: String?): StartPlayback? {
        val p = pending
        pending = null
        return p?.takeIf { currentRoute == Routes.GUIDE }
    }
}
