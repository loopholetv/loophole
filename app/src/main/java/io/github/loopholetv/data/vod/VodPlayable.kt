package io.github.loopholetv.data.vod

/** What the player needs to start one film or episode. */
data class VodPlayable(
    val ref: VodRef,
    val title: String,
    /** May carry the Xtream password: never log it. */
    val streamUrl: String,
    val userAgent: String?,
    val referrer: String?,
) {
    /** Leaves out [streamUrl] and [referrer], which can carry a login. */
    override fun toString(): String = "VodPlayable(ref=$ref, title=$title, streamUrl=<hidden>)"
}
