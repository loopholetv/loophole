package io.github.loopholetv.data.stalker

import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.util.redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class StalkerCmdTest {

    @Test fun normaliseMacForms() {
        assertEquals("00:1A:79:12:34:AB", normaliseMac("00:1a:79:12:34:ab"))
        assertEquals("00:1A:79:12:34:AB", normaliseMac("00-1A-79-12-34-AB"))
        assertEquals("00:1A:79:12:34:AB", normaliseMac("001A791234AB"))
        assertEquals("00:1A:79:12:34:AB", normaliseMac("  001a791234ab "))
        assertNull(normaliseMac("00:1A:79"))
        assertNull(normaliseMac("00:1A:79:12:34:AG"))
        assertNull(normaliseMac("00:1A-79:12:34:AB"))
        assertNull(normaliseMac(""))
        assertTrue(isInfomirMac("00:1A:79:00:00:01"))
        assertTrue(isInfomirMac("00:1a:79:00:00:01"))
        assertFalse(isInfomirMac("AA:BB:CC:00:00:01"))
    }

    @Test fun stripSolutionForms() {
        assertEquals("http://edge.example.test/live/1.ts", stripSolution("ffmpeg http://edge.example.test/live/1.ts"))
        assertEquals("rtsp://h/x", stripSolution("auto rtsp://h/x"))
        assertEquals("http://h/x", stripSolution("http://h/x"))
        assertEquals("http://h/x", stripSolution("  ffrt3   http://h/x  "))
        assertEquals("", stripSolution(""))
        val b64 = Base64.getEncoder().encodeToString("ffmpeg http://edge.example.test/b.ts".toByteArray())
        assertEquals("http://edge.example.test/b.ts", stripSolution(b64))
        // Not base64 of a "<word> <url>" value → kept as it is.
        assertEquals("abcd", stripSolution("abcd"))
    }

    @Test fun needsLinkRules() {
        assertTrue(needsCreateLink(useTmpLink = true, useLoadBalancing = false, cmd = "ffmpeg http://cdn.example.test/1.ts"))
        assertTrue(needsCreateLink(useTmpLink = false, useLoadBalancing = true, cmd = "ffmpeg http://cdn.example.test/1.ts"))
        assertTrue(needsCreateLink(false, false, "ffmpeg http://localhost/ch/1_"))
        assertTrue(needsCreateLink(false, false, "ffmpeg http://127.0.0.5:88/ch/1_"))
        assertTrue(needsCreateLink(false, false, "ffrt4://x"))
        assertTrue(needsCreateLink(false, false, ""))
        assertTrue(needsCreateLink(false, false, "/ch/1"))
        assertFalse(needsCreateLink(false, false, "ffmpeg http://cdn.example.test/1.ts"))
        assertFalse(needsCreateLink(false, false, "rtsp://cdn.example.test/1"))
    }

    @Test fun relativeLinksResolvedAgainstPortal() {
        val root = "http://portal.example.test/stalker_portal/"
        assertEquals("http://portal.example.test/media/1.ts", resolveAgainst(root, "/media/1.ts"))
        assertEquals("http://portal.example.test/stalker_portal/?token=x", resolveAgainst(root, "?token=x"))
        assertEquals("http://other.example.test/a.ts", resolveAgainst(root, "http://other.example.test/a.ts"))
        assertEquals("http://portal.example.test/stalker_portal/",
            portalRoot("http://portal.example.test/stalker_portal/server/load.php"))
        assertEquals("http://portal.example.test/", portalRoot("http://portal.example.test/portal.php"))
        assertEquals("http://portal.example.test/stalker_portal/misc/logos/320/a.png", resolveLogo(root, "a.png"))
        assertEquals("http://portal.example.test/x/c.png", resolveLogo(root, "/x/c.png"))
        assertEquals("http://img.example.test/b.png", resolveLogo(root, "http://img.example.test/b.png"))
        assertNull(resolveLogo(root, " "))
        assertEquals("http://portal.example.test/media/1.ts", playableUrl(root, "ffmpeg /media/1.ts"))
        assertNull(playableUrl(root, "ffmpeg file:///etc/passwd"))
    }

    @Test fun cmdEncodingTouchesOnlyAmpHashSpace() {
        assertEquals("ffmpeg%20http://h/a?x=1%26y=%2F%23z", encodeCmd("ffmpeg http://h/a?x=1&y=%2F#z"))
    }

    @Test fun tvgIdPrefersXmltvId() {
        assertEquals("chan.uk", stalkerTvgId("12", "chan.uk"))
        assertEquals("stalker-12", stalkerTvgId("12", " "))
        assertEquals("stalker-12", stalkerTvgId("12", null))
    }

    @Test fun secretsCoverMacTokenLoginPassword() {
        val cfg = PortalConfig(
            portalUrl = "http://portal.example.test/c/", mac = "00:1a:79:00:00:01", login = "user1",
            password = "pass1", serial = "SERIAL0000001", deviceId = "DEVID1", deviceId2 = "DEVID2", signature = "SIG1",
        )
        val s = stalkerSecrets(cfg, "TOKEN-PLACEHOLDER-1")
        for (v in listOf("00:1A:79:00:00:01", "00:1a:79:00:00:01", "TOKEN-PLACEHOLDER-1", "user1", "pass1",
            "SERIAL0000001", "DEVID1", "DEVID2", "SIG1", "portal.example.test")) {
            assertTrue(v, v in s)
        }
        val text = "mac 00:1A:79:00:00:01 token TOKEN-PLACEHOLDER-1 login user1/pass1 at portal.example.test"
        val r = redact(text, s)
        for (v in listOf("00:1A:79:00:00:01", "TOKEN-PLACEHOLDER-1", "user1", "pass1", "portal.example.test")) {
            assertFalse(r, r.contains(v))
        }
        assertFalse(stalkerSecrets(cfg.copy(login = null, password = null), null).any { it.isBlank() })
    }

    @Test fun portalConfigFromEntityTreatsBlankAsUntyped() {
        val p = PlaylistEntity(
            name = "P", type = PlaylistType.STALKER, url = "http://portal.example.test/c/", xtreamServer = null,
            xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
            lastError = null, mac = "00:1A:79:00:00:01", portalLogin = " ", portalSerial = "SN1", portalDeviceId = "",
        )
        val cfg = p.portalConfig()
        assertEquals(PortalConfig("http://portal.example.test/c/", "00:1A:79:00:00:01", serial = "SN1"), cfg)
    }
}
