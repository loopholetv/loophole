package io.github.loopholetv.player

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.net.UserAgents
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.StalkerAuthException
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerLinkException
import io.github.loopholetv.data.stalker.StalkerSession
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.playableUrl
import io.github.loopholetv.data.stalker.portalRoot
import io.github.loopholetv.data.catchup.CatchupMode
import io.github.loopholetv.data.catchup.CatchupSpec
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.data.catchup.CatchupUrls
import io.github.loopholetv.data.catchup.XtreamArchive
import io.github.loopholetv.data.catchup.catchup
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.player.catchup.ArchiveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the player opens: never stored, never logged (it can carry a temporary token). */
data class ResolvedStream(val url: String, val userAgent: String, val headers: Map<String, String>) {
    override fun toString() = "ResolvedStream(***)"
}

/** Turns a stored channel into something playable, at play time only (one stream at a time). */
interface StreamResolver {
    suspend fun resolve(channel: ChannelEntity): ResolvedStream

    /** Runs while [channel] plays (cancelled on stop or zap); a portal's watchdog ping. */
    suspend fun keepAlive(channel: ChannelEntity) {}

    /** Whether an HTTP 403/404/410 on [channel]'s stream may mean an expired link worth one re-mint. */
    suspend fun canRemint(channel: ChannelEntity): Boolean = false

    /** Resolves [channel]'s archive for [request]; null when this channel can't play that time. */
    suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? = null

    /** The stream for a film or episode: its stored URL with its own user agent and referrer. */
    suspend fun resolveVod(item: VodPlayable): ResolvedStream =
        ResolvedStream(item.streamUrl, item.userAgent ?: "", item.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
}

/** The stored URL with the channel's own user agent and referrer. */
class DirectResolver(private val defaultUa: String) : StreamResolver {
    override suspend fun resolve(channel: ChannelEntity) = ResolvedStream(
        url = channel.streamUrl,
        userAgent = UserAgents.forStream(channel.userAgent, null, null, defaultUa),
        headers = refererHeader(channel),
    )

