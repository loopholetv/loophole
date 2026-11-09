package io.github.loopholetv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * For UI collectors: a read failure the corruption handler doesn't cover (e.g. an IOException
 * from the disk) gives the defaults instead of crashing the app.
 */
fun Flow<AppSettings>.orDefaultsOnError(): Flow<AppSettings> = catch { emit(AppSettings()) }

/** DataStore-backed [AppSettings]. Preference key names are persisted: never rename. */
class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /** Atomic read-modify-write: concurrent updates are serialized by DataStore. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            playlistUpdateHours = this[PLAYLIST_HOURS].validOr(AppSettings.INTERVAL_HOURS, d.playlistUpdateHours),
            playlistUpdateOnStart = this[PLAYLIST_ON_START] ?: d.playlistUpdateOnStart,
            epgUpdateHours = this[EPG_HOURS].validOr(AppSettings.INTERVAL_HOURS, d.epgUpdateHours),
            epgUpdateOnStart = this[EPG_ON_START] ?: d.epgUpdateOnStart,
            epgUpdateOnPlaylistChange = this[EPG_ON_PLAYLIST_CHANGE] ?: d.epgUpdateOnPlaylistChange,
            epgPastDays = this[EPG_PAST_DAYS].validOr(AppSettings.PAST_DAYS, d.epgPastDays),
            epgStoreDescriptions = this[EPG_DESCRIPTIONS] ?: d.epgStoreDescriptions,
            lastChannelOnStart = this[LAST_CHANNEL_ON_START] ?: d.lastChannelOnStart,
            confirmExit = this[CONFIRM_EXIT] ?: d.confirmExit,
            lastGuideScope = this[LAST_GUIDE_SCOPE],
            userAgent = this[USER_AGENT]?.trim()?.takeIf { it.isNotEmpty() },
            udpProxy = this[UDP_PROXY]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[PLAYLIST_HOURS] = s.playlistUpdateHours
        this[PLAYLIST_ON_START] = s.playlistUpdateOnStart
        this[EPG_HOURS] = s.epgUpdateHours
        this[EPG_ON_START] = s.epgUpdateOnStart
        this[EPG_ON_PLAYLIST_CHANGE] = s.epgUpdateOnPlaylistChange
        this[EPG_PAST_DAYS] = s.epgPastDays
        this[EPG_DESCRIPTIONS] = s.epgStoreDescriptions
        this[LAST_CHANNEL_ON_START] = s.lastChannelOnStart
        this[CONFIRM_EXIT] = s.confirmExit
        val scope = s.lastGuideScope
        if (scope == null) remove(LAST_GUIDE_SCOPE) else this[LAST_GUIDE_SCOPE] = scope
        putOrRemove(USER_AGENT, s.userAgent)
        putOrRemove(UDP_PROXY, s.udpProxy)
    }

    /** Blank and null both remove the key. */
    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) remove(key) else this[key] = value.trim()
    }

    private fun Int?.validOr(allowed: List<Int>, default: Int): Int =
        if (this != null && this in allowed) this else default

    private companion object {
        val PLAYLIST_HOURS = intPreferencesKey("playlist_update_hours")
        val PLAYLIST_ON_START = booleanPreferencesKey("playlist_update_on_start")
        val EPG_HOURS = intPreferencesKey("epg_update_hours")
        val EPG_ON_START = booleanPreferencesKey("epg_update_on_start")
        val EPG_ON_PLAYLIST_CHANGE = booleanPreferencesKey("epg_update_on_playlist_change")
        val EPG_PAST_DAYS = intPreferencesKey("epg_past_days")
        val EPG_DESCRIPTIONS = booleanPreferencesKey("epg_store_descriptions")
        val LAST_CHANNEL_ON_START = booleanPreferencesKey("last_channel_on_start")
        val CONFIRM_EXIT = booleanPreferencesKey("confirm_exit")
        val LAST_GUIDE_SCOPE = stringPreferencesKey("last_guide_scope")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val UDP_PROXY = stringPreferencesKey("udp_proxy")
    }
}
