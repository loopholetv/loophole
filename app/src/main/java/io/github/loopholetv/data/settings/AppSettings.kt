package io.github.loopholetv.data.settings

/** User-tunable settings. Interval value `0` means Off. */
data class AppSettings(
    val playlistUpdateHours: Int = 24,
    val playlistUpdateOnStart: Boolean = true,
    val epgUpdateHours: Int = 12,
    val epgUpdateOnStart: Boolean = true,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val epgPastDays: Int = 0,
    val epgStoreDescriptions: Boolean = true,
    val lastChannelOnStart: Boolean = false,
    val confirmExit: Boolean = true,
    val lastGuideScope: String? = null,
    /** General User-Agent; null means the built-in default. */
    val userAgent: String? = null,
    /** UDP proxy as `host:port`; null means off. */
    val udpProxy: String? = null,
) {
    companion object {
        val INTERVAL_HOURS = listOf(0, 3, 6, 12, 24, 48)
        val PAST_DAYS = listOf(0, 1, 2, 3, 7)
    }
}
