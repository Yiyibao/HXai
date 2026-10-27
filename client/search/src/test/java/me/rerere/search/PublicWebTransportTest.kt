package me.rerere.search

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PublicWebTransportTest {
    @Test fun rejectsSpecialPurposeAddressesAndUntrustedAuthorities() {
        listOf("0.0.0.0", "10.0.2.2", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.31.255.255", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.1.1",
            "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "::ffff:127.0.0.1", "64:ff9b::a00:202", "fc00::1", "fe80::1",
            "2001::1", "2001:db8::1", "2002:a00:202::1", "3fff::1").forEach {
            assertFalse(it, PublicWebTransport.isPublicAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888").forEach {
            assertTrue(it, PublicWebTransport.isPublicAddress(InetAddress.getByName(it)))
        }
        listOf("http://localhost/", "https://host.local/", "http://8.8.8.8:8080/",
            "https://user:secret@source.org/", "file:///private", "http://2130706433/").forEach {
            assertTrue(it, runCatching { PublicWebTransport.checkedUrl(it) }.isFailure)
        }
    }

    @Test fun mixedPublicPrivateDnsAnswersNeverDispatch() = runBlocking {
        var exchanges = 0
        val transport = PublicWebTransport(Dns {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
        }) { _, _ -> exchanges++; error("Must not dispatch") }
        assertTrue(runCatching { transport.get("https://source.org/", "text/html") }.isFailure)
        assertEquals(0, exchanges)
    }

    @Test fun sameHostRedirectRechecksDnsBeforeDispatch() = runBlocking {
        var lookups = 0
        var exchanges = 0
        val transport = PublicWebTransport(Dns {
            listOf(InetAddress.getByName(if (lookups++ == 0) "8.8.8.8" else "127.0.0.1"))
        }) { request, _ ->
            exchanges++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "/next").body("".toResponseBody()).build()
        }
        assertTrue(runCatching { transport.get("https://source.org/", "text/html") }.isFailure)
        assertEquals(2, lookups)
        assertEquals(1, exchanges)
    }

    @Test fun redirectToCredentialsOrNonWebPortNeverDispatchesDestination() = runBlocking {
        listOf("https://user:secret@source.org/", "https://source.org:8443/").forEach { destination ->
            var exchanges = 0
            val transport = PublicWebTransport(Dns { listOf(InetAddress.getByName("8.8.8.8")) }) { request, _ ->
                exchanges++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", destination).body("".toResponseBody()).build()
            }
            assertTrue(runCatching { transport.get("https://source.org/", "text/html") }.isFailure)
            assertEquals(1, exchanges)
        }
    }
}
