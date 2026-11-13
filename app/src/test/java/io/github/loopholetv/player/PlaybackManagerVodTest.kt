package io.github.loopholetv.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.cast.CastHost
import io.github.loopholetv.cast.CastingResolver
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.testHttp
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.catchup.PlayMode
import java.io.EOFException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** Real player plus a seam: records stop/prepare order and start positions, and can fake an error or a state. */
private class VodSeamPlayer(private val real: ExoPlayer) : ExoPlayer by real {
    var prepareCount = 0
    val events = mutableListOf<String>()
    val listeners = mutableListOf<Player.Listener>()
    val startPositions = mutableListOf<Long?>()
    var fakeError: ExoPlaybackException? = null
    var fakeState: Int? = null

    override fun prepare() {
        prepareCount++
        events += "prepare"
        fakeError = null
        fakeState = null
        real.prepare()
    }

    override fun stop() {
        events += "stop"
        real.stop()
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        startPositions += null
        real.setMediaSource(mediaSource)
    }

    override fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long) {
        startPositions += startPositionMs
        real.setMediaSource(mediaSource, startPositionMs)
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        real.addListener(listener)
    }

    override fun getPlayerError(): ExoPlaybackException? = fakeError ?: real.playerError
    override fun getPlaybackState(): Int = fakeState ?: real.playbackState
}

/** Plays stored URLs as they are and counts on-demand resolves. */
private class CountingResolver : StreamResolver {
    var vodResolves = 0

    override suspend fun resolve(channel: ChannelEntity) = ResolvedStream(channel.streamUrl, "UA-R", emptyMap())

