package io.github.loopholetv.data.db

/**
 * The set of channels the guide lists and the player zaps through: every channel, the user's
 * favourites (in their order), or one playlist group.
 */
sealed interface ChannelScope {
    data object All : ChannelScope
    data object Favourites : ChannelScope

    /**
     * One group of one playlist, keyed by its **original** title (a rename changes only the label).
     * [playlistId] 0 is a group remembered before groups were per playlist (`"g:<title>"`): resolve it
     * by title with `ScopedChannels.resolve` before use.
     */
    data class Group(val playlistId: Long, val title: String) : ChannelScope
}

private const val ARG_ALL = "all"
private const val ARG_FAVOURITES = "fav"
private const val ARG_GROUP_PREFIX = "pg:"
private const val ARG_LEGACY_GROUP_PREFIX = "g:"

/** Stable string form (navigation argument, saved state); read back with [channelScopeFromArg]. */
fun ChannelScope.toArg(): String = when (this) {
    ChannelScope.All -> ARG_ALL
    ChannelScope.Favourites -> ARG_FAVOURITES
    is ChannelScope.Group -> "$ARG_GROUP_PREFIX$playlistId:$title"
}

/**
 * Inverse of [toArg]. The legacy `"g:<title>"` form gives `Group(0, title)` (resolved by title);
 * `"pg:0:<title>"` (what [toArg] writes for such a group) reads back the same way. Room ids start
 * at 1, so 0 never names a real playlist.
 * Null, unknown or malformed values and a group without a title are [ChannelScope.All].
 */
fun channelScopeFromArg(arg: String?): ChannelScope = when {
    arg == ARG_FAVOURITES -> ChannelScope.Favourites
    arg == null -> ChannelScope.All
    arg.startsWith(ARG_GROUP_PREFIX) -> {
        val rest = arg.substring(ARG_GROUP_PREFIX.length)
        val colon = rest.indexOf(':')
        val id = if (colon > 0) rest.substring(0, colon).toLongOrNull() else null
        val title = if (colon > 0) rest.substring(colon + 1) else ""
        if (id != null && id >= 0 && title.isNotEmpty()) ChannelScope.Group(id, title) else ChannelScope.All
    }
    arg.startsWith(ARG_LEGACY_GROUP_PREFIX) && arg.length > ARG_LEGACY_GROUP_PREFIX.length ->
        ChannelScope.Group(0, arg.substring(ARG_LEGACY_GROUP_PREFIX.length))
    else -> ChannelScope.All
}

/**
 * What the guide and the player call [ChannelScope.All] and [ChannelScope.Favourites]. A group's
 * label can be renamed: use `ScopedChannels.label`; here it falls back to the original title.
 */
fun ChannelScope.label(): String = when (this) {
    ChannelScope.All -> "All channels"
    ChannelScope.Favourites -> "Favourites"
    is ChannelScope.Group -> title
}
