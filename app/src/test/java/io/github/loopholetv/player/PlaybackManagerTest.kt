package io.github.loopholetv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.stalker.FakePortal
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.testHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Real player plus a seam: counts prepare/seek, records listeners, can fake an error or a playback state. */
private class SeamPlayer(private val real: ExoPlayer) : ExoPlayer by real {
    var prepareCount = 0
    var seekToDefaultCount = 0
    val listeners = mutableListOf<Player.Listener>()
    var fakeError: ExoPlaybackException? = null
    var fakeState: Int? = null

    override fun prepare() {
        prepareCount++
        fakeError = null
        fakeState = null
        real.prepare()
    }

    override fun seekToDefaultPosition() {
        seekToDefaultCount++
        real.seekToDefaultPosition()
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        real.addListener(listener)
    }

    override fun getPlayerError(): ExoPlaybackException? = fakeError ?: real.playerError
    override fun getPlaybackState(): Int = fakeState ?: real.playbackState
}

/** Records each resolve; can hold it at a gate, fail it, and count live keep-alives. */
private class FakeResolver(var urlFor: (ChannelEntity) -> String = { it.streamUrl }) : StreamResolver {
    val resolved = mutableListOf<Long>()
    var remint = false
    var gate: CompletableDeferred<Unit>? = null
    /** Return from [gate] even when cancelled, so only PlaybackManager's generation guard can drop the result. */
    var ignoreCancellation = false
    var failure: Throwable? = null
    var onResolve: () -> Unit = {}
    var keepAlives = 0
    var activeKeepAlives = 0

    override suspend fun resolve(channel: ChannelEntity): ResolvedStream {
        onResolve()
        resolved += channel.id
        val g = gate
        if (g != null) {
            // A misbehaving resolver that swallows its cancellation and returns anyway.
            if (ignoreCancellation) {
                try { g.await() } catch (_: kotlinx.coroutines.CancellationException) {}
            } else {
                g.await()
            }
        }
        failure?.let { throw it }
        return ResolvedStream(urlFor(channel), "UA-R", emptyMap())
    }

    override suspend fun canRemint(channel: ChannelEntity) = remint

    override suspend fun keepAlive(channel: ChannelEntity) {
        keepAlives++
        activeKeepAlives++
        try { awaitCancellation() } finally { activeKeepAlives-- }
    }
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var exo: ExoPlayer
    private lateinit var seam: SeamPlayer
    private val prepareCount get() = seam.prepareCount
    private lateinit var manager: PlaybackManager
    private var nowMs = 1_000_000L

