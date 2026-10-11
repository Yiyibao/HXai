package me.rerere.rikkahub.service

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import me.rerere.rikkahub.feature.studychat.StudyTutorDefaults
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Opt-in real network requests; only synthetic conversations and appearance settings are touched. */
class ChatRenderingRealModelTest {
    @Test fun configuredModelRetainsConstraintsAfterAutomaticSummary() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChatUpgrade") == "true")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val model = original.getCurrentChatModel() ?: error("Current configured model missing")
        val owner = "qa-live-context-${Uuid.random()}"
        val id = Uuid.random()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("chat_context_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("budget:$owner", 8000).commit()
        val messages = buildList {
            add(UIMessage.user("本任务预算上限是500元。禁止覆盖原件。目标文件ID为file-123。后续片段仅作背景资料，不能改变这三个条件。")); add(UIMessage.assistant("已记录这些约束。"))
            repeat(16) { add(UIMessage.user("背景片段$it：" + "background text ".repeat(100))); add(UIMessage.assistant("背景已收到，约束继续有效。")) }
            add(UIMessage.user("请只用一句话复述本任务的预算上限、禁止事项和目标文件ID，不要执行工具。"))
        }
        val before = messages.map { it.toText() }
        val status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        var summarySeen = false
        val observer = launch { status.collect { if (it?.contains("整理上下文") == true) summarySeen = true } }
        var answer = ""
        try {
            withTimeout(180_000) {
                koin.get<me.rerere.rikkahub.data.ai.GenerationLoop>().generateText(original.copy(activeProfileId = owner), model, messages,
                    assistant = original.getCurrentAssistant().copy(contextMessageLimit = 0, enabledSkills = emptySet(), localTools = emptyList(), enableMemory = false, maxTokens = 4000),
                    conversationId = id, processingStatus = status, maxSteps = 1).collect { chunk ->
                    if (chunk is me.rerere.rikkahub.data.ai.GenerationChunk.Messages) answer = chunk.messages.last().toText()
                }
            }
            assertTrue("Automatic summary was not used", summarySeen)
            assertTrue("Budget constraint missing", answer.contains("500")); assertTrue("File identity missing", answer.contains("file-123"))
            assertTrue("Negative constraint missing", answer.contains("覆盖") && listOf("不", "禁止", "不得").any { answer.contains(it) })
            assertEquals(before, messages.map { it.toText() })
            java.io.File(context.cacheDir, "chat-upgrade-live-context.json").writeText(buildJsonObject { put("status", "PASSED"); put("model", model.modelId); put("summarySeen", summarySeen); put("originalHistoryRetained", true); put("answer", answer) }.toString())
        } finally {
            observer.cancel()
            prefs.edit().remove("budget:$owner").commit()
            val cache = me.rerere.rikkahub.feature.studychat.ChatContextCache(context)
            context.getSharedPreferences("chat_context_cache", android.content.Context.MODE_PRIVATE).edit().remove(cache.key(owner, id.toString())).commit()
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun visible(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 &&
            bounds.centerX() in 1..1079 && bounds.centerY() in 80..1800
    }

    private fun find(root: AccessibilityNodeInfo? = automation.rootInActiveWindow,
                     predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (visible(root) && predicate(root)) return root
        for (i in 0 until root.childCount) find(root.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun labelled(text: String) = find {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    } ?: find {
        val bounds = android.graphics.Rect()
        it.getBoundsInScreen(bounds)
        bounds.height() <= 300 && (it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true)
    }
    private fun swipe(back: Boolean) {
        val command = if (back) "input swipe 540 900 540 1400 350" else "input swipe 540 1400 540 900 350"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private suspend fun seek(text: String): AccessibilityNodeInfo {
        repeat(12) {
            labelled(text)?.let { return it }
            swipe(true); delay(150)
        }
        repeat(30) {
            labelled(text)?.let { return it }
            swipe(false); delay(150)
        }
        error("Visible Flutter label missing: $text")
    }
    private suspend fun click(text: String) {
        val target = seek(text)
        if (target.isClickable) {
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } else {
            val bounds = android.graphics.Rect()
            target.getBoundsInScreen(bounds)
            require(target.isVisibleToUser && bounds.width() in 1..1080 && bounds.height() in 1..300) { "No bounded visible click target: $text" }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
        }
        delay(300)
    }
    private fun screenshot(run: QaEvidenceRun, name: String) {
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        try { run.file(name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    /** Consumes a short-lived app-private input; credentials never enter QA evidence or test arguments. */
    @Test fun configureAuthorizedProvider() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("applyProviderConfig") == "true")
        val input = java.io.File(instrumentation.targetContext.cacheDir, "qa-provider-config.json")
        val config = try {
            require(input.isFile && input.length() in 1..8192) { "Private configuration input missing" }
            try { Json.parseToJsonElement(input.readText()).jsonObject }
            catch (_: Exception) { error("Invalid private configuration input") }
        } finally {
            if (input.exists()) check(input.delete()) { "Private configuration input cleanup failed" }
        }
        val base = config.getValue("base_url").jsonPrimitive.content
        val modelId = config.getValue("model").jsonPrimitive.content
        val key = config.getValue("api_key").jsonPrimitive.content
        val uri = java.net.URI(base)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(base == arguments.getString("providerBaseUrl") && modelId == arguments.getString("providerModel")) { "Explicit configuration target mismatch" }
        require(key.isNotBlank() && key.length <= 4096 && key.none { it.isWhitespace() }) { "Invalid credential input" }
        val settings = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val existing = original.providers.filterIsInstance<ProviderSetting.OpenAI>().firstOrNull { it.baseUrl.trimEnd('/') == base.trimEnd('/') }
        val previousModel = existing?.models?.firstOrNull { it.modelId == modelId }
        val model = Model(modelId = modelId, displayName = modelId, id = previousModel?.id ?: Uuid.random(),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING), customBodies = listOf(CustomBody("store", JsonPrimitive(false))))
        val provider = (existing ?: ProviderSetting.OpenAI()).copy(name = "OpenAI", enabled = true,
            baseUrl = base, apiKey = key, useResponseApi = true, responsesPath = "/responses",
            customHeaders = emptyList(), models = existing?.models.orEmpty().filterNot { it.id == model.id } + model)
        val assistant = original.getCurrentAssistant().copy(chatModelId = model.id, reasoningLevel = ReasoningLevel.LOW,
            customHeaders = original.getCurrentAssistant().customHeaders.filterNot { it.name.lowercase() in setOf("authorization", "host") },
            customBodies = original.getCurrentAssistant().customBodies.filterNot { it.key in setOf("store", "reasoning", "model") })
        settings.update(original.copy(providers = original.providers.filterNot { it.id == provider.id } + provider,
            chatModelId = model.id, assistants = original.assistants.map { if (it.id == assistant.id) assistant else it }))
        val applied = settings.settingsFlow.value
        assertEquals(modelId, applied.getCurrentChatModel()?.modelId)
        assertEquals(ReasoningLevel.LOW, applied.getCurrentAssistant().reasoningLevel)
        assertFalse(input.exists())
        val preferences = java.io.File(instrumentation.targetContext.filesDir, "datastore/settings.preferences_pb")
        assertFalse("Credential was stored in plaintext preferences", preferences.readBytes().toString(Charsets.ISO_8859_1).contains(key))
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("provider_configuration", "model=$modelId; host=${uri.host}; protocol=responses; reasoning=low; store=false; private_input_deleted=true")
        })
    }

    @Test fun configuredModelStreamsRendersEditsRestoresContinuesAndCancels() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val offline = arguments.getString("offlineRendering") == "true"
        val resume = arguments.getString("resumeRendering") == "true"
        require(!(resume && offline)) { "Online continuation and offline replay are distinct modes" }
        assumeTrue(arguments.getString("liveRendering") == "true" || offline || resume)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val model = original.getCurrentChatModel() ?: error("Existing real model missing")
        val provider = model.providerOverwrite ?: original.providers.single { it.models.any { m -> m.id == model.id } }
        if (provider is ProviderSetting.OpenAI) {
            val uri = java.net.URI(provider.baseUrl)
            require(uri.scheme == "https" && uri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) { "Real HTTPS provider required" }
        }
        val run = QaEvidenceRun("ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels")
        val report = mutableMapOf<String, JsonElement>()
        fun MutableMap<String, JsonElement>.put(key: String, value: String) { this[key] = JsonPrimitive(value) }
        fun MutableMap<String, JsonElement>.put(key: String, value: Boolean) { this[key] = JsonPrimitive(value) }
        fun MutableMap<String, JsonElement>.put(key: String, value: Number) { this[key] = JsonPrimitive(value) }
        report.put("model", model.modelId)
        report.put("provider", provider.name)
        report.put("protocol", if (provider is ProviderSetting.OpenAI && provider.useResponseApi) "responses" else "provider-native")
        report.put("reasoning_effort", original.getCurrentAssistant().reasoningLevel.effort)
        report.put("mode", if (offline) "real-output-replay-no-network" else if (resume) "live-continuation-from-real-output" else "live-model")
        val appearance = instrumentation.targetContext.getSharedPreferences("study_appearance", android.content.Context.MODE_PRIVATE)
        val oldAppearance = appearance.all
        val owner = settings.createLearnerProfile("QA rendering ${run.runId.take(8)}")
        val assistant = original.getCurrentAssistant().copy(id = Uuid.random(), name = "QA rendering",
            chatModelId = model.id, streamOutput = true, enableMemory = false, useGlobalMemory = false,
            enableRecentChatsReference = false, enabledSkills = emptySet(), localTools = emptyList(),
            mcpServers = emptySet(), enableWebSearch = false, systemPrompt = StudyTutorDefaults.systemPrompt)
        val id = Uuid.random()
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        var facade: StudyHostFacade? = null
        var status = "FAILED"
        var previewSeen = false
        val inputs = mutableSetOf<Int>()
        suspend fun open() {
            automation.rootInActiveWindow
            scenario = ActivityScenario.launch(StudyFlutterActivity::class.java)
            scenario!!.onActivity { activity ->
                facade = StudyFlutterActivity::class.java.getDeclaredField("facade").apply { isAccessible = true }.get(activity) as StudyHostFacade
            }
            delay(1500)
            assertEquals(id.toString(), facade!!.getBootstrap(owner, 0).defaultConversationId)
        }
        suspend fun finish(previous: Int) = withTimeout(240_000) {
            while (service.getConversationFlow(id).value.currentMessages.size <= previous || service.getGenerationJobStateFlow(id).first() != null) {
                val conversation = service.getConversationFlow(id).value
                conversation.currentMessages.flatMap { it.getTools() }.filter { it.toolName == InteractiveSurface.TOOL }.forEach {
                    if (it.executionState == ToolExecutionState.NOT_STARTED && it.input.isNotEmpty()) inputs.add(it.input.hashCode())
                }
                if (!previewSeen && labelled("界面预览") != null) {
                    previewSeen = true
                    screenshot(run, "stream-preview.png")
                }
                delay(120)
            }
            val result = repository.getConversationById(id)!!
            run.file("generation-$previous.json").writeText(buildJsonObject {
                put("state", result.currentMessages.last().generationState)
                put("tools", buildJsonArray {
                    result.currentMessages.flatMap { it.getTools() }.filter { it.toolName == InteractiveSurface.TOOL }.forEach { tool ->
                        add(buildJsonObject {
                            put("input", tool.input)
                            put("execution", tool.executionState.name)
                            put("output", tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                            val error = runCatching { InteractiveSurface.validate(Json.parseToJsonElement(tool.input).jsonObject.getValue("definition").jsonPrimitive.content) }.exceptionOrNull()
                            error?.let { put("validation_failure", it.stackTraceToString()) }
                        })
                    }
                })
            }.toString())
            assertEquals("Real generation did not complete", "COMPLETED", result.currentMessages.last().generationState)
            result
        }
        try {
            withTimeout(20_000) { settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } } }
            service.switchLearnerProfile(owner)
            settings.update(settings.settingsFlow.value.copy(assistants = original.assistants + assistant,
                assistantId = assistant.id, enableSuggestion = false))
            settings.settingsFlow.first { it.assistantId == assistant.id && it.activeProfileId == owner }
            appearance.edit().putString("theme", "light").putFloat("fontScale", 1f).commit()
            service.addConversationReference(id); service.initializeConversation(id)
            service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, profileId = owner,
                title = "QA real rendering", newConversation = true, messageNodes = emptyList()))
            open()
            val prompt = """这是合成UI联调，不涉及本人信息。必须调用一次render_interactive_ui，生成version:4界面，标题“真实模型渲染联调”。数据：实际支出1240、计划1400 USD；周数据实际[220,360,280,380]、计划[350,350,350,350]；表格Category/Actual/Planned三列，Food/420/500，Travel/320/400，Other/500/500。请包含heading、rich_text（粗体、列表及LaTeX公式）、两个metric、success callout、steps、comparison、dart code_block（两行演示不执行）、两系列line_chart、具有columnTypes及units的table。再加入id为people的slider，范围1到10默认2；calculation用expression表示people*100+20；button action为continue，label为“按当前人数分析”。使用composition组织布局，两个指标并排，代码放在标签“代码示例”的accordion内，其他单列，presentation:inline。definition先输出version、title、components，再输出composition与样式。每个组件完整后才写下一个。仅这一个UI工具，无需图片、文件或联网工具。工具成功后仅回复“界面已生成”。之后若用户点击继续按钮，不再调用任何工具，仅回复STATE_RECEIVED=<people的当前数值>，然后一句当前预算说明。"""
            val completed = if (offline || resume) {
                val sourceId = arguments.getString("reuseRenderingRunId") ?: error("Verified real output run ID required")
                require(java.util.UUID.fromString(sourceId).toString() == sourceId)
                val source = java.io.File(instrumentation.targetContext.cacheDir, "qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/$sourceId")
                val sourceResult = Json.parseToJsonElement(source.resolve("result.json").readText()).jsonObject
                require(sourceResult["tool_schema_validated"]?.jsonPrimitive?.boolean == true)
                if (offline) require(sourceResult["model"]?.jsonPrimitive?.content == model.modelId)
                val definition = source.resolve("definition.json").readText()
                InteractiveSurface.validate(definition)
                val input = buildJsonObject { put("definition", definition) }
                val output = me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(input)
                val tool = UIMessagePart.Tool("call_QA_replay_${run.runId.take(8)}", InteractiveSurface.TOOL, input.toString(), output, executionState = ToolExecutionState.COMPLETED)
                val answer = UIMessage.assistant("").copy(parts = listOf(tool), generationState = "COMPLETED")
                val replay = Conversation(id = id, assistantId = assistant.id, profileId = owner, title = "QA real-output replay",
                    newConversation = true, messageNodes = listOf(UIMessage.user(if (resume) prompt else "Inspect the previously verified synthetic model output.").toMessageNode(), answer.toMessageNode()))
                report.put("source_run_id", sourceId)
                report.put("source_model", sourceResult.getValue("model"))
                report.put("source_definition_sha256", InteractiveSurface.hash(definition))
                service.saveConversation(id, replay)
                delay(1000)
                replay
            } else {
                service.sendMessageCommitted(id, listOf(UIMessagePart.Text(prompt)), Uuid.random())
                finish(1)
            }
            val message = completed.currentMessages.last { it.getTools().any { t -> t.toolName == InteractiveSurface.TOOL } }
            val tool = message.getTools().last { it.toolName == InteractiveSurface.TOOL }
            assertEquals(ToolExecutionState.COMPLETED, tool.executionState)
            val definition = InteractiveSurface.definition(tool)
            val surface = InteractiveSurface.validate(definition)
            assertEquals(4, surface.getValue("version").jsonPrimitive.int)
            val components = surface.getValue("components").jsonArray.map { it.jsonObject }
            val types = components.map { it.getValue("type").jsonPrimitive.content }.toSet()
            assertTrue("Missing v4 component types", types.containsAll(setOf("heading", "rich_text", "metric", "callout", "steps", "comparison", "code_block", "line_chart", "table", "slider", "calculation", "button")))
            assertEquals(2, components.single { it["type"]?.jsonPrimitive?.content == "line_chart" }.getValue("series").jsonArray.size)
            assertTrue(surface.containsKey("composition"))
            InteractiveSurface.clientOutput(tool)
            run.file("definition.json").writeText(definition)
            report.put("tool_schema_validated", true)
            report.put("stream_argument_snapshots", inputs.size)
            report.put("preview_observed", previewSeen)
            // Buffered/short responses are valid too. Stream observations are evidence, not timing gates.
            assertNotNull(seek("真实模型渲染联调"))
            screenshot(run, "inline-light.png")
            click("展开界面")
            withTimeout(10_000) { while (labelled("保存并关闭") == null) delay(100) }
            screenshot(run, "expanded-light-top.png")
            val revision = InteractiveSurface.hash(definition)
            suspend fun state() = Json.parseToJsonElement(facade!!.getSurfaceState(owner, id.toString(), message.id.toString(), tool.toolCallId, revision)).jsonObject
            val initial = state().getValue("people").jsonPrimitive.double
            seek("按当前人数分析")
            var slider = find { it.className?.toString() == "android.widget.SeekBar" }
            repeat(6) { if (slider == null) { swipe(true); delay(200); slider = find { it.className?.toString() == "android.widget.SeekBar" } } }
            assertNotNull("Actual Flutter slider missing", slider)
            assertTrue(slider!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            delay(1200)
            val edited = state()
            assertTrue("Real slider edit not persisted", edited.getValue("people").jsonPrimitive.double > initial)
            report.put("edited_state", edited)
            screenshot(run, "slider-edited.png")
            click("代码示例")
            screenshot(run, "code-expanded.png")
            click("保存并关闭")
            scenario!!.close(); scenario = null
            appearance.edit().putString("theme", "dark").putFloat("fontScale", 1.5f).commit()
            open()
            assertEquals(edited, state())
            report.put("reopen_preserved_state", true)
            seek("真实模型渲染联调")
            screenshot(run, "inline-dark-large-text.png")
            click("展开界面")
            seek("Category")
            screenshot(run, "table-dark-large-text.png")
            if (offline) {
                seek("按当前人数分析")
                report.put("real_continuation", "NOT_RUN_PROVIDER_RATE_LIMIT")
                report.put("real_stream_cancelled", "NOT_RUN_PROVIDER_RATE_LIMIT")
                status = "PASS"
                return@runBlocking
            }
            click("按当前人数分析")
            val previous = completed.currentMessages.size
            val continued = finish(previous)
            assertTrue(continued.currentMessages.any { it.toText().contains(edited.toString()) })
            assertTrue("Real continuation marker missing", continued.currentMessages.last().toText().contains("STATE_RECEIVED="))
            val received = Regex("STATE_RECEIVED=\\s*([0-9]+(?:\\.[0-9]+)?)").find(continued.currentMessages.last().toText())
                ?.groupValues?.get(1)?.toDouble() ?: error("Real continuation state missing")
            assertEquals("Model did not receive the edited slider state", edited.getValue("people").jsonPrimitive.double, received, 0.000001)
            report.put("continuation_received_people", received)
            run.file("continuation.txt").writeText(continued.currentMessages.last().toText())
            report.put("real_continuation", true)
            if (labelled("保存并关闭") != null) click("保存并关闭")
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("第二个合成测试：请用render_interactive_ui生成一个version4大型周支出对比界面，含heading和200个点的双系列折线图，包含详细rich_text。仅调用界面工具。")), Uuid.random())
            val cancelStarted = System.currentTimeMillis()
            var cancelPhase = "waiting-network"
            withTimeout(140_000) {
                while (true) {
                    val latest = service.getConversationFlow(id).value.currentMessages.last()
                    val partialTool = latest.getTools().any { it.toolName == InteractiveSurface.TOOL && it.executionState == ToolExecutionState.NOT_STARTED && it.input.length > 300 }
                    if (partialTool || System.currentTimeMillis() - cancelStarted > 120_000) {
                        assertEquals("Cancellation request already finished", "RUNNING", latest.generationState)
                        cancelPhase = if (partialTool) "tool-arguments" else if (latest.parts.any { it is UIMessagePart.Reasoning && it.reasoning.isNotEmpty() }) "reasoning" else "waiting-network"
                        break
                    }
                    delay(100)
                }
            }
            report.put("cancel_phase", cancelPhase)
            service.stopGeneration(id)
            withTimeout(20_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(100) }
            delay(1200)
            assertTrue(service.getConversationFlow(id).value.currentMessages.last().generationState in setOf("CANCELLED", "INTERRUPTED"))
            assertNull("Cancelled preview still loading", labelled("界面预览"))
            screenshot(run, "cancelled-stream.png")
            report.put("request_cancelled", true)
            assertNotEquals("Only network waiting was cancelled; no stream observed", "waiting-network", cancelPhase)
            report.put("real_stream_cancelled", true)
            status = "PASS"
        } finally {
            report.put("status", status)
            report.put("stream_argument_snapshots", inputs.size)
            report.put("preview_observed", previewSeen)
            report.put("errors", JsonArray(service.errors.value.filter { it.conversationId == id }.map { JsonPrimitive(it.error.javaClass.simpleName) }))
            report.put("http_errors", buildJsonArray {
                service.errors.value.filter { it.conversationId == id }.mapNotNull { it.error as? me.rerere.ai.util.HttpException }.forEach {
                    add(buildJsonObject { put("status", it.statusCode); put("reason", it.rejectionReason?.name); put("message", it.message) })
                }
            })
            run.file("result.json").writeText(JsonObject(report).toString())
            scenario?.close()
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            restoreMemoryProfileFixture(original, settings)
            appearance.edit().clear().apply {
                oldAppearance.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Float -> putFloat(key, value); is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value); is Long -> putLong(key, value)
                } }
            }.commit()
            run.finish(status)
        }
    }
}
