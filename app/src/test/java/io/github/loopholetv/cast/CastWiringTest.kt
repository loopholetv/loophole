package io.github.loopholetv.cast

import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.MainActivity
import io.github.loopholetv.player.PlaylistStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CastWiringTest {
    /** Records what discovery asks of the platform; nothing reaches a real network. */
    private class RecordingNsd : NsdPort {
        @Volatile var registrations = 0
        @Volatile var registered = false
        @Volatile var browsing = false

        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {
            registrations++
            registered = true
        }

        override fun unregister() {
            registered = false
        }

        override fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit) {
            browsing = true
        }

        override fun stopBrowse() {
            browsing = false
        }
    }

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

    @Test fun controllerStartsOnForegroundStopsOnBackground() {
        val nsd = RecordingNsd()
        app.container.castNsd = { nsd }
        val server = app.container.castServer

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        eventually { server.port != null && nsd.registered && nsd.browsing }

        activity.pause().stop()
        eventually { server.port == null && !nsd.registered && !nsd.browsing }

        // Back on screen: findable again.
        activity.restart().start().resume()
        eventually { server.port != null && nsd.registered && nsd.browsing }
        assertEquals(2, nsd.registrations)

        activity.pause().stop()
        eventually { server.port == null && !nsd.registered }
        activity.destroy()
    }

    @Test @Config(sdk = [25])
    fun wifiLockIsHighPerformanceBeforeAndroid10() {
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, wifiLockMode())
    }

    @Test fun wifiLockIsLowLatencyFromAndroid10() {
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, wifiLockMode())
    }

    @Test fun playbackResolvesThroughTheCastingResolver() {
        val resolver = app.container.playbackResolver
        assertTrue(resolver is CastingResolver)
        assertTrue((resolver as CastingResolver).inner is PlaylistStreamResolver)
    }
}
