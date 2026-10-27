package me.rerere.search

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.net.InetAddress
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebPageReaderTest {
    @Test fun readerExtractsMainContentAndActualPublicationDate() = runBlocking {
        val transport = PublicWebTransport(Dns { listOf(InetAddress.getByName("8.8.8.8")) }) { request, _ ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html><head><title>Original</title><meta property='article:published_time' content='2026-10-01'></head><body><nav>Noise</nav><article>Evidence<script>instruction</script></article></body></html>".toResponseBody("text/html".toMediaType())).build()
        }
        val result = WebPageReader(transport).read("https://source.org/article")
        assertEquals("Evidence", result.urls.single().content)
        assertEquals("Original", result.urls.single().metadata?.title)
        assertEquals("2026-10-01", result.urls.single().metadata?.publishedDate)
        assertNotNull(result.retrievedAt)
    }

    @Test fun rejectsPrivateAddressesAndCredentials() {
        listOf("http://127.0.0.1/", "http://10.0.2.2/", "http://[::1]/", "http://localhost/", "https://user:pass@source.org/", "file:///secret").forEach {
            assertTrue(it, runCatching { PublicWebTransport.checkedUrl(it) }.isFailure)
        }
    }

    @Test fun redirectToPrivateNetworkIsRejectedBeforeSecondRequest() = runBlocking {
        var calls = 0
        val transport = PublicWebTransport(Dns { listOf(InetAddress.getByName("8.8.8.8")) }) { request, _ ->
            calls++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "http://127.0.0.1/private").body("".toResponseBody()).build()
        }
        assertTrue(runCatching { WebPageReader(transport).read("https://source.org/") }.isFailure)
        assertEquals(1, calls)
    }
}
