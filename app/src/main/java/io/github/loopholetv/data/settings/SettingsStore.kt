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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * For UI collectors: a read failure the corruption handler doesn't cover (e.g. an IOException
 * from the disk) gives the defaults instead of crashing the app.
 */
fun Flow<AppSettings>.orDefaultsOnError(): Flow<AppSettings> = catch { emit(AppSettings()) }

/** DataStore-backed [AppSettings]. Preference key names are persisted: never rename. */
class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = flow {
        migrate()
        emitAll(store.data.map { it.toSettings() }.distinctUntilChanged())
    }

    suspend fun current(): AppSettings = settings.first()

    /** Atomic read-modify-write: concurrent updates are serialized by DataStore. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            prefs.applyPastDaysMigration()
            prefs.write(transform(prefs.toSettings()))
        }
    }

    /** One-off upgrade step, run before the first read. */
    private suspend fun migrate() {
        if (store.data.first()[EPG_PAST_DAYS_AUTO_APPLIED] == true) return
        store.edit { it.applyPastDaysMigration() }
    }

    /**
     * Earlier versions defaulted past guide days to 0. An unset or 0 value becomes automatic once;
     * any other stored value is kept. A marker stops this from running again, so 0 can be chosen later.
     */
    private fun MutablePreferences.applyPastDaysMigration() {
        if (this[EPG_PAST_DAYS_AUTO_APPLIED] == true) return
        val stored = this[EPG_PAST_DAYS]
        if (stored == null || stored == 0) this[EPG_PAST_DAYS] = AUTO_PAST_DAYS
        this[EPG_PAST_DAYS_AUTO_APPLIED] = true
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
            checkForUpdates = this[CHECK_FOR_UPDATES] ?: d.checkForUpdates,
            lastGuideScope = this[LAST_GUIDE_SCOPE],
            userAgent = this[USER_AGENT]?.trim()?.takeIf { it.isNotEmpty() },
            udpProxy = this[UDP_PROXY]?.trim()?.takeIf { it.isNotEmpty() },
            playback = readPlayback(),
            remote = readRemote(),
        )
    }

    private fun Preferences.readRemote(): RemoteSettings {
        val d = RemoteSettings()
        return RemoteSettings(
            mediaKeysSeek = this[CATCHUP_MEDIA_KEYS_SEEK] ?: d.mediaKeysSeek,
            rwRewindsLive = this[CATCHUP_RW_REWINDS_LIVE] ?: d.rwRewindsLive,
            leftRightSeek = this[CATCHUP_LEFT_RIGHT_SEEK] ?: d.leftRightSeek,
            leftRewindsLive = this[CATCHUP_LEFT_REWINDS_LIVE] ?: d.leftRewindsLive,
            bindings = decodeBindings(this[KEY_BINDINGS]),
        )
    }

    private fun Preferences.readPlayback(): PlaybackSettings {
        val d = PlaybackSettings()
        val ds = d.subtitleStyle
        return PlaybackSettings(
            bufferPreset = this[BUFFER_PRESET].validOr(d.bufferPreset),
            videoDecoder = this[VIDEO_DECODER].validOr(d.videoDecoder),
            audioDecoder = this[AUDIO_DECODER].validOr(d.audioDecoder),
            autoFrameRate = this[AUTO_FRAME_RATE] ?: d.autoFrameRate,
            preferSurround = this[PREFER_SURROUND] ?: d.preferSurround,
            passthrough = this[AUDIO_PASSTHROUGH].validOr(d.passthrough),
            decodeDtsToPcm = this[DECODE_DTS_PCM] ?: d.decodeDtsToPcm,
            tunneling = this[TUNNELED_PLAYBACK] ?: d.tunneling,
            audioLanguage = this[AUDIO_LANGUAGE].languageOrNull(),
            subtitleLanguage = this[SUBTITLE_LANGUAGE].languageOrNull(),
            audioDelayMs = this[AUDIO_DELAY_MS]?.let(::normaliseAudioDelay) ?: d.audioDelayMs,
            subtitleStyle = SubtitleStyle(
                size = this[SUBTITLE_SIZE].validOr(ds.size),
                colour = this[SUBTITLE_COLOUR].validOr(ds.colour),
                background = this[SUBTITLE_BACKGROUND].validOr(ds.background),
                edge = this[SUBTITLE_EDGE].validOr(ds.edge),
            ),
            pipOnHome = this[PIP_ON_HOME] ?: d.pipOnHome,
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
        this[CHECK_FOR_UPDATES] = s.checkForUpdates
        val scope = s.lastGuideScope
        if (scope == null) remove(LAST_GUIDE_SCOPE) else this[LAST_GUIDE_SCOPE] = scope
        putOrRemove(USER_AGENT, s.userAgent)
        putOrRemove(UDP_PROXY, s.udpProxy)
        writePlayback(s.playback)
        writeRemote(s.remote)
    }

    private fun MutablePreferences.writeRemote(r: RemoteSettings) {
        this[CATCHUP_MEDIA_KEYS_SEEK] = r.mediaKeysSeek
        this[CATCHUP_RW_REWINDS_LIVE] = r.rwRewindsLive
        this[CATCHUP_LEFT_RIGHT_SEEK] = r.leftRightSeek
        this[CATCHUP_LEFT_REWINDS_LIVE] = r.leftRewindsLive
        // Only customised buttons are stored, so untouched ones keep following the defaults.
        if (r.bindings == KeyBindings.DEFAULT) remove(KEY_BINDINGS) else this[KEY_BINDINGS] = encodeBindings(r.bindings)
    }

    private fun MutablePreferences.writePlayback(p: PlaybackSettings) {
        this[BUFFER_PRESET] = p.bufferPreset.name
        this[VIDEO_DECODER] = p.videoDecoder.name
        this[AUDIO_DECODER] = p.audioDecoder.name
        this[AUTO_FRAME_RATE] = p.autoFrameRate
        this[PREFER_SURROUND] = p.preferSurround
        this[AUDIO_PASSTHROUGH] = p.passthrough.name
        this[DECODE_DTS_PCM] = p.decodeDtsToPcm
        this[TUNNELED_PLAYBACK] = p.tunneling
        putOrRemove(AUDIO_LANGUAGE, p.audioLanguage)
        putOrRemove(SUBTITLE_LANGUAGE, p.subtitleLanguage)
        this[AUDIO_DELAY_MS] = normaliseAudioDelay(p.audioDelayMs)
        this[SUBTITLE_SIZE] = p.subtitleStyle.size.name
        this[SUBTITLE_COLOUR] = p.subtitleStyle.colour.name
        this[SUBTITLE_BACKGROUND] = p.subtitleStyle.background.name
        this[SUBTITLE_EDGE] = p.subtitleStyle.edge.name
        this[PIP_ON_HOME] = p.pipOnHome
    }

    /** Blank and null both remove the key. */
    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) remove(key) else this[key] = value.trim()
    }

    private fun Int?.validOr(allowed: List<Int>, default: Int): Int =
        if (this != null && this in allowed) this else default

    /** A stored enum name; unknown or missing names give [default]. */
    private inline fun <reified E : Enum<E>> String?.validOr(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    /** A stored language code from [PLAYBACK_LANGUAGES]; anything else (blank included) is null. */
    private fun String?.languageOrNull(): String? {
        val code = this?.trim()
        return PLAYBACK_LANGUAGES.firstOrNull { it.code == code }?.code
    }

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
        val CHECK_FOR_UPDATES = booleanPreferencesKey("check_for_updates")
        val LAST_GUIDE_SCOPE = stringPreferencesKey("last_guide_scope")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val UDP_PROXY = stringPreferencesKey("udp_proxy")
        val BUFFER_PRESET = stringPreferencesKey("buffer_preset")
        val VIDEO_DECODER = stringPreferencesKey("video_decoder")
        val AUDIO_DECODER = stringPreferencesKey("audio_decoder")
        val AUTO_FRAME_RATE = booleanPreferencesKey("auto_frame_rate")
        val PREFER_SURROUND = booleanPreferencesKey("prefer_surround")
        val AUDIO_PASSTHROUGH = stringPreferencesKey("audio_passthrough")
        val DECODE_DTS_PCM = booleanPreferencesKey("decode_dts_pcm")
        val TUNNELED_PLAYBACK = booleanPreferencesKey("tunneled_playback")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val SUBTITLE_COLOUR = stringPreferencesKey("subtitle_colour")
        val SUBTITLE_BACKGROUND = stringPreferencesKey("subtitle_background")
        val SUBTITLE_EDGE = stringPreferencesKey("subtitle_edge")
        val PIP_ON_HOME = booleanPreferencesKey("pip_on_home")
        val CATCHUP_MEDIA_KEYS_SEEK = booleanPreferencesKey("catchup_media_keys_seek")
        val CATCHUP_RW_REWINDS_LIVE = booleanPreferencesKey("catchup_rw_rewinds_live")
        val CATCHUP_LEFT_RIGHT_SEEK = booleanPreferencesKey("catchup_left_right_seek")
        val CATCHUP_LEFT_REWINDS_LIVE = booleanPreferencesKey("catchup_left_rewinds_live")
        val KEY_BINDINGS = stringPreferencesKey("key_bindings")
        val EPG_PAST_DAYS_AUTO_APPLIED = booleanPreferencesKey("epg_past_days_auto_applied")
    }
}
