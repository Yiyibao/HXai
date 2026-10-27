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
class SourceSharedChatRuntimeTest {
    private val names = listOf("read_material_source_location", "search_material_text", "search_material_library")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private val source = "EXACT_BODY alpha alpha"
    private suspend fun fixture(owner: String): MaterialFile = GlobalContext.get().get<MaterialFiles>().runTask(
        MaterialFile(Uuid.random().toString(), owner, "synthetic-source.pptx", "pptx")) {
        MaterialWriter.write(it, "pptx", "", emptyList(), listOf(source, "NEIGHBOR_ONLY"))
        "synthetic source with logical slides"
    }
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
    private suspend fun inputs(item: MaterialFile): List<JsonObject> = listOf(
        buildJsonObject { put("id", item.id); put("expected_sha256", item.sha256); put("kind", "ppt_logical_slide"); put("number", "1"); put("offset", "0"); put("count", "24000") },
        buildJsonObject { put("id", item.id); put("expected_sha256", item.sha256); put("query", "alpha"); put("offset", "0"); put("limit", "20") },
        buildJsonObject { put("query", "alpha"); put("cursor", ""); put("limit", "20"); put("source_limit", "5") })
    @Test fun factoryExposesThreeTrustedSourceTools(): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>().settingsFlow.first { !it.init }
        val model = Model(modelId = "qa-shared-chapters", abilities = listOf(ModelAbility.TOOL))
        val tools = k.get<ChatToolFactory>().createTools(settings, Assistant(chatModelId = model.id), model, profileId = settings.activeProfileId)
        names.forEach { assertEquals(it, 1, tools.count { tool -> tool.name == it }); assertEquals("local.read", TrustedToolRegistry.require(it).capability) }
    }
    private suspend fun verify(owner: String, args: JsonObject, result: JsonObject): Boolean {
        val tool = UIMessagePart.Tool("source-receipt", names[0], args.toString(), listOf(UIMessagePart.Text(result.toString())), executionState = ToolExecutionState.COMPLETED)
        val conversation = Conversation(assistantId = Uuid.random(), profileId = owner, messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()))
        val k = GlobalContext.get()
        return runCatching { TaskResultVerifier(k.get(), k.get(), k.get()).verify(owner, conversation, tool) }.getOrDefault(false)
    }
    private suspend fun read(item: MaterialFile, args: JsonObject): JsonObject = Json.parseToJsonElement(
        materialSourceLocationTool(GlobalContext.get().get(), item.profileId).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun receiptCase(mode: String): Unit = runBlocking {
        val item = fixture(Uuid.random().toString())
        try {
            val args = inputs(item)[0]; val result = read(item, args)
            val changed = when (mode) {
                "forged" -> JsonObject(result + ("untrusted_file_data" to JsonPrimitive("FORGED_BODY")))
                else -> result
            }
            if (mode == "changed") mutate(item)
            val input = if (mode == "index") JsonObject(args + ("number" to JsonPrimitive("2"))) else args
            assertEquals(mode == "valid", verify(if (mode == "owner") Uuid.random().toString() else item.profileId, input, changed))
        } finally { cleanup(item) }
    }
    @Test fun validBodyHasFreshIndependentReceipt() = receiptCase("valid")
    @Test fun forgedBodyCannotBorrowSourceReceipt() = receiptCase("forged")
    @Test fun wrongLocationCannotBorrowOldBody() = receiptCase("index")
    @Test fun crossOwnerCannotVerifySource() = receiptCase("owner")
    @Test fun changedSourceCannotVerifyOldStep() = receiptCase("changed")
    @Test fun realChatReadsLocationSearchesSourceAndLibrary() = scenario("stable", true)
    @Test fun changedSourceDuringSemanticAcceptanceCannotSucceed() = scenario("changed", false)
    @Test fun deletedSourceDuringSemanticAcceptanceCannotSucceed() = scenario("deleted", false)
    @Test fun wrongSemanticHashCannotAcceptSource() = scenario("badHash", false)
    @Test fun changedUnselectedSourceStepCannotSucceed() = scenario("unselectedChanged", false)
    @Test fun catalogAddedDuringSemanticAcceptanceCannotSucceed() = scenario("catalogAdded", false)
    @Test fun nonMatchingScannedSourceChangedCannotSucceed() = scenario("noHitChanged", false)

    @Test fun searchAndLibraryResultsAreIndependentlyReconstructed(): Unit = runBlocking {
        val item = fixture(Uuid.random().toString()); val k = GlobalContext.get(); val store = k.get<MaterialFiles>()
        try {
            val args = inputs(item)
            for (n in 1..2) {
                val tool = if (n == 1) materialSourceSearchTool(store, item.profileId) else materialLibrarySearchTool(store, item.profileId)
                val output = tool.execute(args[n])
                suspend fun verifies(input: JsonObject, parts: List<UIMessagePart>): Boolean {
                    val call = UIMessagePart.Tool("search-proof", names[n], input.toString(), parts, executionState = ToolExecutionState.COMPLETED)
                    val conversation = Conversation(assistantId = Uuid.random(), profileId = item.profileId, messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(call)).toMessageNode()))
                    return runCatching { TaskResultVerifier(store, k.get(), k.get()).verify(item.profileId, conversation, call) }.getOrDefault(false)
                }
                assertTrue(verifies(args[n], output))
                val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
                assertFalse(verifies(args[n], listOf(UIMessagePart.Text(JsonObject(result + ("hits" to JsonArray(emptyList()))).toString()))))
                assertFalse(verifies(JsonObject(args[n] + ("query" to JsonPrimitive("NEIGHBOR_ONLY"))), output))
                assertFalse(verifies(args[n], output + UIMessagePart.Text("extra unverified text")))
            }
        } finally { cleanup(item) }
    }
    @Test fun unavailableLocationIsNotVerifiedBody(): Unit = runBlocking {
        val item = fixture(Uuid.random().toString())
        try {
            val args = JsonObject(inputs(item)[0] + ("number" to JsonPrimitive("999")))
            val result = read(item, args)
            assertEquals("NOT_AVAILABLE", result.getValue("status").jsonPrimitive.content)
            assertFalse(verify(item.profileId, args, result))
        } finally { cleanup(item) }
    }

    private fun scenario(mode: String, expectedSuccess: Boolean): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val item = fixture(original.activeProfileId)
        val secondary = if (mode in setOf("unselectedChanged", "noHitChanged")) fixture(original.activeProfileId) else null
        val originalBytes = k.get<MaterialFiles>().verifiedFile(item.profileId, item.id).readBytes()
        val noHit = if (mode == "noHitChanged") requireNotNull(secondary).also {
            val store = k.get<MaterialFiles>(); val file = store.file(it); file.writeText("NO_MATCH_SOURCE")
            store.record(it.copy(extension = "txt", size = file.length(), sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()))
            // Extension participates in the actual path; move only this synthetic fixture.
            check(file.renameTo(store.file(store.get(it.profileId, it.id))))
        } else null
        var added: MaterialFile? = null
        val sourceSnapshots = k.get<MaterialFiles>().list(item.profileId).map { entry -> buildJsonObject {
            put("id", entry.id); put("sha256", entry.sha256); put("extension", entry.extension)
            put("name", entry.name); put("size", entry.size); put("parent", entry.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("bytes", android.util.Base64.encodeToString(k.get<MaterialFiles>().verifiedFile(item.profileId, entry.id).readBytes(), android.util.Base64.NO_WRAP))
        } }
        val args = inputs(item).toMutableList()
        if (mode == "unselectedChanged") args[1] = inputs(requireNotNull(secondary))[1]
        val calls = AtomicInteger(); val semantic = AtomicInteger()
        val server = LocalModelServer { body ->
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val first = messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps", JsonArray(names.mapIndexed { n, name -> buildJsonObject { put("id", "chapter$n"); put("tool", name); put("purpose", "Only fixed-source extracted chapter evidence") } }))
                    put("acceptance", JsonArray(names.indices.filter { mode != "unselectedChanged" || it != 1 }.map { n -> buildJsonObject { put("stepId", "chapter$n"); put("kind", "tool_verified") } }))
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    semantic.incrementAndGet()
                    if (mode == "changed" || mode == "deleted") mutate(item, mode == "deleted")
                    if (mode == "unselectedChanged") mutate(requireNotNull(secondary))
                    if (noHit != null) mutate(k.get<MaterialFiles>().get(noHit.profileId, noHit.id))
                    if (mode == "catalogAdded") added = runBlocking { fixture(item.profileId) }
                    val evidence = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted", true); put("goalRevision", evidence.getValue("goalRevision"))
                        put("evidenceHash", if (mode == "badHash") JsonPrimitive("wrong") else evidence.getValue("evidenceHash"))
                        put("reason", "Synthetic evaluator only; fixed source and library must still be checked by host")
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
        var facade: me.rerere.rikkahub.flutter.StudyHostFacade? = null
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant, assistantId = assistant.id,
                chatModelId = model.id, fastModelId = model.id, enableSuggestion = false, learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }; service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA chapter goal", newConversation = true))
            val host = me.rerere.rikkahub.flutter.StudyHostFacade(context, settings, repo, service, k.get(), k.get(), k.get(), pickFiles = { emptyList() })
            facade = host
            val epoch = host.getBootstrap(item.profileId, 1).contextEpoch
            val request = me.rerere.rikkahub.flutter.generated.SendMessageRequest(Uuid.random().toString(), item.profileId, epoch,
                "Read logical slide 1, search literal alpha in fixed source and the current owner library: ${item.id}/${item.sha256}; only claim extracted text and current catalog page, never complete source coverage.", "CHAT", id.toString())
            assertTrue(host.sendMessage(request).success)
            withTimeout(45_000) {
                while (true) {
                    val current = service.getConversationFlow(id).first(); val task = scheduler.current(current)
                    if (task != null && task.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) break
                    delay(20)
                }
            }
            val persisted = requireNotNull(repo.getConversationById(id)); val task = requireNotNull(scheduler.current(persisted))
            val dto = requireNotNull(host.getConversationTask(item.profileId, id.toString(), epoch))
            assertEquals(task.id, dto.id); assertEquals(task.state, dto.state)
            assertEquals(expectedSuccess, dto.acceptanceVerified); assertEquals(3, dto.verifiedSteps.size)
            assertEquals(model.id.toString(), dto.modelId); assertEquals(ReasoningLevel.OFF.name, dto.reasoning)
            val tools = persisted.currentMessages.flatMap { it.getTools() }.filter { it.toolName in names }
            assertEquals(3, tools.size); assertEquals(1, semantic.get())
            val results = tools.map { Json.parseToJsonElement(it.output.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject }
            assertTrue(results.all { it.getValue("status").jsonPrimitive.content == "SUCCEEDED" })
            assertEquals(source + "\n", results[0].getValue("untrusted_file_data").jsonPrimitive.content)
            assertEquals(2, results[1].getValue("hits").jsonArray.size); assertTrue(results[2].getValue("hits").jsonArray.size >= 2)
            val directory = File(context.cacheDir, "shared-source-proof").apply { check(mkdirs() || isDirectory) }
            AgentTaskStore(k.get()).use { store ->
                val plan = store.plan(task.owner, task.id)
                assertEquals(3, plan.steps.size); assertTrue(plan.steps.all { it.state == "VERIFIED" })
                if (mode == "unselectedChanged") assertEquals(2, requireNotNull(plan.plan).acceptance.size)
                names.forEach { name ->
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool=?", arrayOf(task.id, name)).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                    }
                }
                val proof = buildJsonObject {
                    put("task_id", task.id); put("owner", task.owner); put("task_state", task.state); put("acceptance", plan.acceptanceVerified)
                    put("mode", mode); put("expected_success", expectedSuccess); put("source_id", item.id); put("source_sha256", item.sha256); put("source_base64", android.util.Base64.encodeToString(originalBytes, android.util.Base64.NO_WRAP))
                    put("inputs", JsonArray(args)); put("results", JsonArray(results)); put("physical_calls", tools.size)
                    put("source_snapshots", JsonArray(sourceSnapshots))
                }
                java.io.FileOutputStream(File(directory, "$id.json")).use { it.write(proof.toString().toByteArray()); it.fd.sync() }
                assertEquals(expectedSuccess, plan.acceptanceVerified)
            }
            assertEquals(expectedSuccess, task.state == "SUCCEEDED")
            assertTrue(server.bodies.all { Json.parseToJsonElement(it).jsonObject["model"]?.jsonPrimitive?.content == model.modelId })
        } finally {
            facade?.close()
            service.stopGeneration(id); repo.getConversationById(id)?.let { service.deleteConversation(it) }; service.removeConversationReference(id)
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }; server.close(); cleanup(k.get<MaterialFiles>().get(item.profileId, item.id)); secondary?.let { cleanup(k.get<MaterialFiles>().get(it.profileId, it.id)) }; added?.let(::cleanup)
        }
    }
}
