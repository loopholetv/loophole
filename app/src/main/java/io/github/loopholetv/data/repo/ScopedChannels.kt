package io.github.loopholetv.data.repo

import androidx.paging.PagingSource
import io.github.loopholetv.data.db.ChannelDao
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.FavouriteDao
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Fires after every committed change to the tables the [ScopedChannels.pages] lists read
 * (channels and favourites), including changes committed while a list is still loading.
 */
fun AppDatabase.channelListChanges(): Flow<Unit> =
    invalidationTracker.createFlow("channels", "favourites", emitInitialState = false).map { }

/**
 * Channel lists and zapping order per [ChannelScope]: "All channels" in guide-number order and a
 * playlist group in its group order ([ChannelDao]), favourites in the user's order
 * ([FavouriteDao]). All of them wrap the same way and list visible channels only.
 */
class ScopedChannels(private val channelDao: ChannelDao, private val favouriteDao: FavouriteDao) {

    /** [scope]'s channels. A legacy `Group(0, …)` lists nothing: [resolve] it first. */
    fun pages(scope: ChannelScope): PagingSource<Int, ChannelEntity> = when (scope) {
        ChannelScope.All -> channelDao.allChannels()
        ChannelScope.Favourites -> favouriteDao.channels()
        is ChannelScope.Group -> channelDao.groupChannels(scope.playlistId, scope.title)
    }

    /** The channel after (direction >= 0) or before [current] in [scope], wrapping; null when it is empty. */
    suspend fun neighbour(current: ChannelEntity, scope: ChannelScope, direction: Int): ChannelEntity? =
        when (val s = resolveLegacy(scope)) {
            ChannelScope.All -> channelDao.neighbour(current, null, direction)
            ChannelScope.Favourites -> favouriteDao.neighbour(current, direction)
            is ChannelScope.Group -> channelDao.neighbour(current, s, direction)
        }

    /** Up to [each] channels either side of [current] in [scope], without wrapping, [current] between them. */
    suspend fun around(current: ChannelEntity, scope: ChannelScope, each: Int): List<ChannelEntity> =
        when (val s = resolveLegacy(scope)) {
            ChannelScope.All -> channelDao.around(current, null, each)
            ChannelScope.Favourites -> favouriteDao.around(current, each)
            is ChannelScope.Group -> channelDao.around(current, s, each)
        }

    /**
     * Whether [channel] is listed in [scope]: it is visible (read from the database, not the
     * possibly stale row) and, for a group, of that playlist and original group title; for
     * Favourites, a favourite. A legacy `Group(0, …)` never contains anything: [resolve] it first.
     */
    suspend fun contains(channel: ChannelEntity, scope: ChannelScope): Boolean {
        if (!channelDao.isVisible(channel.id)) return false
        return when (scope) {
            ChannelScope.All -> true
            ChannelScope.Favourites -> favouriteDao.contains(channel.playlistId, channel.key)
            is ChannelScope.Group -> channel.playlistId == scope.playlistId && channel.groupTitle == scope.title
        }
    }

    /** What the guide and the player call [scope]: a group's custom name when it was renamed. */
    suspend fun label(scope: ChannelScope): String = when (val s = resolveLegacy(scope)) {
        is ChannelScope.Group -> channelDao.groupCustomName(s.playlistId, s.title) ?: s.title
        else -> s.label()
    }

    /**
     * A scope that can be used now. A legacy `Group(0, title)` (saved as `"g:<title>"` before groups
     * were per playlist) becomes the first visible group with that title, in playlist order.
     * A group without visible channels (hidden, emptied, or its playlist turned off) becomes All.
     */
    suspend fun resolve(scope: ChannelScope): ChannelScope = when (scope) {
        ChannelScope.All, ChannelScope.Favourites -> scope
        is ChannelScope.Group -> when {
            scope.playlistId == LEGACY_PLAYLIST_ID ->
                channelDao.firstPlaylistWithGroup(scope.title)?.let { ChannelScope.Group(it, scope.title) }
                    ?: ChannelScope.All
            channelDao.hasVisibleGroup(scope.playlistId, scope.title) -> scope
            else -> ChannelScope.All
        }
    }

    /** Only a legacy group needs resolving before a query; any other scope is used as it is. */
    private suspend fun resolveLegacy(scope: ChannelScope): ChannelScope =
        if (scope is ChannelScope.Group && scope.playlistId == LEGACY_PLAYLIST_ID) resolve(scope) else scope

    private companion object {
        /** Room ids start at 1, so 0 marks a group known only by title. */
        const val LEGACY_PLAYLIST_ID = 0L
    }
}
