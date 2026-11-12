package io.github.loopholetv.cast.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LocalPeersOnlyTest {
    private val peer = MockWebServer()
    private val attempts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val http = OkHttpClient.Builder()
        .addInterceptor(LocalPeersOnly())
        // Any name would be "found" on the peer itself, so only the interceptor can keep it out.
        .dns { name -> lookups += name; listOf(InetAddress.getByName("127.0.0.1")) }
        .eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                attempts += inetSocketAddress.address.hostAddress.orEmpty()
            }
        })
        .build()

    @Before fun setUp() = peer.start()

    @After fun tearDown() = peer.shutdown()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    @Test fun publicAddressesAndNamesAreRefusedBeforeConnecting() {
        peer.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4.0,\nhttp://203.0.113.9/s-0\n"))
        val playlist = get("http://127.0.0.1:${peer.port}/c/token/1/index.m3u8").use { it.body.string() }
        val segment = playlist.lines().first { it.startsWith("http") }

        for (url in listOf(segment, "http://8.8.8.8/x", "http://localhost:${peer.port}/c/token/1/s-0", "http://tv.example.test/x")) {
            try {
                get(url).close()
                fail("expected a refusal for $url")
            } catch (e: IOException) {
                assertEquals("not a local peer", e.message)
            }
        }
        assertEquals("only the playlist itself connected", listOf("127.0.0.1"), attempts.toList())
        assertTrue(lookups.isEmpty())
        assertEquals(1, peer.requestCount)
    }
}
