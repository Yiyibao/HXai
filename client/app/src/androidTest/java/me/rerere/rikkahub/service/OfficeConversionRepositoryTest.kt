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

/** HTTP replies are controlled; durable intents, crypto, downloads and PDF opening are real. */
class OfficeConversionRepositoryTest {
    private class Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "office-repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val owner = UUID.randomUUID().toString(); val files = MaterialFiles(context)
        lateinit var source: MaterialFile
        val remote = UUID.randomUUID().toString(); var uploads = 0; var cancelCalls = 0
        var loseAck = false; var loseCancelAck = false; var badDigest = false
        var advertisedVersion = "qa-1"; var boundVersion = "qa-1"
        val pdf = File(root, "actual.pdf")
        suspend fun prepare() {
            source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "sample.docx", "docx")) { output -> MaterialWriter.write(output, "docx", "Synthetic source", emptyList(), emptyList()) }
            MaterialWriter.write(pdf, "pdf", "Synthetic rendered response", emptyList(), emptyList())
        }
        fun repository(): DocumentServiceRepository {
            val docs = DocumentServiceRepository(context, files)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/capabilities") -> """{"officePdf":{"ready":true,"rendererVersion":"$advertisedVersion"}}"""
                    path.endsWith("/conversions") -> { uploads++; if (loseAck) throw java.io.IOException("synthetic lost acknowledgement"); """{"taskId":"$remote","operation":"office_pdf","state":"QUEUED","rendererVersion":"$boundVersion"}""" }
                    path.endsWith("/cancel") -> { cancelCalls++; if (loseCancelAck) throw java.io.IOException("synthetic lost cancel acknowledgement"); """{"taskId":"$remote","operation":"office_pdf","state":"CANCELLED"}""" }
                    path.endsWith("/manifest") -> Json.encodeToString(OfficePdfManifest(1, "office_pdf", source.sha256, "docx", "LibreOffice", boundVersion, OfficeRenderOptions(), 1, listOf("Controlled HTTP fixture; not an actual Office engine"), listOf(DocumentAsset("pdf", "output.pdf", "application/pdf", pdf.length(), if (badDigest) "0".repeat(64) else MaterialFiles.digest(pdf)))))
                    path.endsWith("/assets/pdf") -> null
                    request.method == "DELETE" -> """{"taskId":"$remote","cleanupState":"PENDING"}"""
                    else -> """{"taskId":"$remote","operation":"office_pdf","state":"SUCCEEDED"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(if (body == null) pdf.readBytes().toResponseBody("application/pdf".toMediaType()) else body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(docs, client)
            OfficeConversionRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(docs.officeConversions, client)
            return docs
        }
    }
    @Test fun downloadBindsSourceHashAndOwnerAndSurvivesRecreation() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); var docs = f.repository(); docs.configure(f.owner, "https://qa.example.org", "a".repeat(32), true)
            val task = docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Output")
            assertEquals(task.id, docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Output").id); assertEquals(1, f.uploads)
            val artifact = docs.officeConversions.download(f.owner, task.id); assertEquals(MaterialFiles.digest(f.pdf), artifact.sha256)
            docs = f.repository(); assertEquals(artifact.id, docs.officeConversions.artifact(f.owner, task.id).id)
            assertTrue(runCatching { docs.officeConversions.artifact(UUID.randomUUID().toString(), task.id) }.isFailure)
            f.files.file(artifact).appendText("corrupt"); assertTrue(runCatching { docs.officeConversions.artifact(f.owner, task.id) }.isFailure)
            f.files.file(artifact).writeBytes(f.pdf.readBytes())
            docs.configure(f.owner, "https://changed.example.org", "", true)
            assertTrue(runCatching { docs.officeConversions.refresh(f.owner, task.id) }.isFailure); assertEquals(1, f.uploads)
            f.files.file(f.source).appendText("changed source"); assertTrue(runCatching { docs.officeConversions.artifact(f.owner, task.id) }.isFailure)
        } finally { f.root.deleteRecursively() }
    }
    @Test fun lostAcknowledgementCancelNeverReuploadsAndWrongDigestNeverSucceeds() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); var docs = f.repository(); docs.configure(f.owner, "https://qa.example.org", "b".repeat(32), true)
            f.loseAck = true
            assertTrue(runCatching { docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Lost") }.isFailure)
            val pending = docs.officeConversions.tasks(f.owner).single(); assertEquals("SUBMITTING", pending.state)
            f.loseCancelAck = true
            assertTrue(runCatching { docs.officeConversions.cancel(f.owner, pending.id) }.isFailure)
            docs = f.repository(); assertTrue(docs.officeConversions.task(f.owner, pending.id).cancelRequested)
            f.loseCancelAck = false; docs.officeConversions.syncPending(f.owner)
            assertEquals("CANCELLED", docs.officeConversions.task(f.owner, pending.id).state)
            assertFalse(docs.officeConversions.task(f.owner, pending.id).cancelRequested)
            assertTrue(runCatching { docs.officeConversions.resume(f.owner, pending.id) }.isFailure); assertEquals(1, f.uploads); assertEquals(2, f.cancelCalls)
            f.loseAck = false; f.badDigest = true
            val task = docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Bad hash")
            assertTrue(runCatching { docs.officeConversions.download(f.owner, task.id) }.isFailure)
            assertFalse(docs.officeConversions.task(f.owner, task.id).downloaded)
            assertTrue(f.files.list(f.owner).none { it.output && it.state == "SUCCEEDED" })
        } finally { f.root.deleteRecursively() }
    }
    @Test fun lostAcknowledgementResumesOriginalIntentAcrossRendererUpgrade() = runBlocking {
        for (boundVersion in listOf("qa-1", "qa-2")) {
            val f = Fixture()
            try {
                f.prepare()
                var docs = f.repository()
                docs.configure(f.owner, "https://qa.example.org", "d".repeat(32), true)
                f.loseAck = true
                assertTrue(runCatching {
                    docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Upgrade")
                }.isFailure)
                val pending = docs.officeConversions.tasks(f.owner).single()
                f.advertisedVersion = "qa-2"
                // The original request either reached the old engine or never
                // reached the server. Its receipt determines the bound engine.
                f.boundVersion = boundVersion
                f.loseAck = false
                docs = f.repository()
                val recovered = docs.officeConversions.resume(f.owner, pending.id)
                assertEquals(pending.id, recovered.id)
                assertEquals(pending.outputId, recovered.outputId)
                assertEquals(boundVersion, recovered.rendererVersion)
                val artifact = docs.officeConversions.download(f.owner, recovered.id)
                assertEquals(MaterialFiles.digest(f.pdf), artifact.sha256)
                docs = f.repository()
                assertEquals(artifact.id, docs.officeConversions.artifact(f.owner, recovered.id).id)
                assertEquals(pending.id,
                    docs.officeConversions.submit(f.owner, f.source.id, f.source.sha256, "Upgrade").id)
                assertEquals(2, f.uploads)
            } finally { f.root.deleteRecursively() }
        }
    }

    @Test fun currentLocalOnlyPermissionPreventsOfficeUpload() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); val docs = f.repository(); docs.configure(f.owner, "https://qa.example.org", "c".repeat(32), true)
            val result = materialEditTools(f.files, f.owner, docs, allowRemote = false).single { it.name == "convert_material_to_pdf" }.execute(buildJsonObject { put("id", f.source.id); put("expected_sha256", f.source.sha256); put("title", "No upload") })
            assertTrue(result.isNotEmpty()); assertEquals(0, f.uploads); assertTrue(docs.officeConversions.tasks(f.owner).isEmpty())
        } finally { f.root.deleteRecursively() }
    }
}
