package io.github.loopholetv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.source.MediaSource
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.loopholetv.data.repo.ChannelPlaybackPrefs
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.DecoderChoice
import io.github.loopholetv.data.settings.Passthrough
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.player.catchup.PlayMode
import io.github.loopholetv.player.engine.EngineParts
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import android.os.SystemClock
import java.net.SocketException
import java.time.Instant
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
    var fakeTracks: Tracks? = null
    /** Ordered log of stop/prepare calls; tests may append their own events to it. */
    val events = mutableListOf<String>()
    var onPrepare: () -> Unit = {}
    var lastSource: MediaSource? = null
    /** When set, plays this instead of the source the manager built (a known timeline). */
    var substituteSource: MediaSource? = null
    val seeks = mutableListOf<Long>()
    /** Start positions given with a media source (null: the default position). */
    val startPositions = mutableListOf<Long?>()
    var fakePosition: Long? = null
    var onStop: () -> Unit = {}

    override fun prepare() {
        prepareCount++
        events += "prepare"
        onPrepare()
        fakeError = null
        fakeState = null
        real.prepare()
    }

    override fun stop() {
        events += "stop"
        onStop()
        real.stop()
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        lastSource = mediaSource
        startPositions += null
        real.setMediaSource(substituteSource ?: mediaSource)
    }

    override fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long) {
        lastSource = mediaSource
        startPositions += startPositionMs
        real.setMediaSource(substituteSource ?: mediaSource, startPositionMs)
    }

    override fun getCurrentPosition(): Long = fakePosition ?: real.currentPosition

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        real.seekTo(positionMs)
    }

    override fun getCurrentTracks(): Tracks = fakeTracks ?: real.currentTracks

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
    /** The archive URL for a request; null means the channel can't play that time. */
    var archiveUrlFor: (ChannelEntity, ArchiveRequest) -> String? = { ch, _ -> ch.streamUrl }
    val archiveCalls = mutableListOf<Pair<Long, ArchiveRequest>>()
    var archiveFailure: Throwable? = null
    var onResolveArchive: () -> Unit = {}
    /** Holds [resolveArchive] until completed. */
    var archiveGate: CompletableDeferred<Unit>? = null

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

    override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? {
        onResolveArchive()
        archiveCalls += channel.id to request
        archiveGate?.await()
        archiveFailure?.let { throw it }
        return archiveUrlFor(channel, request)?.let { ResolvedStream(it, "UA-R", emptyMap()) }
    }

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

    // ---- Engine, settings, tracks and hand-off ----

    private lateinit var engine: EngineParts
    private val settingsFlow = MutableStateFlow(PlaybackSettings())
    private var settingsGate: CompletableDeferred<Unit>? = null
    private var prefs = ChannelPlaybackPrefs.NONE
    /** What a channel-prefs observer sees; tests set it together with [prefs] (a saved edit). */
    private val prefsFlow = MutableStateFlow(ChannelPlaybackPrefs.NONE)

    /** Saves [p] for the channel, as an edit from any screen does: the read and the observer both see it. */
    private fun saveChannelPrefs(p: ChannelPlaybackPrefs) {
        prefs = p
        prefsFlow.value = p
    }
    /** When set, the play-time read returns this instead of the live flow's value (a lagging snapshot). */
    private var staleSnapshot: PlaybackSettings? = null

    /** A manager wired to [engine], [settingsFlow] (or [settings]), an optional [settingsGate] and [prefs]. */
    private fun engineManager(
        resolver: FakeResolver = FakeResolver(),
        settings: kotlinx.coroutines.flow.Flow<PlaybackSettings> = settingsFlow,
    ): FakeResolver {
        manager.release()
        engine = EngineParts()
        exo = TestExoPlayerBuilder(context).setLoadControl(engine.loadControl).build()
        seam = SeamPlayer(exo)
        manager = PlaybackManager(
            context, OkHttpClient(), seam, clock = { nowMs }, resolver = resolver,
            engine = engine,
            settings = settings,
            awaitSettings = { settingsGate?.await(); staleSnapshot ?: settingsFlow.value },
            channelPrefs = { prefs },
            observeChannelPrefs = { prefsFlow },
            // Runs on the main looper in call order; a set gate holds the open inside its eviction.
            evictIdleConnections = {
                seam.events += "evict"
                evictGate?.await()
            },
            retryDelay = { ms -> retryDelays += ms; delay(ms) },
        )
        return resolver
    }

    private val retryDelays = mutableListOf<Long>()

    /** When set, each eviction suspends until it is completed. */
    private var evictGate: CompletableDeferred<Unit>? = null

    private val params get() = exo.trackSelectionParameters as DefaultTrackSelector.Parameters

    private fun audioGroup(id: String, vararg langs: String) = Tracks.Group(
        TrackGroup(
            id,
            *langs.map { lang ->
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(lang).setChannelCount(2).build()
            }.toTypedArray(),
        ),
        false,
        IntArray(langs.size) { C.FORMAT_HANDLED },
        BooleanArray(langs.size) { it == 0 },
    )

    /** Reports READY through the listeners so the manager's state becomes Playing. */
    private fun reportReady() {
        seam.fakeState = Player.STATE_READY
        seam.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        assertTrue(manager.state.value is PlaybackState.Playing)
    }

    @Test
    fun firstPlayAwaitsSettings() {
        settingsGate = CompletableDeferred()
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        assertEquals(0, prepareCount)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)

        settingsGate!!.complete(Unit)
        idleMain()
        assertEquals(1, prepareCount)
    }

    @Test
    fun channelDecoderOverrideAppliedBeforeLoad() {
        prefs = ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE)
        engineManager()
        var atPrepare: Pair<DecoderChoice, DecoderChoice>? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.video to engine.codecPolicy.audio }
        manager.play(channel(1, "/live/1.ts"))
        assertEquals(DecoderChoice.SOFTWARE to DecoderChoice.HARDWARE, atPrepare)
    }

    @Test
    fun settingsDecoderUsedWhenNoOverride() {
        settingsFlow.value = PlaybackSettings(videoDecoder = DecoderChoice.SOFTWARE, audioDecoder = DecoderChoice.SOFTWARE)
        engineManager()
        var atPrepare: Pair<DecoderChoice, DecoderChoice>? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.video to engine.codecPolicy.audio }
        manager.play(channel(1, "/live/1.ts"))
        assertEquals(DecoderChoice.SOFTWARE to DecoderChoice.SOFTWARE, atPrepare)

        prefs = ChannelPlaybackPrefs(audio = DecoderChoice.HARDWARE)
        manager.play(channel(2, "/live/2.ts"))
        assertEquals(DecoderChoice.SOFTWARE to DecoderChoice.HARDWARE, atPrepare)
    }

    @Test
    fun passthroughAndDtsAppliedBeforeLoad() {
        settingsFlow.value = PlaybackSettings(passthrough = Passthrough.OFF, decodeDtsToPcm = true)
        engineManager()
        var atPrepare: Pair<Passthrough, Boolean>? = null
        seam.onPrepare = { atPrepare = engine.sinkPolicy.passthrough to engine.sinkPolicy.decodeDtsToPcm }
        manager.play(channel(1, "/live/1.ts"))
        assertEquals(Passthrough.OFF to true, atPrepare)
    }

    @Test
    fun bufferPresetSetBeforeLoad() {
        settingsFlow.value = PlaybackSettings(bufferPreset = BufferPreset.LARGE)
        engineManager()
        var pendingAtPrepare: BufferPreset? = null
        seam.onPrepare = { pendingAtPrepare = engine.loadControl.pendingPreset }
        manager.play(channel(1, "/live/1.ts"))
        assertEquals(BufferPreset.LARGE, pendingAtPrepare)
        RobolectricUtil.runMainLooperUntil { engine.loadControl.activePreset == BufferPreset.LARGE }
    }

    @Test
    fun mediaSourceWrappedForAudioDelay() {
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        assertTrue(seam.lastSource is AudioDelayMediaSource)
    }

    @Test
    fun audioDelayFollowsSettingsLive() {
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        val stops = seam.events.count { it == "stop" }

        settingsFlow.value = settingsFlow.value.copy(audioDelayMs = 200)
        idleMain()

        assertEquals(200, engine.audioDelay.delayMs)
        assertEquals(stops, seam.events.count { it == "stop" })
        assertEquals(1, prepareCount)
        assertTrue(manager.state.value is PlaybackState.Playing)
    }

    @Test
    fun setAudioDelayAppliesWithoutRestart() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()

        manager.setAudioDelay(237)
        assertEquals(250, engine.audioDelay.delayMs)
        manager.setAudioDelay(5_000)
        assertEquals(1_000, engine.audioDelay.delayMs)
        idleMain()

        assertEquals(1, prepareCount)
        assertEquals(listOf(1L), fake.resolved)
        assertTrue(manager.state.value is PlaybackState.Playing)
    }

    @Test
    fun zapClearsTrackOverridesKeepsPreferences() {
        settingsFlow.value = PlaybackSettings(audioLanguage = "de")
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        seam.fakeTracks = Tracks(listOf(audioGroup("a", "de", "en")))
        manager.selectTrack(TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
        assertEquals(1, params.overrides.size)

        seam.fakeTracks = null
        manager.play(channel(2, "/live/2.ts"))

        assertTrue(params.overrides.isEmpty())
        assertEquals(listOf("de"), params.preferredAudioLanguages)
    }

    @Test
    fun tracksFlowUpdatesWhenGroupsArriveLate() {
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        assertTrue(manager.tracks.value.audio.isEmpty())

        val late = Tracks(listOf(audioGroup("a", "en", "de")))
        seam.fakeTracks = late
        seam.listeners.forEach { it.onTracksChanged(late) }

        assertEquals(2, manager.tracks.value.audio.size)

        manager.stop()
        assertEquals(PlayerTracks.NONE, manager.tracks.value)
    }

    @Test
    fun zapClearsTracksUntilNewTracksArrive() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        val a = Tracks(listOf(audioGroup("a", "en", "de")))
        seam.fakeTracks = a
        seam.listeners.forEach { it.onTracksChanged(a) }
        assertEquals(2, manager.tracks.value.audio.size)

        fake.gate = CompletableDeferred()
        manager.play(channel(2, "/live/2.ts"))
        assertEquals(PlayerTracks.NONE, manager.tracks.value)
        // A late event from the previous stream while the new one resolves publishes nothing.
        seam.listeners.forEach { it.onTracksChanged(a) }
        assertEquals(PlayerTracks.NONE, manager.tracks.value)

        fake.gate!!.complete(Unit)
        idleMain()
        val b = Tracks(listOf(audioGroup("b", "fr")))
        seam.fakeTracks = b
        seam.listeners.forEach { it.onTracksChanged(b) }
        assertEquals(1, manager.tracks.value.audio.size)
    }

    @Test
    fun engineSettingChangeWhilePlayingRestartsOnce() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        var seenAtResolve: Pair<Int, Int>? = null
        fake.onResolve = { seenAtResolve = exo.playbackState to exo.mediaItemCount }
        seam.events.clear()

        settingsFlow.value = settingsFlow.value.copy(videoDecoder = DecoderChoice.SOFTWARE)
        idleMain()

        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(2, prepareCount)
        // The old stream was released before the new link was resolved.
        assertEquals(Player.STATE_IDLE to 0, seenAtResolve)
        assertEquals("prepare", seam.events.last())
        assertEquals(1, seam.events.count { it == "prepare" })
        assertEquals(DecoderChoice.SOFTWARE, engine.codecPolicy.video)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)

        // A setting that applies live does not restart again.
        settingsFlow.value = settingsFlow.value.copy(audioLanguage = "en")
        idleMain()
        assertEquals(listOf(1L, 1L), fake.resolved)
    }

    @Test
    fun restartUsesTriggeringSettingsWhenSnapshotIsStale() {
        staleSnapshot = PlaybackSettings()
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        var atPrepare: Pair<DecoderChoice, BufferPreset>? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.video to engine.loadControl.pendingPreset }

        settingsFlow.value = settingsFlow.value.copy(videoDecoder = DecoderChoice.SOFTWARE, bufferPreset = BufferPreset.LARGE)
        idleMain()

        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(DecoderChoice.SOFTWARE to BufferPreset.LARGE, atPrepare)
        // What was applied matches the live value, so a later unrelated change does not restart again.
        settingsFlow.value = settingsFlow.value.copy(audioLanguage = "en")
        idleMain()
        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(2, prepareCount)
    }

    @Test
    fun streamStartPrefersLiveSettingsOverStaleSnapshot() {
        staleSnapshot = PlaybackSettings()
        engineManager()
        settingsFlow.value = PlaybackSettings(audioDecoder = DecoderChoice.SOFTWARE)
        idleMain()
        var atPrepare: DecoderChoice? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.audio }

        manager.play(channel(1, "/live/1.ts"))

        assertEquals(DecoderChoice.SOFTWARE, atPrepare)
    }

    @Test
    fun endedSettingsFlowFallsBackToPlayTimeRead() {
        // The live updates stopped after a read error (caught upstream): the last value is frozen.
        val ended = kotlinx.coroutines.flow.flow {
            emit(PlaybackSettings(bufferPreset = BufferPreset.LARGE))
            throw IOException("settings unreadable")
        }.catch { }
        engineManager(settings = ended)
        idleMain()
        settingsFlow.value = PlaybackSettings(bufferPreset = BufferPreset.SMALL)
        var atPrepare: BufferPreset? = null
        seam.onPrepare = { atPrepare = engine.loadControl.pendingPreset }

        manager.play(channel(1, "/live/1.ts"))

        assertEquals(BufferPreset.SMALL, atPrepare)
    }

    @Test
    fun failingSettingsFlowNeverCrashesAndPlaysWithPlayTimeRead() {
        val failing = kotlinx.coroutines.flow.flow<PlaybackSettings> {
            emit(PlaybackSettings(audioDecoder = DecoderChoice.SOFTWARE))
            throw IOException("settings unreadable")
        }
        engineManager(settings = failing)
        idleMain()
        var atPrepare: DecoderChoice? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.audio }

        manager.play(channel(1, "/live/1.ts"))

        assertEquals(DecoderChoice.HARDWARE, atPrepare)
        assertEquals(1, prepareCount)
    }

    @Test
    fun engineRestartKeepsTrackPicks() {
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        seam.fakeTracks = Tracks(listOf(audioGroup("a", "de", "en")))
        manager.selectTrack(TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
        val picked = params.overrides

        settingsFlow.value = settingsFlow.value.copy(passthrough = Passthrough.OFF)
        idleMain()

        assertEquals(2, prepareCount)
        assertEquals(picked, params.overrides)
    }

    @Test
    fun restartOfThePlayingChannelKeepsTrackPicks() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        seam.fakeTracks = Tracks(listOf(audioGroup("a", "de", "en")))
        manager.selectTrack(TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
        val picked = params.overrides

        manager.restart(channel(1, "/live/1.ts"))
        idleMain()

        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(2, prepareCount)
        assertEquals(picked, params.overrides)
    }

    @Test
    fun decoderEditOnPreviewingChannelRestartsOnceAndFullScreenKeepsIt() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        idleMain()
        reportReady()
        var atPrepare: DecoderChoice? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.video }

        // Edited from the guide's or the channel editor's options while it previews.
        saveChannelPrefs(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE))
        idleMain()

        assertEquals("one reconnect", listOf(1L, 1L), fake.resolved)
        assertEquals(DecoderChoice.SOFTWARE, atPrepare)
        reportReady()

        // Opening it full screen resumes the same stream, with the new decoder already in use.
        manager.play(ch)
        idleMain()
        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(2, prepareCount)
        assertEquals(DecoderChoice.SOFTWARE, engine.codecPolicy.video)
    }

    @Test
    fun decoderEditBeforeFirstStartIsReadByTheStartWithoutARestart() {
        saveChannelPrefs(ChannelPlaybackPrefs(audio = DecoderChoice.SOFTWARE))
        val fake = engineManager()
        var atPrepare: DecoderChoice? = null
        seam.onPrepare = { atPrepare = engine.codecPolicy.audio }
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        assertEquals(DecoderChoice.SOFTWARE, atPrepare)
        assertEquals(listOf(1L), fake.resolved)
    }

    @Test
    fun decoderEditOnChannelShowingAnErrorRestartsIt() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        seam.fakeState = Player.STATE_ENDED
        seam.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        assertTrue(manager.state.value is PlaybackState.Error)

        saveChannelPrefs(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE))
        idleMain()

        assertEquals(listOf(1L, 1L), fake.resolved)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
    }

    @Test
    fun nonDecoderEditOrStoppedChannelDoesNotRestart() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        saveChannelPrefs(ChannelPlaybackPrefs(aspect = io.github.loopholetv.data.settings.AspectMode.ZOOM, externalPlayer = "ask"))
        idleMain()
        assertEquals(listOf(1L), fake.resolved)

        manager.stop()
        saveChannelPrefs(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE))
        idleMain()
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(PlaybackState.Idle, manager.state.value)
    }

    @Test
    fun remintKeepsTrackOverrides() {
        val forbidden = serve { MockResponse().setResponseCode(403) }
        try {
            val fake = FakeResolver { forbidden.url("/live/${it.id}.ts").toString() }
            fake.remint = true
            engineManager(fake)
            manager.play(channel(1, "/unused"))
            seam.fakeTracks = Tracks(listOf(audioGroup("a", "de", "en")))
            manager.selectTrack(TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))
            assertEquals(1, params.overrides.size)

            RobolectricUtil.runMainLooperUntil { fake.resolved.size == 2 }
            idleMain()

            assertEquals(1, params.overrides.size)
        } finally {
            forbidden.shutdown()
        }
    }

    @Test
    fun handOffReturnsNullWhenPlayStartsDuringResolve() {
        val fake = engineManager()
        val handOffGate = CompletableDeferred<Unit>()
        fake.gate = handOffGate
        var done = false
        var result: Handoff? = null
        kotlinx.coroutines.MainScope().launch {
            result = manager.handOff(channel(2, "/live/2.ts"))
            done = true
        }
        RobolectricUtil.runMainLooperUntil { fake.resolved.isNotEmpty() }

        // The user starts another channel while the hand-off link is still resolving.
        fake.gate = null
        manager.play(channel(3, "/live/3.ts"))
        handOffGate.complete(Unit)
        RobolectricUtil.runMainLooperUntil { done }

        assertNull(result)
        assertEquals(PlaybackState.Buffering(3), manager.state.value)
        assertEquals("3", exo.currentMediaItem!!.mediaId)
    }

    @Test
    fun firstSettingsEmissionWhilePlayingDoesNotRestart() {
        settingsFlow.value = PlaybackSettings(bufferPreset = BufferPreset.SMALL)
        settingsGate = CompletableDeferred()
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain() // the collector sees its first value while the play waits for settings
        assertEquals(0, prepareCount)

        settingsGate!!.complete(Unit)
        idleMain()

        assertEquals(listOf(1L), fake.resolved)
        assertEquals(1, prepareCount)
        assertEquals(BufferPreset.SMALL, engine.loadControl.pendingPreset)
    }

    @Test
    fun languageChangeWhilePlayingDoesNotRestart() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        reportReady()
        seam.fakeTracks = Tracks(listOf(audioGroup("a", "de", "en")))
        manager.selectTrack(TrackType.AUDIO, TrackChoice.Pick(TrackId("a", 1)))

        settingsFlow.value = settingsFlow.value.copy(audioLanguage = "fr", subtitleLanguage = "de", preferSurround = false)
        idleMain()

        assertEquals(listOf(1L), fake.resolved)
        assertEquals(1, prepareCount)
        assertEquals(listOf("fr"), params.preferredAudioLanguages)
        assertEquals(listOf("de"), params.preferredTextLanguages)
        assertEquals(2, params.maxAudioChannelCount)
        // The current pick survives a live preference change.
        assertEquals(1, params.overrides.size)
    }

    @Test
    fun engineChangeWhileIdleDoesNotPlay() {
        val fake = engineManager()
        settingsFlow.value = settingsFlow.value.copy(bufferPreset = BufferPreset.LARGE)
        idleMain()
        assertTrue(fake.resolved.isEmpty())
        assertEquals(PlaybackState.Idle, manager.state.value)

        manager.play(channel(1, "/live/1.ts"))
        manager.stop()
        settingsFlow.value = settingsFlow.value.copy(videoDecoder = DecoderChoice.SOFTWARE)
        idleMain()
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(PlaybackState.Idle, manager.state.value)
        assertEquals(0, exo.mediaItemCount)
    }

    @Test
    fun tunnelingOnlyWithFullScreenSurfaceAndSetting() {
        settingsFlow.value = PlaybackSettings(tunneling = true)
        engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        assertFalse(params.tunnelingEnabled)

        manager.setFullScreenSurface(true)
        assertTrue(params.tunnelingEnabled)

        manager.play(channel(2, "/live/2.ts"))
        assertTrue(params.tunnelingEnabled)

        settingsFlow.value = settingsFlow.value.copy(tunneling = false)
        idleMain()
        assertFalse(params.tunnelingEnabled)

        settingsFlow.value = settingsFlow.value.copy(tunneling = true)
        idleMain()
        assertTrue(params.tunnelingEnabled)
        manager.setFullScreenSurface(false)
        assertFalse(params.tunnelingEnabled)
    }

    @Test
    fun softwareAudioFailureRetriesWithHardwareOnce() {
        settingsFlow.value = PlaybackSettings(audioDecoder = DecoderChoice.SOFTWARE)
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        fun decodeError() = ExoPlaybackException.createForRenderer(
            IllegalStateException("decode"), "FfmpegAudioRenderer", 1, audio, C.FORMAT_HANDLED, false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        var error = decodeError()
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }

        assertEquals(DecoderChoice.HARDWARE, engine.codecPolicy.audio)
        assertEquals(2, prepareCount)
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)

        error = decodeError()
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }
        assertTrue(manager.state.value is PlaybackState.Error)
        assertEquals(2, prepareCount)

        // A new play follows Settings again.
        manager.play(channel(2, "/live/2.ts"))
        assertEquals(DecoderChoice.SOFTWARE, engine.codecPolicy.audio)
    }

    @Test
    fun handOffStopsAndEvictsBeforeResolve() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        seam.events.clear()
        fake.onResolve = { seam.events += "resolve" }

        val handoff = runBlocking { manager.handOff(channel(2, "/live/2.ts")) }
        idleMain()

        assertEquals(listOf("stop", "evict", "resolve", "evict"), seam.events)
        assertEquals(server.url("/live/2.ts").toString(), handoff!!.stream.url)
        assertFalse(handoff.hls)
        assertEquals(PlaybackState.Idle, manager.state.value)
        assertEquals(0, exo.mediaItemCount)
        assertEquals(1, prepareCount)
        assertEquals(1, fake.keepAlives)
    }

    @Test
    fun handOffResolveFailureReturnsNull() {
        val fake = engineManager()
        fake.failure = IOException("no link")
        assertNull(runBlocking { manager.handOff(channel(2, "/live/2.ts")) })
        assertEquals(PlaybackState.Idle, manager.state.value)
        assertEquals(0, prepareCount)
    }

    @Test
    fun handOffHlsFlagFromUrl() {
        engineManager(FakeResolver { "http://example.test/live/index.m3u8" })
        val handoff = runBlocking { manager.handOff(channel(2, "/unused")) }
        assertTrue(handoff!!.hls)
        assertEquals(0, prepareCount)
    }

    // ---- Archive playback and switch retries ----

    private val programme = ArchiveRequest(Instant.parse("2026-11-09T20:00:00Z"), Instant.parse("2026-11-09T21:00:00Z"))
    private val laterProgramme = ArchiveRequest(Instant.parse("2026-11-09T21:00:00Z"), Instant.parse("2026-11-09T22:00:00Z"))

    private fun awaitPrepares(n: Int) = RobolectricUtil.runMainLooperUntil { prepareCount >= n }

    private fun failWith(error: ExoPlaybackException) {
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }
    }

    private fun httpError(code: Int): ExoPlaybackException {
        val cause = HttpDataSource.InvalidResponseCodeException(
            code, "status", null, emptyMap(), DataSpec(Uri.parse("http://example.test/x")), ByteArray(0),
        )
        return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    private fun resetError(): ExoPlaybackException {
        val cause = HttpDataSource.HttpDataSourceException(
            SocketException("Connection reset"), DataSpec(Uri.parse("http://example.test/x")),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
        return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    }

    private fun reportEnded() {
        seam.fakeState = Player.STATE_ENDED
        seam.listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
    }

    @Test
    fun archiveSwitchStopsBeforeResolve() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        idleMain()
        assertEquals(1, prepareCount)
        seam.events.clear()
        var seen: Pair<Int, Int>? = null
        fake.onResolveArchive = {
            seam.events += "resolveArchive"
            seen = seam.playbackState to exo.mediaItemCount
        }

        manager.playArchive(ch, programme)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        assertEquals(PlayMode.Archive(1, programme), manager.mode.value)
        awaitPrepares(2)

        assertEquals(listOf("stop", "evict", "resolveArchive", "evict", "prepare"), seam.events)
        assertEquals(Player.STATE_IDLE to 0, seen)
        assertEquals(1, exo.mediaItemCount)
        assertEquals(listOf(1L to programme), fake.archiveCalls)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
    }

    @Test
    fun playArchiveSameChannelReconnects() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.playArchive(ch, programme)
        awaitPrepares(1)
        seam.events.clear()
        fake.onResolveArchive = { seam.events += "resolveArchive" }

        manager.playArchive(ch, programme)
        awaitPrepares(2)

        assertEquals(listOf(1L to programme, 1L to programme), fake.archiveCalls)
        assertEquals(listOf("stop", "evict", "resolveArchive", "evict", "prepare"), seam.events)
        assertEquals(1, exo.mediaItemCount)
    }

    @Test
    fun playLiveFromArchiveReconnects() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.playArchive(ch, programme)
        awaitPrepares(1)
        reportReady()
        seam.events.clear()
        fake.onResolve = { seam.events += "resolve" }

        manager.play(ch)
        assertEquals(PlayMode.Live, manager.mode.value)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        awaitPrepares(2)

        // The archive stream is released, and its idle connections closed, before the live link is resolved.
        assertEquals(listOf("stop", "evict", "resolve", "prepare"), seam.events)
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(1, exo.mediaItemCount)
        assertEquals(Uri.parse(ch.streamUrl), exo.currentMediaItem!!.localConfiguration!!.uri)

        // Live again: the same channel is a no-op once more.
        manager.play(ch)
        idleMain()
        assertEquals(listOf(1L), fake.resolved)
        assertEquals(2, prepareCount)
    }

    @Test
    fun archiveResolveNullIsError() {
        val fake = engineManager()
        fake.archiveUrlFor = { _, _ -> null }
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }

        assertEquals(PlaybackState.Error(1, "Couldn't play this programme back."), manager.state.value)
        assertEquals(0, exo.mediaItemCount)
        assertEquals(0, prepareCount)
    }

    @Test
    fun archiveResolveFailureIsErrorWithoutUrl() {
        val fake = engineManager()
        fake.archiveFailure = IOException("failed http://secret.example.test/timeshift/u/p/60/2026-11-09:20-00/1.ts")
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { manager.state.value is PlaybackState.Error }

        assertEquals(PlaybackState.Error(1, "Couldn't play this programme back."), manager.state.value)
        assertEquals(0, exo.mediaItemCount)
    }

    @Test
    fun archiveEndIsNotError() {
        engineManager()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)
        reportEnded()

        assertTrue((manager.mode.value as PlayMode.Archive).ended)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)

        // A live stream that ends is still an error.
        manager.play(channel(2, "/live/2.ts"))
        awaitPrepares(2)
        reportEnded()
        assertEquals(PlayMode.Live, manager.mode.value)
        assertEquals(PlaybackState.Error(2, "Channel 2: can't play this stream"), manager.state.value)
    }

    @Test
    fun archiveHlsUrlLoadsAsHlsAndIsMarked() {
        val fake = engineManager()
        fake.archiveUrlFor = { ch, _ -> ch.streamUrl.replace("/live/1.ts", "/timeshift/1/index.m3u8") }
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)

        assertEquals(MimeTypes.APPLICATION_M3U8, exo.currentMediaItem!!.localConfiguration!!.mimeType)
        assertTrue((manager.mode.value as PlayMode.Archive).hls)

        fake.archiveUrlFor = { ch, _ -> ch.streamUrl }
        manager.playArchive(channel(1, "/live/1.ts"), laterProgramme)
        awaitPrepares(2)
        assertNull(exo.currentMediaItem!!.localConfiguration!!.mimeType)
        assertFalse((manager.mode.value as PlayMode.Archive).hls)
        assertEquals(laterProgramme, (manager.mode.value as PlayMode.Archive).request)
    }

    @Test
    fun firstOpenAfterSwitchRetriesWithBackoff() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        manager.play(channel(2, "/live/2.ts"))
        idleMain()
        val base = prepareCount
        val states = mutableListOf<PlaybackState>()

        failWith(httpError(509))
        states += manager.state.value
        idleMain(500)
        awaitPrepares(base + 1)
        states += manager.state.value

        failWith(httpError(509))
        states += manager.state.value
        idleMain(1_000)
        awaitPrepares(base + 2)
        states += manager.state.value
        reportReady()

        // Three loads of channel 2: the first open and two retries.
        assertEquals(base + 2, prepareCount)
        assertEquals(listOf(500L, 1_000L), retryDelays)
        assertTrue(states.all { it == PlaybackState.Buffering(2) })
        assertEquals(listOf(1L, 2L, 2L, 2L), fake.resolved)
        assertEquals(1, exo.mediaItemCount)
        // Only the stream playing now keeps its keep-alive.
        idleMain()
        assertEquals(1, fake.activeKeepAlives)
    }

    @Test
    fun connectionResetAfterSwitchRetries() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        manager.play(channel(2, "/live/2.ts"))
        idleMain()

        failWith(resetError())
        assertEquals(PlaybackState.Buffering(2), manager.state.value)
        idleMain(500)
        awaitPrepares(3)
        assertEquals(listOf(1L, 2L, 2L), fake.resolved)
    }

    @Test
    fun backoffGivesUpAfterFourRetries() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        idleMain()
        manager.playArchive(ch, programme)
        awaitPrepares(2)
        val started = SystemClock.uptimeMillis()

        val delays = listOf(500L, 1_000L, 2_000L, 4_000L)
        delays.forEachIndexed { i, ms ->
            failWith(httpError(503))
            assertEquals(PlaybackState.Buffering(1), manager.state.value)
            idleMain(ms)
            awaitPrepares(3 + i)
        }
        failWith(httpError(503))

        assertEquals(PlaybackState.Error(1, "Couldn't play this programme back."), manager.state.value)
        assertEquals(delays, retryDelays)
        assertTrue(SystemClock.uptimeMillis() - started >= 7_500)
        assertEquals(5, fake.archiveCalls.size)
        idleMain(10_000)
        assertEquals(5, fake.archiveCalls.size)
        assertEquals(6, prepareCount)
    }

    @Test
    fun noRetryWhenNotAfterSwitch() {
        val fake = engineManager()
        // The first open of the session: nothing was released before it.
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        failWith(httpError(403))
        assertEquals(PlaybackState.Error(1, "Channel 1: access denied by provider"), manager.state.value)
        idleMain(10_000)
        assertEquals(listOf(1L), fake.resolved)

        // An open long after the last release does not retry either.
        manager.play(channel(2, "/live/2.ts"))
        idleMain()
        reportReady()
        manager.stop()
        nowMs += 11_000
        manager.play(channel(3, "/live/3.ts"))
        idleMain()
        failWith(httpError(509))
        assertTrue(manager.state.value is PlaybackState.Error)
        assertTrue(retryDelays.isEmpty())
    }

    @Test
    fun remintComesBeforeSwitchRetry() {
        val fake = engineManager()
        fake.remint = true
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        manager.play(channel(2, "/live/2.ts"))
        idleMain()

        failWith(httpError(403))
        idleMain()
        assertEquals(listOf(1L, 2L, 2L), fake.resolved)
        assertTrue(retryDelays.isEmpty())

        // A re-minted portal link that is refused again is an error, not a backoff.
        failWith(httpError(403))
        assertEquals(PlaybackState.Error(2, "Channel 2: access denied by provider"), manager.state.value)
        assertTrue(retryDelays.isEmpty())
    }

    @Test
    fun seekableArchiveReportsDuration() {
        engineManager()
        seam.substituteSource = FakeMediaSource(FakeTimeline(FakeTimeline.TimelineWindowDefinition(true, false, 3_600_000_000L)))
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { (manager.mode.value as PlayMode.Archive).seekable }

        assertEquals(3_600_000L, (manager.mode.value as PlayMode.Archive).durationMs)

        manager.seekWithin(120_000)
        assertEquals(listOf(120_000L), seam.seeks)
    }

    @Test
    fun seekWithinIgnoredWhenNotSeekable() {
        engineManager()
        manager.seekWithin(5_000) // live
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)
        assertFalse((manager.mode.value as PlayMode.Archive).seekable)

        manager.seekWithin(5_000)

        assertTrue(seam.seeks.isEmpty())
    }

    @Test
    fun pauseResumeAndPosition() {
        engineManager()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)

        manager.pause()
        assertFalse(exo.playWhenReady)
        manager.resume()
        assertTrue(exo.playWhenReady)
        assertEquals(exo.currentPosition, manager.positionMs())
    }

    @Test
    fun staleRetryNeverLoads() {
        val fake = engineManager()
        manager.play(channel(1, "/live/1.ts"))
        idleMain()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(2)
        failWith(httpError(503)) // a retry is now pending

        manager.playArchive(channel(2, "/live/2.ts"), programme)
        awaitPrepares(3)
        idleMain(10_000)

        assertEquals(listOf(1L to programme, 2L to programme), fake.archiveCalls)
        assertEquals(3, prepareCount)
        assertEquals("2", exo.currentMediaItem!!.mediaId)
        assertEquals(PlayMode.Archive(2, programme), manager.mode.value)
    }

    @Test
    fun archiveNeverKeepAlives() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.play(ch)
        idleMain()
        assertEquals(1, fake.activeKeepAlives)

        manager.playArchive(ch, programme)
        awaitPrepares(2)
        idleMain(1_000)

        assertEquals(1, fake.keepAlives)
        assertEquals(0, fake.activeKeepAlives)
    }

    @Test
    fun stopReturnsToLiveMode() {
        engineManager()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)
        manager.stop()
        assertEquals(PlayMode.Live, manager.mode.value)
        assertEquals(PlaybackState.Idle, manager.state.value)
    }

    @Test
    fun decoderChangeReopensArchiveAtCurrentPosition() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.playArchive(ch, programme)
        awaitPrepares(1)
        reportReady()
        seam.fakePosition = 90_000

        saveChannelPrefs(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE))
        RobolectricUtil.runMainLooperUntil { prepareCount >= 2 }

        val shifted = programme.copy(from = programme.from.plusSeconds(90))
        assertEquals(listOf(1L to programme, 1L to shifted), fake.archiveCalls)
        assertEquals(shifted, (manager.mode.value as PlayMode.Archive).request)
        assertEquals(DecoderChoice.SOFTWARE, engine.codecPolicy.video)
        assertTrue(fake.resolved.isEmpty())
    }

    @Test
    fun restartInArchiveNeverPublishesLive() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts")
        manager.playArchive(ch, programme)
        awaitPrepares(1)
        seam.fakePosition = 30_000
        val seen = mutableListOf<Pair<PlayMode, PlaybackState>>()
        seam.onStop = { seen += manager.mode.value to manager.state.value }

        manager.restart(ch)

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.first is PlayMode.Archive && it.second != PlaybackState.Idle })
        assertTrue(manager.mode.value is PlayMode.Archive)
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        awaitPrepares(2)
        assertEquals(programme.from.plusSeconds(30), fake.archiveCalls.last().second.from)
        assertEquals(programme.until, fake.archiveCalls.last().second.until)
    }

    private fun softwareAudioFailure() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        failWith(
            ExoPlaybackException.createForRenderer(
                IllegalStateException("decode"), "FfmpegAudioRenderer", 1, audio, C.FORMAT_HANDLED, false,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            ),
        )
    }

    @Test
    fun audioFallbackInSeekableArchiveReleasesFirstAndKeepsPosition() {
        settingsFlow.value = PlaybackSettings(audioDecoder = DecoderChoice.SOFTWARE)
        val fake = engineManager()
        seam.substituteSource = FakeMediaSource(FakeTimeline(FakeTimeline.TimelineWindowDefinition(true, false, 3_600_000_000L)))
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { (manager.mode.value as PlayMode.Archive).seekable }
        seam.fakePosition = 600_000
        seam.events.clear()
        fake.onResolveArchive = { seam.events += "resolveArchive" }

        softwareAudioFailure()
        awaitPrepares(2)

        assertEquals(DecoderChoice.HARDWARE, engine.codecPolicy.audio)
        // One connection: the failed stream is released before the same position is opened again.
        val shifted = programme.copy(from = programme.from.plusSeconds(600))
        assertEquals(listOf(1L to programme, 1L to shifted), fake.archiveCalls)
        assertEquals(listOf("stop", "evict", "resolveArchive", "evict", "prepare"), seam.events)
    }

    @Test
    fun restartOfMinuteArchiveFloorsStartToTheMinute() {
        val fake = engineManager()
        val ch = channel(1, "/live/1.ts").copy(catchupType = "append", catchupSource = "?start={Y}{m}{d}{H}{M}")
        manager.playArchive(ch, programme)
        awaitPrepares(1)
        reportReady()
        seam.fakePosition = 757_000 // 12:37 in

        manager.restart(ch)
        awaitPrepares(2)

        // The URL can only say 20:12, so the request (and every position derived from it) says so too.
        val floored = programme.copy(from = programme.from.plusSeconds(720))
        assertEquals(floored, fake.archiveCalls.last().second)
        assertEquals(floored, (manager.mode.value as PlayMode.Archive).request)
    }

    @Test
    fun audioFallbackInProgressiveArchiveReopensAtPosition() {
        settingsFlow.value = PlaybackSettings(audioDecoder = DecoderChoice.SOFTWARE)
        val fake = engineManager()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)
        seam.fakePosition = 45_000
        seam.events.clear()
        fake.onResolveArchive = { seam.events += "resolveArchive" }

        softwareAudioFailure()
        assertEquals(PlaybackState.Buffering(1), manager.state.value)
        awaitPrepares(2)

        val shifted = programme.copy(from = programme.from.plusSeconds(45))
        assertEquals(listOf(1L to programme, 1L to shifted), fake.archiveCalls)
        assertEquals(listOf("stop", "evict", "resolveArchive", "evict", "prepare"), seam.events)
        // The fallback stays on the hardware decoder for the reopened stream.
        assertEquals(DecoderChoice.HARDWARE, engine.codecPolicy.audio)
    }

    @Test
    fun liveThenImmediateZapStillEvictsBeforeOpening() {
        val fake = engineManager()
        val ch1 = channel(1, "/live/1.ts")
        manager.playArchive(ch1, programme)
        awaitPrepares(1)
        seam.events.clear()
        fake.onResolve = { seam.events += "resolve" }

        // The live open's eviction can't finish before it is replaced.
        val gate = CompletableDeferred<Unit>()
        evictGate = gate
        manager.play(ch1) // goes live, and is replaced while it is still evicting
        assertEquals(listOf("stop", "evict"), seam.events)
        manager.play(channel(2, "/live/2.ts"))
        gate.complete(Unit)
        awaitPrepares(2)
        idleMain()

        assertEquals(listOf(2L), fake.resolved)
        // Both opens evicted; the second did so before resolving.
        assertEquals(listOf("stop", "evict", "stop", "evict", "resolve", "prepare"), seam.events)
        assertEquals("2", exo.currentMediaItem!!.mediaId)
    }

    @Test
    fun stopThenPlayAfterArchiveEvictsFirst() {
        val fake = engineManager()
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        awaitPrepares(1)
        manager.stop()
        seam.events.clear()
        fake.onResolve = { seam.events += "resolve" }

        manager.play(channel(2, "/live/2.ts"))
        awaitPrepares(2)

        assertEquals(listOf("stop", "evict", "resolve", "prepare"), seam.events)
    }

    @Test
    fun zapWhileArchiveEvictingNeverLoadsIt() {
        val fake = engineManager()
        val gate = CompletableDeferred<Unit>()
        evictGate = gate
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        // The archive open is held inside its eviction when the zap comes.
        assertEquals(listOf("stop", "evict"), seam.events)
        manager.play(channel(2, "/live/2.ts"))
        gate.complete(Unit)
        awaitPrepares(1)
        idleMain(1_000)

        assertTrue(fake.archiveCalls.isEmpty())
        assertEquals(listOf(2L), fake.resolved)
        assertEquals(1, prepareCount)
        assertEquals("2", exo.currentMediaItem!!.mediaId)
        assertEquals(PlaybackState.Buffering(2), manager.state.value)
        assertEquals(PlayMode.Live, manager.mode.value)
    }

    @Test
    fun stopWhileArchiveEvictingNeverLoadsIt() {
        val fake = engineManager()
        val gate = CompletableDeferred<Unit>()
        evictGate = gate
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        // The archive open is held inside its eviction when the stop comes.
        assertEquals(listOf("stop", "evict"), seam.events)
        manager.stop()
        gate.complete(Unit)
        idleMain(1_000)

        assertTrue(fake.archiveCalls.isEmpty())
        assertEquals(0, prepareCount)
        assertEquals(PlaybackState.Idle, manager.state.value)
    }

    @Test
    fun zapWhileArchiveResolvingNeverLoadsIt() {
        val fake = engineManager()
        val gate = CompletableDeferred<Unit>()
        fake.archiveGate = gate
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { fake.archiveCalls.size == 1 }
        fake.archiveGate = null

        manager.play(channel(2, "/live/2.ts"))
        gate.complete(Unit)
        awaitPrepares(1)
        idleMain(1_000)

        assertEquals(1, fake.archiveCalls.size)
        assertEquals(1, prepareCount)
        assertEquals("2", exo.currentMediaItem!!.mediaId)
        assertEquals(PlaybackState.Buffering(2), manager.state.value)
    }

    @Test
    fun stopOrReleaseWhileArchiveResolvingNeverLoadsIt() {
        val fake = engineManager()
        var gate = CompletableDeferred<Unit>()
        fake.archiveGate = gate
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { fake.archiveCalls.size == 1 }
        manager.stop()
        gate.complete(Unit)
        idleMain(1_000)
        assertEquals(0, prepareCount)
        assertEquals(PlaybackState.Idle, manager.state.value)

        gate = CompletableDeferred()
        fake.archiveGate = gate
        manager.playArchive(channel(1, "/live/1.ts"), programme)
        RobolectricUtil.runMainLooperUntil { fake.archiveCalls.size == 2 }
        manager.release()
        gate.complete(Unit)
        idleMain(1_000)
        assertEquals(0, prepareCount)
        assertEquals(PlaybackState.Idle, manager.state.value)
    }
}
