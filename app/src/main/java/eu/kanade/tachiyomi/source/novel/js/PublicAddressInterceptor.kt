package eu.kanade.tachiyomi.source.novel.js

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/**
 * Keeps plugin requests off this device and the local network, e.g. TorrServer on 127.0.0.1 or
 * the router: novel plugins only scrape public sites.
 *
 * Everything is checked before connecting, so a plugin cannot tell open ports from closed ones:
 * - [PublicDns] drops inside addresses from DNS answers; OkHttp connects only to the addresses it
 *   returns, so a DNS answer that changes to an inside address is covered too;
 * - added as an application interceptor, this refuses IP literals, which skip DNS;
 * - added as a network interceptor, it refuses redirects to inside IP literals before OkHttp
 *   follows them, and as a last check the address actually connected to.
 *
 * Every refusal is the same [IOException]. Through a proxy the connected address is the proxy's,
 * so that last check is skipped there.
 */
class PublicAddressInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isInsideLiteral()) throw refused()
        val route = chain.connection()?.route()
        if (route != null && route.proxy.type() == Proxy.Type.DIRECT) {
            val address = route.socketAddress.address
            if (address != null && !address.isPublic()) throw refused()
        }
        val response = chain.proceed(request)
        val target = response.takeIf { it.isRedirect }?.header("Location")?.let(request.url::resolve)
        if (target != null && target.isInsideLiteral()) {
            response.close()
            throw refused()
        }
        return response
    }
}

/** [dns] without the inside addresses; a host with only inside addresses is refused. */
class PublicDns(private val dns: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        dns.lookup(hostname).filter { it.isPublic() }.ifEmpty { throw refused() }
}

private const val REFUSED = "plugins may only connect to public sites"

/** An [UnknownHostException], the one [IOException] a [Dns] may throw. */
private fun refused() = UnknownHostException(REFUSED)

/** Hosts OkHttp connects to without DNS: IPv6, dotted IPv4 and the other numeric IPv4 forms. */
private val IP_LITERAL = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")

/** True for an IP literal host that is not public. getByName does no DNS lookup for literals. */
internal fun HttpUrl.isInsideLiteral(): Boolean {
    if (!IP_LITERAL.matches(host)) return false
    // A numeric host that is no literal, e.g. 1.2.3.4.5, would be looked up: refused instead
    val parts = host.split('.')
    if (':' !in host && (parts.size > 4 || parts.any { it.isEmpty() })) return true
    return try {
        !InetAddress.getByName(host).isPublic()
    } catch (_: UnknownHostException) {
        true
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
