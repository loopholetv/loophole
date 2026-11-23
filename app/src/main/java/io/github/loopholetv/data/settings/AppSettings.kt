package io.github.loopholetv.data.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.loopholetv.record.DEFAULT_PAD_AFTER_MIN
import io.github.loopholetv.record.DEFAULT_PAD_BEFORE_MIN
import io.github.loopholetv.record.DEFAULT_REMINDER_LEAD_MIN
import io.github.loopholetv.record.REMINDER_LEAD_CHOICES
import io.github.loopholetv.record.storage.StorageKind

/** Longest TV name kept. */
const val MAX_TV_NAME_LENGTH = 40

/** The device's own name: its `device_name` setting, else the model, else "Loophole TV". */
fun defaultTvName(context: Context): String {
    val configured = try {
        Settings.Global.getString(context.contentResolver, "device_name")
    } catch (_: Exception) {
        null
    }
    return configured?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Loophole TV"
}

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
    /** Join a cast automatically when nothing is playing. */
    val castAllowJoin: Boolean = true,
    /** Name shown to other TVs; null means the device's own name ([defaultTvName]). */
    val castTvName: String? = null,
    /** The first-start screen was skipped: with no playlist the app opens an empty guide. */
    val onboardingSkipped: Boolean = false,
    val playback: PlaybackSettings = PlaybackSettings(),
    val remote: RemoteSettings = RemoteSettings(),
    val recordStorage: StorageKind = StorageKind.NONE,
    val smbHost: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
    val smbPassword: String = "",
    val smbDomain: String = "",
    val nfsHost: String = "",
    val nfsExport: String = "",
    val nfsFolder: String = "",
    /** The chosen USB folder's tree address; empty when none. */
    val usbTreeUri: String = "",
    /** Minutes before a programme a reminder shows; one of [REMINDER_LEAD_CHOICES]. */
    val reminderLeadMin: Int = DEFAULT_REMINDER_LEAD_MIN,
    val reminderAutoSwitch: Boolean = false,
    val padBeforeMin: Int = DEFAULT_PAD_BEFORE_MIN,
    val padAfterMin: Int = DEFAULT_PAD_AFTER_MIN,
    /** Whether this TV has already asked for the notification permission (it asks once). */
    val askedNotifications: Boolean = false,
    /** The user's own TMDB key (a v3 API key or v4 read token); empty when none. Never shown or logged. */
    val tmdbKey: String = "",
) {
    /** Leaves out the SMB password and the TMDB key. */
    override fun toString(): String =
        "AppSettings(playlistUpdateHours=$playlistUpdateHours, playlistUpdateOnStart=$playlistUpdateOnStart, " +
            "epgUpdateHours=$epgUpdateHours, epgUpdateOnStart=$epgUpdateOnStart, " +
            "epgUpdateOnPlaylistChange=$epgUpdateOnPlaylistChange, epgPastDays=$epgPastDays, " +
            "epgStoreDescriptions=$epgStoreDescriptions, lastChannelOnStart=$lastChannelOnStart, " +
            "confirmExit=$confirmExit, checkForUpdates=$checkForUpdates, lastGuideScope=$lastGuideScope, " +
            "userAgent=$userAgent, udpProxy=$udpProxy, castAllowJoin=$castAllowJoin, castTvName=$castTvName, " +
            "onboardingSkipped=$onboardingSkipped, playback=$playback, remote=$remote, " +
            "recordStorage=$recordStorage, smbHost=$smbHost, smbShare=$smbShare, smbFolder=$smbFolder, " +
            "smbUser=$smbUser, smbDomain=$smbDomain, nfsHost=$nfsHost, nfsExport=$nfsExport, nfsFolder=$nfsFolder, " +
            "usbTreeUri=$usbTreeUri, reminderLeadMin=$reminderLeadMin, reminderAutoSwitch=$reminderAutoSwitch, " +
            "padBeforeMin=$padBeforeMin, padAfterMin=$padAfterMin, askedNotifications=$askedNotifications)"

    companion object {
        val PAD_CHOICES = listOf(0, 1, 2, 5, 10, 15, 30)
        val INTERVAL_HOURS = listOf(0, 3, 6, 12, 24, 48)
        val PAST_DAYS = listOf(AUTO_PAST_DAYS, 0, 1, 2, 3, 7)
    }
}
