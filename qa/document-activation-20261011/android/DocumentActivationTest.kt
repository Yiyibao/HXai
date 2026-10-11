package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class DocumentActivationTest {
    @Test fun activateCurrentProfileAndRunRealChatDocumentTool() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentialsFile = File(context.cacheDir, "document-activation-credentials.json")
        try {
            val credentials = Json.parseToJsonElement(credentialsFile.readText()).jsonObject
            val owner = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }.activeProfileId
            val materials = GlobalContext.get().get<MaterialFiles>()
            val documents = GlobalContext.get().get<DocumentServiceRepository>()
            val endpoint = credentials.getValue("baseUrl").jsonPrimitive.content
            val token = credentials.getValue("token").jsonPrimitive.content
            documents.configure(owner, endpoint, token, true)
            credentialsFile.delete()
            val capabilities = documents.capabilities(owner)
            assertTrue(capabilities.getValue("ready").jsonPrimitive.boolean)
            val allows = DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("允许上传合成测试文件，使用远端文档服务解析第一页")))
            assertTrue(allows)
            val tools = documentServiceTools(documents, owner, true, allows)
            val parse = tools.single { it.name == "parse_material_enhanced" }
            val source = File(context.cacheDir, "document-activation-synthetic.pdf")
            val material = materials.import(owner, source.toUri(), "文档服务连接测试.pdf")
            val output = parse.execute(buildJsonObject { put("id", material.id); put("start_page", "1"); put("end_page", "5") })
            val receipt = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            val id = receipt.getValue("task_id").jsonPrimitive.content
            val finished = withTimeout(240000) {
                var task = documents.refresh(owner, id)
                while (task.state in setOf("SUBMITTING", "QUEUED", "RUNNING")) { delay(2000); task = documents.refresh(owner, id) }
                task
            }
            assertEquals("SUCCEEDED", finished.state)
            val result = documents.download(owner, id)
            assertEquals(listOf(1, 2, 3, 4, 5), result.processedPages)
            assertTrue(result.blocks.isNotEmpty())
            val reread = DocumentServiceRepository(context, materials)
            assertTrue(reread.config(owner).enabled)
            assertEquals(result, reread.read(owner, id))
            documents.cleanupRemote(owner, id)
            File(context.cacheDir, "document-activation-evidence.json").writeText(buildJsonObject {
                put("profileId", owner); put("endpoint", endpoint); put("enabled", true)
                put("capabilitiesReady", true); put("officeReady", capabilities["officePdf"]?.jsonObject?.get("ready")?.jsonPrimitive?.booleanOrNull == true)
                put("chatToolExecuted", parse.name); put("taskId", id); put("state", finished.state)
                put("pages", result.processedPages.size); put("blocks", result.blocks.size)
                put("downloadVerified", true); put("reopenedConfigVerified", true)
                put("remoteCleanupConfirmed", !documents.tasks(owner).single { it.id == id }.cleanupPending)
            }.toString())
        } finally { credentialsFile.delete() }
    }
}
