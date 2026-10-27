package me.rerere.search

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Resolve once per hop; the socket uses exactly that validated address set. */
internal class PublicWebTransport(
    private val resolver: Dns = Dns.SYSTEM,
    private val exchange: suspend (Request, Dns) -> Response = { request, pinnedDns ->
        // Never inherit app/provider interceptors, credentials, cookies, proxy or cache.
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .dns(pinnedDns)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build().newCall(request).await()
    },
) {
    suspend fun get(rawUrl: String, accept: String): Response {
        var url = checkedUrl(rawUrl)
        repeat(6) { hop ->
            val addresses = resolver.lookup(url.host).toList()
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
                "Only public web addresses are supported"
            }
            val host = url.host
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    require(hostname == host) { "Unexpected web destination" }
                    return addresses
                }
            }
            val response = exchange(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", accept).build(), pinnedDns)
            if (response.code !in REDIRECTS) return response
            response.use {
                require(hop < 5) { "Too many redirects" }
                val location = requireNotNull(it.header("Location")) { "Redirect has no destination" }
                url = checkedUrl(requireNotNull(url.resolve(location)).toString())
            }
        }
        error("Too many redirects")
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        internal fun checkedUrl(raw: String): HttpUrl {
            require(validWebImageUrl(raw) != null) { "Invalid web URL" }
            val url = raw.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && (url.port == 80 || url.port == 443)) {
                "Unsupported web authority"
            }
            val host = url.host
            require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
                "Only public web addresses are supported"
            }
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                require(isPublicAddress(InetAddress.getByName(host))) { "Only public web addresses are supported" }
            }
            return url
        }

        internal fun isPublicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 255
                val b = bytes[1].toInt() and 255
                val c = bytes[2].toInt() and 255
                return when {
                    a == 0 || a == 10 || a == 127 || a >= 224 -> false
                    a == 100 && b in 64..127 -> false
                    a == 169 && b == 254 -> false
                    a == 172 && b in 16..31 -> false
                    a == 192 && b == 168 -> false
                    a == 192 && b == 0 && (c == 0 || c == 2) -> false
                    a == 192 && b == 88 && c == 99 -> false
                    a == 198 && b in 18..19 -> false
                    a == 198 && b == 51 && c == 100 -> false
                    a == 203 && b == 0 && c == 113 -> false
                    else -> true
                }
            }
            if (bytes.size != 16 || (bytes[0].toInt() and 0xe0) != 0x20) return false
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            val d = bytes[3].toInt() and 255
            // Reject special-purpose, documentation and IPv4-encapsulated ranges.
            return !(a == 0x20 && b == 1 && c < 2) &&
                !(a == 0x20 && b == 1 && c == 0x0d && d == 0xb8) &&
                !(a == 0x20 && b == 2) &&
                !(a == 0x3f && b == 0xff && c < 0x10)
        }
    }
}
