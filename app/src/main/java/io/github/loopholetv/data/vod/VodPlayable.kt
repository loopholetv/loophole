package io.github.loopholetv.data.vod

/** What the player needs to start one film or episode. */
data class VodPlayable(
    val ref: VodRef,
    val title: String,
    val streamUrl: String,
    val userAgent: String?,
    val referrer: String?,
)
