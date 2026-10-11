package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryType
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.flutter.generated.SendMessageRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class FlutterChatBridgeTest {
    @Test fun memoryToggleGatesCapturedRequestsAndReenableRestoresRetrieval() = runBlocking {
        server.mode = "context"
        val koin = GlobalContext.get()
        val store = koin.get<LearningMemoryStore>()
        val before = settings.settingsFlow.value
        val owner = Uuid.random().toString()
        val retained = "偏好先看反例_${Uuid.random()}"
        val forgotten = "已忘记背景_${Uuid.random()}"
        val seed = Conversation(assistantId = before.assistantId, profileId = owner, title = "QA memory sources",
            messageNodes = listOf(
                MessageNode(messages = listOf(me.rerere.ai.ui.UIMessage.user("请记住$retained"))),
                MessageNode(messages = listOf(me.rerere.ai.ui.UIMessage.user("请记住$forgotten")))))
        val ids = mutableListOf<Uuid>()
        try {
            val providers = before.providers.map { provider ->
                if (provider is ProviderSetting.OpenAI && provider.models.any { it.id == before.chatModelId }) {
                    provider.copy(models = provider.models.map { model ->
                        if (model.id == before.chatModelId) model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)) else model
                    })
                } else provider
            }
            restoreMemoryProfileFixture(before.copy(activeProfileId = owner, providers = providers,
                learnerProfiles = before.learnerProfiles + LearnerProfile(owner, "QA memory request", false)), settings)
            facade.close()
            facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings, repository,
                service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
            repository.insertConversation(seed); ids += seed.id
            val first = seed.currentMessages.first()
            val last = seed.currentMessages.last()
            val retainedId = store.saveExplicit(owner, seed.id.toString(), first.id.toString(), first.toText(),
                MemoryType.PREFERENCE, "教学", "顺序", retained)
            val deletedId = store.saveExplicit(owner, seed.id.toString(), last.id.toString(), last.toText(),
                MemoryType.BACKGROUND, "背景", "旧背景", forgotten)
            store.delete(owner, deletedId, 1)
            val usedBefore = store.read(owner).usedMemories
            assertEquals("", store.context(owner, "disabled-context", "教学", enabled = false))
            assertEquals(usedBefore, store.read(owner).usedMemories)
            assertTrue(store.hiddenMessageIds(owner).contains(last.id.toString()))

            suspend fun send(stream: Boolean, enabled: Boolean): Pair<Uuid, String> {
                assertTrue(facade.toggleAutoMemory(owner, enabled).success)
                settings.update { config -> config.copy(assistants = config.assistants.map {
                    if (it.id == config.assistantId) it.copy(streamOutput = stream) else it
                }) }
                settings.settingsFlow.first { it.getCurrentAssistant().streamOutput == stream }
                val text = "请记住我希望逐步核对证明_${Uuid.random()}"
                val result = facade.sendMessage(SendMessageRequest(Uuid.random().toString(), owner, 1L, text, "CHAT"))
                assertTrue(result.success)
                val id = Uuid.parse(result.idResult!!); ids += id
                withTimeout(30_000) {
                    combine(service.getConversationFlow(id), service.errors) { conversation, errors ->
                        val error = errors.lastOrNull { it.conversationId == id }
                        check(error == null) { "Chat request failed: ${error?.error?.stackTraceToString()}" }
                        conversation
                    }.first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" }
                }
                val body = server.contextBodies.single { request ->
                    request.contains(text) && Json.parseToJsonElement(request).jsonObject["tools"]?.jsonArray
                        ?.any { it.jsonObject["name"]?.jsonPrimitive?.content == "save_explicit_memory" } == true
                }
                assertEquals(stream, Json.parseToJsonElement(body).jsonObject.getValue("stream").jsonPrimitive.boolean)
                assertFalse("Deleted memory must stay suppressed", body.contains(forgotten))
                // Explicit saving stays available: the switch controls use and automatic extraction, not manual requests.
                assertTrue(body.contains("save_explicit_memory"))
                return id to body
            }
            for (stream in listOf(true, false)) {
                val (disabledId, disabledBody) = send(stream, false)
                assertFalse(disabledBody.contains(retained))
                assertFalse(store.read(owner).usedMemories.containsKey(disabledId.toString()))
                assertTrue(store.read(owner).items.any { it.id == retainedId })
                val (enabledId, enabledBody) = send(stream, true)
                assertTrue(enabledBody.contains(retained))
                assertTrue(store.read(owner).usedMemories.getValue(enabledId.toString()).contains(retainedId))
            }
        } finally {
            settings.setLearnerMemoryEnabled(owner, false)
            for (id in ids) {
                service.stopGeneration(id)
                repository.getConversationById(id, owner)?.let { service.deleteConversation(it) }
                service.removeConversationReference(id)
            }
            koin.get<AppDatabase>().openHelper.writableDatabase.execSQL(
                "DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(owner))
            restoreMemoryProfileFixture(before, settings)
        }
    }

    @Test fun generationBoundaryUsesConversationProfileAndKeepsDeletionFiltering() = runBlocking {
        server.mode = "context"
        val loop = GlobalContext.get().get<me.rerere.rikkahub.data.ai.GenerationLoop>()
        val current = settings.settingsFlow.value
        val owner = "qa-generation-owner"
        val other = "qa-generation-other"
        val memory = "existing-private-memory-${Uuid.random()}"
        val legacy = "legacy-private-memory-${Uuid.random()}"
        val deletedUser = me.rerere.ai.ui.UIMessage.user("deleted-private-source-${Uuid.random()}")
        val deletedAnswer = me.rerere.ai.ui.UIMessage.assistant("deleted-private-answer-${Uuid.random()}")
        val input = listOf(deletedUser, deletedAnswer, me.rerere.ai.ui.UIMessage.user("current question"))
        for (stream in listOf(true, false)) {
            for (enabled in listOf(false, true)) {
                val config = current.copy(activeProfileId = other, learnerProfiles = listOf(
                    LearnerProfile(owner, "Owner", enabled), LearnerProfile(other, "Other", !enabled)))
                val count = server.contextBodies.size
                loop.generateText(config, config.getCurrentChatModel()!!, input,
                    assistant = config.getCurrentAssistant().copy(streamOutput = stream, enableMemory = true),
                    profileId = owner, learningMemoryContext = memory, memories = listOf(AssistantMemory(1, legacy)),
                    hiddenSourceMessageIds = setOf(deletedUser.id.toString()), maxSteps = 1).collect { }
                val body = server.contextBodies.drop(count).single()
                assertEquals(enabled, body.contains(memory))
                assertEquals(enabled, body.contains(legacy))
                assertFalse(body.contains(deletedUser.toText()))
                assertFalse(body.contains(deletedAnswer.toText()))
                assertTrue(body.contains("current question"))
            }
        }
        val count = server.contextBodies.size
        loop.generateText(current, current.getCurrentChatModel()!!, listOf(input.last()),
            assistant = current.getCurrentAssistant(), profileId = "missing-profile",
            learningMemoryContext = memory, maxSteps = 1).collect { }
        assertFalse(server.contextBodies.drop(count).single().contains(memory))
    }

    @Test fun cumulativeEventsAndFinalSnapshotMatchPersistedContent() = runBlocking {
        val message = me.rerere.ai.ui.UIMessage.assistant("a").copy(generationState = "RUNNING")
        var conv = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId, profileId = original.activeProfileId, newConversation = true,
            messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))))
        conversations += conv.id
        service.initializeConversation(conv.id); service.saveConversation(conv.id, conv)
        val events = CopyOnWriteArrayList<Map<*, *>>()
        facade.eventSink = object : EventChannel.EventSink {
            override fun success(event: Any?) { events += event as Map<*, *> }
            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun endOfStream() = Unit
        }
        facade.watchConversation(original.activeProfileId, conv.id.toString(), 1)
        withTimeout(10_000) { while (events.none { it["type"] == "conversation_changed" }) delay(25) }
        conv = conv.copy(messageNodes = listOf(conv.messageNodes.single().copy(messages = listOf(message.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("abc")))))))
        service.saveConversation(conv.id, conv)
        withTimeout(10_000) { while (events.none { it["type"] == "message_patch" }) delay(25) }
        val patch = events.single { it["type"] == "message_patch" }["payload"] as Map<*, *>
        val change = (patch["changes"] as List<*>).single() as Map<*, *>
        assertEquals(message.id.toString(), change["messageId"])
        assertEquals("abc", ((change["parts"] as List<*>).single() as Map<*, *>)["text"])
        conv = conv.copy(messageNodes = listOf(conv.messageNodes.single().copy(messages = listOf(conv.currentMessages.single().copy(generationState = "COMPLETED")))))
        service.saveConversation(conv.id, conv)
        withTimeout(10_000) { while (events.count { it["type"] == "conversation_changed" } < 2) delay(25) }
        assertEquals("abc", facade.getConversationDetails(original.activeProfileId, conv.id.toString()).nodes.single().branches.single().parts.single().text)
        assertEquals("abc", repository.getConversationById(conv.id)!!.currentMessages.single().toText())
        val sequences = events.map { (it["sequence"] as Number).toLong() }
        assertEquals(sequences.sorted().distinct(), sequences)
    }

    @Test fun longContextSummarizesCachesAndInvalidatesWithoutChangingHistory() = runBlocking {
        server.mode = "context"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "qa-context-${Uuid.random()}"
        val id = Uuid.random()
        val cache = me.rerere.rikkahub.feature.studychat.ChatContextCache(context)
        val preferences = context.getSharedPreferences("chat_context_settings", android.content.Context.MODE_PRIVATE)
        preferences.edit().putInt("budget:$owner", 8000).commit()
        val current = settings.settingsFlow.value.copy(activeProfileId = owner)
        val messages = buildList {
            repeat(16) { add(me.rerere.ai.ui.UIMessage.user("历史$it 禁止覆盖原件；预算500元；文件id=file-123；" + "a".repeat(1500))); add(me.rerere.ai.ui.UIMessage.assistant("已完成核对。")) }
            add(me.rerere.ai.ui.UIMessage.user("继续刚才的任务，复述限制。"))
        }
        val immutable = messages.map { it.toText() }
        suspend fun generate(input: List<me.rerere.ai.ui.UIMessage>) = withTimeout(30_000) {
            GlobalContext.get().get<me.rerere.rikkahub.data.ai.GenerationLoop>().generateText(current, current.getCurrentChatModel()!!, input,
                assistant = current.getCurrentAssistant(), conversationId = id, maxSteps = 1).collect { }
        }
        fun summaries() = server.contextBodies.count { Json.parseToJsonElement(it).jsonObject["stream"]?.jsonPrimitive?.boolean == false }
        try {
            generate(messages)
            val count = summaries(); assertTrue(count > 0)
            val finalRequest = server.contextBodies.last()
            assertTrue(finalRequest.contains("历史任务摘要")); assertTrue(finalRequest.contains("禁止覆盖原件")); assertTrue(finalRequest.contains("file-123"))
            generate(messages); assertEquals(count, summaries())
            val edited = messages.toMutableList().also { it[0] = it[0].copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("修正历史约束：" + immutable[0]))) }
            generate(edited); assertTrue(summaries() > count)
            assertEquals(immutable, messages.map { it.toText() })
            assertNull(cache.read(cache.key("another-profile", id.toString()), "any"))
        } finally {
            preferences.edit().remove("budget:$owner").commit()
            context.getSharedPreferences("chat_context_cache", android.content.Context.MODE_PRIVATE).edit().remove(cache.key(owner, id.toString())).commit()
        }
    }

    @Test fun failedSummaryPreservesHistoryAndNeverStartsFinalGeneration() = runBlocking {
        server.mode = "401"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "qa-context-${Uuid.random()}"
        val preferences = context.getSharedPreferences("chat_context_settings", android.content.Context.MODE_PRIVATE)
        preferences.edit().putInt("budget:$owner", 8000).commit()
        val current = settings.settingsFlow.value.copy(activeProfileId = owner)
        val messages = buildList { repeat(16) { add(me.rerere.ai.ui.UIMessage.user("a".repeat(1500))); add(me.rerere.ai.ui.UIMessage.assistant("完成")) }; add(me.rerere.ai.ui.UIMessage.user("continue")) }
        val before = messages.map { it.toText() }
        try {
            val result = runCatching { GlobalContext.get().get<me.rerere.rikkahub.data.ai.GenerationLoop>().generateText(current, current.getCurrentChatModel()!!, messages,
                assistant = current.getCurrentAssistant(), conversationId = Uuid.random(), maxSteps = 1).collect { } }
            assertTrue(result.isFailure); assertEquals(1, server.requests.get()); assertEquals(before, messages.map { it.toText() })
        } finally { preferences.edit().remove("budget:$owner").commit() }
    }
    @Test fun actualFlutterReasoningCollapsesAndExpands() {
        val thought = "先核对资料的来源与时间，再比较不同来源是否一致。\n\n确认关键数据后，整理成便于阅读的回答。"
        val message = me.rerere.ai.ui.UIMessage.assistant("").copy(
            parts = listOf(me.rerere.ai.ui.UIMessagePart.Reasoning(thought),
                me.rerere.ai.ui.UIMessagePart.Text("资料已整理。点击上方状态行可查看模型返回的思考过程。")),
            generationState = "COMPLETED")
        val conversation = me.rerere.rikkahub.data.model.Conversation(
            assistantId = settings.settingsFlow.value.assistantId, title = "思考过程显示测试",
            profileId = original.activeProfileId, newConversation = true,
            messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))))
        conversations += conversation.id
        runBlocking {
            service.addConversationReference(conversation.id)
            service.initializeConversation(conversation.id)
            service.saveConversation(conversation.id, conversation)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(label: String, root: android.view.accessibility.AccessibilityNodeInfo? = automation.rootInActiveWindow): android.view.accessibility.AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.text?.toString()?.contains(label) == true || root.contentDescription?.toString()?.contains(label) == true) return root
            for (i in 0 until root.childCount) find(label, root.getChild(i))?.let { return it }
            return null
        }
        fun waitFor(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) { find(label)?.let { return it }; Thread.sleep(100) }
            error("Missing reasoning control: $label")
        }
        fun capture(name: String) {
            Thread.sleep(300)
            val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
            try { java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        try {
            androidx.test.core.app.ActivityScenario.launch(me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).use {
                var control = waitFor("已完成思考")
                assertNull(find("先核对资料"))
                capture("reasoning-collapsed.png")
                while (!control.isClickable) control = control.parent ?: error("Reasoning control not clickable")
                assertTrue(control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("先核对资料")
                capture("reasoning-expanded.png")
                control = waitFor("已完成思考")
                while (!control.isClickable) control = control.parent ?: error("Reasoning control not clickable")
                assertTrue(control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(300)
                assertNull(find("先核对资料"))
            }
        } finally { service.removeConversationReference(conversation.id) }
    }
    @Test fun versionThreeChartSelectionPersistsAndContinuesOnce() = runBlocking {
        val spec = """{"version":3,"title":"Budget","components":[{"id":"amount","type":"slider","label":"Amount","min":1,"max":10,"value":2},{"id":"cost","type":"calculation","label":"Cost","expression":{"op":"multiply","args":[{"parameter":"amount"},30]}},{"id":"chart","type":"bar_chart","selectable":true,"points":[[1,2],[2,3]]},{"id":"go","type":"button","label":"Continue","action":"continue"}],"layout":[["amount","cost"],["chart"],["go"]]}"""
        val revision = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(spec)
        val input = kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(spec)) }
        val output = me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(input)
        val message = me.rerere.ai.ui.UIMessage.assistant("").copy(generationState = "COMPLETED", parts = listOf(me.rerere.ai.ui.UIMessagePart.Tool("p0-v3", "render_interactive_ui", input.toString(), output, executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)))
        val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId, title = "P0 v3", profileId = original.activeProfileId, newConversation = true, messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))))
        conversations += conversation.id; service.initializeConversation(conversation.id); service.saveConversation(conversation.id, conversation)
        val owner = original.activeProfileId; val id = conversation.id.toString(); val messageId = message.id.toString()
        assertEquals("{\"amount\":2,\"chart\":-1}", facade.getSurfaceState(owner, id, messageId, "p0-v3", revision))
        val values = "{\"amount\":7,\"chart\":1}"
        assertTrue(facade.surfaceAction(owner, id, messageId, "p0-v3", revision, values, null, "save-v3", 1).success)
        facade.close(); val koin = GlobalContext.get()
        facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings, repository, service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        assertEquals(values, facade.getSurfaceState(owner, id, messageId, "p0-v3", revision))
        assertTrue(facade.surfaceAction(owner, id, messageId, "p0-v3", revision, values, "go", "action-v3", 1).success)
        withTimeout(30_000) { service.getConversationFlow(conversation.id).first { it.currentMessages.size > 1 && it.currentMessages.last().generationState == "COMPLETED" } }
        facade.surfaceAction(owner, id, messageId, "p0-v3", revision, values, "go", "action-v3", 1)
        assertEquals(1, repository.getConversationById(conversation.id)!!.currentMessages.count { it.role == me.rerere.ai.core.MessageRole.USER })
    }
    @Test fun fullHistoryAndEditedBranchPersistAndIsolate() = runBlocking {
        val owner = original.activeProfileId
        val assistant = settings.settingsFlow.value.assistantId
        val marker = "p0_history_${Uuid.random()}"
        val target = me.rerere.rikkahub.data.model.Conversation(id = Uuid.random(), assistantId = assistant,
            profileId = owner, title = "Old synthetic", updateAt = java.time.Instant.EPOCH,
            messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(me.rerere.ai.ui.UIMessage.user(marker)))))
        conversations += target.id; repository.insertConversation(target)
        repeat(55) {
            val c = target.copy(id = Uuid.random(), title = "Recent $it", updateAt = java.time.Instant.now(), messageNodes = emptyList())
            conversations += c.id; repository.insertConversation(c)
        }
        assertFalse(facade.getConversations(owner, 50, 0, false).any { it.id == target.id.toString() })
        assertEquals(target.id.toString(), facade.searchConversations(owner, marker, 50, 0, false).single().id)
        service.addConversationReference(target.id); service.initializeConversation(target.id)
        val message = target.currentMessages.single()
        val edit = facade.editMessage(owner, target.id.toString(), message.id.toString(), "Updated synthetic", "p0-edit", 1)
        assertTrue(edit.success)
        assertEquals(edit.idResult, facade.editMessage(owner, target.id.toString(), message.id.toString(), "Updated synthetic", "p0-edit", 1).idResult)
        assertTrue(runCatching { facade.editMessage(owner, target.id.toString(), message.id.toString(), "different", "p0-edit", 1) }.isFailure)
        val saved = repository.getConversationById(target.id)!!
        assertEquals(2, saved.messageNodes.single().messages.size); assertEquals("Updated synthetic", saved.currentMessages.single().toText())
        assertTrue(facade.switchBranch(owner, target.id.toString(), saved.messageNodes.single().id.toString(), 0).success)
        assertEquals(marker, repository.getConversationById(target.id)!!.currentMessages.single().toText())
        assertTrue(runCatching { facade.editMessage(owner, target.id.toString(), message.id.toString(), "stale", "new", 999) }.isFailure)
        service.removeConversationReference(target.id)
    }

    @Test fun pinArchivePersistAndFilterHistoryWithoutLosingContent() = runBlocking {
        val owner = original.activeProfileId
        val marker = "QA_pin_archive_${Uuid.random()}"
        val old = Conversation(assistantId = original.assistantId, profileId = owner,
            title = "$marker old", updateAt = java.time.Instant.EPOCH,
            messageNodes = listOf(MessageNode(messages = listOf(me.rerere.ai.ui.UIMessage.user(marker)))))
        val recent = old.copy(id = Uuid.random(), title = "$marker recent",
            updateAt = java.time.Instant.now(), messageNodes = emptyList())
        conversations += old.id; conversations += recent.id
        repository.insertConversation(old); repository.insertConversation(recent)
        assertTrue(facade.setConversationPinned(owner, old.id.toString(), true, 1).success)
        assertTrue(facade.setConversationPinned(owner, old.id.toString(), true, 1).success)
        assertEquals(listOf(old.id.toString(), recent.id.toString()),
            facade.searchConversations(owner, marker, 50, 0, false).map { it.id })
        assertTrue(facade.setConversationArchived(owner, old.id.toString(), true, 1).success)
        // A stale streaming checkpoint must not overwrite independently persisted metadata.
        repository.checkpointMessages(old)
        assertEquals(listOf(recent.id.toString()),
            facade.searchConversations(owner, marker, 50, 0, false).map { it.id })
        assertFalse(facade.getConversations(owner, 100, 0, false).any { it.id == old.id.toString() })
        assertFalse(GlobalContext.get().get<AppDatabase>().conversationDao()
            .projectSummaries(owner, "").any { it.id == old.id.toString() })
        facade.close()
        val koin = GlobalContext.get()
        facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings,
            repository, service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        val restored = repository.getConversationById(old.id)!!
        assertTrue(restored.isPinned && restored.isArchived)
        assertEquals(marker, restored.currentMessages.single().toText())
        val archived = facade.searchConversations(owner, marker, 50, 0, true).single()
        assertEquals(old.id.toString(), archived.id)
        assertTrue(archived.isPinned && archived.isArchived)
        assertTrue(runCatching {
            facade.setConversationArchived("other-owner", old.id.toString(), false, 1)
        }.isFailure)
        assertTrue(runCatching {
            facade.setConversationPinned(owner, old.id.toString(), false, 999)
        }.isFailure)
        assertTrue(facade.setConversationArchived(owner, old.id.toString(), false, 1).success)
        assertTrue(facade.setConversationPinned(owner, old.id.toString(), false, 1).success)
        assertEquals(listOf(recent.id.toString(), old.id.toString()),
            facade.searchConversations(owner, marker, 50, 0, false).map { it.id })
        assertTrue(facade.searchConversations(owner, marker, 50, 0, true).isEmpty())
    }

    @Test(timeout = 120_000) fun actualFlutterPinArchiveUsesPersistentMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val owner = original.activeProfileId
        val old = Conversation(assistantId = settings.settingsFlow.value.assistantId, profileId = owner,
            title = "QA pinned history", updateAt = java.time.Instant.EPOCH, messageNodes = emptyList())
        val recent = old.copy(id = Uuid.random(), title = "QA recent history", updateAt = java.time.Instant.now())
        runBlocking {
            conversations += old.id; conversations += recent.id
            repository.insertConversation(old); repository.insertConversation(recent)
            facade.setConversationPinned(owner, old.id.toString(), true, 1)
        }
        fun findAll(label: String, root: android.view.accessibility.AccessibilityNodeInfo? = automation.rootInActiveWindow): List<android.view.accessibility.AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            if (root.isVisibleToUser && (root.text?.toString() == label || root.contentDescription?.toString() == label)) result += root
            for (i in 0 until root.childCount) result += findAll(label, root.getChild(i))
            return result
        }
        fun waitFor(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                findAll(label).firstOrNull()?.let { return it }
                Thread.sleep(100)
            }
            error("Flutter control missing: $label")
        }
        fun bounds(node: android.view.accessibility.AccessibilityNodeInfo) =
            android.graphics.Rect().also(node::getBoundsInScreen)
        fun clickNode(node: android.view.accessibility.AccessibilityNodeInfo) {
            val rect = bounds(node)
            check(rect.width() > 0 && rect.height() > 0)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}")).use { it.readBytes() }
            Thread.sleep(350)
        }
        fun click(label: String) = clickNode(waitFor(label))
        fun more(title: String) {
            val rect = bounds(waitFor(title))
            val control = findAll("更多操作").minByOrNull { kotlin.math.abs(bounds(it).centerY() - rect.centerY()) }
                ?: error("Chat menu missing: $title")
            check(kotlin.math.abs(bounds(control).centerY() - rect.centerY()) < 100)
            clickNode(control)
        }
        fun persisted(id: Uuid, check: (Conversation) -> Boolean) = runBlocking {
            withTimeout(20_000) {
                while (!check(repository.getConversationById(id)!!)) delay(100)
            }
        }
        fun absent(label: String) {
            val deadline = System.currentTimeMillis() + 20_000
            while (findAll(label).isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Archived chat remains in the ordinary drawer", findAll(label).isEmpty())
        }
        fun screenshot(name: String) {
            val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
            try { File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        androidx.test.core.app.ActivityScenario.launch(me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).use {
            click("历史对话")
            waitFor(recent.title)
            assertTrue(bounds(waitFor(old.title)).top < bounds(waitFor(recent.title)).top)
            more(old.title); click("取消置顶")
            persisted(old.id) { !it.isPinned }
            waitFor(recent.title)
            assertTrue(bounds(waitFor(recent.title)).top < bounds(waitFor(old.title)).top)
            more(old.title); click("置顶聊天")
            persisted(old.id) { it.isPinned }
            waitFor("置顶")
            more(recent.title); click("归档聊天")
            persisted(recent.id) { it.isArchived }
            absent(recent.title)
            screenshot("review-chat-pinned.png")
            click("已归档聊天")
            waitFor(recent.title); absent(old.title)
            screenshot("review-chat-archived.png")
            more(recent.title); click("取消归档")
            persisted(recent.id) { !it.isArchived }
            absent(recent.title)
            click("返回最近聊天")
            waitFor(recent.title)
        }
        androidx.test.core.app.ActivityScenario.launch(me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).use {
            click("历史对话")
            waitFor(recent.title)
            assertTrue(bounds(waitFor(old.title)).top < bounds(waitFor(recent.title)).top)
        }
    }

    @Test fun actualFlutterSurfaceRestoresEditsClosesAndContinues() {
        val definition = """{"version":3,"title":"预算探索","components":[{"id":"note","type":"text","text":"调整人数，查看预算变化，再交给 AI 分析。"},{"id":"amount","type":"slider","label":"人数","min":1,"max":10,"value":2},{"id":"cost","type":"calculation","label":"总预算（元）","parameter":"amount","a":100,"b":20},{"id":"chart","type":"bar_chart","points":[[1,100],[2,200],[3,300]],"xLabel":"方案","yLabel":"费用（元）"},{"id":"go","type":"button","label":"继续分析","action":"continue"}]}"""
        val revision = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(definition)
        val input = kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(definition)) }
        val tool = me.rerere.ai.ui.UIMessagePart.Tool("flutter-surface", "render_interactive_ui", input.toString(),
            runBlocking { me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(input) }, executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)
        val message = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool), generationState = "COMPLETED")
        val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId,
            title = "预算探索", profileId = original.activeProfileId, newConversation = true,
            messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))))
        conversations += conversation.id
        runBlocking {
            service.addConversationReference(conversation.id)
            service.initializeConversation(conversation.id)
            service.saveConversation(conversation.id, conversation)
            facade.surfaceAction(original.activeProfileId, conversation.id.toString(), message.id.toString(), "flutter-surface", revision, "{\"amount\":7}", null, "fixture", 1)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun find(label: String, root: android.view.accessibility.AccessibilityNodeInfo? = automation.rootInActiveWindow): android.view.accessibility.AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
            for (i in 0 until root.childCount) find(label, root.getChild(i))?.let { return it }
            return null
        }
        fun waitFor(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) { find(label)?.let { return it }; Thread.sleep(100) }
            fun dump(root: android.view.accessibility.AccessibilityNodeInfo?, result: StringBuilder) {
                if (root == null) return
                result.append(root.className).append(" text=").append(root.text).append(" label=").append(root.contentDescription).append('\n')
                for (i in 0 until root.childCount) dump(root.getChild(i), result)
            }
            val details = StringBuilder(); dump(automation.rootInActiveWindow, details)
            java.io.File(instrumentation.targetContext.cacheDir, "smart-ui-accessibility.txt").writeText(details.toString())
            error("Flutter control missing: $label")
        }
        fun click(label: String) {
            repeat(18) { attempt ->
                val node = find(label)
                val bounds = android.graphics.Rect()
                node?.getBoundsInScreen(bounds)
                if (node != null && node.isVisibleToUser && bounds.width() > 0 && bounds.height() in 1..300 && bounds.centerY() in 80..1800) {
                    if (node.isClickable) assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    else android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                    return
                }
                val swipe = if (attempt < 6) "input swipe 540 850 540 1450 250" else "input swipe 540 1450 540 850 250"
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(swipe)).use { it.readBytes() }
                Thread.sleep(200)
            }
            error("Visible control missing: $label")
        }
        fun screenshot(name: String) {
            Thread.sleep(500)
            val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
            try { java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            androidx.test.core.app.ActivityScenario.launch(me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).use {
                waitFor("720")
                // Chat follows the bottom of a tall response; scroll to its card header.
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input swipe 540 700 540 1700 400")).use { it.readBytes() }
                Thread.sleep(500)
                click("展开界面")
                waitFor("720")
                screenshot("smart-ui-expanded.png")
                fun slider(root: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if (root == null) return null
                    if (root.className?.toString() == "android.widget.SeekBar") return root
                    for (i in 0 until root.childCount) slider(root.getChild(i))?.let { return it }
                    return null
                }
                val control = slider(automation.rootInActiveWindow) ?: error("No accessible slider")
                assertTrue(control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                click("保存并关闭")
                waitFor("展开界面")
                val stored = runBlocking { facade.getSurfaceState(original.activeProfileId, conversation.id.toString(), message.id.toString(), "flutter-surface", revision) }
                val amount = org.json.JSONObject(stored).getDouble("amount")
                assertTrue("Slider change was lost on close", amount > 7)
                screenshot("smart-ui-inline.png")
                // Expand to give the submit button a full-height viewport.
                click("展开界面")
                click("继续分析")
                runBlocking { withTimeout(30_000) {
                    service.getConversationFlow(conversation.id).first { it.currentMessages.size > 1 && it.currentMessages.last().generationState == "COMPLETED" }
                } }
                val user = service.getConversationFlow(conversation.id).value.currentMessages.single { it.role == me.rerere.ai.core.MessageRole.USER }
                assertTrue(user.toText().contains(stored))
                assertEquals(1, server.requests.get())
            }
        } finally { service.removeConversationReference(conversation.id) }
    }

    @Test(timeout = 120_000) fun documentRoutingRequestIncludesCoverageAndHonorsUploadRestrictions() = runBlocking {
        server.mode = "context"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val files = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        val docs = koin.get<me.rerere.rikkahub.feature.material.DocumentServiceRepository>()
        val current = settings.settingsFlow.value
        settings.update { config -> config.copy(providers = config.providers.map { provider ->
            if (provider is ProviderSetting.OpenAI) provider.copy(models = provider.models.map { model ->
                if (model.id == config.chatModelId) model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)) else model
            }) else provider
        }) }
        val owner = settings.createLearnerProfile("文档调度回归")
        withTimeout(20_000) { settings.selectLearnerProfile(owner) }
        facade.close()
        facade = StudyHostFacade(context, settings, repository, service, koin.get(), files, koin.get(), pickFiles = { emptyList() })
        val scan = files.runTask(me.rerere.rikkahub.feature.material.MaterialFile(Uuid.random().toString(), owner, "scan.pdf", "pdf")) { target ->
            val pdf = android.graphics.pdf.PdfDocument()
            val bitmap = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawText("Synthetic scan", 10f, 50f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 18f })
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 200, 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null); pdf.finishPage(page)
                target.outputStream().use { pdf.writeTo(it) }
            } finally { bitmap.recycle(); pdf.close() }
            "Synthetic raster PDF"
        }
        try {
            suspend fun capture(text: String, enabled: Boolean): JsonObject {
                docs.configure(owner, "https://qa.example.org", "q".repeat(32), enabled)
                val before = server.contextBodies.size
                val result = facade.sendMessage(SendMessageRequest(Uuid.random().toString(), owner, 1, text, "CHAT", attachmentPaths = listOf(files.file(scan).absolutePath)))
                assertTrue(result.success)
                val id = Uuid.parse(result.idResult!!); conversations += id
                withTimeout(30_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" } }
                return Json.parseToJsonElement(server.contextBodies.drop(before).single { it.contains("【文件任务自动选工具】") }).jsonObject
            }
            val enabled = capture("请提取这份扫描件中的文字和公式。", true)
            fun toolNames(body: JsonObject) = body.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(toolNames(enabled).containsAll(listOf("parse_material_enhanced", "list_material_parse_results")))
            assertTrue(enabled.toString().contains("total_pages"))
            assertTrue(enabled.toString().contains("pages_without_extractable_text"))
            assertTrue(enabled.toString().contains("应主动调用"))
            val restricted = capture("仅本地处理，不上传服务器，请提取文字。", true)
            assertFalse(toolNames(restricted).contains("parse_material_enhanced"))
            assertTrue(toolNames(restricted).contains("list_material_parse_results"))
            assertTrue(restricted.toString().contains("本轮不能新增远端解析任务"))
            val disabled = capture("请提取扫描件文字。", false)
            assertFalse(toolNames(disabled).contains("parse_material_enhanced"))
            settings.update { config -> config.copy(providers = config.providers.map { provider ->
                if (provider is ProviderSetting.OpenAI) provider.copy(models = provider.models.map { model ->
                    if (model.id == config.chatModelId) model.copy(abilities = emptyList()) else model
                }) else provider
            }) }
            val withoutTools = capture("请提取扫描件文字。", true)
            assertFalse(withoutTools.containsKey("tools"))
            assertFalse(withoutTools.toString().contains("本轮parse_material_enhanced可用"))
            assertTrue(withoutTools.toString().contains("模型未启用工具调用"))
            assertTrue(docs.tasks(owner).isEmpty())
        } finally {
            restoreMemoryProfileFixture(current, settings)
            java.io.File(context.filesDir, "materials/$owner").deleteRecursively()
            java.io.File(context.noBackupFilesDir, "document-service/$owner").deleteRecursively()
        }
    }

    private lateinit var settings: SettingsStore
    private lateinit var original: Settings
    private lateinit var server: FaultServer
    private lateinit var service: ChatService
    private lateinit var repository: ConversationRepository
    private lateinit var facade: StudyHostFacade
    private val conversations = mutableListOf<Uuid>()

    @Before fun setup() = runBlocking {
        val koin = GlobalContext.get()
        settings = koin.get()
        original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        server = FaultServer().apply { mode = "complete" }
        val model = Model(modelId = "qa-local")
        val provider = ProviderSetting.OpenAI(name = "QA bridge", models = listOf(model), apiKey = "synthetic", baseUrl = server.baseUrl, useResponseApi = true)
        val assistant = Assistant(name = "QA bridge", chatModelId = model.id, localTools = emptyList())
        settings.update(original.copy(providers = original.providers + provider, assistants = original.assistants + assistant,
            assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
            networkSetting = original.networkSetting.copy(proxyUrl = "", proxyUsername = "", proxyPassword = "")))
        settings.settingsFlow.first { it.assistantId == assistant.id }
        service = koin.get(); repository = koin.get()
        facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings, repository,
            service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
    }

    @After fun cleanup() = runBlocking {
        if (::facade.isInitialized) facade.close()
        try {
            for (id in conversations) {
                service.stopGeneration(id)
                repository.getConversationById(id)?.let { service.deleteConversation(it) }
            }
        } finally {
            if (::original.isInitialized) settings.update(original)
            if (::server.isInitialized) server.close()
        }
    }

    @Test fun committedSendStreamsAndDuplicateSurvivesFacadeRecreation() = runBlocking {
        val request = SendMessageRequest(requestId = Uuid.random().toString(), profileId = original.activeProfileId,
            contextEpoch = 1L, text = "list a travel checklist", studyMode = "CHAT")
        val result = facade.sendMessage(request)
        assertTrue(result.success)
        val id = Uuid.parse(result.idResult!!); conversations += id
        val events = CopyOnWriteArrayList<Map<*, *>>()
        facade.eventSink = object : EventChannel.EventSink {
            override fun success(event: Any?) { assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); events += event as Map<*, *> }
            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun endOfStream() = Unit
        }
        facade.watchConversation(original.activeProfileId, id.toString(), 1L)
        withTimeout(30_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" } }
        withTimeout(10_000) { while (events.isEmpty()) delay(30) }
        assertTrue(events.any { it["type"] == "conversation_changed" && it["contextEpoch"] == 1L })
        val detail = facade.getConversationDetails(original.activeProfileId, id.toString())
        assertEquals("CHAT", detail.studyMode)
        assertEquals(2, detail.nodes.size)
        facade.close()
        val koin = GlobalContext.get()
        facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings, repository,
            service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        assertEquals(id.toString(), facade.sendMessage(request).idResult)
        delay(250)
        assertEquals(2, facade.getConversationDetails(original.activeProfileId, id.toString()).nodes.size)
        assertTrue(runCatching { facade.sendMessage(request.copy(text = "changed request")) }.isFailure)
        assertTrue(runCatching { facade.watchConversation("another-profile", id.toString(), 1L) }.isFailure)
        assertTrue(runCatching { facade.watchConversation(original.activeProfileId, id.toString(), 999L) }.isFailure)
    }
    @Test fun surfaceStatePersistsAndRejectsStaleBranches() = runBlocking { checkSurfacePersistence(false) }
    @Test fun legacyReferencePersistsAndContinuesThroughSharedProtocol() = runBlocking { checkSurfacePersistence(true) }
    private suspend fun checkSurfacePersistence(legacy: Boolean) {
        val definition = """{"version":1,"title":"预算","components":[{"id":"amount","type":"slider","label":"人数","min":1,"max":10,"value":2},{"id":"go","type":"button","label":"继续","action":"continue"}]}"""
        val revision = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(definition)
        val input = if (legacy) definition else kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(definition)) }.toString()
        val output = if (legacy) listOf(me.rerere.ai.ui.UIMessagePart.InteractiveSurfaceRef("legacy_ref_id", "预算", definition))
            else me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(kotlinx.serialization.json.Json.parseToJsonElement(input))
        val message = me.rerere.ai.ui.UIMessage.assistant("").copy(generationState = "COMPLETED", parts = listOf(me.rerere.ai.ui.UIMessagePart.Tool("qa-surface", if (legacy) "create_interactive_surface" else "render_interactive_ui", input, output, executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)))
        val node = me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message, me.rerere.ai.ui.UIMessage.assistant("alternative")))
        val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId, title = "QA interactive", messageNodes = listOf(node), profileId = original.activeProfileId, newConversation = true)
        conversations += conversation.id
        service.initializeConversation(conversation.id)
        service.saveConversation(conversation.id, conversation)
        val owner = original.activeProfileId; val id = conversation.id.toString(); val messageId = message.id.toString()
        val exposed = facade.getConversationDetails(owner, id).nodes.single().branches.first().parts
        assertEquals(1, exposed.size)
        assertEquals("tool", exposed.single().type)
        assertTrue(exposed.single().toolOutput!!.contains(revision))
        assertEquals("{\"amount\":2}", facade.getSurfaceState(owner, id, messageId, "qa-surface", revision))
        assertTrue(facade.surfaceAction(owner, id, messageId, "qa-surface", revision, "{\"amount\":7}", null, "save", 1L).success)
        val storedTool = repository.getConversationById(conversation.id)!!.currentMessages.single().parts.single() as me.rerere.ai.ui.UIMessagePart.Tool
        assertNotNull(storedTool.metadata?.get(me.rerere.rikkahub.feature.interactive.InteractiveSurface.STATE_KEY))
        facade.close()
        val koin = GlobalContext.get()
        facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, settings, repository, service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        assertEquals("{\"amount\":7}", facade.getSurfaceState(owner, id, messageId, "qa-surface", revision))
        assertTrue(runCatching { facade.surfaceAction(owner, id, messageId, "qa-surface", "stale", "{\"amount\":7}", "go", "action", 1L) }.isFailure)
        assertTrue(runCatching { facade.surfaceAction(owner, id, messageId, "qa-surface", revision, "{\"amount\":11}", "go", "action", 1L) }.isFailure)
        service.selectMessageNode(conversation.id, node.id, 1)
        assertTrue(runCatching { facade.getSurfaceState(owner, id, messageId, "qa-surface", revision) }.isFailure)
        service.selectMessageNode(conversation.id, node.id, 0)
        assertTrue(facade.surfaceAction(owner, id, messageId, "qa-surface", revision, "{\"amount\":7}", "go", "action", 1L).success)
        withTimeout(30_000) { service.getConversationFlow(conversation.id).first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" && it.currentMessages.size > 1 } }
        facade.surfaceAction(owner, id, messageId, "qa-surface", revision, "{\"amount\":7}", "go", "action", 1L)
        assertEquals("{\"amount\":7}", facade.getSurfaceState(owner, id, messageId, "qa-surface", revision))
        assertEquals(1, service.getConversationFlow(conversation.id).value.currentMessages.count { it.role == me.rerere.ai.core.MessageRole.USER })
        assertTrue(runCatching { facade.surfaceAction(owner, id, messageId, "qa-surface", revision, "{\"amount\":8}", "go", "action", 1L) }.isFailure)
    }

    @Test fun realArtifactInToolResultIsExposedAndSaveIsVerified() = runBlocking {
        val koin = GlobalContext.get(); val store = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = original.activeProfileId
        val item = store.runTask(me.rerere.rikkahub.feature.material.MaterialFile(java.util.UUID.randomUUID().toString(), owner, "QA bridge.docx", "docx", output = true)) { file -> me.rerere.rikkahub.feature.material.MaterialWriter.write(file, "docx", "真实交付合成段落", emptyList(), emptyList()) }
        val destination = java.io.File(ctx.cacheDir, "qa-bridge-save.docx")
        try {
            facade.close()
            facade = StudyHostFacade(ctx, settings, repository, service, koin.get(), store, koin.get(), pickFiles = { emptyList() }, saveFile = { _, _ -> android.net.Uri.fromFile(destination) })
            val message = me.rerere.ai.ui.UIMessage.assistant("").copy(generationState = "COMPLETED", parts = listOf(me.rerere.ai.ui.UIMessagePart.Tool("qa-artifact", "create_material_file", "{}", listOf(me.rerere.ai.ui.UIMessagePart.Document(android.net.Uri.fromFile(store.verifiedFile(owner, item.id)).toString(), item.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")), executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)))
            val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId, title = "QA files", messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))), profileId = owner, newConversation = true)
            conversations += conversation.id
            service.initializeConversation(conversation.id); service.saveConversation(conversation.id, conversation)
            val card = facade.getConversationDetails(owner, conversation.id.toString()).nodes.single().branches.single().parts.single { it.type == "file" }
            assertEquals(item.id, card.artifactId)
            assertTrue(facade.saveArtifact(owner, item.id).success)
            assertEquals(item.sha256, me.rerere.rikkahub.feature.material.MaterialFiles.digest(destination))
            destination.appendText("corrupted")
            store.file(item).appendText("corrupted")
            assertTrue(runCatching { facade.saveArtifact(owner, item.id) }.isFailure)
            assertTrue(runCatching { facade.saveArtifact("other-owner", item.id) }.isFailure)
        } finally {
            destination.delete(); store.file(item).delete(); java.io.File(ctx.filesDir, "materials/$owner/${item.id}.meta.json").delete()
        }
    }

    @Test fun verifiedCachedDocumentResumesOnceAndCorruptCacheNeverResumes() = runBlocking {
        val koin = GlobalContext.get(); val store = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        val docs = koin.get<me.rerere.rikkahub.feature.material.DocumentServiceRepository>()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext; val owner = original.activeProfileId
        val source = store.runTask(me.rerere.rikkahub.feature.material.MaterialFile(java.util.UUID.randomUUID().toString(), owner, "QA source.pdf", "pdf")) { file -> me.rerere.rikkahub.feature.material.MaterialWriter.write(file, "pdf", "合成扫描结果来源", emptyList(), emptyList()) }
        val taskId = java.util.UUID.randomUUID().toString()
        val directory = java.io.File(ctx.noBackupFilesDir, "document-service/$owner/$taskId.result").apply { mkdirs() }
        val taskFile = java.io.File(directory.parentFile, "$taskId.task.json")
        try {
            val doc = me.rerere.rikkahub.feature.material.ParsedDocument(1, source.sha256, 1, "synthetic", "1", "basic", 1, listOf(1,1), listOf(1), emptyList(), emptyList(), true, emptyList(), listOf(me.rerere.rikkahub.feature.material.ParsedBlock("p1-b1", 1, "text", "合成识别结果")))
            val bytes = kotlinx.serialization.json.Json.encodeToString(me.rerere.rikkahub.feature.material.ParsedDocument.serializer(), doc)
            val document = java.io.File(directory, "document").apply { writeText(bytes) }
            val manifest = me.rerere.rikkahub.feature.material.DocumentManifest(1, source.sha256, 1, "synthetic", "1", "basic", 1, listOf(1,1), listOf(1), emptyList(), emptyList(), true, emptyList(), listOf(me.rerere.rikkahub.feature.material.DocumentAsset("document", "document.json", "application/json", document.length(), me.rerere.rikkahub.feature.material.MaterialFiles.digest(document))))
            java.io.File(directory, "manifest.json").writeText(kotlinx.serialization.json.Json.encodeToString(me.rerere.rikkahub.feature.material.DocumentManifest.serializer(), manifest))
            val task = me.rerere.rikkahub.feature.material.DocumentTask(taskId, source.id, source.sha256, 1, 1, "synthetic-cache", state = "SUCCEEDED", downloaded = true)
            taskFile.writeText(kotlinx.serialization.json.Json.encodeToString(me.rerere.rikkahub.feature.material.DocumentTask.serializer(), task))
            val input = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(source.id)); put("start_page", kotlinx.serialization.json.JsonPrimitive("1")); put("end_page", kotlinx.serialization.json.JsonPrimitive("1")) }.toString()
            val tool = me.rerere.ai.ui.UIMessagePart.Tool("qa-document", "parse_material_enhanced", input, listOf(me.rerere.ai.ui.UIMessagePart.Text("{\"task_id\":\"$taskId\"}")), executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)
            val message = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool), generationState = "COMPLETED")
            val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = settings.settingsFlow.value.assistantId, title = "QA document continuation", messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(me.rerere.ai.ui.UIMessage.user("总结PDF"))), me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message))), profileId = owner, newConversation = true)
            conversations += conversation.id; service.initializeConversation(conversation.id); service.saveConversation(conversation.id, conversation)
            facade.watchConversation(owner, conversation.id.toString(), 1L)
            withTimeout(30_000) { service.getConversationFlow(conversation.id).first { it.currentMessages.size == 4 && it.currentMessages.last().generationState == "COMPLETED" } }
            facade.watchConversation(owner, conversation.id.toString(), 1L)
            delay(700)
            assertEquals(4, service.getConversationFlow(conversation.id).value.currentMessages.size)
            assertEquals(doc, docs.read(owner, taskId))
            val localReader = me.rerere.rikkahub.feature.material.documentServiceTools(docs, owner, false, allowRemote = false).single { it.name == "read_material_parse_blocks" }
            assertTrue(localReader.execute(kotlinx.serialization.json.buildJsonObject { put("task_id", kotlinx.serialization.json.JsonPrimitive(taskId)); put("cursor", kotlinx.serialization.json.JsonPrimitive("")); put("count", kotlinx.serialization.json.JsonPrimitive("1")) }).isNotEmpty())
            document.appendText("corrupt")
            assertTrue(runCatching { docs.read(owner, taskId) }.isFailure)
            val other = conversation.copy(id = Uuid.random(), newConversation = true)
            conversations += other.id; service.initializeConversation(other.id); service.saveConversation(other.id, other)
            facade.watchConversation(owner, other.id.toString(), 1L)
            delay(1000)
            assertEquals(2, service.getConversationFlow(other.id).value.currentMessages.size)
            taskFile.writeText(kotlinx.serialization.json.Json.encodeToString(me.rerere.rikkahub.feature.material.DocumentTask.serializer(), task.copy(state = "FAILED", downloaded = false)))
            facade.documentTaskAction(owner, other.id.toString(), message.id.toString(), "qa-document", "retry", 1L)
            withTimeout(30_000) { service.getConversationFlow(other.id).first { it.currentMessages.size == 4 && it.currentMessages.last().generationState == "COMPLETED" } }
            facade.documentTaskAction(owner, other.id.toString(), message.id.toString(), "qa-document", "retry", 1L)
            assertEquals(4, service.getConversationFlow(other.id).value.currentMessages.size)
        } finally {
            facade.watchConversation(owner, null, 1L)
            taskFile.delete(); directory.deleteRecursively(); store.file(source).delete(); java.io.File(ctx.filesDir, "materials/$owner/${source.id}.meta.json").delete()
        }
    }

}
