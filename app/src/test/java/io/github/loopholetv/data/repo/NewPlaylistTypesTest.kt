package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import io.github.loopholetv.util.redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The portal and local-file playlist types before their sources exist (stubs that keep the build exhaustive). */
@RunWith(RobolectricTestRunner::class)
class NewPlaylistTypesTest {
    private fun playlist(type: PlaylistType) = PlaylistEntity(
        name = "P", type = type, url = "http://portal.example.test", xtreamServer = null, xtreamUser = null,
        xtreamPass = null, epgUrlsFromSource = "http://example.test/a.xml", manualEpgUrl = null,
        lastRefreshedAt = null, lastError = null,
    )

    @Test fun stalkerSecretsCoverEveryPortalCredential() {
        val p = playlist(PlaylistType.STALKER).copy(
            mac = "00:1A:79:00:00:01", portalToken = "tok", portalLogin = "login", portalPassword = "pw",
            portalSerial = "serial", portalDeviceId = "dev1", portalDeviceId2 = "dev2", portalSignature = "sig",
            portalEndpoint = "http://portal.example.test/stalker_portal/server/load.php",
        )
        assertEquals(
            listOf(
                "00:1A:79:00:00:01", "tok", "login", "pw", "serial", "dev1", "dev2", "sig",
                // The portal address and endpoint (and their host) are secrets too.
                "http://portal.example.test", "http://portal.example.test/stalker_portal/server/load.php",
                "portal.example.test",
            ),
            playlistSecrets(p),
        )
        assertEquals(listOf("http://portal.example.test", "portal.example.test"), playlistSecrets(playlist(PlaylistType.STALKER)))
        assertTrue(playlistSecrets(playlist(PlaylistType.M3U_FILE)).isEmpty())
    }

    @Test fun stalkerSecretsAreRedactedPlainAndEncoded() {
        val p = playlist(PlaylistType.STALKER).copy(mac = "00:1A:79:00:00:01", portalToken = "Tok+en/9=")
        val text = "mac=00:1A:79:00:00:01 token=Tok+en/9= cookie: mac=00%3A1A%3A79%3A00%3A00%3A01; " +
            "auth=Tok%2Ben%2F9%3D"

        val out = redact(text, playlistSecrets(p))

        for (leak in listOf("00:1A:79:00:00:01", "00%3A1A%3A79%3A00%3A00%3A01", "Tok+en/9=", "Tok%2Ben%2F9%3D")) {
            assertFalse("leaked $leak in $out", out.contains(leak))
        }
        assertEquals(4, Regex("""\*\*\*""").findAll(out).count())
    }

    @Test fun announcedGuidesOfNewTypes() {
        val never: (XtreamCredentials) -> String = { error("not Xtream") }
        assertEquals(listOf("http://example.test/a.xml"), announcedEpgUrls(playlist(PlaylistType.M3U_FILE), never))
        assertEquals(listOf(PORTAL_EPG_SOURCE), announcedEpgUrls(playlist(PlaylistType.STALKER), never))
    }

    @Test fun refreshOfUnsupportedTypeFailsWithFixedText() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val http = OkHttpClient()
            val repo = PlaylistRepository(db, http, XtreamClient(http))
            for (type in listOf(PlaylistType.M3U_FILE, PlaylistType.STALKER)) {
                val id = db.playlistDao().insert(playlist(type))
                val result = repo.refresh(id) {}
                assertEquals(ImportResult.Failure(ImportError.Unexpected, "This playlist type isn't supported yet."), result)
                assertEquals("This playlist type isn't supported yet.", db.playlistDao().byId(id)!!.lastError)
            }
        } finally {
            db.close()
        }
    }
}
