package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.math.MathVerification
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Real host registration/receipt and complete ChatService task path, loopback only. */
@RunWith(AndroidJUnit4::class)
class MathSharedChatRuntimeTest {
    private fun identity(right: String = "x^2+2*x+1") = buildJsonObject {
        put("operation", "identity"); put("left", "(x+1)^2"); put("right", right)
    }
    private fun bound(expression: String = "1/(x+1)", claim: String = "1") = buildJsonObject {
        put("operation", "rational_uniform_bound_sturm"); put("expression", expression)
        put("approximation", "0"); put("lower", "0"); put("upper", "1"); put("claimed_bound", claim)
    }

    @Test fun factoryExposesTrustedMathTool(): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = withTimeout(20_000) { k.get<SettingsStore>().settingsFlow.first { !it.init } }
        val model = Model(modelId = "qa-shared-math", abilities = listOf(ModelAbility.TOOL))
        val tools = k.get<ChatToolFactory>().createTools(settings, Assistant(chatModelId = model.id), model, profileId = settings.activeProfileId)
        assertEquals(1, tools.count { it.name == "math_verify" })
        assertEquals("EXACT_MATH", TrustedToolRegistry.require("math_verify").verifier.name)
    }

    private suspend fun receipt(input: JsonObject, output: JsonObject, ownerMismatch: Boolean = false): Boolean {
        val k = GlobalContext.get(); val owner = "qa-math-${Uuid.random()}"
        val tool = UIMessagePart.Tool("math-call", "math_verify", input.toString(), listOf(UIMessagePart.Text(output.toString())),
            executionState = ToolExecutionState.COMPLETED)
        val conversation = Conversation(assistantId = Uuid.random(), profileId = owner,
            messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()))
        return TaskResultVerifier(k.get(), k.get(), k.get()).verify(if (ownerMismatch) "$owner-other" else owner, conversation, tool)
    }

    @Test fun validDurableExactReceiptCanBeVerified(): Unit = runBlocking {
        val input = identity(); assertTrue(receipt(input, MathVerification.verify(input)))
    }
    @Test fun wrongInputCannotReuseAnotherExactCertificate(): Unit = runBlocking {
        assertFalse(receipt(identity("0"), MathVerification.verify(identity())))
    }
    @Test fun forgedVerifiedStatusCannotTurnDisproofIntoSuccess(): Unit = runBlocking {
        val input = identity("0"); val actual = MathVerification.verify(input)
        assertFalse(receipt(input, JsonObject(actual + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
    }
    @Test fun modifiedCertificateFieldCannotPassIndependentRecomputation(): Unit = runBlocking {
        val input = identity(); val actual = MathVerification.verify(input)
        assertFalse(receipt(input, JsonObject(actual + ("input_sha256" to JsonPrimitive("forged-input")))))
    }
    @Test fun receiptCannotCrossOwner(): Unit = runBlocking {
        val input = identity(); val actual = MathVerification.verify(input)
        assertTrue(runCatching { receipt(input, actual, true) }.isFailure)
    }

    @Test fun realChatIdentityPassesCompleteTaskAcceptance() = scenario(identity(), "VERIFIED", true)
    @Test fun realChatRationalBoundPassesCompleteTaskAcceptance() = scenario(bound(), "VERIFIED", true)
    @Test fun falseClaimCannotSucceedDespiteModelSuccess() = scenario(bound(claim = "0"), "DISPROVED", false)
    @Test fun originalDomainHoleCannotSucceedDespiteModelSuccess() = scenario(bound(expression = "x/x"), "UNSUPPORTED", false)
    @Test fun generalFunctionCannotBorrowAnExactPolynomialSuccess() = scenario(identity("sin(x)"), "UNSUPPORTED", false)
    @Test fun wrongSemanticEvidenceHashCannotAcceptAnExactFact() = scenario(identity(), "VERIFIED", false, true)

    private fun scenario(input: JsonObject, status: String, expectedSuccess: Boolean, badSemanticHash: Boolean = false): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val calls = AtomicInteger()
        val server = LocalModelServer { body ->
            val parsed = Json.parseToJsonElement(body).jsonObject
            val messages = parsed.getValue("messages").jsonArray
            val first = messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps", buildJsonArray { add(buildJsonObject { put("id", "math"); put("tool", "math_verify"); put("purpose", "Verify only the exact requested claim and original domain") }) })
                    put("acceptance", buildJsonArray { add(buildJsonObject { put("stepId", "math"); put("kind", "tool_verified") }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    val evidence = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted", true); put("goalRevision", evidence.getValue("goalRevision"))
                        put("evidenceHash", if (badSemanticHash) JsonPrimitive("wrong-evidence") else evidence.getValue("evidenceHash"))
                        put("reason", "Synthetic semantic evaluator: exact requested scope only")
                    }.toString())
                }
                calls.incrementAndGet() == 1 -> buildJsonObject {
                    put("id", "synthetic-math"); put("model", "qa-shared-math")
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("finish_reason", "tool_calls")
                        put("message", buildJsonObject {
                            put("role", "assistant"); put("content", "")
                            put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("id", "math-once"); put("type", "function")
                                put("function", buildJsonObject { put("name", "math_verify"); put("arguments", input.toString()) })
                            }) })
                        })
                    }) })
                }.toString()
                else -> LocalModelServer.completion("The model claims success; only the application can accept the goal.")
            }
        }
        val model = Model(modelId = "qa-shared-math", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(name = "QA shared math", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA shared math", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(k.get()); val id = Uuid.random()
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA math goal", newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("Use exact math_verify on this precise claim and original domain: $input. Unsupported or disproved is not success.")), Uuid.random())
            withTimeout(45_000) {
                while (true) {
                    val current = service.getConversationFlow(id).first()
                    val task = scheduler.current(current)
                    if (task != null && task.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) break
                    delay(20)
                }
            }
            val persisted = requireNotNull(repo.getConversationById(id))
            val task = requireNotNull(scheduler.current(persisted))
            assertEquals(expectedSuccess, task.state == "SUCCEEDED")
            val tool = persisted.currentMessages.flatMap { it.getTools() }.single { it.toolName == "math_verify" }
            val actual = Json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            assertEquals(status, actual.getValue("status").jsonPrimitive.content)
            assertEquals(input, Json.parseToJsonElement(tool.input))
            AgentTaskStore(k.get()).use { store ->
                val plan = store.plan(task.owner, task.id)
                assertEquals(expectedSuccess, plan.acceptanceVerified)
                assertEquals(1, plan.steps.size)
                if (status == "VERIFIED") assertEquals("VERIFIED", plan.steps.single().state)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool='math_verify'", arrayOf(task.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
            assertTrue(server.bodies.all { Json.parseToJsonElement(it).jsonObject["model"]?.jsonPrimitive?.content == model.modelId })
            assertEquals(if (status == "VERIFIED") 1 else 0, server.bodies.count { "HXAI_GOAL_VERIFY_V6" in it })
            val evidence = buildJsonObject {
                put("task_id", task.id); put("owner", task.owner); put("task_state", task.state)
                put("requested_input", input); put("actual_result", actual); put("expected_success", expectedSuccess)
                put("bad_semantic_hash", badSemanticHash); put("physical_tool_calls", 1); put("model", model.modelId)
            }
            val directory = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "shared-math-proof")
            check(directory.isDirectory || directory.mkdirs())
            java.io.FileOutputStream(java.io.File(directory, "$id.json")).use { it.write(evidence.toString().toByteArray()); it.fd.sync() }
        } finally {
            service.stopGeneration(id)
            repo.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
