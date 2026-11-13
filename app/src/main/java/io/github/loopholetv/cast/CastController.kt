package io.github.loopholetv.cast

import io.github.loopholetv.cast.net.LocalHttpServer
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.player.CAST_CHANNEL_ID
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.catchup.PlayMode
import java.io.IOException
import java.net.InetAddress
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Casting and following on this TV: one state machine over [host] (this TV's cast), [client]
 * (another TV's cast), [discovery] and [table] (the other TVs), and the one [playback].
 *
 * Call everything on [scope]'s thread (the main thread in the app); only [selfAnnounce] may be
 * called from any thread. The server, discovery and the beacon run only between
 * `onForeground(true)` and `onForeground(false)`, and only once [selfId] has loaded (it starts
 * loading when the controller is created). So does [watchNetwork]: when this TV's addresses change,
 * the server moves to the new ones.
 *
 * - **Casting.** [startCasting] starts [host] and re-opens what plays so it goes through the relay;
 *   without an address other TVs can reach ([reachable]) it shows [NO_NETWORK_MESSAGE] instead.
 *   [holdWifi] is held from the start of a cast to its end.
 *   [stopCasting] with [StopReason.STOPPED] re-opens it directly afterwards, unless something newer
 *   started meanwhile; the other reasons leave playback to the caller, which is about to stop it or
 *   hand it to another player. While casting, this TV's player pausing, playing on or going idle
 *   tells the followers.
 * - **Joining on its own, once per cast.** A cast this TV hasn't seen yet is joined when this TV is
 *   idle, or offered as an [invite] for [INVITE_MS] when it plays something. Not while casting or
 *   following, nor when the setting or [canAutoJoin] says no. A cast that was ignored, timed out or
 *   left is never offered again; [join] from the TVs list still works. Coming back to the foreground
 *   counts as a new sighting of any cast not declined; for the first [settleMs] the sightings are
 *   only gathered, so the most recent of several casts wins however they arrive.
 * - **Following.** Plays each [CastEvent.Playing] from the casting TV's relay, pauses on the last
 *   frame on [CastEvent.Idle] (the casting TV paused or stopped) and plays on with the next Playing,
 *   and ends with a message on [CastEvent.Stopped], a lost connection or a full cast. A stream that
 *   fails here is asked for again after 2 s, then 4, 8… up to 30 s.
 *   Playing or stopping anything locally leaves the cast silently.
 *
 * [tvName] is the device's own name, announced while no TV name is set. [clock] is the wall clock;
 * it seeds the announce counter so it keeps growing across restarts. [castPosition] is the
 * follower's position when its stream is seekable (null otherwise) and [seekCast] moves it; both are
 * called on [scope]'s thread.
 */
class CastController(
    private val scope: CoroutineScope,
    selfId: suspend () -> String,
    private val playback: Playback,
    private val host: CastHost,
    private val discovery: CastDiscovery,
    private val table: PeerTable,
    private val client: CastClient,
    private val server: LocalHttpServer,
    private val binds: () -> List<InetAddress>,
    settings: Flow<AppSettings>,
    private val tvName: () -> String,
    clock: () -> Long,
    /** False keeps this TV from joining or being invited on its own (for example, a locked TV). */
    private val canAutoJoin: () -> Boolean = { true },
    private val castPosition: () -> Long? = {
        playback.player.takeIf { it.isCurrentMediaItemSeekable }?.currentPosition
    },
    private val seekCast: (Long) -> Unit = { playback.player.seekTo(it) },
    /** Whether other TVs can reach the server on [binds]' addresses; loopback alone isn't enough. */
    private val reachable: (List<InetAddress>) -> Boolean = { addresses -> addresses.any { !it.isLoopbackAddress } },
    /** Taken while this TV casts, so Wi-Fi power saving can't starve the other TVs; closed when it stops. */
    private val holdWifi: () -> AutoCloseable = { AutoCloseable {} },
    /**
     * Registers a callback, called on any thread, for changes to the default network or its
     * addresses; closing the result unregisters it. Watched only in the foreground.
     */
    private val watchNetwork: (onChange: () -> Unit) -> AutoCloseable = { AutoCloseable {} },
    private val inviteMs: Long = INVITE_MS,
    private val drainMs: Long = DRAIN_MS,
    private val settleMs: Long = SETTLE_MS,
) : CastView {
    /** One followed cast; replaced, never reused. */
    private class Follow(val peer: CastPeer, val selfId: String) {
        val castId: String = peer.castId.orEmpty()
        var job: Job? = null
        var playing: CastEvent.Playing? = null
        var url: String? = null

        /** True once [Playback.playCast] has returned for this cast. */
        var issued = false

        /** True from a [CastEvent.Idle] until the next [CastEvent.Playing]. */
        var idle = false
        var unsupportedShown = false

        /** Failures in a row of the current stream, and the pending request for it again. */
        var errors = 0
        var errorShown = false
        var retry: Job? = null

        fun resetErrors() {
            retry?.cancel()
            retry = null
            errors = 0
            errorShown = false
        }
    }

    private val _state = MutableStateFlow<CastState>(CastState.Off)
    override val state: StateFlow<CastState> = _state.asStateFlow()

    override val peers: StateFlow<List<CastPeer>> = table.peers

    private val _invite = MutableStateFlow<CastInvite?>(null)
    override val invite: StateFlow<CastInvite?> = _invite.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val latestSettings = MutableStateFlow<AppSettings?>(null)

    private val idLoad = scope.async { selfId() }

    /** This TV's id once loaded. */
    @Volatile private var me: String? = null

    private var foreground = false

    /** False for the first [settleMs] after coming to the foreground: sightings are gathered only. */
    private var settled = false

    /** Whether the running server can be reached by other TVs. */
    private var serverReachable = false

    /** The addresses the running server listens on. */
    private var boundAddresses: List<InetAddress> = emptyList()

    private var wifiHold: AutoCloseable? = null
    private var networkWatch: AutoCloseable? = null

    /** Goes up on every start, stop or join, so a stop's late re-open can tell it is out of date. */
    private var castGeneration = 0

    /** Casts already reacted to since this TV last came to the foreground. */
    private val seen = HashSet<String>()

    /** Casts this TV left, ignored or let time out; never offered again. */
    private val declined = HashSet<String>()

    private var foregroundJob: Job? = null
    private var castJob: Job? = null
    private var inviteJob: Job? = null
    private var follow: Follow? = null

    private val announceLock = Any()
    private var seq = clock()
    private var announced: CastAnnounce? = null

    init {
        // A read error keeps the last good settings.
        scope.launch { settings.catch { }.collect { latestSettings.value = it } }
    }

    // Lifecycle

    /**
     * True: starts the server and discovery. False: ends this TV's cast ([StopReason.LEFT_APP]),
     * leaves a followed one, and stops discovery and the server.
     */
    fun onForeground(foreground: Boolean) {
        if (foreground == this.foreground) return
        this.foreground = foreground
        if (foreground) enterForeground() else leaveForeground()
    }

    private fun enterForeground() {
        seen.clear()
        settled = false
        // Entries kept from before the background can describe casts that ended long ago.
        table.prune()
        foregroundJob = scope.launch {
            val id = try {
                idLoad.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // No id, no casting this time.
            }
            me = id
            startServer()
            networkWatch = watchNetwork { scope.launch { onNetworkChanged() } }
            table.prune()
            discovery.start(::selfAnnounce)
            launch {
                delay(settleMs)
                settled = true
                latestSettings.value?.let { onPeers(table.peers.value, it) }
            }
            launch {
                combine(table.peers, latestSettings.filterNotNull(), ::Pair).collect { (list, s) -> onPeers(list, s) }
            }
            combine(_state, playback.state, latestSettings.filterNotNull()) { _, _, s ->
                listOf(selfStatus(host.active.value), s.castAllowJoin, s.castTvName)
            }.distinctUntilChanged().collect { discovery.refresh() }
        }
    }

    /** Starts the server on this TV's current addresses, on [preferredPort] when that is free. */
    private fun startServer(preferredPort: Int = 0) {
        if (server.port != null) return // Still up from a moment ago.
        val addresses = binds()
        serverReachable = false
        boundAddresses = emptyList()
        try {
            try {
                server.start(addresses, preferredPort)
            } catch (e: IOException) {
                if (preferredPort == 0) throw e
                server.start(addresses)
            }
            serverReachable = reachable(addresses)
            boundAddresses = addresses
        } catch (_: Exception) {
            // No casting from this TV without a server; following still works.
        }
    }

    /**
     * This TV's network changed (a new address, or a switch between Wi-Fi and Ethernet): the
     * server moves to the new addresses, keeping its port when it can, and the others are told.
     */
    private fun onNetworkChanged() {
        if (!foreground || me == null) return
        val addresses = binds()
        if (server.port == null || addresses != boundAddresses) {
            val port = server.port ?: 0
            server.stop()
            startServer(preferredPort = port)
            // This TV's own player was reading the relay through the old server.
            if (_state.value is CastState.Casting && server.port != null) playback.reopen()
        }
        discovery.refresh()
    }

    private fun leaveForeground() {
        foregroundJob?.cancel()
        foregroundJob = null
        networkWatch?.let { runCatching { it.close() } }
        networkWatch = null
        clearInvite(decline = false)
        discovery.stop()
        follow?.let { end(it, message = null, notifyHost = true, decline = false, stopPlayback = true) }
        val casting = endCasting(StopReason.LEFT_APP, reopen = false)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            casting?.join()
            // Lets the last events reach the followers before their connections close.
            if (casting != null) delay(drainMs)
            if (!this@CastController.foreground) server.stop()
        }
    }

    // Casting

    override fun startCasting() {
        if (_state.value is CastState.Casting) return
        if (server.port == null || !serverReachable) {
            _messages.tryEmit(NO_NETWORK_MESSAGE)
            return
        }
        follow?.let { end(it, message = null, notifyHost = true, decline = true, stopPlayback = true) }
        clearInvite(decline = true)
        castGeneration++
        host.start()
        wifiHold = try {
            holdWifi()
        } catch (_: RuntimeException) {
            null // Casting works without it, just less steadily on some Wi-Fi.
        }
        playback.reopen()
        _state.value = CastState.Casting(host.followers.value)
        discovery.refresh()
        castJob = scope.launch {
            launch {
                host.followers.collect { n -> if (_state.value is CastState.Casting) _state.value = CastState.Casting(n) }
            }
            // Pausing here pauses the followers; they play on when this TV does.
            launch { playback.paused.collect { host.pause(it) } }
            // A local stop (sleep timer, settings) sends nothing more; followers keep the last frame.
            playback.state.collect { if (it is PlaybackState.Idle) host.idle() }
        }
    }

    /**
     * Ends this TV's cast. The relay is inactive and its routes are gone when this returns; its
     * provider connection is closed just after.
     */
    override fun stopCasting(reason: StopReason) {
        endCasting(reason, reopen = reason == StopReason.STOPPED)
    }

    /** The job that stops [host] and then re-opens playback when [reopen]; null when not casting. */
    private fun endCasting(reason: StopReason, reopen: Boolean): Job? {
        if (_state.value !is CastState.Casting) return null
        castJob?.cancel()
        castJob = null
        wifiHold?.let { runCatching { it.close() } }
        wifiHold = null
        _state.value = CastState.Off
        val generation = ++castGeneration
        val playingThen = playKey()
        // Undispatched: the relay is no longer active by the time this returns.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.stop(reason)
            // Only what played when the cast stopped, and only if nothing newer started meanwhile
            // (another cast, a join, a channel change or a stop).
            val current = generation == castGeneration && host.active.value == null && playKey() == playingThen
            if (reopen && current) playback.reopen()
        }
        discovery.refresh()
        return job
    }

    /** What is playing locally: the channel and the archive programme, if any. */
    private fun playKey(): List<Any?> {
        val channel = when (val st = playback.state.value) {
            is PlaybackState.Buffering -> st.channelId
            is PlaybackState.Playing -> st.channelId
            is PlaybackState.Error -> st.channelId
            PlaybackState.Idle -> null
        }
        val mode = playback.mode.value
        return listOf(channel, (mode as? PlayMode.Archive)?.request ?: mode::class)
    }

    // Joining

    private fun onPeers(list: List<CastPeer>, s: AppSettings) {
        val offered = _invite.value
        if (offered != null && list.none { it.isCasting() && it.castId == offered.peer.castId }) {
            clearInvite(decline = false)
        }
        // While held, sightings aren't counted, so [recheckAutoJoin] can still react to them.
        if (!foreground || !settled || !canAutoJoin()) return
        val fresh = list.filter { it.isCasting() && it.castId !in seen }
        if (fresh.isEmpty()) return
        fresh.forEach { seen += it.castId.orEmpty() }
        val latest = fresh.filter { it.castId !in declined }
            .maxWithOrNull(compareBy<CastPeer> { it.castStartedAtMs ?: Long.MIN_VALUE }.thenBy { it.id })
            ?: return
        if (_state.value != CastState.Off || !s.castAllowJoin) return
        if (playback.state.value is PlaybackState.Idle) follow(latest) else offer(latest)
    }

    /**
     * [canAutoJoin] may have changed: reacts to the casts on the network now, as on a new
     * sighting. Casts seen while it said no were not counted, so one of them can be joined now.
     */
    fun recheckAutoJoin() {
        latestSettings.value?.let { onPeers(table.peers.value, it) }
    }

    private fun offer(peer: CastPeer) {
        clearInvite(decline = true)
        _invite.value = CastInvite(peer)
        inviteJob = scope.launch {
            delay(inviteMs)
            if (_invite.value?.peer?.castId == peer.castId) clearInvite(decline = true)
        }
    }

    override fun ignoreInvite() = clearInvite(decline = true)

    private fun clearInvite(decline: Boolean) {
        val offered = _invite.value ?: return
        inviteJob?.cancel()
        inviteJob = null
        _invite.value = null
        if (decline) declined += offered.peer.castId.orEmpty()
    }

    /** Joins [peerId]'s cast, also one this TV declined. A cast this TV runs ends first. */
    override fun join(peerId: String) {
        val peer = table.peers.value.firstOrNull { it.id == peerId && it.isCasting() } ?: return
        follow(peer)
    }

    // Following

    private fun follow(peer: CastPeer) {
        val castId = peer.castId ?: return
        val id = me ?: return
        // Checked again right before connecting: only a TV on the home network is ever contacted.
        if (!CastClient.isLocalHost(peer.host)) return
        val current = follow
        if (current != null) {
            if (current.castId == castId) return
            end(current, message = null, notifyHost = true, decline = true, stopPlayback = false)
        }
        endCasting(StopReason.STOPPED, reopen = false)
        castGeneration++
        clearInvite(decline = _invite.value?.peer?.castId != castId)
        seen += castId
        playback.stop()
        val f = Follow(peer, id)
        follow = f
        _state.value = CastState.Following(peer)
        f.job = scope.launch { followLoop(f) }
    }

    override fun leave() {
        follow?.let { end(it, message = null, notifyHost = true, decline = true, stopPlayback = true) }
    }

    private suspend fun followLoop(f: Follow) = coroutineScope {
        val guard = launch { watchPlayback(f) }
        val message = try {
            client.events(f.peer, f.selfId).collect { onEvent(f, it) }
            lostMessage(name(f))
        } catch (e: CancellationException) {
            throw e
        } catch (_: CastFullException) {
            fullMessage(name(f))
        } catch (_: Exception) {
            lostMessage(name(f))
        }
        guard.cancel()
        end(f, message, notifyHost = false, decline = false, stopPlayback = true)
    }

    private fun onEvent(f: Follow, e: CastEvent) {
        if (follow !== f || e.cast != f.castId) return
        when (e) {
            is CastEvent.Playing -> {
                if (resumes(f, e)) {
                    f.idle = false
                    playback.resume()
                    return
                }
                val again = !f.issued || f.idle || f.playing?.epoch != e.epoch
                f.idle = false
                f.unsupportedShown = false
                if (!again) return
                val url = CastClient.mediaUrl(f.peer, e.path, f.selfId) ?: return
                f.resetErrors()
                f.playing = e
                _state.value = CastState.Following(f.peer, e.epoch)
                play(f, url, e.hls)
            }
            is CastEvent.Idle -> {
                f.idle = true
                f.retry?.cancel()
                f.retry = null
                // The casting TV paused or stopped: hold the last frame rather than play on alone.
                playback.pause()
                if (e.reason == CastEvent.Idle.REASON_UNSUPPORTED && !f.unsupportedShown) {
                    f.unsupportedShown = true
                    _messages.tryEmit(UNSUPPORTED_MESSAGE)
                }
            }
            is CastEvent.Stopped ->
                end(f, stoppedMessage(name(f), e.reason), notifyHost = false, decline = false, stopPlayback = true)
            is CastEvent.Heartbeat -> onHeartbeat(f, e)
        }
    }

    /**
     * Whether [e] is the casting TV playing on after a pause, and this TV can simply play on too: the
     * same HLS stream, still loaded without error. A TS stream is opened again instead, since its
     * connection may have been dropped while nothing read it.
     */
    private fun resumes(f: Follow, e: CastEvent.Playing): Boolean {
        val playing = f.playing ?: return false
        return f.issued && f.idle && playing.epoch == e.epoch && playing.hls && e.hls &&
            playback.mode.value is PlayMode.Cast && playback.state.value !is PlaybackState.Error
    }

    private fun play(f: Follow, url: String, hls: Boolean) {
        f.url = url
        playback.playCast(url, hls)
        f.issued = true
    }

    private fun onHeartbeat(f: Follow, beat: CastEvent.Heartbeat) {
        val playing = f.playing ?: return
        if (f.idle || beat.epoch != playing.epoch || playback.state.value !is PlaybackState.Playing) return
        val target = beat.positionMs ?: return
        val position = castPosition() ?: return
        if (abs(position - target) > RESYNC_MS) seekCast(target)
    }

    /**
     * Leaves silently once anything else plays here, or playback stops without the cast ending.
     * A failed cast stream is asked for again, waiting longer each time.
     */
    private suspend fun watchPlayback(f: Follow) = coroutineScope {
        combine(playback.mode, playback.state) { _, _ -> }.collect {
            if (follow !== f) return@collect
            val mode = playback.mode.value
            val st = playback.state.value
            val local = (st is PlaybackState.Buffering && st.channelId != CAST_CHANNEL_ID) ||
                (st is PlaybackState.Playing && st.channelId != CAST_CHANNEL_ID)
            val tookOver = if (f.issued) mode !is PlayMode.Cast || st is PlaybackState.Idle else local || mode is PlayMode.Archive
            when {
                tookOver -> end(f, message = null, notifyHost = true, decline = true, stopPlayback = false)
                st is PlaybackState.Playing -> f.resetErrors()
                st is PlaybackState.Error && f.issued && !f.idle && f.retry == null -> retryLater(f)
            }
        }
    }

    /** Called from [watchPlayback]'s scope, so the retry ends with the follow. */
    private fun CoroutineScope.retryLater(f: Follow) {
        f.errors++
        if (f.errors >= ERRORS_BEFORE_MESSAGE && !f.errorShown) {
            f.errorShown = true
            _messages.tryEmit(CANT_PLAY_MESSAGE)
        }
        val wait = minOf(RETRY_FIRST_MS shl (f.errors - 1).coerceAtMost(RETRY_MAX_DOUBLINGS), RETRY_MAX_MS)
        f.retry = launch {
            delay(wait)
            f.retry = null
            val st = playback.state.value
            val playing = f.playing ?: return@launch
            if (follow === f && !f.idle && st is PlaybackState.Error) f.url?.let { play(f, it, playing.hls) }
        }
    }

    private fun end(f: Follow, message: String?, notifyHost: Boolean, decline: Boolean, stopPlayback: Boolean) {
        if (follow !== f) return
        follow = null
        f.job?.cancel()
        if (decline) declined += f.castId
        if (_state.value is CastState.Following) _state.value = CastState.Off
        message?.let { _messages.tryEmit(it) }
        if (stopPlayback) playback.stop()
        if (notifyHost) scope.launch { client.leave(f.peer, f.selfId) }
    }

    private fun name(f: Follow): String = table.peers.value.firstOrNull { it.id == f.peer.id }?.name ?: f.peer.name

    // Announcing

    private fun selfStatus(session: CastSession?): CastStatus = when {
        session != null -> CastStatus.CASTING
        playback.state.value.let { it is PlaybackState.Buffering || it is PlaybackState.Playing } -> CastStatus.WATCHING
        else -> CastStatus.IDLE
    }

    /** What this TV tells the others now; [CastAnnounce.seq] goes up whenever anything else changes. Any thread. */
    fun selfAnnounce(): CastAnnounce = synchronized(announceLock) {
        val session = host.active.value
        val s = latestSettings.value
        val now = CastAnnounce(
            id = me.orEmpty(),
            // The same settings that trigger a new announce, so a rename goes out at once.
            name = s?.castTvName ?: tvName(),
            port = server.port ?: 0,
            status = selfStatus(session),
            accepts = s?.castAllowJoin ?: true,
            castId = session?.castId,
            token = session?.token,
            castStartedAtMs = session?.startedAtMs,
            seq = 0,
            setup = CastAnnounce.SETUP_VERSION,
        )
        if (now != announced) {
            announced = now
            seq++
        }
        now.copy(seq = seq)
    }

    companion object {
        const val INVITE_MS = 10_000L
        const val DRAIN_MS = 500L
        const val SETTLE_MS = 3_000L
        private const val RESYNC_MS = 10_000L
        const val RETRY_FIRST_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L
        private const val RETRY_MAX_DOUBLINGS = 4
        private const val ERRORS_BEFORE_MESSAGE = 3

        const val UNSUPPORTED_MESSAGE = "This programme can't be shown on other TVs."
        /** The same words the player uses for a cast stream it can't play. */
        const val CANT_PLAY_MESSAGE = "Can't play this stream."
        const val NO_NETWORK_MESSAGE = "Connect this TV to your home network to cast."

        fun stoppedMessage(name: String, reason: StopReason): String = when (reason) {
            StopReason.STOPPED, StopReason.LEFT_APP -> "$name stopped casting"
            StopReason.EXTERNAL_PLAYER -> "$name switched to another player"
        }

        fun lostMessage(name: String) = "Lost $name"

        fun fullMessage(name: String) = "$name is already casting to ${CastHost.MAX_FOLLOWERS} TVs."

        private val CastEvent.cast: String
            get() = when (this) {
                is CastEvent.Playing -> castId
                is CastEvent.Idle -> castId
                is CastEvent.Stopped -> castId
                is CastEvent.Heartbeat -> castId
            }

        private fun CastPeer.isCasting() = status == CastStatus.CASTING && castId != null && token != null
    }
}