    @Before
    fun setUp() {
        // Serve an endless-looking body so loads stay open; content is irrelevant to these tests.
        repeat(20) { server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(188 * 50)))) }
        server.start()
        exo = TestExoPlayerBuilder(context).build()
        seam = SeamPlayer(exo)
        manager = PlaybackManager(context, OkHttpClient(), seam, clock = { nowMs })
    }

    @After
    fun tearDown() {
        manager.release()
        server.shutdown()
    }

    private fun channel(id: Long, path: String, userAgent: String? = null, referrer: String? = null) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = "Channel $id",
        streamUrl = server.url(path).toString(), logo = null, groupTitle = "G", groupOrder = 0, sortOrder = 0,
        tvgId = null, tvgName = null, userAgent = userAgent, referrer = referrer,
        catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = null,
    )

    @Test
    fun playSameChannelTwiceDoesNotReprepare() {
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        val firstItem = exo.currentMediaItem

        manager.play(ch)

        assertEquals(1, prepareCount)
        assertEquals(1, exo.mediaItemCount)
        assertTrue(firstItem === exo.currentMediaItem)
    }

    @Test
    fun playDifferentChannelReplacesMediaItem() {
        manager.play(channel(1, "/live/1.ts"))
        val second = channel(2, "/live/2.ts")
        manager.play(second)

        assertEquals(2, prepareCount)
        assertEquals(1, exo.mediaItemCount)
        assertEquals(Uri.parse(second.streamUrl), exo.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(PlaybackState.Buffering(2), manager.state.value)
    }

    @Test
    fun hlsUrlGetsM3u8MimeTypeIgnoringQuery() {
        manager.play(channel(1, "/hls/index.m3u8?token=abc"))
        assertEquals(MimeTypes.APPLICATION_M3U8, exo.currentMediaItem!!.localConfiguration!!.mimeType)

        manager.play(channel(2, "/live/2.ts"))
        assertNull(exo.currentMediaItem!!.localConfiguration!!.mimeType)
    }

    @Test
    fun userAgentHeaderApplied() {
        manager.play(channel(1, "/live/1.ts", userAgent = "UA1", referrer = "http://ref.example.test/"))

        RobolectricUtil.runMainLooperUntil { server.requestCount > 0 }

        val request = server.takeRequest()
        assertEquals("UA1", request.getHeader("User-Agent"))
        assertEquals("http://ref.example.test/", request.getHeader("Referer"))
    }

    @Test
    fun defaultUserAgentAndNoRefererWhenUnset() {
        manager.play(channel(1, "/live/1.ts"))

        RobolectricUtil.runMainLooperUntil { server.requestCount > 0 }

        val request = server.takeRequest()
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Loophole/"))
        assertNull(request.getHeader("Referer"))
    }

    @Test
    fun stopReturnsToIdleAndClearsItem() {
        manager.play(channel(1, "/live/1.ts"))
        manager.stop()

        assertEquals(PlaybackState.Idle, manager.state.value)
        assertEquals(0, exo.mediaItemCount)
    }

    @Test
    fun httpErrorBecomesErrorStateAndSameChannelRetries() {
        val failing = MockWebServer()
        repeat(5) { failing.enqueue(MockResponse().setResponseCode(509)) }
        failing.start()
        val ch = channel(7, "/unused").copy(streamUrl = failing.url("/live/7.ts").toString())
        try {
            manager.play(ch)
            RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }
            assertEquals(
                PlaybackState.Error(7, "Channel 7: provider connection limit reached (another device may be watching)"),
                manager.state.value,
            )

            manager.play(ch)
            assertEquals(2, prepareCount)
            assertEquals(PlaybackState.Buffering(7), manager.state.value)
        } finally {
            failing.shutdown()
        }
    }

    @Test
    fun replaySameChannelAfterPauseResumesWithoutReprepare() {
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        exo.playWhenReady = false // e.g. audio focus loss or becoming noisy

        manager.play(ch)

        assertTrue(exo.playWhenReady)
        assertEquals(1, prepareCount)
    }

    @Test
    fun hlsDetectionTable() {
        val cases = mapOf(
            "http://h.example.test/live/1.m3u8" to true,
            "http://h.example.test/live/1.M3U8?token=x" to true,
            "http://h.example.test/get.php?output=m3u8" to true,
            "http://h.example.test/get.php?a=1&OUTPUT=HLS" to true,
            "http://h.example.test/live?extension=m3u8" to true,
            "http://h.example.test/hls/abc/stream" to true,
            "http://h.example.test/live/1.ts" to false,
            "http://h.example.test/live/1" to false,
            "http://h.example.test/live?output=ts" to false,
            "http://h.example.test/hlsx/1" to false,
            "http://h.example.test/x.m3u8.ts" to false,
        )
        cases.forEach { (url, expected) -> assertEquals(url, expected, isHlsUrl(Uri.parse(url))) }
    }

    private fun serve(dispatch: (RecordedRequest) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = dispatch(request)
        }
        start()
    }

    @Test
    fun extensionlessHlsRetriesAsHlsWithoutSurfacingFirstError() {
        val hls = serve { request ->
            if (request.path == "/stream/7") {
                MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nseg0.ts\n")
            } else {
                MockResponse().setBody(Buffer().write(ByteArray(188 * 10)))
            }
        }
        try {
            val ch = channel(7, "/unused").copy(streamUrl = hls.url("/stream/7").toString())
            manager.play(ch)
            assertNull(exo.currentMediaItem!!.localConfiguration!!.mimeType)

            RobolectricUtil.runMainLooperUntil { hls.requestCount >= 2 }

            assertEquals("/stream/7", hls.takeRequest().path)
            assertEquals("/stream/7", hls.takeRequest().path)
            assertEquals(MimeTypes.APPLICATION_M3U8, exo.currentMediaItem!!.localConfiguration!!.mimeType)
            assertEquals(2, prepareCount)
            assertFalse(manager.state.value is PlaybackState.Error)
        } finally {
            hls.shutdown()
        }
    }

    @Test
    fun failedHlsRetrySurfacesErrorAndRetriesOnlyOnce() {
        val junk = serve { MockResponse().setBody("not a stream at all, just text") }
        try {
            val ch = channel(8, "/unused").copy(streamUrl = junk.url("/stream/8").toString())
            manager.play(ch)

            RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }

            assertEquals(PlaybackState.Error(8, "Channel 8: can't play this stream"), manager.state.value)
            assertEquals(2, prepareCount)
            assertEquals(2, junk.requestCount)
        } finally {
            junk.shutdown()
        }
    }

    @Test
    fun behindLiveWindowSeeksToLiveEdgeAndReprepares() {
        manager.play(channel(1, "/live/1.m3u8"))
        val error = ExoPlaybackException.createForSource(
            BehindLiveWindowException(), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        )
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }

        assertEquals(1, seam.seekToDefaultCount)
        assertEquals(2, prepareCount)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
    }

    @Test
    fun endedLiveStreamBecomesErrorAndSameChannelRetries() {
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        seam.fakeState = Player.STATE_ENDED
        seam.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }

        assertEquals(PlaybackState.Error(1, "Channel 1: can't play this stream"), manager.state.value)

        manager.play(ch)
        assertEquals(2, prepareCount)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
    }

    private fun behindLiveWindow() {
        val error = ExoPlaybackException.createForSource(
            BehindLiveWindowException(), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        )
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }
    }

    @Test
    fun behindLiveWindowRecoveryIsCappedAtThreePerThirtySeconds() {
        manager.play(channel(1, "/live/1.m3u8"))
        repeat(3) {
            behindLiveWindow()
            assertEquals(PlaybackState.Buffering(1), manager.state.value)
            nowMs += 1_000
        }
        assertEquals(3, seam.seekToDefaultCount)
        assertEquals(4, prepareCount)

        behindLiveWindow()

        assertEquals(PlaybackState.Error(1, "Channel 1: can't play this stream"), manager.state.value)
        assertEquals(3, seam.seekToDefaultCount)
        assertEquals(4, prepareCount)
    }

    @Test
    fun behindLiveWindowCountWindowSlides() {
        manager.play(channel(1, "/live/1.m3u8"))
        repeat(3) { behindLiveWindow() }
        nowMs += 31_000

        behindLiveWindow()

        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        assertEquals(4, seam.seekToDefaultCount)
    }

    @Test
    fun playResetsBehindLiveWindowCount() {
        val ch = channel(1, "/live/1.m3u8")
        manager.play(ch)
        repeat(3) { behindLiveWindow() }
        behindLiveWindow()
        assertTrue(manager.state.value is PlaybackState.Error)

        manager.play(ch) // retry
        behindLiveWindow()

        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        assertEquals(4, seam.seekToDefaultCount)
    }

    // ---- Play-time resolution ----

    /** Replaces the default manager with one that resolves through [resolver]. */
    private fun managerWith(resolver: StreamResolver): PlaybackManager {
        manager.release()
        exo = TestExoPlayerBuilder(context).build()
        seam = SeamPlayer(exo)
        manager = PlaybackManager(context, OkHttpClient(), seam, clock = { nowMs }, resolver = resolver)
        return manager
    }

    private fun idleMain(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun portalResolver(portal: FakePortal, portalServer: MockWebServer, delays: MutableList<Long>): PlaylistStreamResolver {
        val client = StalkerClient(testHttp(), timeZone = { "Europe/London" })
        val playlists = mutableMapOf(
            2L to PlaylistEntity(
                id = 2, name = "Portal", type = PlaylistType.STALKER, url = portalServer.url("/stalker_portal/c/").toString(),
                xtreamServer = null, xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
                lastRefreshedAt = null, lastError = null, mac = "00:1A:79:00:00:01",
            ),
        )
        val sessions = StalkerSessions(client, { playlists[it] }) { id, e, t ->
            playlists[id] = playlists.getValue(id).copy(portalEndpoint = e, portalToken = t)
        }
        // Minted links point at the plain stream server, so loads stay open.
        portal.createLinkJson = """{"js":{"id":"1","cmd":"ffmpeg ${server.url("/live/minted.ts")}","error":""}}"""
        return PlaylistStreamResolver(
            { playlists[it] }, { AppSettings() }, "Loophole/test", sessions, client,
            delayMs = { ms -> delays += ms; delay(ms) },
        )
    }

    private fun portalChannel(id: Long) = channel(id, "/unused").copy(
        playlistId = 2, streamUrl = "ffmpeg http://localhost/ch/$id", needsLink = true,
    )

    @Test
    fun oldStreamStoppedBeforeResolveStarts() {
        val fake = FakeResolver()
        managerWith(fake)
        manager.play(channel(1, "/live/1.ts"))
        assertEquals(1, exo.mediaItemCount)
        var seen: Triple<Int, Int, PlaybackState>? = null
        fake.onResolve = { seen = Triple(seam.playbackState, exo.mediaItemCount, manager.state.value) }

        manager.play(channel(2, "/live/2.ts"))

        assertEquals(Triple(Player.STATE_IDLE, 0, PlaybackState.Buffering(2)), seen)
        assertEquals(1, exo.mediaItemCount)
    }

    @Test
    fun rapidZapMintsOnlyForLast() {
        val portal = FakePortal()
        val portalServer = MockWebServer().also { it.dispatcher = portal; it.start() }
        try {
            val delays = mutableListOf<Long>()
            managerWith(portalResolver(portal, portalServer, delays))
            manager.play(portalChannel(11))
            idleMain(150)
            manager.play(portalChannel(12))
            idleMain(150)
            manager.play(portalChannel(13))
            assertEquals(0, exo.mediaItemCount)
            assertEquals(PlaybackState.Buffering(13), manager.state.value)
            idleMain(STALKER_ZAP_DEBOUNCE_MS)
            RobolectricUtil.runMainLooperUntil { exo.mediaItemCount == 1 }

            assertEquals(listOf(400L, 400L, 400L), delays)
            assertEquals(1, portal.count("create_link"))
            assertEquals(
                "ffmpeg http://localhost/ch/13",
                portal.ofAction("create_link").single().requestUrl!!.queryParameter("cmd"),
            )
            assertEquals("13", exo.currentMediaItem!!.mediaId)
            assertEquals(Uri.parse(server.url("/live/minted.ts").toString()), exo.currentMediaItem!!.localConfiguration!!.uri)
        } finally {
            manager.stop()
            portalServer.shutdown()
        }
    }

    @Test
    fun remintOnceOn403() {
        val forbidden = serve { MockResponse().setResponseCode(403) }
        try {
            val fake = FakeResolver { forbidden.url("/live/${it.id}.ts").toString() }
            fake.remint = true
            managerWith(fake)
            val seen = mutableListOf<Pair<Int, Int>>()
            fake.onResolve = { seen += seam.playbackState to exo.mediaItemCount }
            manager.play(channel(1, "/unused"))

            RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }

            assertEquals(listOf(1L, 1L), fake.resolved)
            // The rejected stream was released before the second resolve.
            assertEquals(Player.STATE_IDLE to 0, seen[1])
            fake.onResolve = {}
            assertEquals(PlaybackState.Error(1, "Channel 1: access denied by provider"), manager.state.value)
            // Settled: no third resolve after the error.
            idleMain(1_000)
            assertEquals(listOf(1L, 1L), fake.resolved)

            // Not a portal: no re-mint at all.
            fake.remint = false
            fake.resolved.clear()
            manager.play(channel(2, "/unused"))
            RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }
            assertEquals(listOf(2L), fake.resolved)
            assertEquals(PlaybackState.Error(2, "Channel 2: access denied by provider"), manager.state.value)
        } finally {
            forbidden.shutdown()
        }
    }

    @Test
    fun resolveFailureShowsFixedMessageWithoutUrl() {
        val fake = FakeResolver()
        managerWith(fake)
        val ch = channel(1, "/live/1.ts").copy(customName = "My One")

        fake.failure = IOException("failed http://secret.example.test/live?token=abc 00:1A:79:00:00:01")
        manager.play(ch)
        assertEquals(PlaybackState.Error(1, "My One: can't open this channel."), manager.state.value)
        assertEquals(0, exo.mediaItemCount)

        fake.failure = StalkerLinkException("limit")
        manager.play(ch) // same channel in Error → retries
        assertEquals(
            PlaybackState.Error(1, "My One: the portal says too many connections are in use. Try again in a moment."),
            manager.state.value,
        )

        fake.failure = StalkerAuthException("Authorization failed for http://portal.example.test")
        manager.play(ch)
        assertEquals(PlaybackState.Error(1, "My One: the portal refused the login."), manager.state.value)

        fake.failure = StalkerLinkException("nothing_to_play")
        manager.play(ch)
        assertEquals(PlaybackState.Error(1, "My One: can't open this channel."), manager.state.value)
        assertEquals(0, fake.keepAlives)
    }

    @Test
    fun watchdogStopsOnStopAndOnOtherChannel() {
        val fake = FakeResolver()
        managerWith(fake)
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        assertEquals(1, fake.activeKeepAlives)

        manager.play(channel(2, "/live/2.ts"))
        idleMain()
        assertEquals(2, fake.keepAlives)
        assertEquals(1, fake.activeKeepAlives)

        manager.stop()
        idleMain()
        assertEquals(0, fake.activeKeepAlives)

        // A resolve still in progress is cancelled by stop() too.
        fake.gate = CompletableDeferred()
        manager.play(channel(3, "/live/3.ts"))
        manager.stop()
        fake.gate!!.complete(Unit)
        idleMain()
        assertEquals(0, exo.mediaItemCount)
        assertEquals(PlaybackState.Idle, manager.state.value)
        assertEquals(2, fake.keepAlives)

        // On a portal, stop() during the debounce means create_link is never called.
        val portal = FakePortal()
        val portalServer = MockWebServer().also { it.dispatcher = portal; it.start() }
        try {
            managerWith(portalResolver(portal, portalServer, mutableListOf()))
            manager.play(portalChannel(21))
            idleMain(200)
            manager.stop()
            idleMain(2_000)
            assertEquals(0, portal.count("create_link"))
            assertEquals(0, exo.mediaItemCount)
            assertEquals(PlaybackState.Idle, manager.state.value)
        } finally {
            portalServer.shutdown()
        }
    }

    @Test
    fun nonStalkerPlaysWithoutDelay() {
        val delays = mutableListOf<Long>()
        val playlist = PlaylistEntity(
            id = 1, name = "List", type = PlaylistType.M3U, url = "http://example.test/l.m3u", xtreamServer = null,
            xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
            lastError = null, userAgent = "PlaylistUA",
        )
        val unused = StalkerClient(testHttp())
        managerWith(
            PlaylistStreamResolver(
                // Suspends like Room's byId does in the app.
                { yield(); playlist }, { AppSettings(userAgent = "GeneralUA") }, "Loophole/test",
                StalkerSessions(unused, { null }) { _, _, _ -> }, unused,
                delayMs = { ms -> delays += ms; delay(ms) },
            ),
        )

        manager.play(channel(1, "/live/1.ts"))
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        idleMain() // no clock advance: no debounce for a plain playlist

        assertEquals(1, exo.mediaItemCount)
        assertEquals(1, prepareCount)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        assertTrue(delays.isEmpty())
        RobolectricUtil.runMainLooperUntil { server.requestCount > 0 }
        assertEquals("PlaylistUA", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun hlsFallbackUsesResolvedUrl() {
        val hls = serve { request ->
            if (request.path == "/stream/7") {
                MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nseg0.ts\n")
            } else {
                MockResponse().setBody(Buffer().write(ByteArray(188 * 10)))
            }
        }
        try {
            val fake = FakeResolver { hls.url("/stream/7").toString() }
            managerWith(fake)
            manager.play(channel(7, "/unused").copy(streamUrl = "ffmpeg http://raw.example.test/never"))

            RobolectricUtil.runMainLooperUntil { hls.requestCount >= 2 }

            assertEquals("/stream/7", hls.takeRequest().path)
            val retry = hls.takeRequest()
            assertEquals("/stream/7", retry.path)
            assertEquals("UA-R", retry.getHeader("User-Agent"))
            assertEquals(MimeTypes.APPLICATION_M3U8, exo.currentMediaItem!!.localConfiguration!!.mimeType)
            assertEquals(Uri.parse(hls.url("/stream/7").toString()), exo.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(listOf(7L), fake.resolved)
        } finally {
            hls.shutdown()
        }
    }

    @Test
    fun staleResolveThatIgnoresCancellationIsDropped() {
        val fake = FakeResolver()
        managerWith(fake)
        val gateA = CompletableDeferred<Unit>()
        fake.gate = gateA
        fake.ignoreCancellation = true
        manager.play(channel(1, "/live/1.ts"))
        fake.gate = null
        fake.ignoreCancellation = false
        manager.play(channel(2, "/live/2.ts"))
        assertEquals("2", exo.currentMediaItem!!.mediaId)

        idleMain() // A resumes, swallows its cancellation and returns a stream
        gateA.complete(Unit)
        idleMain()
        assertEquals(listOf(1L, 2L), fake.resolved)

        assertEquals(1, exo.mediaItemCount)
        assertEquals("2", exo.currentMediaItem!!.mediaId)
        assertEquals(1, prepareCount)
        assertEquals(PlaybackState.Buffering(2), manager.state.value)
        assertEquals(1, fake.keepAlives)
    }

    @Test
    fun remintKeepsHlsAfterFallback() {
        val hls = serve { request ->
            if (request.path == "/stream/7") {
                MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nseg0.ts\n")
            } else {
                MockResponse().setBody(Buffer().write(ByteArray(188 * 10)))
            }
        }
        try {
            val fake = FakeResolver { hls.url("/stream/7").toString() }
            fake.remint = true
            managerWith(fake)
            manager.play(channel(7, "/unused"))
            RobolectricUtil.runMainLooperUntil {
                exo.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8
            }
            val prepares = prepareCount

            val forbidden = HttpDataSource.InvalidResponseCodeException(
                403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse(hls.url("/stream/7").toString())), ByteArray(0),
            )
            val error = ExoPlaybackException.createForSource(forbidden, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            seam.fakeError = error
            seam.listeners.forEach { it.onPlayerError(error) }

            // The fresh link opens as HLS straight away, not as progressive first.
            assertEquals(listOf(7L, 7L), fake.resolved)
            assertEquals(prepares + 1, prepareCount)
            assertEquals(MimeTypes.APPLICATION_M3U8, exo.currentMediaItem!!.localConfiguration!!.mimeType)
        } finally {
            hls.shutdown()
        }
    }

    @Test
    fun behindLiveWindowDoesNotResolveAgain() {
        val fake = FakeResolver()
        fake.remint = true
        managerWith(fake)
        manager.play(channel(1, "/live/1.m3u8"))
        behindLiveWindow()
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(2, prepareCount)
    }
}
