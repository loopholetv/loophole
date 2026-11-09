package io.github.loopholetv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        assertEquals(0, s.epgPastDays)
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
