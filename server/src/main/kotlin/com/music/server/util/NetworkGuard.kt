package com.music.server.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy

/**
 * Where the server's requests may go, checked on every connection (each redirect too), so
 * addresses given by people (their own sources, radio stations anyone can list) can't turn the
 * server on its own network:
 *
 *  - never cloud metadata or link-local addresses (no home server lives there);
 *  - never from a public server into the local network by a redirect;
 *  - [Reach.NO_LOOPBACK]: not the server's own machine (sources people add for themselves);
 *  - [Reach.PUBLIC]: only the internet (radio streams, which anyone can list).
 *
 * A request says how far it may reach with a [Reach] tag; a client can set a default.
 */
object NetworkGuard {
    enum class Reach { ANY, NO_LOOPBACK, PUBLIC }

    class Refused(message: String) : IOException(message)

    /** Tags [builder]'s request with how far it may reach. */
    fun Request.Builder.reach(reach: Reach): Request.Builder = tag(Reach::class.java, reach)

    fun interceptor(default: Reach = Reach.ANY) = Interceptor { chain ->
        val request = chain.request()
        val route = chain.connection()?.route()
        // Connected straight to the server: the address it's at. Through a proxy (which connects
        // for us), the addresses its name has.
        val addresses = when {
            route == null -> emptyList()
            route.proxy.type() == Proxy.Type.DIRECT -> listOfNotNull(route.socketAddress.address)
            else -> runCatching { InetAddress.getAllByName(request.url.host).toList() }.getOrDefault(emptyList())
        }
        if (addresses.isNotEmpty()) {
            val reach = maxOf(request.tag(Reach::class.java) ?: Reach.ANY, default)
            val original = chain.call().request().url
            val redirected = request.url.host != original.host
            val fromPublic = redirected && isPublicHost(original.host)
            addresses.firstNotNullOfOrNull { refusal(it, reach, redirectedFromPublic = fromPublic) }?.let { throw Refused("$it (${request.url.host})") }
        }
        chain.proceed(request)
    }

    /** Why [address] can't be reached, or null if it can. */
    fun refusal(address: InetAddress, reach: Reach, redirectedFromPublic: Boolean = false): String? = when {
        isMetadata(address) -> "Not allowed: a link-local or cloud metadata address"
        isLoopback(address) && reach >= Reach.NO_LOOPBACK -> "Not allowed: the server's own address"
        !isPublic(address) && reach == Reach.PUBLIC -> "Not allowed: a local network address"
        !isPublic(address) && redirectedFromPublic -> "Not allowed: a redirect into the local network"
        else -> null
    }

    fun isLoopback(a: InetAddress) = a.isLoopbackAddress || a.isAnyLocalAddress

    fun isMetadata(a: InetAddress): Boolean {
        val b = a.address
        return a.isLinkLocalAddress ||
            (a is Inet4Address && (b[0].toInt() and 0xff) == 169 && (b[1].toInt() and 0xff) == 254) ||
            (a is Inet4Address && a.hostAddress == "100.100.100.200") ||
            (a is Inet6Address && a.hostAddress.startsWith("fd00:ec2:"))
    }

    fun isPublic(a: InetAddress): Boolean {
        if (isLoopback(a) || isMetadata(a) || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            // Carrier-grade NAT (100.64/10) and 0/8 aren't the internet either.
            is Inet4Address -> !((b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) && (b[0].toInt() and 0xff) != 0
            // Unique local addresses (fc00::/7).
            is Inet6Address -> (b[0].toInt() and 0xfe) != 0xfc
            else -> true
        }
    }

    private fun isPublicHost(host: String): Boolean =
        runCatching { InetAddress.getAllByName(host).all(::isPublic) }.getOrDefault(false)

    /** Why a source at [host] can't be added by someone who isn't an admin, or null if it can. */
    fun refusalForHost(host: String, reach: Reach): String? {
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return null
        return addresses.firstNotNullOfOrNull { refusal(it, reach) }
    }
}