    override suspend fun resolveVod(item: VodPlayable) = ResolvedStream(
        url = item.streamUrl,
        userAgent = UserAgents.forStream(item.userAgent, null, null, defaultUa),
        headers = refererHeader(item.referrer),
    )
}

const val STALKER_ZAP_DEBOUNCE_MS = 400L
const val STALKER_LIMIT_RETRY_MS = 1_500L
const val STALKER_CREATE_LINK_TIMEOUT_MS = 8_000L
private const val WATCHDOG_MIN_SECONDS = 30
private const val WATCHDOG_MAX_SECONDS = 3_600
private const val WATCHDOG_DEFAULT_SECONDS = 120

/**
 * Resolves a channel to its stream: user agent, UDP proxy and portal links. A portal channel waits [STALKER_ZAP_DEBOUNCE_MS]
 * first (cancelled by the next zap, so only the channel finally chosen mints), then calls
 * `create_link` when the row needs it. Links are never cached.
 */
class PlaylistStreamResolver(
    private val playlist: suspend (Long) -> PlaylistEntity?,
    /** Suspends so the first play after process start can wait for the stored settings (SettingsSnapshot.await). */
    private val settings: suspend () -> AppSettings,
    private val defaultUa: String,
    private val sessions: StalkerSessions,
    private val client: StalkerClient,
    private val delayMs: suspend (Long) -> Unit = ::delay,
    /** The device zone, used for archive times the playlist doesn't pin to a server zone. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Logs in to an Xtream account (an API request, not a stream); null skips learning the server zone. */
    private val xtream: XtreamClient? = null,
    /** Stores the learned (zone, archive extension) for a playlist id. */
    private val storeArchiveInfo: suspend (Long, String?, String?) -> Unit = { _, _, _ -> },
) : StreamResolver {

    /** Playlists whose zone was already asked for in this session, so a failed login isn't retried. */
    private val zoneAsked: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The playlist type [resolve] just looked up, consumed by the [canRemint] that PlaybackManager
     * calls right after it: one Room query per zap instead of two. Holds no URL or credential.
     */
    @Volatile private var lastLookup: Pair<Long, PlaylistType?>? = null

    override suspend fun resolve(channel: ChannelEntity): ResolvedStream {
        val p = playlist(channel.playlistId)
        lastLookup = channel.playlistId to p?.type
        val url = if (p?.type == PlaylistType.STALKER) portalUrl(p, channel) else channel.streamUrl
        // Read after the debounce/mint so a settings change made meanwhile applies.
        val s = settings()
        return ResolvedStream(
            url = rewriteForProxy(url, parseUdpProxy(s.udpProxy)),
            userAgent = UserAgents.forStream(channel.userAgent, p?.userAgent, s.userAgent, defaultUa),
            headers = refererHeader(channel),
        )
    }

    /** A film or episode's stored URL, with the same user agent choice as [resolve]. */
    override suspend fun resolveVod(item: VodPlayable): ResolvedStream {
        val p = playlist(item.ref.playlistId)
        val s = settings()
        return ResolvedStream(
            url = item.streamUrl,
            userAgent = UserAgents.forStream(item.userAgent, p?.userAgent, s.userAgent, defaultUa),
            headers = refererHeader(item.referrer),
        )
    }

    /**
     * The archive link for [request] (guide-shown times, already final), or null when the channel
     * has no catch-up or its playlist is a portal. Goes through the same proxy, agent and referrer as [resolve].
     */
    override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? {
        val p = playlist(channel.playlistId)
        if (p?.type == PlaylistType.STALKER) return null
        val spec = channel.catchup() ?: return null
        val url = if (p?.type == PlaylistType.XTREAM) {
            val server = p.xtreamServer
            val user = p.xtreamUser
            val pass = p.xtreamPass
            if (server.isNullOrBlank() || user == null || pass == null) return null
            val known = if (p.xtreamTimeZone == null) learnArchiveInfo(p, server, user, pass) else null
            val zoneId = p.xtreamTimeZone ?: known?.first
            val ext = p.xtreamArchiveExt ?: known?.second ?: "ts"
            val serverZone = zoneId?.let(::zoneOf) ?: zone()
            val archive = XtreamArchive(server, user, pass, channel.key, serverZone, ext)
            CatchupUrls.xtream(archive, request.from, request.until, maxOf(now, request.until))
        } else {
            CatchupUrls.m3u(
                spec, channel.streamUrl, request.from, request.until, endCap(spec, request, now), zone(),
                Duration.ofMinutes(-channel.catchupCorrectionMinutes.toLong()),
            )
        } ?: return null
        val s = settings()
        return ResolvedStream(
            url = rewriteForProxy(url, parseUdpProxy(s.udpProxy)),
            userAgent = UserAgents.forStream(channel.userAgent, p?.userAgent, s.userAgent, defaultUa),
            headers = refererHeader(channel),
        )
    }

    /**
     * The "now" the URL builders cap the archive's end at. A programme still airing is asked for up
     * to its stop, so the provider keeps serving as the recording grows instead of ending at the
     * moment it was opened: whenever the URL says nothing about the current time itself, the
     * request's end is passed as "now". A template that does read the current time (an offset from
     * now, or a now stamp) keeps the real one.
     */
    private fun endCap(spec: CatchupSpec, request: ArchiveRequest, now: Instant): Instant {
        val open = maxOf(now, request.until)
        return when (spec.mode) {
            CatchupMode.XC -> open
            CatchupMode.DEFAULT, CatchupMode.APPEND ->
                if (spec.source?.let(NOW_PLACEHOLDER::containsMatchIn) == true) now else open
            CatchupMode.SHIFT, CatchupMode.FLUSSONIC_HLS, CatchupMode.FLUSSONIC_TS -> now
        }
    }

    /**
     * One account login for a playlist saved before the server zone was recorded; stores what it
     * returns. Asked once per playlist per session: a failure leaves the device zone in use.
     */
    private suspend fun learnArchiveInfo(p: PlaylistEntity, server: String, user: String, pass: String): Pair<String?, String>? {
        val api = xtream ?: return null
        if (!zoneAsked.add(p.id)) return null
        return try {
            val s = settings()
            val ua = UserAgents.forDownload(p.userAgent, s.userAgent, defaultUa)
            val account = api.authenticate(XtreamCredentials(server, user, pass), ua)
            storeArchiveInfo(p.id, account.serverTimeZone, account.archiveExt)
            account.serverTimeZone to account.archiveExt
        } catch (e: CancellationException) {
            zoneAsked.remove(p.id)
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Only a portal row that mints a temporary link; a static row would re-resolve to the same URL. */
    override suspend fun canRemint(channel: ChannelEntity): Boolean {
        if (!channel.needsLink) return false
        val memo = lastLookup?.takeIf { it.first == channel.playlistId }
        lastLookup = null
        val type = if (memo != null) memo.second else playlist(channel.playlistId)?.type
        return type == PlaylistType.STALKER
    }

    override suspend fun keepAlive(channel: ChannelEntity) {
        val p = playlist(channel.playlistId)?.takeIf { it.type == PlaylistType.STALKER } ?: return
        var seconds = ping(p.id) { s, _ -> s.watchdogSeconds } ?: return
        while (true) {
            delayMs(seconds.coerceIn(WATCHDOG_MIN_SECONDS, WATCHDOG_MAX_SECONDS) * 1_000L)
            seconds = ping(p.id) { s, cfg -> client.watchdog(s, cfg); s.watchdogSeconds } ?: return
        }
    }

    /**
     * One watchdog step. Null when the portal refused the session (stop pinging; the stream will
     * fail on its own). A transient failure keeps the default interval.
     */
    private suspend fun ping(
        playlistId: Long,
        block: suspend (StalkerSession, PortalConfig) -> Int,
    ): Int? = try {
        sessions.withSession(playlistId, block)
    } catch (e: CancellationException) {
        throw e
    } catch (_: StalkerAuthException) {
        null
    } catch (_: Exception) {
        WATCHDOG_DEFAULT_SECONDS
    }

    private suspend fun portalUrl(p: PlaylistEntity, channel: ChannelEntity): String {
        delayMs(STALKER_ZAP_DEBOUNCE_MS)
        if (!channel.needsLink) {
            val root = portalRoot(p.portalEndpoint?.takeIf { it.isNotBlank() } ?: p.url.orEmpty())
            return playableUrl(root, channel.streamUrl) ?: throw StalkerLinkException("link_fault")
        }
        return try {
            mint(p.id, channel.streamUrl)
        } catch (e: StalkerLinkException) {
            if (e.code != "limit") throw e
            delayMs(STALKER_LIMIT_RETRY_MS)
            mint(p.id, channel.streamUrl)
        }
    }

    /** One create_link; a timeout is a link failure, not a cancellation of the play. */
    private suspend fun mint(playlistId: Long, cmd: String): String =
        withTimeoutOrNull(STALKER_CREATE_LINK_TIMEOUT_MS) {
            sessions.withSession(playlistId) { s, cfg -> client.createLink(s, cfg, cmd) }
        } ?: throw StalkerLinkException("timeout")
}

// Template placeholders that read the current time rather than the programme's.
private val NOW_PLACEHOLDER = Regex("""\{(?:offset|lutc|now|timestamp)(?::[^{}]*)?\}""")

private fun refererHeader(channel: ChannelEntity): Map<String, String> = refererHeader(channel.referrer)

private fun refererHeader(referrer: String?): Map<String, String> =
    referrer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap()

private fun zoneOf(id: String): ZoneId? = try {
    ZoneId.of(id.trim())
} catch (_: java.time.DateTimeException) {
    null
}