    override suspend fun resolveVod(item: VodPlayable): ResolvedStream {
        vodResolves++
        return ResolvedStream(item.streamUrl, "UA-R", emptyMap())
    }
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackManagerVodTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var seam: VodSeamPlayer
    private lateinit var manager: PlaybackManager
    private val retryDelays = mutableListOf<Long>()
    private val stateJobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        repeat(20) { server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(188 * 50)))) }
        server.start()
    }

    @After
    fun tearDown() {
        stateJobs.forEach { it.cancel() }
        manager.release()
        server.shutdown()
    }

    private fun build(resolver: StreamResolver = CountingResolver()) {
        seam = VodSeamPlayer(TestExoPlayerBuilder(context).build())
        manager = PlaybackManager(
            context, OkHttpClient(), seam, clock = { 1_000_000L }, resolver = resolver,
            evictIdleConnections = { seam.events += "evict" },
            retryDelay = { ms -> retryDelays += ms; delay(ms) },
        )
    }

    private val ref = VodRef(VodKind.MOVIE, playlistId = 1, key = "film-1")

    private fun film(userAgent: String? = null, referrer: String? = null) = VodPlayable(
        ref = ref, title = "A Film", streamUrl = server.url("/movie/film.mp4").toString(),
        userAgent = userAgent, referrer = referrer,
    )

    private fun channel(id: Long) = ChannelEntity(
        id = id, playlistId = 1, key = "k$id", number = id.toInt(), name = "Channel $id",
        streamUrl = server.url("/live/$id.ts").toString(), logo = null, groupTitle = "G", groupOrder = 0, sortOrder = 0,
        tvgId = null, tvgName = null, userAgent = null, referrer = null,
        catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = null,
    )

    private fun idleMain(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun recordStates(): List<PlaybackState> {
        val seen = mutableListOf<PlaybackState>()
        stateJobs += CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
            manager.state.collect { seen += it }
        }
        return seen
    }

    private fun report(state: Int) {
        seam.fakeState = state
        seam.listeners.forEach { it.onPlaybackStateChanged(state) }
    }

    private fun failWith(error: ExoPlaybackException) {
        seam.fakeError = error
        seam.listeners.forEach { it.onPlayerError(error) }
    }

    @Test
    fun playVodStartsAtPosition() {
        build()
        val states = recordStates()

        manager.play(channel(1))
        manager.playVod(film(), startMs = 90_000)
        idleMain()

        val item = film()
        assertEquals(Uri.parse(item.streamUrl), seam.currentMediaItem!!.localConfiguration!!.uri)
        assertEquals(90_000L, seam.startPositions.last())
        assertEquals(PlaybackState.Buffering(VOD_CHANNEL_ID), manager.state.value)
        assertEquals(PlayMode.Vod(ref, null, false), manager.mode.value)

        report(Player.STATE_READY)
        idleMain()
        assertEquals(VOD_CHANNEL_ID, (manager.state.value as PlaybackState.Playing).channelId)
        val vodStates = states.dropWhile { it != PlaybackState.Buffering(VOD_CHANNEL_ID) }
        assertTrue(vodStates.isNotEmpty())
        assertEquals(VOD_CHANNEL_ID, (vodStates.last() as PlaybackState.Playing).channelId)
    }

    @Test
    fun playVodStopsLivePreview() {
        build()
        manager.play(channel(1))
        idleMain()
        seam.events.clear()

        manager.playVod(film(), startMs = 0)
        idleMain()

        assertEquals(1, seam.mediaItemCount)
        assertEquals(Uri.parse(film().streamUrl), seam.currentMediaItem!!.localConfiguration!!.uri)
        // The live stream is released, and idle connections closed, before the film opens.
        assertEquals(listOf("stop", "evict", "prepare"), seam.events)
    }

    @Test
    fun vodUsesPlaylistUserAgent() {
        val playlists = mapOf(
            1L to PlaylistEntity(
                id = 1, name = "List", type = PlaylistType.M3U, url = "http://example.test/list.m3u", xtreamServer = null,
                xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null, lastRefreshedAt = null,
                lastError = null, userAgent = "ListAgent/1",
            ),
        )
        val client = StalkerClient(testHttp(), timeZone = { "Europe/London" })
        val sessions = StalkerSessions(client, { playlists[it] }) { _, _, _ -> }
        build(
            PlaylistStreamResolver(
                playlist = { playlists[it] }, settings = { AppSettings() }, defaultUa = "Loophole/test",
                sessions = sessions, client = client,
            ),
        )

        manager.playVod(film(referrer = "http://ref.example.test/"), startMs = 0)
        RobolectricUtil.runMainLooperUntil { server.requestCount > 0 }

        val request = server.takeRequest()
        assertEquals("/movie/film.mp4", request.path)
        assertEquals("ListAgent/1", request.getHeader("User-Agent"))
        assertEquals("http://ref.example.test/", request.getHeader("Referer"))
    }

    @Test
    fun vodEndedSetsModeEnded() {
        val resolver = CountingResolver()
        build(resolver)
        val states = recordStates()
        manager.playVod(film(), startMs = 0)
        idleMain()
        report(Player.STATE_READY)

        report(Player.STATE_ENDED)
        idleMain(10_000)

        assertTrue((manager.mode.value as PlayMode.Vod).ended)
        // An ended film is not a dropped stream: no reopen and no error.
        assertEquals(1, resolver.vodResolves)
        assertTrue(retryDelays.isEmpty())
        assertTrue(states.none { it is PlaybackState.Error })
    }

    @Test
    fun vodStartFailureRetriesSilently() {
        val resolver = CountingResolver()
        build(resolver)
        val states = recordStates()
        manager.playVod(film(), startMs = 42_000)
        idleMain()

        failWith(ExoPlaybackException.createForSource(EOFException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(PlaybackState.Buffering(VOD_CHANNEL_ID), manager.state.value)
        idleMain(1_000)
        RobolectricUtil.runMainLooperUntil { seam.prepareCount >= 2 }
        report(Player.STATE_READY)

        assertEquals(listOf(1_000L), retryDelays)
        assertEquals(2, resolver.vodResolves)
        assertEquals(listOf<Long?>(42_000L, 42_000L), seam.startPositions)
        assertTrue(manager.state.value is PlaybackState.Playing)
        assertTrue(states.none { it is PlaybackState.Error })
        assertTrue(manager.mode.value is PlayMode.Vod)
    }

    @Test
    fun castingResolverDoesNotRelayVod() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relayServer = LocalHttpServer(scope, maxPerRemote = 16)
        relayServer.start(listOf(InetAddress.getByName("127.0.0.1")))
        val host = CastHost(
            scope = scope, server = relayServer, upstreamHttp = OkHttpClient(), clock = { 0L },
            random = SecureRandom(), userAgent = "Loophole/test", tsCapacityBytes = 512 * 1024,
        )
        val direct = ResolvedStream("http://provider.example.test/movie/film.mp4", "ProviderAgent/9", mapOf("Referer" to "r"))
        val inner = object : StreamResolver {
            override suspend fun resolve(channel: ChannelEntity) = error("not used")
            override suspend fun resolveVod(item: VodPlayable) = direct
        }
        build()
        try {
            host.start()
            assertTrue(host.active.value != null)
            assertEquals(direct, CastingResolver(inner, host).resolveVod(film()))
        } finally {
            host.stop(StopReason.STOPPED)
            relayServer.stop()
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun historyIgnoresVod() = runTest {
        build()
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val recorded = mutableListOf<Long>()
        // As the channel table: only stored channels are found.
        val stored = mapOf(1L to channel(1))
        val recorder = HistoryRecorder(state, { stored[it] }, { recorded += it.id }, clock = { testScheduler.currentTime })
        recorder.start(backgroundScope)
        runCurrent()

        state.value = PlaybackState.Playing(VOD_CHANNEL_ID, StreamTags.NONE)
        runCurrent()
        advanceTimeBy(HISTORY_MIN_PLAY_MS * 3)
        runCurrent()

        assertTrue(recorded.isEmpty())
        assertFalse(stored.containsKey(VOD_CHANNEL_ID))
    }
}
