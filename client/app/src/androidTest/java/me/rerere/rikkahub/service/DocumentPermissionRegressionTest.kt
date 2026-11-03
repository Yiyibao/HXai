package me.rerere.rikkahub.service

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.material.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class DocumentPermissionRegressionTest {
    @Test fun revokedUploadPreservesApprovedTaskOperationsWithoutResubmission() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "qa-permissions-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val owner = UUID.randomUUID().toString(); val files = MaterialFiles(context)
            val source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.png", "png")) {
                it.writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1UAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)); "Synthetic input"
            }
            val doc = ParsedDocument(1, source.sha256, 1, "synthetic", "1", "basic", 1,
                listOf(1, 1), listOf(1), emptyList(), emptyList(), true, listOf("synthetic pagination regression"),
                listOf(ParsedBlock("p1-b1", 1, "table_body", "a".repeat(23_997) + "TAIL", "\\frac{x}{y}".repeat(4000) + "LATEX_TAIL"),
                    ParsedBlock("p1-b2", 1, "text", "final block")))
            val bytes = Json.encodeToString(doc).toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val manifest = DocumentManifest(1, source.sha256, 1, "synthetic", "1", "basic", 1,
                listOf(1, 1), listOf(1), emptyList(), emptyList(), true, emptyList(), listOf(DocumentAsset("document", "document.json", "application/json", bytes.size.toLong(), hash)))
            val remote = UUID.randomUUID().toString()
            val requests = mutableListOf<String>(); val keys = mutableListOf<String>(); var lose = true; var expired = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); val path = request.url.encodedPath
                requests += "${request.method} $path"
                val upload = request.method == "POST" && path == "/api/v1/jobs"
                if (upload) { keys += request.header("Idempotency-Key")!!; if (lose) throw IOException("Synthetic lost receipt") }
                if (request.method == "DELETE") expired = true
                val body = when {
                    path.endsWith("/manifest") -> Json.encodeToString(manifest)
                    path.endsWith("/assets/document") -> bytes.toString(Charsets.UTF_8)
                    else -> "{\"taskId\":\"$remote\",\"state\":\"${if (expired) "EXPIRED" else "SUCCEEDED"}\"}"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            fun repository() = DocumentServiceRepository(context, files).also {
                DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(it, client)
            }
            var repo = repository(); repo.configure(owner, "https://qa.example.org", "a".repeat(32), true)
            assertTrue(runCatching { repo.submit(owner, source.id, 1, 1) }.isFailure)
            val intent = repo.tasks(owner).single(); repo = repository(); lose = false
            assertFalse(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("解析文件"), UIMessage.user("不要上传，仅本地处理"))))
            repo.syncPending(owner); assertEquals(1, keys.size)
            fun readArgs(id: String) = buildJsonObject { put("task_id", id); put("cursor", ""); put("count", "1") }
            val pendingReader = documentServiceTools(repo, owner, false, allowRemote = false).single { it.name == "read_material_parse_blocks" }
            val beforePending = requests.size
            assertTrue(runCatching { pendingReader.execute(readArgs(intent.id)) }.isFailure)
            assertEquals(beforePending, requests.size)
            // Only an explicit approved resume may submit the saved intent again.
            repo.resumeSubmission(owner, intent.id); assertEquals(listOf(intent.id, intent.id), keys)
            val tools = documentServiceTools(repo, owner, false, allowRemote = false)
            assertFalse(tools.any { it.name == "parse_material_enhanced" })
            tools.single { it.name == "get_material_parse_status" }.execute(buildJsonObject { put("task_id", intent.id) })
            val reader = tools.single { it.name == "read_material_parse_blocks" }
            var cursor = ""
            var pages = 0
            var cachedRequestCount = -1
            val restored = mutableMapOf<Pair<Int, String>, StringBuilder>()
            do {
                val raw = (reader.execute(buildJsonObject {
                    put("task_id", intent.id); put("cursor", cursor); put("count", "1")
                }).single() as UIMessagePart.Text).text
                assertTrue(raw.length <= 24_000)
                val page = Json.parseToJsonElement(raw).jsonObject
                assertEquals(source.id, page.getValue("source_id").jsonPrimitive.content)
                for (fragment in page.getValue("untrusted_blocks").jsonArray.map { it.jsonObject }) {
                    val key = fragment.getValue("block_index").jsonPrimitive.int to fragment.getValue("field").jsonPrimitive.content
                    val value = restored.getOrPut(key) { StringBuilder() }
                    assertEquals(value.length, fragment.getValue("char_offset").jsonPrimitive.int)
                    value.append(fragment.getValue("content").jsonPrimitive.content)
                }
                if (pages == 0) cachedRequestCount = requests.size else assertEquals(cachedRequestCount, requests.size)
                cursor = page["next_cursor"]?.jsonPrimitive?.content ?: ""
                assertTrue(++pages < 20)
            } while (cursor.isNotEmpty())
            assertTrue(pages > 3)
            doc.blocks.forEachIndexed { index, block ->
                assertEquals(block.text, restored.getValue(index to "text").toString())
                block.latex?.let { assertEquals(it, restored.getValue(index to "latex").toString()) }
                val metadata = Json.parseToJsonElement(restored.getValue(index to "metadata").toString()).jsonObject
                assertEquals(block.blockId, metadata.getValue("blockId").jsonPrimitive.content)
                assertEquals(block.type, metadata.getValue("type").jsonPrimitive.content)
                assertEquals(block.page, metadata.getValue("page").jsonPrimitive.int)
            }
            assertEquals(doc, repo.read(owner, intent.id))
            assertFalse(repo.tasks(owner).single().cleanupPending)
            assertTrue(requests.any { it.startsWith("DELETE ") })
            val beforeCached = requests.size
            assertTrue(reader.execute(readArgs(intent.id)).isNotEmpty()); assertEquals(beforeCached, requests.size)
            val cached = repo.availableResults(owner, source.id, 0, 20).getValue("results").jsonArray
            assertEquals(intent.id, cached.single().jsonObject.getValue("task_id").jsonPrimitive.content)
            assertTrue(cached.single().jsonObject.getValue("downloaded").jsonPrimitive.boolean)
            assertEquals(beforeCached, requests.size)
            assertTrue(runCatching { repo.availableResults(UUID.randomUUID().toString(), source.id, 0, 20) }.isFailure)
            val cachedDocument = File(context.noBackupFilesDir, "document-service/$owner/${intent.id}.result/document")
            cachedDocument.appendText("tampered")
            assertTrue(repo.availableResults(owner, source.id, 0, 20).getValue("results").jsonArray.isEmpty())
            val replacement = repo.submit(owner, source.id, 1, 1)
            assertNotEquals(intent.id, replacement.id)
            assertEquals(3, keys.size)
            cachedDocument.writeBytes(bytes)
            tools.single { it.name == "cancel_material_parse" }.execute(buildJsonObject { put("task_id", intent.id) })
            assertTrue(requests.any { it.endsWith("/cancel") })
            assertEquals(3, keys.size)
            assertTrue(repo.availableResults(owner, source.id, 0, 20).getValue("results").jsonArray.isEmpty())
            val beforeWrongOwner = requests.size
            val otherReader = documentServiceTools(repo, UUID.randomUUID().toString(), false, allowRemote = false).single { it.name == "read_material_parse_blocks" }
            assertTrue(runCatching { otherReader.execute(readArgs(intent.id)) }.isFailure)
            assertEquals(beforeWrongOwner, requests.size)
        } finally { root.deleteRecursively() }
    }
}
