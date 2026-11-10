package io.github.loopholetv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataStore(
        scope: CoroutineScope,
        file: File = File(tmp.root, "settings.preferences_pb"),
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) { file }

    @Test
    fun defaultsWhenEmpty() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        assertEquals(AppSettings(), store.current())
    }

    @Test
    fun remoteDefaults() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        assertEquals(RemoteSettings(), store.current().remote)
        assertEquals(AUTO_PAST_DAYS, store.current().epgPastDays)
    }

    @Test
    fun settingsSaveWithUntouchedButtonsWritesNoBindings() = runTest {
        val ds = dataStore(backgroundScope)
        val store = SettingsStore(ds)
        store.update { it.copy(confirmExit = !it.confirmExit) }
        val key = stringPreferencesKey("key_bindings")
        assertEquals(null, ds.data.first()[key])
    }

    @Test
    fun resettingButtonsRemovesStoredBindings() = runTest {
        val ds = dataStore(backgroundScope)
        val store = SettingsStore(ds)
        val key = stringPreferencesKey("key_bindings")
        val custom = KeyBindings(player = mapOf(183 to PlayerAction.FAVOURITE), guide = emptyMap())
        store.update { it.copy(remote = it.remote.copy(bindings = custom)) }
        assertEquals(true, ds.data.first()[key] != null)
        assertEquals(custom, store.current().remote.bindings)
        store.update { it.copy(remote = it.remote.copy(bindings = KeyBindings.DEFAULT)) }
        assertEquals(null, ds.data.first()[key])
        assertEquals(KeyBindings.DEFAULT, store.current().remote.bindings)
    }

    @Test
    fun remoteRoundTrip() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        val remote = RemoteSettings(
            mediaKeysSeek = false,
            rwRewindsLive = false,
            leftRightSeek = false,
            leftRewindsLive = true,
            bindings = KeyBindings(
                player = mapOf(183 to PlayerAction.FAVOURITE, 89 to PlayerAction.NONE),
                guide = mapOf(184 to GuideAction.NOW),
            ),
        )
        store.update { it.copy(remote = remote) }
        assertEquals(remote, store.current().remote)
    }

    @Test
    fun pastDaysZeroBecomesAutoOnce() = runTest {
        val ds = dataStore(backgroundScope)
        ds.edit { it[intPreferencesKey("epg_past_days")] = 0 }
        val store = SettingsStore(ds)
        assertEquals(AUTO_PAST_DAYS, store.current().epgPastDays)
        store.update { it.copy(epgPastDays = 0) }
        assertEquals(0, store.current().epgPastDays)
    }

    @Test
    fun updateBeforeFirstReadKeepsChosenZero() = runTest {
        val ds = dataStore(backgroundScope)
        ds.edit { it[intPreferencesKey("epg_past_days")] = 0 }
        SettingsStore(ds).update { it.copy(epgPastDays = 0) }
        assertEquals(0, SettingsStore(ds).current().epgPastDays)
        assertEquals(true, ds.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey("epg_past_days_auto_applied")])
    }

    @Test
    fun pastDaysOtherValueKept() = runTest {
        val ds = dataStore(backgroundScope)
        ds.edit { it[intPreferencesKey("epg_past_days")] = 3 }
        val store = SettingsStore(ds)
        assertEquals(3, store.current().epgPastDays)
        assertEquals(true, ds.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey("epg_past_days_auto_applied")])
        store.update { it.copy(epgPastDays = 0) }
        assertEquals(0, store.current().epgPastDays)
    }

    @Test
    fun updateRoundTripsEveryField() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        val changed = AppSettings(
            playlistUpdateHours = 6,
            playlistUpdateOnStart = false,
            epgUpdateHours = 48,
            epgUpdateOnStart = false,
            epgUpdateOnPlaylistChange = false,
            epgPastDays = 7,
            epgStoreDescriptions = false,
            lastChannelOnStart = true,
            confirmExit = false,
            checkForUpdates = false,
            lastGuideScope = "group:42",
        )
        store.update { changed }
        assertEquals(changed, store.current())
    }

    @Test
    fun invalidStoredValuesReadAsDefaults() = runTest {
        val ds = dataStore(backgroundScope)
        ds.edit {
            it[intPreferencesKey("epg_past_days")] = 4
            it[intPreferencesKey("epg_update_hours")] = -1
            it[intPreferencesKey("playlist_update_hours")] = 5
        }
        val s = SettingsStore(ds).current()
        assertEquals(AUTO_PAST_DAYS, s.epgPastDays)
        assertEquals(12, s.epgUpdateHours)
        assertEquals(24, s.playlistUpdateHours)
    }

    @Test
    fun corruptFileFallsBackToDefaults() = runTest {
        val file = File(tmp.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x55, 0x13, 0x00, 0x66))
        val store = SettingsStore(dataStore(backgroundScope, file))
        assertEquals(AppSettings(), store.current())
    }

    @Test
    fun concurrentUpdatesBothApply() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        listOf(
            async { store.update { it.copy(confirmExit = false) } },
            async { store.update { it.copy(epgPastDays = 3) } },
        ).awaitAll()
        val s = store.current()
        assertEquals(false, s.confirmExit)
        assertEquals(3, s.epgPastDays)
    }

    @Test
    fun readFailureFallsBackToDefaults() = runTest {
        // A non-corruption IOException (e.g. disk error) must not crash the UI collecting this.
        val failing = kotlinx.coroutines.flow.flow<AppSettings> { throw java.io.IOException("disk") }
        assertEquals(listOf(AppSettings()), failing.orDefaultsOnError().toList())
    }

    @Test
    fun userAgentAndProxyDefaultNullAndRoundTrip() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        assertEquals(null, store.current().userAgent)
        assertEquals(null, store.current().udpProxy)
        store.update { it.copy(userAgent = "VLC/3", udpProxy = "192.168.1.1:4022") }
        assertEquals("VLC/3", store.current().userAgent)
        assertEquals("192.168.1.1:4022", store.current().udpProxy)
        store.update { it.copy(userAgent = null, udpProxy = null) }
        assertEquals(null, store.current().userAgent)
        assertEquals(null, store.current().udpProxy)
    }

    @Test
    fun paddedValuesAreTrimmedOnWriteAndRead() = runTest {
        val ds = dataStore(backgroundScope)
        val store = SettingsStore(ds)
        store.update { it.copy(userAgent = " VLC ", udpProxy = " h:1 ") }
        assertEquals("VLC", store.current().userAgent)
        assertEquals("h:1", store.current().udpProxy)
        ds.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("user_agent")] = "  X  " }
        assertEquals("X", store.current().userAgent)
    }

    @Test
    fun playbackDefaults() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        assertEquals(PlaybackSettings(), store.current().playback)
    }

    @Test
    fun playbackRoundTrip() = runTest {
        val store = SettingsStore(dataStore(backgroundScope))
        val playback = PlaybackSettings(
            bufferPreset = BufferPreset.LARGE,
            videoDecoder = DecoderChoice.SOFTWARE,
            audioDecoder = DecoderChoice.SOFTWARE,
            autoFrameRate = true,
            preferSurround = false,
            passthrough = Passthrough.OFF,
            decodeDtsToPcm = true,
            tunneling = true,
            audioLanguage = "de",
            subtitleLanguage = "fr",
            audioDelayMs = -350,
            subtitleStyle = SubtitleStyle(
                size = SubtitleSize.EXTRA_LARGE,
                colour = SubtitleColour.YELLOW,
                background = SubtitleBackground.BLACK,
                edge = SubtitleEdge.SHADOW,
            ),
            pipOnHome = true,
        )
        store.update { it.copy(playback = playback, confirmExit = false) }
        val s = store.current()
        assertEquals(playback, s.playback)
        assertEquals(false, s.confirmExit)

        // Back to defaults: optional languages are removed, not kept.
        store.update { it.copy(playback = PlaybackSettings()) }
        assertEquals(PlaybackSettings(), store.current().playback)
    }

    @Test
    fun invalidPlaybackValuesFallBack() = runTest {
        val ds = dataStore(backgroundScope)
        ds.edit {
            it[stringPreferencesKey("buffer_preset")] = "HUGE"
            it[stringPreferencesKey("video_decoder")] = "hardware"
            it[stringPreferencesKey("audio_passthrough")] = ""
            it[stringPreferencesKey("audio_language")] = "xx"
            it[stringPreferencesKey("subtitle_language")] = "  "
            it[intPreferencesKey("audio_delay_ms")] = 37
            it[stringPreferencesKey("subtitle_size")] = "TINY"
            it[stringPreferencesKey("subtitle_colour")] = "RED"
            it[stringPreferencesKey("subtitle_background")] = "GREY"
            it[stringPreferencesKey("subtitle_edge")] = "GLOW"
        }
        val p = SettingsStore(ds).current().playback
        assertEquals(BufferPreset.NORMAL, p.bufferPreset)
        assertEquals(DecoderChoice.HARDWARE, p.videoDecoder)
        assertEquals(Passthrough.AUTO, p.passthrough)
        assertEquals(null, p.audioLanguage)
        assertEquals(null, p.subtitleLanguage)
        assertEquals(50, p.audioDelayMs)
        assertEquals(SubtitleStyle(), p.subtitleStyle)

        ds.edit { it[intPreferencesKey("audio_delay_ms")] = 5000 }
        assertEquals(1000, SettingsStore(ds).current().playback.audioDelayMs)
    }

    @Test
    fun blankUserAgentStoredAsNull() = runTest {
        val ds = dataStore(backgroundScope)
        val store = SettingsStore(ds)
        store.update { it.copy(userAgent = "   ", udpProxy = "") }
        assertEquals(null, store.current().userAgent)
        assertEquals(null, store.current().udpProxy)
        ds.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("user_agent")] = " "
            it[androidx.datastore.preferences.core.stringPreferencesKey("udp_proxy")] = ""
        }
        assertEquals(null, store.current().userAgent)
        assertEquals(null, store.current().udpProxy)
    }
}
