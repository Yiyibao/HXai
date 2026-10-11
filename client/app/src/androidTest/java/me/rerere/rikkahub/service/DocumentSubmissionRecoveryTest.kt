package me.rerere.rikkahub.service

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.material.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class DocumentSubmissionRecoveryTest {
    @Test fun lostAcknowledgementKeepsIntentAndRecoveryUsesSameKeyAndConfiguration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "qa-submit-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val files = MaterialFiles(context); val owner = UUID.randomUUID().toString()
            val source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.png", "png")) { file ->
                file.writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1UAAAAASUVORK5CYII=", android.util.Base64.DEFAULT))
                "Synthetic input for submission contract testing"
            }
            val repository = DocumentServiceRepository(context, files)
            repository.configure(owner, "https://qa.example.org", "a".repeat(32), true)
            val requests = mutableListOf<String>(); val methods = mutableListOf<String>(); var lose = true
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                synchronized(requests) {
                    methods += chain.request().method
                    if (chain.request().method == "POST") requests += chain.request().header("Idempotency-Key")!!
                }
                if (lose && chain.request().method == "POST") throw IOException("Synthetic lost acknowledgement")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"taskId\":\"${UUID.randomUUID()}\",\"state\":\"QUEUED\"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(repository, client)
            val tool = documentServiceTools(repository, owner, false).single { it.name == "parse_material_enhanced" }
            val invalid = tool.execute(buildJsonObject { put("id", source.id); put("start_page", "bad"); put("end_page", "1") }).single() as UIMessagePart.Text
            assertTrue(invalid.text.contains("FAILED")); assertTrue(repository.tasks(owner).isEmpty())
            val pending = tool.execute(buildJsonObject { put("id", source.id); put("start_page", "1"); put("end_page", "1") }).single() as UIMessagePart.Text
            assertTrue(pending.text.contains("SUBMITTING")); assertFalse(pending.text.contains("SUCCEEDED"))
            val intent = repository.tasks(owner).single()
            repository.configure(owner, "https://other.example.org", "", true)
            assertTrue(runCatching { repository.resumeSubmission(owner, intent.id) }.isFailure)
            assertEquals(1, requests.size)
            repository.configure(owner, "https://qa.example.org", "", true); lose = false
            // The worker cannot inspect the latest chat's upload permission. It
            // must keep the intent without POSTing, even after network recovery.
            repository.syncPending(owner)
            assertEquals(1, requests.size)
            assertEquals("SUBMITTING", repository.tasks(owner).single().state)
            assertNull(repository.tasks(owner).single().remoteId)
            val resumed = repository.resumeSubmission(owner, intent.id)
            assertEquals(intent.id, resumed.id); assertEquals("QUEUED", resumed.state)
            assertEquals(listOf(intent.id, intent.id), requests)
            repository.syncPending(owner)
            assertEquals(listOf("POST", "POST", "GET"), methods)
            assertEquals(2, requests.size)
        } finally { root.deleteRecursively() }
    }
}
