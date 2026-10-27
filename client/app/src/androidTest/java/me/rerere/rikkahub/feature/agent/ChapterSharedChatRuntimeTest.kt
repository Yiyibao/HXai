package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChapterSharedChatRuntimeTest {
    private val names = listOf("index_material_chapters", "read_material_chapter", "search_material_chapter")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private val source = "# First\nEXACT_BODY alpha alpha\n# Second\nNEIGHBOR_ONLY\n"
    private suspend fun fixture(owner: String): MaterialFile = GlobalContext.get().get<MaterialFiles>().runTask(
        MaterialFile(Uuid.random().toString(), owner, "synthetic-chapters.md", "md")) { it.writeText(source); "synthetic fixed chapter source" }
    private fun cleanup(item: MaterialFile) {
        val store = GlobalContext.get().get<MaterialFiles>()
        store.file(item).delete()
        File(context.filesDir, "materials/${item.profileId}/${item.id}.meta.json").delete()
        store.invalidateChapterIndex(item.profileId, item.id)
    }
    private fun mutate(item: MaterialFile, deletion: Boolean = false) {
        val store = GlobalContext.get().get<MaterialFiles>(); val file = store.file(item)
        if (deletion) { check(file.delete()); return }
        // Deliberately model corrupted/replaced same-id metadata too: a fresh valid hash alone must not pass.
        file.writeText("# Replacement\nCHANGED_SOURCE\n")
        store.record(item.copy(size = file.length(), sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()))
        store.verifiedFile(item.profileId, item.id)
    }
    private suspend fun inputs(item: MaterialFile): List<JsonObject> {
        val index = GlobalContext.get().get<MaterialFiles>().chapterIndex(item.profileId, item.id, item.sha256, 0, 20)
        fun common(chapter: Boolean = false) = buildJsonObject {
            put("id", item.id); put("expected_sha256", item.sha256); put("offset", "0")
            if (chapter) { put("index_sha256", index.getValue("index_sha256")); put("chapter_id", index.getValue("chapters").jsonArray.first().jsonObject.getValue("chapter_id")) }
        }
        return listOf(JsonObject(common() + ("limit" to JsonPrimitive("20"))),
            JsonObject(common(true) + ("count" to JsonPrimitive("24000"))),
            JsonObject(common(true) + mapOf("query" to JsonPrimitive("alpha"), "limit" to JsonPrimitive("20"))))
    }
    @Test fun factoryExposesThreeTrustedChapterTools(): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>().settingsFlow.first { !it.init }
        val model = Model(modelId = "qa-shared-chapters", abilities = listOf(ModelAbility.TOOL))
        val tools = k.get<ChatToolFactory>().createTools(settings, Assistant(chatModelId = model.id), model, profileId = settings.activeProfileId)
        names.forEach { assertEquals(it, 1, tools.count { tool -> tool.name == it }); assertEquals("local.read", TrustedToolRegistry.require(it).capability) }
    }
    private suspend fun verify(owner: String, args: JsonObject, result: JsonObject): Boolean {
        val tool = UIMessagePart.Tool("chapter-receipt", names[1], args.toString(), listOf(UIMessagePart.Text(result.toString())), executionState = ToolExecutionState.COMPLETED)
        val conversation = Conversation(assistantId = Uuid.random(), profileId = owner, messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()))
        val k = GlobalContext.get()
        return runCatching { TaskResultVerifier(k.get(), k.get(), k.get()).verify(owner, conversation, tool) }.getOrDefault(false)
    }
    private suspend fun read(item: MaterialFile, args: JsonObject): JsonObject = Json.parseToJsonElement(
        materialChapterReadTool(GlobalContext.get().get(), item.profileId).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun receiptCase(mode: String): Unit = runBlocking {
        val item = fixture(Uuid.random().toString())
        try {
            val args = inputs(item)[1]; val result = read(item, args)
            val changed = when (mode) {
                "forged" -> JsonObject(result + ("untrusted_file_data" to JsonPrimitive("FORGED_BODY")))
                else -> result
            }
            if (mode == "changed") mutate(item)
            val input = if (mode == "index") JsonObject(args + ("index_sha256" to JsonPrimitive("0".repeat(64)))) else args
            assertEquals(mode == "valid", verify(if (mode == "owner") Uuid.random().toString() else item.profileId, input, changed))
        } finally { cleanup(item) }
    }
    @Test fun validBodyHasFreshIndependentReceipt() = receiptCase("valid")
    @Test fun forgedBodyCannotBorrowSourceReceipt() = receiptCase("forged")
    @Test fun wrongIndexCannotBorrowOldBody() = receiptCase("index")
    @Test fun crossOwnerCannotVerifySource() = receiptCase("owner")
    @Test fun changedSourceCannotVerifyOldStep() = receiptCase("changed")
    @Test fun realChatIndexesReadsAndSearchesFixedSource() = scenario("stable", true)
    @Test fun changedSourceDuringSemanticAcceptanceCannotSucceed() = scenario("changed", false)
    @Test fun deletedSourceDuringSemanticAcceptanceCannotSucceed() = scenario("deleted", false)
    @Test fun wrongSemanticHashCannotAcceptChapter() = scenario("badHash", false)
    @Test fun changedUnselectedChapterStepCannotSucceed() = scenario("unselectedChanged", false)

    private fun scenario(mode: String, expectedSuccess: Boolean): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val item = fixture(original.activeProfileId)
        val secondary = if (mode == "unselectedChanged") fixture(original.activeProfileId) else null
        val initialSource = k.get<MaterialFiles>().verifiedFile(item.profileId, item.id).readText()
        val args = inputs(item).toMutableList()
        if (secondary != null) args[2] = inputs(secondary)[2]
        val calls = AtomicInteger(); val semantic = AtomicInteger()
        val server = LocalModelServer { body ->
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val first = messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps", JsonArray(names.mapIndexed { n, name -> buildJsonObject { put("id", "chapter$n"); put("tool", name); put("purpose", "Only fixed-source extracted chapter evidence") } }))
                    put("acceptance", JsonArray(names.indices.filter { mode != "unselectedChanged" || it != 2 }.map { n -> buildJsonObject { put("stepId", "chapter$n"); put("kind", "tool_verified") } }))
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    semantic.incrementAndGet()
                    if (mode == "changed" || mode == "deleted") mutate(item, mode == "deleted")
                    if (secondary != null) mutate(secondary)
                    val evidence = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted", true); put("goalRevision", evidence.getValue("goalRevision"))
                        put("evidenceHash", if (mode == "badHash") JsonPrimitive("wrong") else evidence.getValue("evidenceHash"))
                        put("reason", "Synthetic evaluator only; fixed source must still be checked by host")
                    }.toString())
                }
                calls.incrementAndGet() == 1 -> buildJsonObject {
                    put("id", "synthetic-chapters"); put("model", "qa-shared-chapters")
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("finish_reason", "tool_calls")
                        put("message", buildJsonObject {
                            put("role", "assistant"); put("content", "")
                            put("tool_calls", JsonArray(names.mapIndexed { n, name -> buildJsonObject {
                                put("id", "chapter-call$n"); put("type", "function")
                                put("function", buildJsonObject { put("name", name); put("arguments", args[n].toString()) })
                            } }))
                        })
                    }) })
                }.toString()
                else -> LocalModelServer.completion("Model claims success; application must verify every original source.")
            }
        }
        val model = Model(modelId = "qa-shared-chapters", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(name = "QA chapters", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA chapters", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>(); val scheduler = AgentScheduler.shared(k.get()); val id = Uuid.random()
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant, assistantId = assistant.id,
                chatModelId = model.id, fastModelId = model.id, enableSuggestion = false, learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }; service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA chapter goal", newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("Index, read and search alpha only in first detected section of fixed source ${item.id}/${item.sha256}; do not assert complete chapter/book coverage.")), Uuid.random())
            withTimeout(45_000) {
                while (true) {
                    val current = service.getConversationFlow(id).first(); val task = scheduler.current(current)
                    if (task != null && task.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) break
                    delay(20)
                }
            }
            val persisted = requireNotNull(repo.getConversationById(id)); val task = requireNotNull(scheduler.current(persisted))
            val tools = persisted.currentMessages.flatMap { it.getTools() }.filter { it.toolName in names }
            assertEquals(3, tools.size); assertEquals(1, semantic.get())
            val results = tools.map { Json.parseToJsonElement(it.output.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject }
            assertTrue(results.all { it.getValue("status").jsonPrimitive.content == "SUCCEEDED" })
            assertEquals("# First\nEXACT_BODY alpha alpha\n", results[1].getValue("untrusted_file_data").jsonPrimitive.content)
            assertEquals(2, results[2].getValue("hits").jsonArray.size)
            val directory = File(context.cacheDir, "shared-chapter-proof").apply { check(mkdirs() || isDirectory) }
            AgentTaskStore(k.get()).use { store ->
                val plan = store.plan(task.owner, task.id)
                assertEquals(3, plan.steps.size); assertTrue(plan.steps.all { it.state == "VERIFIED" })
                if (secondary != null) assertEquals(2, requireNotNull(plan.plan).acceptance.size)
                names.forEach { name ->
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool=?", arrayOf(task.id, name)).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                    }
                }
                val proof = buildJsonObject {
                    put("task_id", task.id); put("owner", task.owner); put("task_state", task.state); put("acceptance", plan.acceptanceVerified)
                    put("mode", mode); put("expected_success", expectedSuccess); put("source_id", item.id); put("source_sha256", item.sha256); put("source", initialSource)
                    put("inputs", JsonArray(args)); put("results", JsonArray(results)); put("physical_calls", tools.size)
                }
                java.io.FileOutputStream(File(directory, "$id.json")).use { it.write(proof.toString().toByteArray()); it.fd.sync() }
                assertEquals(expectedSuccess, plan.acceptanceVerified)
            }
            assertEquals(expectedSuccess, task.state == "SUCCEEDED")
            assertTrue(server.bodies.all { Json.parseToJsonElement(it).jsonObject["model"]?.jsonPrimitive?.content == model.modelId })
        } finally {
            service.stopGeneration(id); repo.getConversationById(id)?.let { service.deleteConversation(it) }; service.removeConversationReference(id)
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }; server.close(); cleanup(item); secondary?.let(::cleanup)
        }
    }
}
