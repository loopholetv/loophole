package io.github.loopholetv.data.settings

/** Where the one-off film and show fill (`VodFill`) keeps whether it is done and which turned-off playlists it left out. */
interface VodFillFlags {
    suspend fun isFilled(): Boolean

    /** The fill is done; [skipped] are the playlists it left out because they were turned off. */
    suspend fun markFilled(skipped: Set<Long>)

    suspend fun skipped(): Set<Long>

    suspend fun clearSkipped(id: Long)
}
