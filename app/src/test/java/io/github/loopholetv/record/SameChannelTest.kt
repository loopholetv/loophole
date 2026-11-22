package io.github.loopholetv.record

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameChannelTest {
    private fun playlist(
        id: Long,
        type: PlaylistType,
        url: String? = null,
        xtreamServer: String? = null,
        portalEndpoint: String? = null,
        user: String? = null,
    ) = PlaylistEntity(
        id = id, name = "P$id", type = type, url = url, xtreamServer = xtreamServer, xtreamUser = user,
        xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null, lastError = null,
        portalEndpoint = portalEndpoint,
    )

    private fun channel(name: String, guideEpgId: String? = null) = ChannelEntity(
        id = 1, playlistId = 1, key = "k", number = 1, name = name, streamUrl = "http://x/1",
        logo = null, groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null,
        userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
        epgChannelId = guideEpgId, guideEpgId = guideEpgId,
    )

    @Test fun providerHostSameServerTwoLogins() {
        val one = playlist(1, PlaylistType.XTREAM, xtreamServer = "http://Provider.Example:8080", user = "u1")
        val two = playlist(2, PlaylistType.XTREAM, xtreamServer = "http://provider.example:8080/", user = "u2")
        assertEquals("provider.example", providerHost(one))
        assertEquals(providerHost(one), providerHost(two))
        assertEquals(
            "portal.example",
            providerHost(playlist(3, PlaylistType.STALKER, portalEndpoint = "http://Portal.example/c/load.php")),
        )
        assertEquals("m3u.example", providerHost(playlist(4, PlaylistType.M3U, url = "https://M3U.example/list.m3u")))
    }

    @Test fun filePlaylistsAreSeparate() {
        val f1 = playlist(5, PlaylistType.M3U_FILE, url = "content://a")
        val f2 = playlist(6, PlaylistType.M3U_FILE, url = "content://a")
        assertEquals("file:5", providerHost(f1))
        assertFalse(providerHost(f1) == providerHost(f2))
    }

    @Test fun sameChannelByGuideIdThenName() {
        assertTrue(sameChannel(channel("One", "bbc1.uk"), channel("Other", "BBC1.UK")))
        assertTrue(sameChannel(channel("BBC One HD"), channel("bbc one")))
        assertTrue(sameChannel(channel("BBC-One 4K", " "), channel("BBC One", "")))
        assertFalse(sameChannel(channel("BBC One"), channel("BBC One Scotland")))
        assertEquals("bbcone", channelNameKey("BBC One FHD"))
    }

    @Test fun differentGuideIdsNeverMatchByName() {
        assertFalse(sameChannel(channel("BBC One", "bbc1.london"), channel("BBC One", "bbc1.scotland")))
        assertTrue(sameChannel(channel("BBC One", "bbc1.london"), channel("BBC One", null)))
    }

    @Test fun blankPortalEndpointFallsBackToUrl() {
        val p = playlist(7, PlaylistType.STALKER, url = "http://fallback.example/c/", portalEndpoint = "  ")
        assertEquals("fallback.example", providerHost(p))
    }
}
