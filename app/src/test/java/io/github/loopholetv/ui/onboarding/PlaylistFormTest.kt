package io.github.loopholetv.ui.onboarding

import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.xtream.XtreamCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFormTest {
    private fun ok(input: PlaylistFormInput) = checkPlaylistForm(input) as FormCheck.Ok

    @Test fun m3uGetsScheme() {
        val r = ok(PlaylistFormInput(Mode.M3U, url = "example.com/x.m3u"))
        assertEquals(SourceEdit.M3uUrl("http://example.com/x.m3u"), r.source)
        assertEquals("example.com", r.name)
    }

    @Test fun xtreamNeedsAllThree() {
        val full = PlaylistFormInput(Mode.XTREAM, server = "http://s.tv:8080", user = "u", pass = "p")
        assertTrue(checkPlaylistForm(full) is FormCheck.Ok)
        assertEquals(FormCheck.Incomplete, checkPlaylistForm(full.copy(pass = "")))
        assertEquals(FormCheck.Incomplete, checkPlaylistForm(full.copy(user = " ")))
        assertEquals(FormCheck.Incomplete, checkPlaylistForm(full.copy(server = "")))
    }

    @Test fun xtreamKeepsPasswordSpaces() {
        val r = ok(PlaylistFormInput(Mode.XTREAM, server = " http://s.tv ", user = " u ", pass = " p w "))
        val creds = (r.source as SourceEdit.Xtream).creds
        assertEquals(XtreamCredentials("http://s.tv", "u", " p w "), creds)
    }

    @Test fun portalBadMacIsInvalidMac() {
        assertEquals(FormCheck.InvalidMac, checkPlaylistForm(PlaylistFormInput(Mode.PORTAL, portalUrl = "http://p", mac = "00:1A")))
    }

    @Test fun portalBlankExtrasAreNull() {
        val r = ok(
            PlaylistFormInput(
                Mode.PORTAL, portalUrl = " http://p ", mac = "00:1a:79:12:34:56",
                portalLogin = " ", portalPass = "  ", serial = "", deviceId = " ", deviceId2 = "", signature = " ",
            ),
        )
        val cfg = (r.source as SourceEdit.Portal).cfg
        assertEquals("http://p", cfg.portalUrl)
        assertNull(cfg.login)
        assertNull(cfg.password)
        assertNull(cfg.serial)
        assertNull(cfg.deviceId)
        assertNull(cfg.deviceId2)
        assertNull(cfg.signature)
        assertEquals("p", r.name)
    }

    @Test fun typedNameWins() {
        val r = ok(PlaylistFormInput(Mode.M3U, name = " Mine ", url = "example.com/x.m3u"))
        assertEquals("Mine", r.name)
    }

    @Test fun fileWaitsForDisplayNameAndIsNamedAfterIt() {
        val pending = PlaylistFormInput(Mode.FILE, fileLocation = "content://x/1")
        assertEquals(FormCheck.Incomplete, checkPlaylistForm(pending))
        assertEquals("my list", ok(pending.copy(fileName = "my list.m3u")).name)
    }

    @Test fun toStringHidesSecrets() {
        val s = PlaylistFormInput(Mode.XTREAM, server = "http://secret-host", user = "bob", pass = "hunter2", mac = "00:1A:79:12:34:56").toString()
        assertEquals("PlaylistFormInput(mode=XTREAM)", s)
        assertFalse("hunter2" in s)
    }

    @Test fun uiMapsToInput() {
        val ui = AddPlaylistUi(mode = Mode.PORTAL, portalUrl = "http://p", mac = "00:1A:79:12:34:56", serial = "S")
        val input = ui.toFormInput()
        assertEquals(Mode.PORTAL, input.mode)
        assertEquals("S", input.serial)
        assertEquals("00:1A:79:12:34:56", input.mac)
    }
}
