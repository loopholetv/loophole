package io.github.loopholetv.cast.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    private fun local(s: String) = isLocalPeer(InetAddress.getByName(s))

    @Test fun isLocalPeerCases() {
        assertTrue(local("10.0.0.1"))
        assertTrue(local("172.31.255.1"))
        assertFalse(local("172.32.0.1"))
        assertTrue(local("192.168.1.5"))
        assertFalse(local("8.8.8.8"))
        assertTrue(local("127.0.0.1"))
        assertTrue(local("169.254.1.1"))
        assertTrue(local("::1"))
        assertTrue(local("fd00::1"))
        assertTrue(local("fc00::1"))
        assertTrue(local("fe80::1"))
        assertFalse(local("2001:db8::1"))
        assertFalse(local("fec0::1"))
        assertFalse(local("::ffff:8.8.8.8"))
        assertTrue(local("::ffff:192.168.1.5"))
    }

    @Test fun localIpv4LiteralCases() {
        for (host in listOf("10.0.0.1", "192.168.1.5", "172.16.0.9", "169.254.3.4", "127.0.0.1", "10.0.0.0")) {
            assertTrue(host, isLocalIpv4Literal(host))
            assertEquals(host, localIpv4(host)?.hostAddress)
        }
        val refused = listOf(
            "8.8.8.8", "203.0.113.9", "0.0.0.0", "localhost", "tv.local", "example.test", "", "10.0.0", "10.0.0.1.2",
            "10.0.0.256", "010.0.0.1", "10.0.0.-1", "10.0.0.1 ", " 10.0.0.1", "10.0.0.1:80", "::1", "fd00::1",
            "::ffff:10.0.0.1", "10.0.0.1%wlan0", "1e1.0.0.1", "0x0a.0.0.1", "10..0.1", "１０.0.0.1",
        )
        for (host in refused) {
            assertFalse(host, isLocalIpv4Literal(host))
            assertNull(host, localIpv4(host))
        }
    }

    private fun c(address: String, transport: LanTransport = LanTransport.WIFI, active: Boolean = false) =
        LanCandidate(InetAddress.getByName(address), transport, active)

    private fun pick(vararg candidates: LanCandidate) = pickLanAddress(candidates.toList())?.hostAddress

    @Test fun picksSiteLocalV4OnWifi() {
        assertEquals("192.168.1.20", pick(c("fe80::1"), c("8.8.8.8"), c("192.168.1.20"), c("10.0.0.2")))
        assertNull(pick(c("8.8.8.8")))
        assertNull(pick())
    }

    @Test fun neverPicksLoopbackLinkLocalPublicOrIpv6() {
        assertNull(pick(c("127.0.0.1"), c("169.254.3.4"), c("100.64.0.1"), c("8.8.8.8"), c("fd00::1"), c("fe80::1")))
    }

    @Test fun neverPicksVpnOrOtherNetworks() {
        assertNull(pick(c("10.8.0.2", LanTransport.VPN, active = true), c("10.20.0.3", LanTransport.OTHER)))
        assertEquals(
            "192.168.1.9",
            pick(c("10.8.0.2", LanTransport.VPN, active = true), c("192.168.1.9", LanTransport.WIFI)),
        )
    }

    @Test fun activeNetworkWinsThenEthernet() {
        assertEquals(
            "192.168.1.9",
            pick(c("10.0.0.5", LanTransport.ETHERNET), c("192.168.1.9", LanTransport.WIFI, active = true)),
        )
        assertEquals(
            "10.0.0.5",
            pick(c("192.168.1.9", LanTransport.WIFI), c("10.0.0.5", LanTransport.ETHERNET)),
        )
    }
}
