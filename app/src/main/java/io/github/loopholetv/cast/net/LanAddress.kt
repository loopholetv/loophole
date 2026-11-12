package io.github.loopholetv.cast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * True for addresses that can only belong to the local network: loopback, 10/8, 172.16/12,
 * 192.168/16, 169.254/16, fc00::/7 and fe80::/10.
 */
fun isLocalPeer(a: InetAddress): Boolean = when (a) {
    is Inet4Address -> a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
    is Inet6Address -> a.mappedV4()?.let(::isLocalPeer)
        ?: (a.isLoopbackAddress || a.isLinkLocalAddress || (a.address[0].toInt() and 0xFE) == 0xFC)
    else -> false
}

/**
 * [host] as an address when it is a plain dotted IPv4 literal (four decimal parts of 0-255, no
 * leading zeros) on the local network ([isLocalPeer]); null for anything else. Never looks a name
 * up, so a name that happens to resolve to a local address is refused too.
 */
fun localIpv4(host: String): Inet4Address? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    val address = InetAddress.getByAddress(bytes) as Inet4Address
    return address.takeIf(::isLocalPeer)
}

/** True when [host] is an IPv4 literal on the local network; see [localIpv4]. */
fun isLocalIpv4Literal(host: String): Boolean = localIpv4(host) != null

private fun Inet6Address.mappedV4(): InetAddress? {
    val b = address
    val mapped = (0 until 10).all { b[it].toInt() == 0 } &&
        b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
    return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
}

/** The kind of network an address belongs to, as far as choosing a LAN address goes. */
internal enum class LanTransport { ETHERNET, WIFI, VPN, OTHER }

/** One address of one network; [active] marks the system's default network. */
internal data class LanCandidate(val address: InetAddress, val transport: LanTransport, val active: Boolean)

/**
 * The address to serve other TVs on: a site-local IPv4 address (10/8, 172.16/12, 192.168/16) of a
 * Wi-Fi or Ethernet network. The active network wins, then Ethernet over Wi-Fi, then list order.
 * VPN and other networks, loopback, link-local, public and IPv6 addresses are never picked.
 */
internal fun pickLanAddress(candidates: List<LanCandidate>): Inet4Address? = candidates
    .filter { it.transport == LanTransport.WIFI || it.transport == LanTransport.ETHERNET }
    .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
    .sortedWith(compareByDescending<LanCandidate> { it.active }.thenBy { it.transport != LanTransport.ETHERNET })
    .firstOrNull()
    ?.address as Inet4Address?

/** The site-local IPv4 address of the active Wi-Fi or Ethernet network, or null when there is none. */
@Suppress("DEPRECATION")
fun lanAddress(context: Context): Inet4Address? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    val active = cm.activeNetwork
    val candidates = cm.allNetworks.flatMap { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> LanTransport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanTransport.WIFI
            else -> LanTransport.OTHER
        }
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return@flatMap emptyList()
        lp.linkAddresses.map { LanCandidate(it.address, transport, network == active) }
    }
    return pickLanAddress(candidates)
}
