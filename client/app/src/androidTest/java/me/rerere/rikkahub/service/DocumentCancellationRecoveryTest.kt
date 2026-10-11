package me.rerere.rikkahub.service

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.feature.material.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real loopback HTTP/socket faults; real persistence, PDFs, credentials and repository recreation. */
class DocumentCancellationRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private class Fixture : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "parse-cancellation-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val owner = UUID.randomUUID().toString()
        val files = MaterialFiles(context)
        lateinit var source: MaterialFile
        val uploads = mutableListOf<String>()
        val cancelPaths = mutableListOf<String>()
        val ranges = mutableMapOf<String, Pair<Int, Int>>()
        var loseUploadAck = false
        var offlineCancel = false
        var accepted: CountDownLatch? = null
        var releaseUpload: CountDownLatch? = null
        private val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val serverFailure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "parse-cancellation-http") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val text = StringBuilder()
                            while (true) {
                                val byte = input.read()
                                check(byte >= 0) { "Unexpected HTTP EOF" }
                                if (byte == 10) return text.toString().removeSuffix("\r")
                                check(text.length < 8192)
                                text.append(byte.toChar())
                            }
                        }
                        val first = line().split(' ')
                        val headers = Headers.Builder()
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            headers.add(header.substringBefore(':'), header.substringAfter(':').trim())
                        }
                        val values = headers.build()
                        val length = values["Content-Length"]?.toInt() ?: 0
                        val bytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(bytes, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        val request = Request.Builder().url("http://127.0.0.1:${server.localPort}${first[1]}")
                            .headers(values).method(first[0], if (first[0] == "POST") ByteArray(0).toRequestBody() else null).build()
                        if (request.url.encodedPath == "/api/v1/jobs" && request.method == "POST") {
                            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            check(digest == source.sha256 && request.header("X-Source-Sha256") == digest)
                        }
                        val body = reply(request) ?: return@use // Accept, then drop the actual TCP acknowledgement.
                        val response = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(response)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) serverFailure.compareAndSet(null, error)
                }
            }
        }
        suspend fun prepare(): DocumentServiceRepository {
            source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "seven.pdf", "pdf")) { output ->
                val pdf = android.graphics.pdf.PdfDocument()
                try {
                    repeat(7) { index ->
                        val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                        page.canvas.drawText("Synthetic page ${index + 1}", 20f, 50f, android.graphics.Paint().apply { textSize = 16f })
                        pdf.finishPage(page)
                    }
                    output.outputStream().use { pdf.writeTo(it) }
                } finally { pdf.close() }
                "Synthetic cancellation input"
            }
            return repository().also { it.configure(owner, "https://qa.example.org", "c".repeat(32), true) }
        }
        fun repository(): DocumentServiceRepository = DocumentServiceRepository(context, files).also { repository ->
            // Preserve the production configuration contract; send real request
            // bytes to the emulator's local fault server, not an HTTP response mock.
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(server.localPort).build()).build())
            }.build()
            DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(repository, client)
        }
        private fun reply(request: Request): String? {
                val path = request.url.encodedPath
                return when {
                    path == "/api/v1/jobs" && request.method == "POST" -> {
                        val key = request.header("Idempotency-Key")!!
                        uploads += key
                        ranges[key] = request.url.queryParameter("start_page")!!.toInt() to request.url.queryParameter("end_page")!!.toInt()
                        accepted?.countDown()
                        releaseUpload?.let { check(it.await(10, TimeUnit.SECONDS)) }
                        if (loseUploadAck) return null
                        """{"taskId":"$key","state":"QUEUED"}"""
                    }
                    path.endsWith("/cancel") -> {
                        cancelPaths += path
                        if (offlineCancel) return null
                        val key = path.substringBeforeLast('/').substringAfterLast('/')
                        """{"taskId":"$key","state":"CANCELLED"}"""
                    }
                    else -> {
                        val key = path.substringAfter("/jobs/").substringBefore('/')
                        val (start, end) = ranges.getValue(key)
                        val document = ParsedDocument(1, source.sha256, 1, "synthetic-parser", "1", "basic", 7,
                            listOf(start, end), (start..end).toList(), emptyList(), emptyList(), false, emptyList(),
                            (start..end).map { ParsedBlock("p$it-b1", it, "text", "Synthetic page $it") })
                        val bytes = Json.encodeToString(document).toByteArray()
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        when {
                            path.endsWith("/manifest") -> Json.encodeToString(DocumentManifest(1, source.sha256, 1,
                                "synthetic-parser", "1", "basic", 7, listOf(start, end), (start..end).toList(),
                                emptyList(), emptyList(), false, emptyList(),
                                listOf(DocumentAsset("document", "document.json", "application/json", bytes.size.toLong(), hash))))
                            path.endsWith("/assets/document") -> bytes.toString(Charsets.UTF_8)
                            else -> "{\"taskId\":\"$key\",\"state\":\"${if (request.method == "DELETE") "EXPIRED" else "SUCCEEDED"}\"}"
                        }
                    }
                }
        }
        override fun close() {
            releaseUpload?.countDown()
            server.close()
            worker.join(10_000)
            root.deleteRecursively()
            serverFailure.get()?.let { throw AssertionError("Local cancellation HTTP fixture failed", it) }
        }
    }

    @Test fun acceptedWithoutAcknowledgementCancelsByRequestKeyWithoutReupload() = runBlocking {
        Fixture().use { f ->
            var repository = f.prepare()
            f.loseUploadAck = true
            assertTrue(runCatching { repository.submit(f.owner, f.source.id, 1, 1) }.isFailure)
            val pending = repository.tasks(f.owner).single()
            assertNull(pending.remoteId)
            val cancelled = repository.cancel(f.owner, pending.id)
            assertEquals(listOf("/api/v1/parse-requests/${pending.id}/cancel"), f.cancelPaths)
            assertEquals("CANCELLED", cancelled.state)
            assertTrue(cancelled.cancelRequested)
            assertTrue(cancelled.cancelConfirmed)
            repository = f.repository()
            repository.syncPending(f.owner)
            assertTrue(runCatching { repository.resumeSubmission(f.owner, pending.id) }.isFailure)
            assertEquals(listOf(pending.id), f.uploads)
            assertEquals(1, f.cancelPaths.size)
        }
    }

    @Test fun offlineCancelSurvivesRecreationAndDisabledConfiguration() = runBlocking {
        Fixture().use { f ->
            var repository = f.prepare()
            f.loseUploadAck = true
            assertTrue(runCatching { repository.submit(f.owner, f.source.id, 1, 1) }.isFailure)
            val id = repository.tasks(f.owner).single().id
            repository.configure(f.owner, "https://qa.example.org", "", false)
            assertTrue(runCatching { repository.cancel(f.owner, id) }.isFailure)
            repository = f.repository()
            assertTrue(repository.tasks(f.owner).single().cancelRequested)
            assertEquals("CANCELLING", repository.tasks(f.owner).single().state)
            repository.configure(f.owner, "https://qa.example.org", "", true)
            f.offlineCancel = true
            repository.syncPending(f.owner)
            assertFalse(repository.tasks(f.owner).single().cancelConfirmed)
            assertTrue(runCatching { repository.resumeSubmission(f.owner, id) }.isFailure)
            repository = f.repository()
            f.offlineCancel = false
            repository.syncPending(f.owner)
            assertTrue(repository.tasks(f.owner).single().cancelConfirmed)
            assertEquals("CANCELLED", repository.tasks(f.owner).single().state)
            assertEquals(listOf(id), f.uploads)
            assertEquals(List(2) { "/api/v1/parse-requests/$id/cancel" }, f.cancelPaths)
        }
    }

    @Test fun batchCancelRecordsEveryChildBeforeOfflineRequestsAndNeverRestartsFirstBatch() = runBlocking {
        Fixture().use { f ->
            var repository = f.prepare()
            val (batch, first) = repository.submitBatch(f.owner, f.source.id, 1, 7)
            repository.download(f.owner, first.id)
            f.loseUploadAck = true
            assertTrue(runCatching { repository.advanceBatch(f.owner, batch.id) {} }.isFailure)
            val children = repository.batchTasks(f.owner, repository.batch(f.owner, batch.id))
            assertEquals(2, children.size)
            assertNull(children.last().remoteId)
            f.offlineCancel = true
            assertTrue(runCatching { repository.cancelBatch(f.owner, batch.id) }.isFailure)
            assertEquals(2, f.cancelPaths.size)
            repository = f.repository()
            assertTrue(repository.batch(f.owner, batch.id).cancelled)
            assertTrue(repository.batchTasks(f.owner, repository.batch(f.owner, batch.id)).all { it.cancelRequested && !it.cancelConfirmed })
            var uploadPermissionChecks = 0
            assertTrue(runCatching { repository.advanceBatch(f.owner, batch.id) { uploadPermissionChecks++ } }.isFailure)
            assertEquals(0, uploadPermissionChecks)
            assertTrue(runCatching { repository.resumeSubmission(f.owner, children.last().id) }.isFailure)
            f.offlineCancel = false
            repository.syncPending(f.owner)
            assertTrue(repository.batchTasks(f.owner, repository.batch(f.owner, batch.id)).all { it.cancelRequested && it.cancelConfirmed })
            assertEquals(children.map { it.id }, f.uploads)
            assertTrue(f.cancelPaths.contains("/api/v1/jobs/${first.id}/cancel"))
            assertTrue(f.cancelPaths.contains("/api/v1/parse-requests/${children.last().id}/cancel"))
        }
    }

    @Test fun cancellationIntentCannotBeOverwrittenByLateUploadAcknowledgement() = runBlocking {
        Fixture().use { f ->
            val repository = f.prepare()
            f.accepted = CountDownLatch(1)
            f.releaseUpload = CountDownLatch(1)
            val upload = async(Dispatchers.IO) { repository.submit(f.owner, f.source.id, 1, 1) }
            try {
                assertTrue(withContext(Dispatchers.IO) { f.accepted!!.await(10, TimeUnit.SECONDS) })
                val id = repository.tasks(f.owner).single().id
                val cancel = async(Dispatchers.IO) { repository.cancel(f.owner, id) }
                withTimeout(10_000) { repository.changes.first { repository.tasks(f.owner).single().cancelRequested } }
                assertEquals("CANCELLING", repository.tasks(f.owner).single().state)
                f.releaseUpload!!.countDown()
                assertTrue(upload.await().cancelRequested)
                assertTrue(cancel.await().cancelConfirmed)
                assertEquals("CANCELLED", repository.tasks(f.owner).single().state)
                assertEquals(listOf(id), f.uploads)
                assertTrue(runCatching { repository.resumeSubmission(f.owner, id) }.isFailure)
            } finally { f.releaseUpload!!.countDown() }
        }
    }

    @Test fun previouslyCancelledBatchBlocksFirstUploadEvenWithoutChildCancellationFlags() = runBlocking {
        Fixture().use { f ->
            var repository = f.prepare()
            f.loseUploadAck = true
            assertTrue(runCatching { repository.submitBatch(f.owner, f.source.id, 1, 7) }.isFailure)
            val batch = repository.findBatch(f.owner, f.source.id, 1, 7)!!
            val pending = repository.batchTasks(f.owner, batch).single()
            File(f.context.noBackupFilesDir, "document-service/${f.owner}/${batch.id}.batch.json")
                .writeText(Json.encodeToString(batch.copy(cancelled = true)))
            repository = f.repository()
            assertFalse(repository.tasks(f.owner).single().cancelRequested)
            assertTrue(runCatching { repository.resumeSubmission(f.owner, pending.id) }.isFailure)
            repository.syncPending(f.owner)
            assertTrue(repository.tasks(f.owner).single().cancelConfirmed)
            assertEquals(listOf(pending.id), f.uploads)
            assertEquals(listOf("/api/v1/parse-requests/${pending.id}/cancel"), f.cancelPaths)
        }
    }

    @Test fun materialPanelOffersUnconfirmedCancelAndRemovesUploadRecoveryAfterOfflineCancel() {
        Fixture().use { f ->
            val repository = runBlocking { f.prepare() }
            f.loseUploadAck = true
            runBlocking { assertTrue(runCatching { repository.submit(f.owner, f.source.id, 1, 1) }.isFailure) }
            compose.setContent { MaterialTheme { Column { DocumentServicePanel(f.owner, listOf(f.source), repository) } } }
            compose.onNodeWithText("重试提交").assertIsDisplayed()
            f.offlineCancel = true
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("重试取消").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("重试提交").assertDoesNotExist()
            compose.onNodeWithText("CANCELLING", substring = true).assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("文档服务连接失败", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("重试取消").assertIsEnabled()
            f.offlineCancel = false
            compose.onNodeWithText("重试取消").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("CANCELLED", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("重试提交").assertDoesNotExist()
            assertEquals(1, f.uploads.size)
        }
    }
}
