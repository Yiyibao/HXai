package me.rerere.rikkahub.feature.agent

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Real Chat/approval/journal -> loopback FastAPI/UNO -> independent native readback.
 * Model responses are synthetic; document responses are real except explicit transport faults. */
@RunWith(AndroidJUnit4::class)
class XlsxSharedChatRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    @Test fun realChatEditsApprovesRecalculatesAndDeliversEditableVersion() = scenario("stable")
    @Test fun witnessChangedDuringSemanticAcceptanceCannotSucceed() = scenario("witness")
    @Test fun sourceChangedDuringSemanticAcceptanceCannotSucceed() = scenario("source")
    @Test fun cancellationDuringSemanticAcceptanceCannotSucceed() = scenario("cancel")
    @Test fun revocationDuringSemanticAcceptanceCannotSucceed() = scenario("revoke")
    @Test fun downloadedCommitWithoutChatAckResumesSameVersion() = scenario("downloadAck")
    @Test fun forgedWitnessCannotPublishAnArtifact() = scenario("forged")
    @Test fun missingWitnessCannotPublishAnArtifact() = scenario("missing")
    @Test fun uploadAckLossKeepsUnknownAndNeverReuploads() = scenario("uploadAck")

    private fun toolReply(name: String, args: JsonObject, call: String) = buildJsonObject {
        put("id", call); put("model", "qa-xlsx-locked")
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("finish_reason", "tool_calls")
            put("message", buildJsonObject {
                put("role", "assistant"); put("content", "")
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", call); put("type", "function")
                    put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
                }) })
            })
        }) })
    }.toString()

    private fun scenario(mode: String): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }; val owner = original.activeProfileId
        val materials = k.get<MaterialFiles>(); val documents = k.get<DocumentServiceRepository>(); val office = documents.officeConversions
        val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>(); val scheduler = AgentScheduler.shared(context)
        val fixture = Json.parseToJsonElement(File(context.cacheDir, "xlsx-chat-fixture.json").readText()).jsonObject
        val source = materials.import(owner, File(context.cacheDir, "xlsx-chat-source.xlsx").toUri(), "XLSX Chat synthetic source.xlsx")
        val sourceBytes = materials.verifiedFile(owner, source.id).readBytes()
        val id = Uuid.random(); val title = "XLSX Chat $mode ${id.toString().take(8)}"
        val uploads = AtomicInteger(); val main = AtomicInteger(); val semantic = AtomicInteger()
        val proof = File(context.cacheDir, "xlsx-chat-proof/$mode").apply { mkdirs() }
        var edited: MaterialFile? = null
        var facade: StudyHostFacade? = null; var surface: ActivityScenario<StudyFlutterActivity>? = null
        val restoredFiles = mutableListOf<Pair<File, ByteArray>>()
        val oldConfigFile = File(context.noBackupFilesDir, "document-service/$owner/config.json")
        val oldConfig = oldConfigFile.takeIf { it.exists() }?.readBytes()
        fun officeTask() = office.tasks(owner).single { it.sourceId == requireNotNull(edited).id && it.title == title }
        fun witness() = File(context.noBackupFilesDir, "document-service/$owner/${officeTask().id}.office-calculation.json")
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request(); check(request.url.host == "xlsx-chat.example.org")
            val target = request.url.newBuilder().scheme("http").host("127.0.0.1").port(fixture.getValue("port").jsonPrimitive.int).build()
            val response = chain.proceed(request.newBuilder().url(target).build())
            if (request.url.encodedPath.endsWith("/recalculations")) {
                uploads.incrementAndGet()
                if (mode == "uploadAck") { response.close(); throw java.io.IOException("Synthetic lost upload ACK after real server accepted bytes") }
            }
            if(request.url.encodedPath.endsWith("/assets/calculation")) {
                if(mode=="missing") return@addInterceptor response.newBuilder().code(404).message("Synthetic missing asset").build()
                if(mode=="forged") {
                    val type=response.body.contentType(); response.close()
                    return@addInterceptor response.newBuilder().body("{\"forged\":true}".toResponseBody(type)).build()
                }
            }
            response
        }.build()
        val docHttp = DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }
        val officeHttp = OfficeConversionRepository::class.java.getDeclaredField("http").apply { isAccessible = true }
        val oldDocHttp = docHttp.get(documents); val oldOfficeHttp = officeHttp.get(office)
        val model = Model(modelId = "qa-xlsx-locked", abilities = listOf(ModelAbility.TOOL))
        val changed = Model(modelId = "qa-xlsx-picker-changed", abilities = listOf(ModelAbility.TOOL))
        val server = LocalModelServer { body ->
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val first = messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps", buildJsonArray {
                        for ((step, tool) in listOf("inspect" to "inspect_material_edit", "edit" to "edit_material_file", "recalc" to "recalculate_material_xlsx")) {
                            add(buildJsonObject { put("id", step); put("tool", tool); put("purpose", "Preserve source and deliver actually recalculated editable workbook") })
                        }
                    })
                    put("acceptance", buildJsonArray { add(buildJsonObject {
                        put("stepId", "recalc"); put("kind", "artifact_content"); put("format", "xlsx")
                        put("contains", buildJsonArray { add("21"); add("sum=22"); add("001") })
                    }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    semantic.incrementAndGet()
                    val evidence = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    File(proof, "semantic-input.json").writeText(evidence.toString())
                    when (mode) {
                        "witness" -> witness().let { restoredFiles += it to it.readBytes(); it.appendText("corrupt witness after semantic input") }
                        "source" -> materials.file(requireNotNull(edited)).let { restoredFiles += it to it.readBytes(); it.appendText("changed source") }
                        "cancel" -> runBlocking { office.cancel(owner, officeTask().id) }
                        "revoke" -> AgentTaskStore(context).use { store ->
                            val task = requireNotNull(store.current(owner, id.toString()))
                            store.command(owner, task.id, Uuid.random().toString(), task.revision, task.fence, "revoke")
                        }
                    }
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted", true); put("goalRevision", evidence.getValue("goalRevision")); put("evidenceHash", evidence.getValue("evidenceHash"))
                        put("reason", "Synthetic evaluator; host must still verify original source and calculation evidence")
                    }.toString())
                }
                else -> when (main.incrementAndGet()) {
                    1 -> toolReply("inspect_material_edit", buildJsonObject {
                        put("id", source.id); put("selection", "{\"sheet\":\"Data\",\"offset\":0,\"count\":20}")
                    }, "xlsx-inspect")
                    2 -> toolReply("edit_material_file", buildJsonObject {
                        put("id", source.id); put("expected_sha256", source.sha256); put("operation", "set_cells"); put("title", "$title edited")
                        put("spec", "{\"sheet\":\"Data\",\"changes\":[{\"cell\":\"A2\",\"expected\":2,\"value\":7}]}")
                    }, "xlsx-edit")
                    3 -> {
                        edited = materials.list(owner).single { it.name == "$title edited.xlsx" }
                        toolReply("recalculate_material_xlsx", buildJsonObject {
                            put("id", requireNotNull(edited).id); put("expected_sha256", requireNotNull(edited).sha256); put("title", title)
                        }, "xlsx-recalc")
                    }
                    else -> LocalModelServer.completion("The user needs the verified editable XLSX, not a queue receipt or static PDF.")
                }
            }
        }
        val provider = ProviderSetting.OpenAI(name = "QA XLSX loopback", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model, changed))
        val assistant = Assistant(name = "QA XLSX", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val accepted = original.copy(providers = listOf(provider), assistants = original.assistants + assistant, assistantId = assistant.id,
            chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
            learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) }, networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
        suspend fun idle() = withTimeout(60_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(25) }
        try {
            docHttp.set(documents, client); officeHttp.set(office, client)
            documents.configure(owner, "https://xlsx-chat.example.org", fixture.getValue("tokens").jsonObject.getValue(mode).jsonPrimitive.content, true)
            settings.update(accepted); settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id); service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner, title = title, newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("允许上传：检查 ${source.id}/${source.sha256}，仅将Data!A2从2改为7，保留两表、日期、001字符串、隐藏Summary及关联图表，真实重算后交付可编辑XLSX，B2=21、跨表sum=22。")), Uuid.random())
            idle()
            val pending = service.getConversationFlow(id).value.currentMessages.flatMap { it.getTools() }.singleOrNull { it.toolName == "recalculate_material_xlsx" }
            assertNotNull("The real Chat factory must expose the XLSX tool and reach trusted approval", pending)
            assertTrue("No upload before foreground user approval", requireNotNull(pending).isPending)
            assertEquals(0, uploads.get())
            val originalTask = requireNotNull(scheduler.current(service.getConversationFlow(id).value))
            settings.update(accepted.copy(chatModelId = changed.id, assistants = accepted.assistants.map { if (it.id == assistant.id) it.copy(chatModelId = changed.id) else it }))
            settings.settingsFlow.first { it.chatModelId == changed.id }
            service.handleToolApproval(id, pending.toolCallId, true)
            idle()
            val remote = officeTask()
            assertEquals(1, uploads.get())
            assertNotEquals("A queue receipt is not whole-goal success", "SUCCEEDED", scheduler.get(owner, originalTask.id).state)
            val saved = File(proof, "saved.xlsx")
            facade = StudyHostFacade(context, settings, repo, service, k.get(), materials, k.get(), pickFiles = { emptyList() },
                saveFile = { name, mime -> assertEquals("$title.xlsx", name); assertEquals(XLSX_MEDIA_TYPE, mime); saved.toUri() }, documents = documents)
            val epoch = facade.getBootstrap(owner, 1L).contextEpoch
            if (mode == "uploadAck") {
                assertEquals("SUBMITTING", remote.state); assertNull(remote.remoteId)
                assertEquals("UNCERTAIN_SIDE_EFFECT", scheduler.get(owner, originalTask.id).state)
                facade.watchConversation(owner, id.toString(), epoch); delay(1500)
                assertEquals(1, uploads.get())
                AgentTaskStore(context).use { store ->
                    store.readableDatabase.rawQuery("SELECT state FROM calls WHERE task_id=? AND call_id='xlsx-recalc'", arrayOf(originalTask.id)).use {
                        assertTrue(it.moveToFirst()); assertEquals("UNKNOWN", it.getString(0))
                    }
                    assertTrue(runCatching { store.claim(owner, originalTask.id) }.isFailure)
                }
                File(proof, "result.json").writeText("{\"uploadAckLost\":true,\"uploads\":1,\"unknownRetained\":true}")
                return@runBlocking
            }
            assertNotNull(remote.remoteId)
            withTimeout(180_000) {
                while (office.refresh(owner, remote.id).state != "SUCCEEDED") {
                    check(office.task(owner, remote.id).state !in setOf("FAILED", "CANCELLED")); delay(100)
                }
            }
            if (mode == "downloadAck") {
                office.download(owner, remote.id)
                assertEquals(remote.outputId, DocumentServiceRepository(context, materials).officeConversions.artifact(owner, remote.id).id)
            }
            facade.watchConversation(owner, id.toString(), epoch)
            withTimeout(90_000) {
                while (true) {
                    val task = scheduler.get(owner, originalTask.id)
                    if (service.getGenerationJobStateFlow(id).first() == null && (semantic.get() > 0 || task.state == "FAILED_BLOCKED")) break
                    delay(50)
                }
            }
            facade.close(); facade = null
            val current = requireNotNull(repo.getConversationById(id)); val task = scheduler.get(owner, originalTask.id)
            val succeeds = mode in setOf("stable", "downloadAck")
            assertEquals(mode, succeeds, task.state == "SUCCEEDED")
            assertEquals(if (mode in setOf("forged", "missing")) 0 else 1, semantic.get())
            assertEquals(1, uploads.get()); assertEquals(originalTask.id, requireNotNull(scheduler.current(current)).id)
            assertEquals(1, current.currentMessages.count { it.role == me.rerere.ai.core.MessageRole.USER })
            assertTrue(server.bodies.all { Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content == model.modelId })
            assertArrayEquals(sourceBytes, materials.verifiedFile(owner, source.id).readBytes())
            if(mode in setOf("forged","missing")) {
                assertFalse(office.task(owner,remote.id).downloaded)
                assertTrue(materials.list(owner).none { it.id==remote.outputId && it.state=="SUCCEEDED" })
            }
            AgentTaskStore(context).use { store ->
                assertEquals(succeeds, store.plan(owner, task.id).acceptanceVerified)
                store.readableDatabase.rawQuery("SELECT tool,COUNT(*) FROM calls WHERE task_id=? AND tool NOT LIKE 'provider:%' GROUP BY tool", arrayOf(task.id)).use { rows ->
                    var count = 0; while (rows.moveToNext()) { assertEquals(1, rows.getInt(1)); count++ }; assertEquals(3, count)
                }
            }
            if (succeeds) {
                val artifact = office.artifact(owner, remote.id)
                assertEquals(requireNotNull(edited).id, artifact.sourceId)
                assertEquals(setOf(source.id, requireNotNull(edited).id, artifact.id), MaterialVersions.family(materials.list(owner), artifact.id).map { it.id }.toSet())
                val data = OfficePackage.read(materials.verifiedFile(owner, artifact.id))
                assertTrue(data.keys.any { it.startsWith("xl/charts/") && it.endsWith(".xml") })
                val values=Json.parseToJsonElement(witness().readText()).jsonObject.getValue("cells").jsonArray.map { it.jsonObject }
                assertEquals(0.0,values.single { it.getValue("sheet").jsonPrimitive.content=="Summary" && it.getValue("cell").jsonPrimitive.content=="F1" }.getValue("number").jsonPrimitive.double,0.0)
                val originalParts = OfficePackage.read(materials.verifiedFile(owner, source.id)); val editedParts = OfficePackage.read(materials.verifiedFile(owner, requireNotNull(edited).id))
                originalParts.filterKeys { it !in setOf("xl/worksheets/sheet1.xml", "xl/worksheets/summary9.xml", "xl/workbook.xml", "xl/calcChain.xml", "xl/_rels/workbook.xml.rels", "[Content_Types].xml") }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, editedParts.getValue(name)) }
                facade = StudyHostFacade(context, settings, repo, service, k.get(), materials, k.get(), pickFiles = { emptyList() },
                    saveFile = { _, mime -> assertEquals(XLSX_MEDIA_TYPE, mime); saved.toUri() }, documents = documents)
                val freshEpoch = facade.getBootstrap(owner, epoch).contextEpoch
                val preview = facade.getArtifactPreview(owner, artifact.id, freshEpoch)
                assertTrue(preview.previewContent!!.contains("sum=22")); assertTrue(preview.statusDetail!!.contains("真实重算"))
                assertTrue(runCatching { office.artifact(Uuid.random().toString(),remote.id) }.isFailure)
                assertTrue(runCatching { facade.getArtifactPreview(owner,artifact.id,freshEpoch+1) }.isFailure)
                val partDtos = facade.getConversationDetails(owner, id.toString()).nodes.flatMap { it.branches }.flatMap { it.parts }
                assertEquals(1, partDtos.count { it.artifactId == artifact.id && it.mime == XLSX_MEDIA_TYPE })
                assertTrue(facade.saveArtifact(owner, artifact.id).success)
                assertEquals(artifact.sha256, MaterialFiles.digest(saved))
                materials.verifiedFile(owner, artifact.id).copyTo(File(proof, "recalculated.xlsx"), overwrite = true)
                witness().copyTo(File(proof, "calculation.json"), overwrite = true)
                if (mode == "stable") {
                    surface = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/artifacts"))
                    val deadline = System.currentTimeMillis() + 40000
                    fun text(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
                        append(node.text ?: "").append(node.contentDescription ?: ""); for (n in 0 until node.childCount) append(text(node.getChild(n)))
                    }
                    while (artifact.name !in text(instrumentation.uiAutomation.rootInActiveWindow)) { check(System.currentTimeMillis() < deadline); delay(100) }
                    File(proof, "surface.txt").writeText(text(instrumentation.uiAutomation.rootInActiveWindow))
                    File(proof, "surface.png").outputStream().use { assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }
            File(proof, "result.json").writeText(buildJsonObject {
                put("mode", mode); put("state", task.state); put("accepted", succeeds); put("taskId", task.id); put("officeTaskId", remote.id)
                put("sourceSha256", source.sha256); put("uploads", uploads.get()); put("semanticCalls", semantic.get()); put("realUno", true); put("realModel", false)
            }.toString())
        } finally {
            surface?.close(); facade?.close(); service.stopGeneration(id); service.removeConversationReference(id)
            restoredFiles.forEach { (file, bytes) -> file.writeBytes(bytes) }
            // Retain synthetic artifacts and journal for host-side evidence archival; no production data.
            docHttp.set(documents, oldDocHttp); officeHttp.set(office, oldOfficeHttp)
            if (oldConfig == null) oldConfigFile.delete() else oldConfigFile.writeBytes(oldConfig)
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }; server.close()
        }
    }
}
