package io.github.loopholetv.player

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExternalPlayersTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun handoff(url: String = "http://host.test/live.ts", hls: Boolean = false) = Handoff(
        ResolvedStream(url, "UA/1", mapOf("Referer" to "http://example.test/")),
        hls,
    )

    private fun install(pkg: String) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg })
    }

    @Test fun installedListsOnlyInstalledKnownInOrder() {
        install("org.xbmc.kodi")
        install("org.videolan.vlc")
        install("com.unknown.player")
        assertEquals(
            listOf(ExternalPlayerApp("org.videolan.vlc", "VLC"), ExternalPlayerApp("org.xbmc.kodi", "Kodi")),
            ExternalPlayers.installed(app.packageManager),
        )
    }

    @Test fun mxIntentCarriesHeadersClassAndResult() {
        val i = ExternalPlayers.intentFor("com.mxtech.videoplayer.ad", handoff(), "News")
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("com.mxtech.videoplayer.ad", i.component?.packageName)
        assertEquals("com.mxtech.videoplayer.ad.ActivityScreen", i.component?.className)
        assertArrayEquals(
            arrayOf("User-Agent", "UA/1", "Referer", "http://example.test/"),
            i.getStringArrayExtra("headers"),
        )
        assertTrue(i.getBooleanExtra("return_result", false))
        assertEquals("News", i.getStringExtra("title"))
    }

    @Test fun mxProUsesProActivity() {
        val i = ExternalPlayers.intentFor("com.mxtech.videoplayer.pro", handoff(), "News")
        assertEquals("com.mxtech.videoplayer.ActivityScreen", i.component?.className)
    }

    @Test fun kodiUrlCarriesEncodedUserAgentAndReferer() {
        val i = ExternalPlayers.intentFor("org.xbmc.kodi", handoff(), "News")
        assertEquals(
            "http://host.test/live.ts|User-Agent=UA%2F1&Referer=http%3A%2F%2Fexample.test%2F",
            i.data.toString(),
        )
        assertEquals("org.xbmc.kodi", i.`package`)
    }

    @Test fun kodiOmitsAbsentReferer() {
        val h = Handoff(ResolvedStream("http://host.test/a", "UA/1", emptyMap()), false)
        assertEquals("http://host.test/a|User-Agent=UA%2F1", ExternalPlayers.intentFor("org.xbmc.kodi", h, "t").data.toString())
    }

    @Test fun vlcIntentHasPackageAndTitle() {
        val i = ExternalPlayers.intentFor("org.videolan.vlc", handoff(), "News")
        assertEquals("org.videolan.vlc", i.`package`)
        assertEquals("News", i.getStringExtra("title"))
        assertEquals("http://host.test/live.ts", i.data.toString())
        assertEquals(Intent.ACTION_VIEW, i.action)
    }

    @Test fun hlsUsesMpegUrlMime() {
        assertEquals("application/x-mpegURL", ExternalPlayers.intentFor("org.videolan.vlc", handoff(hls = true), "t").type)
        assertEquals("video/*", ExternalPlayers.intentFor("org.videolan.vlc", handoff(hls = false), "t").type)
    }

    @Test fun askUsesChooser() {
        val i = ExternalPlayers.intentFor(ASK_EACH_TIME, handoff(), "News")
        assertEquals(Intent.ACTION_CHOOSER, i.action)
        val inner = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, inner.action)
        assertEquals("http://host.test/live.ts", inner.data.toString())
    }

    @Test fun choiceLabels() {
        assertEquals("Off", ExternalPlayers.choiceLabel(null))
        assertEquals("Ask each time", ExternalPlayers.choiceLabel(ASK_EACH_TIME))
        assertEquals("VLC", ExternalPlayers.choiceLabel("org.videolan.vlc"))
        assertEquals("com.other.app", ExternalPlayers.choiceLabel("com.other.app"))
    }

    @Test fun handoffToStringHidesUrl() {
        val s = handoff("http://secret.test/tok123").toString()
        assertFalse(s.contains("secret.test"))
        assertFalse(s.contains("tok123"))
    }
}
