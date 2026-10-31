package eu.kanade.tachiyomi.source.novel.js

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy

/**
 * Refuses to send a plugin request to this device or the local network, e.g. TorrServer on
 * 127.0.0.1 or the router: novel plugins only scrape public sites.
 *
 * A network interceptor sees the address actually connected to, so it also covers IP literals
 * (which skip DNS), redirects and DNS answers that point inside. Through a proxy the connected
 * address is the proxy's, so nothing is checked there.
 */
class PublicAddressInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val route = chain.connection()?.route()
        if (route != null && route.proxy.type() == Proxy.Type.DIRECT) {
            val address = route.socketAddress.address
            if (address != null && !address.isPublic()) {
                throw IOException("plugins may not connect to ${address.hostAddress}")
            }
        }
        return chain.proceed(chain.request())
    }
}

/** False for loopback, private, link-local, CGNAT, unique-local, unspecified and multicast addresses. */
internal fun InetAddress.isPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }
    val bytes = address
    return when (this) {
        // 100.64.0.0/10, carrier-grade NAT
        is Inet4Address -> !(bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64)
        // fc00::/7, unique local
        is Inet6Address -> (bytes[0].toInt() and 0xFE) != 0xFC
        else -> true
    }
}
