package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentServiceLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualHttpsBasicParseDownloadPersistenceIsolationAndSource() = runBlocking {
        val credentialFile = File(context.cacheDir, "document-service-test-credentials.json")
        assertTrue("Synthetic live credentials must be supplied privately", credentialFile.exists())
        val credentials = Json.parseToJsonElement(credentialFile.readText()).jsonObject
        val token = credentials.getValue("token").jsonPrimitive.content
        val endpoint = credentials.getValue("baseUrl").jsonPrimitive.content
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        val materials = MaterialFiles(context)
        var repository = DocumentServiceRepository(context, materials)
        repository.configure(owner, endpoint, token, true)
        assertFalse(repository.enabled(other))
        assertTrue(runCatching { repository.configure(other, endpoint, token, true) }.isFailure)
        val privateConfig = File(context.noBackupFilesDir, "document-service/$owner/config.json").readText()
        assertFalse(privateConfig.contains(token))
        val capabilities = repository.capabilities(owner)
        assertTrue(capabilities.getValue("ready").jsonPrimitive.boolean)
        val source = File(context.cacheDir, "document-service-synthetic.pdf")
        assertTrue(source.exists())
        val material = materials.import(owner, source.toUri(), "合成数学扫描.pdf")
        val task = repository.submit(owner, material.id, 1, 5)
        assertEquals(task.id, repository.submit(owner, material.id, 1, 5).id)
        repository = DocumentServiceRepository(context, materials)
        assertEquals(task.id, repository.tasks(owner).single().id)
        assertTrue(repository.tasks(other).isEmpty())
        val finished = withTimeout(180000) {
            var current = repository.refresh(owner, task.id)
            while (current.state in setOf("QUEUED", "RUNNING")) { delay(2000); current = repository.refresh(owner, task.id) }
            current
        }
        assertEquals("SUCCEEDED", finished.state)
        val document = repository.download(owner, task.id)
        assertEquals(listOf(1, 2, 3, 4, 5), document.processedPages)
        assertEquals(listOf(3), document.blankPages)
        assertTrue(document.complete)
        assertTrue(document.blocks.any { it.text.contains("x") && it.text.contains("2") })
        assertTrue(document.blocks.any { it.type == "table_body" && it.text.contains("16") })
        assertTrue(document.blocks.any { it.latex?.contains("frac") == true })
        assertTrue(runCatching { repository.read(other, task.id) }.isFailure)
        val reopened = DocumentServiceRepository(context, materials)
        assertEquals(document, reopened.read(owner, task.id))
        val block = document.blocks.first()
        val sourceParts = reopened.sourcePage(owner, task.id, block.blockId)
        assertTrue(sourceParts.any { it is me.rerere.ai.ui.UIMessagePart.Image })
        assertTrue(runCatching { reopened.sourcePage(owner, task.id, "p200-b999") }.isFailure)
        val imageBlock = document.blocks.first { it.assetId != null }
        assertTrue(reopened.blockImage(owner, task.id, imageBlock.blockId).length() > 0)
        val file = File(context.noBackupFilesDir, "document-service/$owner/${task.id}.result/document")
        val original = file.readBytes()
        file.appendText("tampered")
        assertTrue(runCatching { reopened.read(owner, task.id) }.isFailure)
        file.writeBytes(original)
        delay(3000)
        reopened.cleanupRemote(owner, task.id)
        assertFalse(reopened.tasks(owner).single().cleanupPending)
        File(context.cacheDir, "document-service-live-evidence.json").writeText(buildJsonObject {
            put("owner", owner); put("taskId", task.id)
            put("https", true); put("tier", document.tier); put("processedPages", JsonArray(document.processedPages.map(::JsonPrimitive)))
            put("blankPage", 3); put("sourcePageRendered", true); put("formulaAndTable", true)
            put("ownerIsolation", true); put("corruptionRejected", true); put("restartPersistence", true); put("remoteCleanupConfirmed", true)
        }.toString())
        repository.configure(owner, endpoint, "", false)
        credentialFile.delete()
        Unit
    }
}
