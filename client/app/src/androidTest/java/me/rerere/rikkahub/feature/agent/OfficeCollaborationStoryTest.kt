package me.rerere.rikkahub.feature.agent

import android.content.Context
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Actual Chat/Planner/lease/journal/Office versions/host preview/SAF callback and readback.
 * The localhost model is synthetic; this does not measure real model planning quality. */
@RunWith(AndroidJUnit4::class)
class OfficeCollaborationStoryTest {
    @Test fun twoLectureWordPreviewSaveAndSameTaskRevisionKeepOriginalReceipts() = scenario("docx", false)
    @Test fun secondLectureChangedDuringGoalVerificationCannotSucceed() = scenario("docx", true)
    @Test fun pptLogicalSlideEditDeliversActualEditableVersion() = scenario("pptx", false)
    @Test fun pdfTwoSourceAssemblyDeliversBothOriginalPages() = scenario("pdf", false)
    @Test fun secondPdfChangedDuringGoalVerificationCannotSucceed() = scenario("pdf", true)

    private fun scenario(format: String, corrupt: Boolean): Unit = runBlocking {
        val k = GlobalContext.get(); val context = k.get<Context>(); val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId; val materials = k.get<MaterialFiles>()
        val uid = Uuid.random().toString(); val title = "Office story $uid"
        val textA = "THEOREM_A_$uid\nEXERCISE_A_$uid"
        val textB = "THEOREM_B_$uid\nEXERCISE_B_$uid"
        val sourceFormat = if (format == "docx") "pdf" else format
        suspend fun source(label: String, body: String) = materials.runTask(MaterialFile(Uuid.random().toString(), owner,
            "$label-$uid.$sourceFormat", sourceFormat)) { MaterialWriter.write(it, sourceFormat, body, emptyList(),
                if (format == "pptx") listOf("UNTOUCHED_$uid", body) else emptyList()) }
        val sources = listOf(source("Lecture-A", textA), source("Lecture-B", textB))
        val originalBytes = sources.map { materials.verifiedFile(owner, it.id).readBytes() }
        val read = sources.map { MaterialReader.read(materials.verifiedFile(owner, it.id), sourceFormat) }
        val pptRun = if (format == "pptx") MaterialEditor.textRuns(materials.verifiedFile(owner, sources.first().id),
            "pptx", 2, 0, 20).getValue("runs").jsonArray.first().jsonObject else null
        val references = buildJsonArray { sources.forEachIndexed { index, source -> add(buildJsonObject {
            put("id", source.id); put("sha256", source.sha256); put("offset", 0)
            put("count", read[index].text.length); put("quote", read[index].text)
        }) } }
        var revision = false
        var changedBytes: ByteArray? = null
        val mainCalls = AtomicInteger()
        fun input(): JsonObject = if (format == "docx") buildJsonObject {
            put("format", format); put("title", if (revision) "$title revised" else title)
            put("content", buildJsonObject {
                put("body", if (revision) "THEOREM_A_$uid\nTHEOREM_B_$uid\nAPPENDIX\nEXERCISE_A_$uid\nEXERCISE_B_$uid" else "$textA\n$textB")
                put("sources", references)
                if (revision) {
                    val parent = materials.list(owner).single { it.name == "$title.docx" }
                    put("parent_id", parent.id); put("parent_sha256", parent.sha256)
                }
            }.toString())
        } else buildJsonObject {
            put("id", sources.first().id); put("expected_sha256", sources.first().sha256); put("title", title)
            put("operation", if (format == "pptx") "replace_text_run" else "pdf_select_pages")
            put("spec", if (format == "pptx") buildJsonObject {
                put("slide", 2); put("index", requireNotNull(pptRun).getValue("index")); put("expectedText", pptRun.getValue("text")); put("text", "REVISED_$uid")
            }.toString() else buildJsonObject { put("sources", buildJsonArray {
                sources.forEach { source -> add(buildJsonObject {
                    put("id", source.id); put("sha256", source.sha256); put("pages", buildJsonArray { add(1) })
                }) }
            }) }.toString())
        }
        val tool = if (format == "docx") "create_material_file" else "edit_material_file"
        val required = if (format == "pptx") listOf("UNTOUCHED_$uid", "REVISED_$uid") else listOf("THEOREM_A_$uid", "THEOREM_B_$uid")
        val server = LocalModelServer { body ->
            val request = Json.parseToJsonElement(body).jsonObject
            val messages = request.getValue("messages").jsonArray
            val system = messages.first().jsonObject.getValue("content").jsonPrimitive.content
            val user = messages.last().jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
            when {
                "HXAI_TASK_PLAN_V6" in system -> LocalModelServer.completion(buildJsonObject {
                    put("steps", buildJsonArray {
                        add(buildJsonObject { put("id", "initial"); put("tool", tool); put("purpose", "Deliver requested original version") })
                        if (revision) add(buildJsonObject { put("id", "revised"); put("tool", tool); put("purpose", "Revise same task without repeating initial effect") })
                    })
                    put("acceptance", buildJsonArray { add(buildJsonObject {
                        put("stepId", if (revision) "revised" else "initial"); put("kind", "artifact_content"); put("format", format)
                        put("contains", JsonArray(required.map(::JsonPrimitive)))
                    }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in system -> {
                    val data = Json.parseToJsonElement(user).jsonObject
                    val artifacts = data.getValue("evidence").jsonArray.map { it.jsonObject }.filter { "artifactId" in it }
                    check(artifacts.isNotEmpty())
                    check(artifacts.any { item -> required.all { it in item.getValue("content").jsonPrimitive.content } })
                    if (format != "pptx") check(artifacts.all { it["sourceReferences"]?.jsonArray?.size == 2 })
                    if (corrupt && changedBytes == null) {
                        val file = materials.verifiedFile(owner, sources[1].id)
                        changedBytes = file.readBytes()
                        file.appendText("SYNTHETIC_SOURCE_CORRUPTION")
                    }
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted", true); put("goalRevision", data.getValue("goalRevision")); put("evidenceHash", data.getValue("evidenceHash"))
                        put("reason", "Actual content, provenance and local Office readback match the synthetic goal")
                    }.toString())
                }
                mainCalls.incrementAndGet() % 2 == 1 -> buildJsonObject {
                    put("id", "story-tool-${mainCalls.get()}"); put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("finish_reason", "tool_calls"); put("message", buildJsonObject {
                            put("role", "assistant"); put("content", ""); put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("id", "story-${mainCalls.get()}"); put("type", "function"); put("function", buildJsonObject {
                                    put("name", tool); put("arguments", input().toString())
                                })
                            }) })
                        })
                    }) })
                }.toString()
                else -> LocalModelServer.completion("The actual task receipts determine completion.")
            }
        }
        val model = Model(modelId = "qa-office-story", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF,
            streamOutput = false, enableWebSearch = false, localTools = emptyList())
        val config = original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
            assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
            learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
            networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
        val id = Uuid.random(); val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(context)
        val saved = File(context.filesDir, "office-story-$uid/saved.$format").apply { parentFile!!.mkdirs() }
        val host = StudyHostFacade(context, settings, repo, service, k.get(), materials, k.get(),
            pickFiles = { emptyList() }, saveFile = { _, _ -> saved.toUri() })
        suspend fun settled(): AgentTask = withTimeout(45_000) {
            while (true) {
                val task = scheduler.current(service.getConversationFlow(id).first())
                if (task != null && task.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) return@withTimeout task
                delay(30)
            }
            error("unreachable")
        }
        try {
            settings.update(config); settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner, title = title, newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("Use owned lecture versions ${sources.map { it.id }} to deliver $format: ${required.joinToString()}. Preserve originals and provide actual sources and preview.")), Uuid.random())
            val firstTask = settled()
            assertEquals(if (corrupt) "FAILED_BLOCKED" else "SUCCEEDED", firstTask.state)
            if (corrupt) { assertNotNull(changedBytes); return@runBlocking }
            val artifact = materials.list(owner).single { it.name == "$title.$format" }
            val firstBytes = materials.verifiedFile(owner, artifact.id).readBytes()
            val epoch = host.getBootstrap(owner, 1).contextEpoch
            val preview = host.getArtifactPreview(owner, artifact.id, epoch)
            assertEquals(artifact.id, preview.id)
            assertTrue(host.saveArtifact(owner, artifact.id).success)
            assertArrayEquals(firstBytes, saved.readBytes())
            if (format == "pdf") assertEquals(2, com.artifex.mupdf.fitz.Document.openDocument(saved.absolutePath).let { try { it.countPages() } finally { it.destroy() } })
            if (format == "docx") {
                val firstStep = scheduler.plan(owner, firstTask.id).steps.single()
                revision = true
                val task = requireNotNull(host.getConversationTask(owner, id.toString(), epoch))
                val command = Uuid.random().toString()
                val payload = "Keep both theorems in the body; move both exercises to APPENDIX, deliver a new version of the same Word; preserve the previous version."
                assertTrue(host.taskCommand(owner, id.toString(), task.id, command, epoch, task.revision, task.fence, "supplement", payload).success)
                val revised = settled(); assertEquals(firstTask.id, revised.id); assertEquals("SUCCEEDED", revised.state)
                assertEquals(firstTask.configurationReference, revised.configurationReference)
                val next = materials.list(owner).single { it.name == "$title revised.docx" }
                assertEquals(artifact.id, next.sourceId)
                assertArrayEquals(firstBytes, materials.verifiedFile(owner, artifact.id).readBytes())
                assertEquals(firstStep, scheduler.plan(owner, revised.id).steps.single { it.id == "initial" })
                val text = MaterialReader.read(materials.verifiedFile(owner, next.id), "docx").text
                assertTrue(text.indexOf("APPENDIX") < text.indexOf("EXERCISE_A_"))
                assertTrue(sources.all { it.id in text && it.sha256 in text })
                val beforeAck = server.bodies.size
                assertTrue(host.taskCommand(owner, id.toString(), task.id, command, epoch, task.revision, task.fence, "supplement", payload).success)
                delay(150); assertEquals(beforeAck, server.bodies.size)
                AgentTaskStore(context).use { store -> store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM calls WHERE task_id=? AND tool='create_material_file'", arrayOf(firstTask.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                } }
            }
            sources.forEachIndexed { index, source -> assertArrayEquals(originalBytes[index], materials.verifiedFile(owner, source.id).readBytes()) }
            println("OFFICE_STORY_ARTIFACT $format ${artifact.id} ${artifact.sha256}")
        } finally {
            changedBytes?.let { materials.file(sources[1]).writeBytes(it) }
            host.close(); service.stopGeneration(id); service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }; server.close()
        }
    }
}
