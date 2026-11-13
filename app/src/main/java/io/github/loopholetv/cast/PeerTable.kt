package io.github.loopholetv.cast

import io.github.loopholetv.cast.net.localIpv4
import io.github.loopholetv.util.takeCodePoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Other TVs seen on the network. Entries expire after [expiryMs] without an announce.
 *
 * Sightings of one TV can arrive late and out of order (a cached discovery record next to a fresh
 * beacon), so an announce with a lower [CastAnnounce.seq] than the stored one is ignored; an equal
 * or higher one replaces the entry. Only IPv4 hosts are listed (IPv4-mapped IPv6 counts as its IPv4
 * address): the server only listens on IPv4, so an announce from any other kind of address is ignored.
 * A live entry also can't be moved to another host or jumped far ahead in sequence by an announce
 * from elsewhere; that takes the entry going quiet first.
 * [selfId] is this TV's id, or null while it is still being loaded; nothing is listed until it is known.
 * Safe to call from any thread.
 */
class PeerTable(
    private val selfId: () -> String?,
    private val clock: () -> Long,
    private val expiryMs: Long = 8_000,
) {
    constructor(selfId: String, clock: () -> Long, expiryMs: Long = 8_000) : this({ selfId }, clock, expiryMs)

    private val lock = Any()
    private val byId = HashMap<String, CastPeer>()
    private val seqById = HashMap<String, Long>()

    private val order = compareBy<CastPeer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    private val sorted = MutableStateFlow<List<CastPeer>>(emptyList())

    /** Excludes this TV; sorted by name (case-insensitive), then id. */
    val peers: StateFlow<List<CastPeer>> get() = sorted

    fun seen(a: CastAnnounce, rawHost: String) {
        val self = selfId() ?: return
        if (a.id == self) return
        val host = localHost(rawHost) ?: return
        synchronized(lock) {
            val stored = byId[a.id]
            if (stored == null) {
                if (!makeRoom(host)) return
            } else if (!accepts(stored, a, host)) {
                return
            }
            byId[a.id] = CastPeer(
                a.id, a.name.takeCodePoints(MAX_NAME), host, a.port, a.status, a.accepts, a.castId,
                a.token, a.castStartedAtMs, clock(), a.setup,
            )
            seqById[a.id] = a.seq
            publish()
        }
    }

    /**
     * Whether an announce for a known id may replace [stored]. Announces are unauthenticated, so a
     * live entry can't be moved to another host or pushed far ahead in sequence; only an entry that
     * has gone quiet (older than half the expiry) accepts that.
     */
    private fun accepts(stored: CastPeer, a: CastAnnounce, host: String): Boolean {
        val storedSeq = seqById[a.id] ?: Long.MIN_VALUE
        if (a.seq < storedSeq) return false
        val stale = stored.lastSeenMs < clock() - expiryMs / 2
        if (stale) return true
        if (host != stored.host) return false
        return storedSeq == Long.MIN_VALUE || a.seq - storedSeq <= MAX_SEQ_STEP
    }

    private fun remove(id: String): Boolean {
        seqById.remove(id)
        return byId.remove(id) != null
    }

    /** Frees a slot for a new id from [host]; false when only live entries stand in the way. */
    private fun makeRoom(host: String): Boolean {
        val staleBefore = clock() - expiryMs / 2
        val sameHost = byId.values.filter { it.host == host }
        if (sameHost.size >= MAX_PER_HOST) {
            val oldest = sameHost.minBy { it.lastSeenMs }
            if (oldest.lastSeenMs >= staleBefore) return false
            remove(oldest.id)
        }
        if (byId.size >= MAX_PEERS) {
            val oldest = byId.values.minBy { it.lastSeenMs }
            if (oldest.lastSeenMs >= staleBefore) return false
            remove(oldest.id)
        }
        return true
    }

    fun lost(id: String) = synchronized(lock) {
        if (remove(id)) publish()
    }

    fun prune() = synchronized(lock) {
        val now = clock()
        val expired = byId.values.filter { now - it.lastSeenMs > expiryMs }
        expired.forEach { remove(it.id) }
        if (expired.isNotEmpty()) publish()
    }

    private fun publish() {
        sorted.value = byId.values.sortedWith(order)
    }

    private companion object {
        const val MAPPED_PREFIX = "::ffff:"

        /**
         * The dotted text of [raw] when it is an IPv4 address on the local network, written plainly
         * or IPv4-mapped (`::ffff:a.b.c.d`); null for anything else, IPv6 included. No name lookup.
         */
        fun localHost(raw: String): String? {
            val text = raw.trim()
            val v4 = when {
                ':' !in text -> text
                text.startsWith(MAPPED_PREFIX, ignoreCase = true) -> text.substring(MAPPED_PREFIX.length)
                else -> return null
            }
            return localIpv4(v4)?.hostAddress
        }

        const val MAX_PEERS = 32
        const val MAX_PER_HOST = 2
        const val MAX_NAME = 40
        const val MAX_SEQ_STEP = 10_000L
    }
}
