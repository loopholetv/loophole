package io.github.loopholetv.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerTableTest {
    private var now = 0L
    private val table = PeerTable("self", { now })

    private fun ann(id: String, name: String = id) =
        CastAnnounce(id, name, 8000, CastStatus.IDLE, true, null, null, null)

    @Test
    fun peerKeepsTheSetupVersion() {
        table.seen(ann("old"), "10.0.0.2")
        table.seen(ann("new").copy(setup = 1), "10.0.0.3")
        assertEquals(mapOf("new" to 1, "old" to 0), table.peers.value.associate { it.id to it.setup })
    }

    @Test
    fun peerTableExcludesSelf() {
        table.seen(ann("self"), "10.0.0.1")
        table.seen(ann("other"), "10.0.0.2")
        assertEquals(listOf("other"), table.peers.value.map { it.id })
    }

    @Test
    fun peerTableReplacesById() {
        table.seen(ann("x", "Old"), "10.0.0.2")
        now = 4_001
        table.seen(ann("x", "New"), "10.0.0.3")
        val peers = table.peers.value
        assertEquals(1, peers.size)
        assertEquals("New", peers[0].name)
        assertEquals("10.0.0.3", peers[0].host)
    }

    @Test
    fun peerTableSortsByName() {
        table.seen(ann("3", "bravo"), "10.9.0.0")
        table.seen(ann("2", "Alpha"), "10.9.0.1")
        table.seen(ann("1", "alpha"), "10.9.0.2")
        table.seen(ann("4", "Charlie"), "10.9.0.3")
        // "Alpha" and "alpha" tie case-insensitively, so the id decides.
        assertEquals(listOf("1", "2", "3", "4"), table.peers.value.map { it.id })
    }

    @Test
    fun pruneDropsAfter8s() {
        table.seen(ann("x"), "10.9.9.9")
        now = 7_999
        table.prune()
        assertEquals(1, table.peers.value.size)
        now = 8_001
        table.prune()
        assertEquals(0, table.peers.value.size)
    }

    @Test
    fun lostRemovesImmediately() {
        table.seen(ann("x"), "10.9.9.9")
        table.lost("x")
        assertEquals(0, table.peers.value.size)
    }

    @Test
    fun livePeersCannotBeEvictedBySpam() {
        repeat(32) {
            now = it * 10L
            table.seen(ann("p$it"), "10.0.1.$it")
        }
        now = 320
        val before = table.peers.value
        repeat(100) { table.seen(ann("spam$it"), "10.0.2.${it % 200}") }
        assertEquals(before, table.peers.value)
    }

    @Test
    fun staleEntryReplacedWhenFull() {
        repeat(32) {
            now = it.toLong() * 100
            table.seen(ann("p$it"), "10.0.1.$it")
        }
        now = 3_300 // p0 last seen at 0: older than 4 s? no, 3.3 s; wait until it is
        table.seen(ann("new"), "10.0.2.1")
        assertEquals(false, "new" in table.peers.value.map { it.id })
        now = 4_100
        table.seen(ann("new"), "10.0.2.1")
        val ids = table.peers.value.map { it.id }
        assertEquals(32, ids.size)
        assertEquals(false, "p0" in ids)
        assertEquals(true, "new" in ids)
    }

    @Test
    fun perHostCapRespectsLiveEntries() {
        table.seen(ann("a"), "10.0.0.5")
        table.seen(ann("b"), "10.0.0.5")
        table.seen(ann("c"), "10.0.0.5")
        assertEquals(setOf("a", "b"), table.peers.value.map { it.id }.toSet())
        now = 4_001
        table.seen(ann("c"), "10.0.0.5")
        assertEquals(2, table.peers.value.size)
        assertEquals(true, "c" in table.peers.value.map { it.id })
    }

    @Test
    fun mappedAndPlainIpv4CountAsSameHost() {
        table.seen(ann("a"), "10.0.0.5")
        table.seen(ann("b"), "::ffff:10.0.0.5")
        table.seen(ann("c"), "10.0.0.5")
        assertEquals(2, table.peers.value.size)
        assertEquals(setOf("10.0.0.5"), table.peers.value.map { it.host }.toSet())
    }

    @Test
    fun nonLiteralHostRejected() {
        table.seen(ann("a"), "example.com")
        table.seen(ann("b"), "")
        table.seen(ann("c"), "1.2.3")
        assertEquals(0, table.peers.value.size)
        table.seen(ann("d"), "fe80::1%wlan0")
        assertEquals(0, table.peers.value.size)
    }

    @Test
    fun idMovesToNewHost() {
        table.seen(ann("x"), "10.0.0.2")
        table.seen(ann("y"), "10.0.0.2")
        now = 4_001
        table.seen(ann("x"), "10.0.0.3")
        val peers = table.peers.value
        assertEquals(2, peers.size)
        assertEquals("10.0.0.3", peers.first { it.id == "x" }.host)
    }

    @Test
    fun longNamesAreCapped() {
        table.seen(ann("x", "n".repeat(100)), "10.9.9.9")
        assertEquals(40, table.peers.value[0].name.length)
    }

    @Test
    fun exactly8sIsKept() {
        table.seen(ann("x"), "10.9.9.9")
        now = 8_000
        table.prune()
        assertEquals(1, table.peers.value.size)
    }

    private fun casting(id: String, seq: Long) =
        CastAnnounce(id, id, 8000, CastStatus.CASTING, true, "c1", "t", 1_000L, seq = seq)

    @Test
    fun olderSeqDoesNotOverwrite() {
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        now = 1_000
        table.seen(casting("x", seq = 4), "10.0.0.3")
        val peer = table.peers.value.single()
        assertEquals(CastStatus.IDLE, peer.status)
        assertEquals(null, peer.castId)
        assertEquals("10.0.0.2", peer.host)
        assertEquals(0L, peer.lastSeenMs)
        now = 4_001
        table.seen(casting("x", seq = 6), "10.0.0.3")
        assertEquals(CastStatus.CASTING, table.peers.value.single().status)
    }

    @Test
    fun equalSeqRefreshesLastSeen() {
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        now = 7_000
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        assertEquals(7_000L, table.peers.value.single().lastSeenMs)
        now = 14_000
        table.prune()
        assertEquals(1, table.peers.value.size)
    }

    @Test
    fun expiredPeerForgetsItsSeq() {
        table.seen(ann("x").copy(seq = 900), "10.0.0.2")
        now = 9_000
        table.prune()
        table.seen(casting("x", seq = 1), "10.0.0.2")
        assertEquals(CastStatus.CASTING, table.peers.value.single().status)
    }

    @Test
    fun ipv6SightingIgnored() {
        table.seen(ann("x"), "fe80::1%wlan0")
        table.seen(ann("y"), "fd00::2")
        table.seen(ann("z"), "2001:db8::3")
        assertEquals(0, table.peers.value.size)
        table.seen(ann("m"), "::ffff:10.0.0.7")
        assertEquals("10.0.0.7", table.peers.value.single().host)
    }

    @Test
    fun ipv6SightingCannotUpdateLiveEntry() {
        table.seen(casting("x", seq = 5), "10.0.0.2")
        now = 1_000
        table.seen(ann("x", "Spoofed").copy(seq = 6), "fe80::1%wlan0")
        val peer = table.peers.value.single()
        assertEquals("10.0.0.2", peer.host)
        assertEquals("x", peer.name)
        assertEquals(CastStatus.CASTING, peer.status)
        assertEquals("c1", peer.castId)
        assertEquals("t", peer.token)
        assertEquals(0L, peer.lastSeenMs)
        now = 4_001
        table.seen(ann("x", "Spoofed").copy(seq = 6), "fd00::9")
        assertEquals(CastStatus.CASTING, table.peers.value.single().status)
    }

    @Test
    fun nameCapNeverSplitsAnEmoji() {
        val tv = "\ud83d\udcfa"
        table.seen(ann("x", "n".repeat(39) + tv + tv), "10.9.9.9")
        assertEquals("n".repeat(39) + tv, table.peers.value[0].name)
    }

    @Test
    fun impostorOnAnotherHostIsIgnoredWhileHolderIsLive() {
        table.seen(ann("x", "Lounge").copy(seq = 5), "10.0.0.2")
        now = 1_000
        table.seen(ann("x", "Lounge").copy(seq = 9_999), "10.0.0.9")
        val peer = table.peers.value.single()
        assertEquals("10.0.0.2", peer.host)
        assertEquals(0L, peer.lastSeenMs)
        now = 1_500
        table.seen(ann("x", "Lounge").copy(seq = 6), "10.0.0.2")
        assertEquals(1_500L, table.peers.value.single().lastSeenMs)
    }

    @Test
    fun hostMovesAfterHolderGoesQuiet() {
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        now = 4_000
        table.seen(ann("x").copy(seq = 6), "10.0.0.3")
        assertEquals("10.0.0.2", table.peers.value.single().host)
        now = 4_001
        table.seen(ann("x").copy(seq = 6), "10.0.0.3")
        assertEquals("10.0.0.3", table.peers.value.single().host)
    }

    @Test
    fun hugeSeqJumpIgnoredWhileLive() {
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        now = 1_000
        table.seen(casting("x", seq = 5 + 10_001), "10.0.0.2")
        assertEquals(CastStatus.IDLE, table.peers.value.single().status)
        assertEquals(0L, table.peers.value.single().lastSeenMs)
        table.seen(casting("x", seq = 5 + 10_000), "10.0.0.2")
        assertEquals(CastStatus.CASTING, table.peers.value.single().status)
    }

    @Test
    fun hostsOutsideTheHomeNetworkAreIgnored() {
        table.seen(ann("pub"), "8.8.8.8")
        table.seen(ann("pub2"), "203.0.113.9")
        table.seen(ann("lan"), "192.168.1.9")
        assertEquals(listOf("lan"), table.peers.value.map { it.id })
    }

    @Test
    fun onlyPlainLocalIpv4LiteralsAreAccepted() {
        for (host in listOf("localhost", "010.0.0.2", "10.0.0.300", "10.0.0.2:80", "0.0.0.0", "::ffff:8.8.8.8")) {
            table.seen(ann(host), host)
        }
        assertEquals(0, table.peers.value.size)
        table.seen(ann("pad"), " 10.0.0.2 ")
        table.seen(ann("mapped"), "::FFFF:10.0.0.3")
        assertEquals(listOf("10.0.0.3", "10.0.0.2"), table.peers.value.map { it.host })
    }

    @Test
    fun restartedTvWithWallClockSeqAcceptedAfterQuiet() {
        table.seen(ann("x").copy(seq = 5), "10.0.0.2")
        now = 4_001
        table.seen(casting("x", seq = 1_700_000_000_000L), "10.0.0.2")
        assertEquals(CastStatus.CASTING, table.peers.value.single().status)
        now = 4_500
        table.seen(ann("x").copy(seq = 1_700_000_000_001L), "10.0.0.2")
        assertEquals(CastStatus.IDLE, table.peers.value.single().status)
    }
}
