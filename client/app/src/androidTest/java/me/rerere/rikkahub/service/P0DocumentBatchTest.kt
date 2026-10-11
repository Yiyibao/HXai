package me.rerere.rikkahub.service

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** HTTP is synthetic here. This tests real repository files and restart/permission semantics, never OCR accuracy. */
class P0DocumentBatchTest {
    @Test fun sevenPagesPersistCoverExactlyOnceAndRevokedUploadBlocksNextBatch() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "p0-batch-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val owner = UUID.randomUUID().toString(); val files = MaterialFiles(context)
            val source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "seven.pdf", "pdf")) { file ->
                val pdf = android.graphics.pdf.PdfDocument()
                try { repeat(7) { i -> val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, i + 1).create()); page.canvas.drawText("Synthetic page ${i + 1}", 20f, 50f, android.graphics.Paint().apply { textSize = 16f }); pdf.finishPage(page) }; file.outputStream().use { pdf.writeTo(it) } } finally { pdf.close() }
                "Synthetic seven-page input"
            }
            val remote = mutableMapOf<String, Pair<Int, Int>>(); val uploads = mutableListOf<Pair<Int, Int>>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); val path = request.url.encodedPath
                val id = if (request.method == "POST" && path.endsWith("/jobs")) {
                    val range = request.url.queryParameter("start_page")!!.toInt() to request.url.queryParameter("end_page")!!.toInt()
                    uploads += range
                    request.header("Idempotency-Key")!!.also { remote[it] = range }
                } else path.substringAfter("/jobs/").substringBefore('/')
                val (start, end) = remote.getValue(id)
                val doc = ParsedDocument(1, source.sha256, 1, "synthetic-parser", "1", "basic", 7, listOf(start, end), (start..end).toList(), emptyList(), emptyList(), false, emptyList(), (start..end).map { ParsedBlock("p$it-b1", it, "text", "Synthetic page $it") })
                val bytes = Json.encodeToString(doc).toByteArray(); val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val manifest = DocumentManifest(1, source.sha256, 1, "synthetic-parser", "1", "basic", 7, listOf(start, end), (start..end).toList(), emptyList(), emptyList(), false, emptyList(), listOf(DocumentAsset("document", "document.json", "application/json", bytes.size.toLong(), hash)))
                val body = when { path.endsWith("/manifest") -> Json.encodeToString(manifest); path.endsWith("/assets/document") -> bytes.toString(Charsets.UTF_8); else -> "{\"taskId\":\"$id\",\"state\":\"${if (request.method == "DELETE") "EXPIRED" else "SUCCEEDED"}\"}" }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            fun repo() = DocumentServiceRepository(context, files).also { DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(it, client) }
            var repo = repo(); repo.configure(owner, "https://qa.example.org", "a".repeat(32), true)
            val (batch, first) = repo.submitBatch(owner, source.id, 1, 7)
            assertEquals(first.id, repo.submitBatch(owner, source.id, 1, 7).second.id); assertEquals(listOf(1 to 5), uploads)
            repo.download(owner, first.id); repo = repo()
            assertTrue(runCatching { repo.advanceBatch(owner, batch.id) { error("upload revoked") } }.isFailure)
            assertEquals(1, uploads.size); assertEquals((1..5).toList(), repo.read(owner, first.id).processedPages)
            val second = repo.advanceBatch(owner, batch.id) {}!!; assertEquals(6, second.start); assertEquals(7, second.end)
            repo.download(owner, second.id); repo = repo()
            assertNull(repo.advanceBatch(owner, batch.id) { error("completed batches must not upload") })
            assertEquals((1..7).toList(), repo.batchTasks(owner, repo.batch(owner, batch.id)).flatMap { repo.read(owner, it.id).processedPages })
            assertEquals(listOf(1 to 5, 6 to 7), uploads)
            repo.cancelBatch(owner, batch.id)
            assertTrue(runCatching { repo.advanceBatch(owner, batch.id) {} }.isFailure); assertEquals(2, uploads.size)
        } finally { root.deleteRecursively() }
    }
}
