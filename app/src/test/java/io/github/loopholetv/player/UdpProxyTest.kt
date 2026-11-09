package io.github.loopholetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpProxyTest {
    private val proxy = UdpProxy("192.168.1.1", 4022)
    private val base = "http://192.168.1.1:4022"

    @Test fun rewritesResearchExamples() {
        assertEquals("$base/udp/239.1.1.1:1234", rewriteForProxy("udp://@239.1.1.1:1234", proxy))
        assertEquals("$base/rtp/239.1.1.1:5000", rewriteForProxy("rtp://239.1.1.1:5000", proxy))
        assertEquals("$base/udp/10.0.0.5@232.1.1.1:5000", rewriteForProxy("udp://10.0.0.5@232.1.1.1:5000", proxy))
        assertEquals("$base/udp/239.1.1.1:1234", rewriteForProxy("udp://@239.1.1.1:1234?pkt_size=1316", proxy))
        assertEquals("$base/udp/239.1.1.1:1", rewriteForProxy("UDP://@239.1.1.1:1", proxy))
        assertEquals("$base/udp/239.1.1.1:1234", rewriteForProxy("igmp://239.1.1.1:1234", proxy))
        assertEquals("$base/udp/[ff15::1]:1234", rewriteForProxy("udp://@[ff15::1]:1234", proxy))
        assertEquals("$base/udp/239.1.1.1:1234", rewriteForProxy("udp://@239.1.1.1:1234#frag", proxy))
    }

    @Test fun leavesOtherUrlsAndNoProxyAlone() {
        assertEquals("http://a.test/x.ts", rewriteForProxy("http://a.test/x.ts", proxy))
        assertEquals("https://a.test/x.ts", rewriteForProxy("https://a.test/x.ts", proxy))
        assertEquals("udp://@239.1.1.1:1234", rewriteForProxy("udp://@239.1.1.1:1234", null))
    }

    @Test fun proxyInputNormalised() {
        assertEquals(UdpProxy("192.168.1.1", 4022), parseUdpProxy("http://192.168.1.1:4022/"))
        assertEquals(UdpProxy("192.168.1.1", 4022), parseUdpProxy(" https://192.168.1.1/ "))
        assertEquals(UdpProxy("192.168.1.1", 4022), parseUdpProxy("192.168.1.1"))
        assertEquals(UdpProxy("router.lan", 4000), parseUdpProxy("router.lan:4000"))
        assertEquals(UdpProxy("[fe80::1]", 4022), parseUdpProxy("[fe80::1]"))
        assertEquals(UdpProxy("[fe80::1]", 99), parseUdpProxy("[fe80::1]:99"))
        assertNull(parseUdpProxy("h:99999"))
        assertNull(parseUdpProxy("h:0"))
        assertNull(parseUdpProxy("h:99999999999"))
        assertNull(parseUdpProxy("a@b:80"))
        assertNull(parseUdpProxy("a?b"))
        assertNull(parseUdpProxy("a#b"))
        assertNull(parseUdpProxy(":4022"))
        assertNull(parseUdpProxy(""))
        assertNull(parseUdpProxy(null))
    }

    @Test fun asSettingRoundTrips() {
        assertEquals("192.168.1.1:4022", parseUdpProxy("http://192.168.1.1:4022/")?.asSetting())
        listOf("192.168.1.1:4022", "router.lan:4000", "[fe80::1]:99").forEach {
            assertEquals(it, parseUdpProxy(it)?.asSetting())
        }
    }

    @Test fun unbracketedHostBuiltDirectlyIsBracketed() {
        assertEquals("[::1]:1", UdpProxy("::1", 1).asSetting())
        assertEquals("http://[::1]:1/udp/239.1.1.1:1", rewriteForProxy("udp://@239.1.1.1:1", UdpProxy("::1", 1)))
    }

    @Test fun edgeCases() {
        assertEquals("$base/udp/239.1.1.1:1234/", rewriteForProxy("udp://@239.1.1.1:1234/", proxy))
        assertEquals("udp://@", rewriteForProxy("udp://@", proxy))
        assertEquals("$base/rtp/g:p", rewriteForProxy("rtp://@g:p", proxy))
    }

    @Test fun ipv6ProxyRewrites() {
        assertEquals(
            "http://[fe80::1]:99/udp/239.1.1.1:1",
            rewriteForProxy("udp://@239.1.1.1:1", parseUdpProxy("[fe80::1]:99")),
        )
    }
}
