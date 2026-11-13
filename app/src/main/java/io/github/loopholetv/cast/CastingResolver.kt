package io.github.loopholetv.cast

import androidx.core.net.toUri
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.vod.VodPlayable
import io.github.loopholetv.player.ResolvedStream
import io.github.loopholetv.player.StreamResolver
import io.github.loopholetv.player.catchup.ArchiveRequest
import io.github.loopholetv.player.isHlsUrl
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Plays through [host]'s relay while this TV casts, so only the relay talks to the provider.
 * Without a cast it is [inner] unchanged.
 *
 * Every resolve replaces what is playing, so the relay lets go of its upstream first, as a direct
 * player would have stopped before resolving: a portal may refuse a new link while the old one is
 * still connected. [CastHost.open] checks the cast again after [inner] resolves (that can take a
 * while), so a cast that ended meanwhile gets the real stream.
 */
class CastingResolver(internal val inner: StreamResolver, private val host: CastHost) : StreamResolver {
    override suspend fun resolve(channel: ChannelEntity): ResolvedStream {
        if (host.active.value == null) return inner.resolve(channel)
        host.detach()
        val real = idleOnFailure { inner.resolve(channel) }
        return relay(real, archive = false)
    }

    override suspend fun resolveArchive(channel: ChannelEntity, request: ArchiveRequest, now: Instant): ResolvedStream? {
        if (host.active.value == null) return inner.resolveArchive(channel, request, now)
        host.detach()
        val real = idleOnFailure { inner.resolveArchive(channel, request, now) }
        if (real == null) {
            host.idle()
            return null
        }
        return relay(real, archive = true)
    }

    /** Films and episodes are never relayed: other TVs only follow live and archive streams. */
    override suspend fun resolveVod(item: VodPlayable): ResolvedStream = inner.resolveVod(item)

    override suspend fun keepAlive(channel: ChannelEntity) = inner.keepAlive(channel)

    override suspend fun canRemint(channel: ChannelEntity): Boolean = inner.canRemint(channel)

    /** Nothing will be opened after a failed resolve, so followers learn the casting TV is idle. */
    private suspend fun <T> idleOnFailure(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        // A newer play (or a stop) took over; it does the telling.
        throw e
    } catch (e: Exception) {
        host.idle()
        throw e
    }

    private suspend fun relay(real: ResolvedStream, archive: Boolean): ResolvedStream =
        host.open(real, hls = isHlsUrl(real.url.toUri()), archive = archive)
}
