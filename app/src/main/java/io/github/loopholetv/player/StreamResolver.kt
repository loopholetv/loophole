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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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
}

/** The stored URL with the channel's own user agent and referrer. */
class DirectResolver(private val defaultUa: String) : StreamResolver {
    override suspend fun resolve(channel: ChannelEntity) = ResolvedStream(
        url = channel.streamUrl,
        userAgent = UserAgents.forStream(channel.userAgent, null, null, defaultUa),
        headers = refererHeader(channel),
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
) : StreamResolver {

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

private fun refererHeader(channel: ChannelEntity): Map<String, String> =
    channel.referrer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap()
