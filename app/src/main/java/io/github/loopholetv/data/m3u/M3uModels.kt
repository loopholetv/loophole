package io.github.loopholetv.data.m3u

data class M3uHeader(val epgUrls: List<String>)

data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val chno: Int?,
    val userAgent: String?,
    val referrer: String?,
    val catchup: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    /** The playlist's correction in minutes (from decimal hours); archive times are moved back by this much. */
    val catchupCorrectionMinutes: Int = 0,
)
