package io.github.loopholetv.data.settings

/** Past guide days follow the provider's archive length. */
const val AUTO_PAST_DAYS = -1

/** Past guide days to keep for a [setting]; automatic uses the archive length, at most a week. */
fun effectivePastDays(setting: Int, maxCatchupDays: Int?): Int =
    if (setting == AUTO_PAST_DAYS) (maxCatchupDays ?: 0).coerceIn(0, 7) else setting

/** User-tunable settings. Interval value `0` means Off. */
data class AppSettings(
    val playlistUpdateHours: Int = 24,
    val playlistUpdateOnStart: Boolean = true,
    val epgUpdateHours: Int = 12,
    val epgUpdateOnStart: Boolean = true,
    val epgUpdateOnPlaylistChange: Boolean = true,
    /** Days of past guide to keep; [AUTO_PAST_DAYS] follows the longest archive the provider offers. */
    val epgPastDays: Int = AUTO_PAST_DAYS,
    val epgStoreDescriptions: Boolean = true,
    val lastChannelOnStart: Boolean = false,
    val confirmExit: Boolean = true,
    /** Look for a new Loophole release on app start (at most once a day). */
    val checkForUpdates: Boolean = true,
    val lastGuideScope: String? = null,
    /** General User-Agent; null means the built-in default. */
    val userAgent: String? = null,
    /** UDP proxy as `host:port`; null means off. */
    val udpProxy: String? = null,
    val playback: PlaybackSettings = PlaybackSettings(),
    val remote: RemoteSettings = RemoteSettings(),
) {
    companion object {
        val INTERVAL_HOURS = listOf(0, 3, 6, 12, 24, 48)
        val PAST_DAYS = listOf(AUTO_PAST_DAYS, 0, 1, 2, 3, 7)
    }
}
