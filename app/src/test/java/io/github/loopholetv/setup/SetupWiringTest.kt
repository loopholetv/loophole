package io.github.loopholetv.setup

import android.content.res.Configuration
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.MainActivity
import io.github.loopholetv.data.db.PlaylistType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The app's own copy controller: built once, its routes on the cast server, its after-import work. */
@RunWith(RobolectricTestRunner::class)
class SetupWiringTest {
    private val app = ApplicationProvider.getApplicationContext<LoopholeApp>()

    /** Runs the main looper until [done], letting IO threads (the id load, sockets) finish meanwhile. */
    private fun eventually(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test fun screensUseTheRealController() {
        val c = app.container
        assertTrue(c.setup is SetupController)
        assertSame(c.setupController, c.setup)
        assertSame(c.setup, c.setup)
    }

    @Test fun setupRoutesAnswerOnTheCastServer() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val server = app.container.castServer
        app.container.setup // built with the screens, as the app does
        eventually { server.port != null }
        val http = OkHttpClient()
        val offer = Request.Builder()
            .url("http://127.0.0.1:${server.port}${SetupHost.OFFER}")
            .post("""{"from":"nobody"}""".toRequestBody("application/json".toMediaType()))
            .build()
        // A TV that isn't in the peer list is refused by the setup routes; without them it would be 404.
        val code = http.newCall(offer).execute().use { it.code }
        assertEquals(403, code)
        activity.pause().stop().destroy()
        http.dispatcher.executorService.shutdown()
    }

    @Test fun pictureInPictureRefusesSetupRequests() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val controller = app.container.setupController
        assertFalse(controller.paused)
        activity.get().onPictureInPictureModeChanged(true, Configuration())
        assertTrue(controller.paused)
        activity.get().onPictureInPictureModeChanged(false, Configuration())
        assertFalse(controller.paused)
        activity.pause().stop().destroy()
    }

    @Test fun aPauseLeftOverFromPictureInPictureClearsWhenTheAppComesBack() {
        val controller = app.container.setupController
        // The window closed without a mode-changed(false) callback (it varies by device).
        controller.setPaused(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(controller.paused)

        controller.setPaused(true)
        activity.pause().resume()
        assertFalse(controller.paused)
        activity.pause().stop().destroy()
    }

    /** The join behaviour itself is in CastControllerTest; here, the app starts with joining held. */
    @Test fun appStartsWithAutoJoinHeldUntilTheFirstScreenIsKnown() {
        assertFalse(app.container.autoJoinHold.allows())
    }

    @Test fun afterImportForgetsReplacedSessionsAndRefreshes() = runBlocking {
        val invalidated = mutableListOf<Long>()
        val calls = mutableListOf<String>()
        val result = SetupImportResult.Ok(
            skippedFiles = 0,
            removed = listOf(
                RemovedPlaylist(3, PlaylistType.STALKER, "http://portal.example.test/c/"),
                RemovedPlaylist(4, PlaylistType.M3U, "http://m3u.example.test/a.m3u"),
                RemovedPlaylist(9, PlaylistType.STALKER, "http://portal2.example.test/c/"),
            ),
        )

        afterSetupImported(
            result,
            invalidateSession = { invalidated += it; calls += "invalidate $it" },
            refreshAll = { calls += "refresh" },
        )

        assertEquals(listOf(3L, 4L, 9L), invalidated)
        // Sessions go first, so the refresh logs in to the new portals afresh.
        assertEquals(listOf("invalidate 3", "invalidate 4", "invalidate 9", "refresh"), calls)
    }

    @Test fun afterImportRefreshesWithNothingReplaced() = runBlocking {
        var refreshed = 0
        afterSetupImported(SetupImportResult.Ok(0), invalidateSession = { error("nothing to forget") }, refreshAll = { refreshed++ })
        assertEquals(1, refreshed)
    }

    @Test fun afterImportStillRefreshesWhenForgettingASessionFails() = runBlocking {
        var refreshed = 0
        val result = SetupImportResult.Ok(0, listOf(RemovedPlaylist(3, PlaylistType.STALKER, null)))
        afterSetupImported(result, invalidateSession = { throw IllegalStateException() }, refreshAll = { refreshed++ })
        assertEquals(1, refreshed)
    }
}
