package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Original Chat, Room and TaskStore. The loopback model is not a mathematical authority. */
@RunWith(AndroidJUnit4::class)
class HostAcceptanceRuntimeTest {
    @Test fun textbookRecurrenceCompletesOnlyWhenTheReplyQuotesTheClosedForm() = runBlocking {
        val disposition = AtomicInteger()
        val task = chat("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。", "由归纳可得 a_n=n^2。", disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"由归纳可得 a_n=n^2。","reason":"model prose is not a proof"}""")
            } else LocalModelServer.completion("由归纳可得 a_n=n^2。")
        }
        assertEquals("SUCCEEDED", task.state)
        assertEquals(0, disposition.get())
        assertEquals("mathematical_induction_exact_polynomial_base_and_step", task.proofKind)
        assertEquals("VERIFIED", task.mathematicsStatus)
        assertEquals(InductionGoal.KIND, task.acceptanceKind)
        assertEquals(0, task.mathCalls)
    }
    @Test fun conicIdentityCompletesOnlyWhenTheReplyDeniesGeometry() = runBlocking {
        val goal = "x,y为实数。验证(x+y)^2+(x-y)^2=2x^2+2y^2，并说明能否直接视为已证明某圆锥曲线的几何结论。"
        val reply = "展开后(x+y)^2+(x-y)^2等于2x^2+2y^2。不能直接视为已经证明了某个圆锥曲线的几何结论。"
        val disposition = AtomicInteger()
        val task = chat(goal, reply, disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"model prose","reason":"not a geometric proof"}""")
            } else LocalModelServer.completion(reply)
        }
        assertEquals("SUCCEEDED", task.state)
        assertEquals(0, disposition.get())
        assertEquals("exact_multivariate_rational_coefficients", task.proofKind)
        assertEquals(IdentityCaveatGoal.KIND, task.acceptanceKind)
    }

    @Test fun conicIdentityThatClaimsGeometryStaysUnverified() = runBlocking {
        val goal = "x,y为实数。验证(x+y)^2+(x-y)^2=2x^2+2y^2，并说明能否直接视为已证明某圆锥曲线的几何结论。"
        val reply = "展开后(x+y)^2+(x-y)^2等于2x^2+2y^2。因此已经证明了该圆锥曲线的几何结论。"
        val disposition = AtomicInteger()
        val task = chat(goal, reply, disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"model prose","reason":"false geometry"}""")
            } else LocalModelServer.completion(reply)
        }
        assertEquals("COMPLETED_UNVERIFIED", task.state)
        assertEquals(0, disposition.get())
    }

    @Test fun recognizedRecurrenceWithoutClosedFormStaysUnverified() = runBlocking {
        val disposition = AtomicInteger()
        val task = chat("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。", "这个数列可以用归纳法处理。", disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"这个数列可以用归纳法处理。","reason":"model prose is not a proof"}""")
            } else LocalModelServer.completion("这个数列可以用归纳法处理。")
        }
        assertEquals("COMPLETED_UNVERIFIED", task.state)
        assertEquals(0, disposition.get())
        assertNull(task.proofKind)
    }
    @Test fun rationalRecurrenceCompletesOnlyWhenTheReplyQuotesTheClosedForm() = runBlocking {
        val goal = "数列满足a_0=0，a_(n+1)=((n+1)/(n+2))*a_n+(n+1)，n为非负整数。求通项并证明。允许使用归纳或变换后求和，不得因方法不同误判。"
        val reply = "对一切非负整数，通项为 a_n=\\frac{n(n+2)}{3}。"
        val disposition = AtomicInteger()
        val task = chat(goal, reply, disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"通项为 a_n=n(n+2)/3。","reason":"model prose is not a proof"}""")
            } else LocalModelServer.completion(reply)
        }
        assertEquals("SUCCEEDED", task.state)
        assertEquals(0, disposition.get())
        assertEquals("rational_linear_recurrence_exact_base_and_step", task.proofKind)
        assertEquals("VERIFIED", task.mathematicsStatus)
        assertEquals(RationalRecurrenceGoal.KIND, task.acceptanceKind)
        assertEquals(0, task.mathCalls)
    }

    @Test fun rationalRecurrenceWrongClosedFormStaysUnverified() = runBlocking {
        val goal = "数列满足a_0=0，a_(n+1)=((n+1)/(n+2))*a_n+(n+1)，n为非负整数。求通项并证明。"
        val disposition = AtomicInteger()
        val task = chat(goal, "通项为 a_n=n。", disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"通项为 a_n=n。","reason":"model prose is not a proof"}""")
            } else LocalModelServer.completion("通项为 a_n=n。")
        }
        assertEquals("COMPLETED_UNVERIFIED", task.state)
        assertEquals(0, disposition.get())
        assertNull(task.proofKind)
    }
    @Test fun cubicParameterDiscussionCompletesWhenTheReplyStatesAllThreeCells() = runBlocking {
        val goal = "对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界，不能只给导数或代入若干参数值。"
        val reply = "a<0时在R上严格递增。a=0时x=0孤立驻点仍严格递增。a>0时临界点满足x^2=a，|x|>sqrt(a)严格递增，|x|<sqrt(a)严格递减。"
        val disposition = AtomicInteger()
        val task = chat(goal, reply, disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"model prose","reason":"not a sign chart"}""")
            } else LocalModelServer.completion(reply)
        }
        assertEquals("SUCCEEDED", task.state)
        assertEquals(0, disposition.get())
        assertEquals("cubic_parameter_derivative_sign_chart", task.proofKind)
        assertEquals(CubicParameterGoal.KIND, task.acceptanceKind)
        assertEquals(0, task.mathCalls)
    }

    @Test fun cubicParameterDiscussionThatOmitsTheZeroCellStaysUnverified() = runBlocking {
        val goal = "对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界，不能只给导数或代入若干参数值。"
        val reply = "a<0时严格递增。a>0时外侧严格递增，中间严格递减。"
        val disposition = AtomicInteger()
        val task = chat(goal, reply, disposition) { body ->
            if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                disposition.incrementAndGet()
                LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"model prose","reason":"missing zero"}""")
            } else LocalModelServer.completion(reply)
        }
        assertEquals("COMPLETED_UNVERIFIED", task.state)
        assertEquals(0, disposition.get())
        assertNull(task.proofKind)
    }



    @Test fun falseIdentityCheckCompletesWithoutPromotingTheClaim() = runBlocking {
        val input = buildJsonObject {
            put("operation", "identity")
            put("left", "(x+1)^2")
            put("right", "x^2+1")
        }
        val calls = AtomicInteger()
        val task = chat("核验(x+1)^2是否等于x^2+1。", "不是恒等式。x=1时左边是4，右边是2。", AtomicInteger()) { body ->
            val parsed = Json.parseToJsonElement(body).jsonObject
            val first = parsed.getValue("messages").jsonArray.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps", buildJsonArray { add(buildJsonObject {
                        put("id", "math"); put("tool", "math_verify"); put("purpose", "Recompute the exact identity claim")
                    }) })
                    put("acceptance", buildJsonArray { add(buildJsonObject { put("stepId", "math"); put("kind", "tool_verified") }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first || "HXAI_RESPONSE_DISPOSITION_V1" in first ->
                    LocalModelServer.completion("""{"accepted":true,"outcome":"delivered","goalRevision":1,"evidenceHash":"wrong","responseQuote":"不是恒等式。x=1时左边是4，右边是2。","reason":"model cannot promote a false identity"}""")
                calls.incrementAndGet() == 1 -> buildJsonObject {
                    put("id", "synthetic-math"); put("model", "qa-host-acceptance")
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
                else -> LocalModelServer.completion("不是恒等式。x=1时左边是4，右边是2。")
            }
        }
        assertEquals("SUCCEEDED", task.state)
        assertEquals(1, task.mathCalls)
        assertEquals("RETURNED", task.mathState)
        assertEquals("DISPROVED", task.mathStatus)
        assertEquals(false, task.claimPromoted)
        assertEquals(RefutationDelivery.KIND, task.acceptanceKind)
    }
    @Test fun cubicIntervalNotationCompletesAndSwappedIntervalsStayUnverified() = runBlocking {
        val goal = "对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界，不能只给导数或代入若干参数值。"
        val reply = "a<0时在R上严格递增。a=0时x=0为驻点仍严格递增。a>0时临界点x=±\\sqrt a。严格递增：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)。严格递减：[-\\sqrt a,\\sqrt a]。"
        val swapped = "a<0时在R上严格递增。a=0时仍严格递增。a>0时临界点x=±\\sqrt a。严格递减：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)。严格递增：[-\\sqrt a,\\sqrt a]。"
        val accepted = chat(goal, reply, AtomicInteger()) { LocalModelServer.completion(reply) }
        assertEquals("SUCCEEDED", accepted.state)
        assertEquals(CubicParameterGoal.KIND, accepted.acceptanceKind)
        val rejected = chat(goal, swapped, AtomicInteger()) { LocalModelServer.completion(swapped) }
        assertEquals("COMPLETED_UNVERIFIED", rejected.state)
        assertNull(rejected.proofKind)
    }
    @Test fun conicIntersectionAndUniformConvergenceStayUnverified() = runBlocking {
        val conic = "椭圆x^2/4+y^2=1与直线y=kx+1，k为任意实数。完整给出交点，并讨论何时相切、何时有两个不同交点。保留参数退化与几何依据。"
        val uniform = "在[-1,1]上令f_n(x)=sqrt(x^2+1/n)，n>=1。证明其一致收敛，求极限函数，并判断是否能由每个f_n可微推断极限在0可微。解释导数极限与极限求导的区别。"
        for ((goal, reply) in listOf(conic to "交点已经完整证明。", uniform to "一致收敛到绝对值，且可以逐项求导。")) {
            val disposition = AtomicInteger()
            val task = chat(goal, reply, disposition) { body ->
                if ("HXAI_RESPONSE_DISPOSITION_V1" in body || "HXAI_GOAL_VERIFY_V6" in body) {
                    disposition.incrementAndGet()
                    LocalModelServer.completion("""{"outcome":"delivered","accepted":true,"goalRevision":1,"evidenceHash":"wrong","responseQuote":"$reply","reason":"model prose is not a certificate"}""")
                } else LocalModelServer.completion(reply)
            }
            assertEquals("COMPLETED_UNVERIFIED", task.state)
            assertEquals(0, disposition.get())
            assertNull(task.proofKind)
            assertNull(task.acceptanceKind)
            assertEquals(0, task.mathCalls)
        }
    }

    private data class Observed(val state: String, val proofKind: String?, val mathematicsStatus: String?,
        val claimPromoted: Boolean?, val mathCalls: Int, val mathState: String?, val mathStatus: String?, val acceptanceKind: String?)

    private suspend fun chat(goal: String, expectedReply: String, disposition: AtomicInteger,
        responder: (String) -> String): Observed {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val server = LocalModelServer(response = responder)
        val model = Model(modelId = "qa-host-acceptance", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(name = "QA host acceptance", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA host acceptance", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF,
            streamOutput = false, localTools = emptyList())
        val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(k.get()); val id = Uuid.random()
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId,
                title = "QA host acceptance", newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text(goal)), Uuid.random())
            withTimeout(50_000) { while (true) {
                val current = service.getConversationFlow(id).first(); val running = scheduler.current(current)
                if (running != null && running.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) break
                delay(20)
            } }
            val persisted = requireNotNull(repo.getConversationById(id))
            val task = requireNotNull(scheduler.current(persisted))
            assertEquals(expectedReply, persisted.currentMessages.last().toText().trim())
            return AgentTaskStore(k.get()).use { store ->
                val plan = store.plan(task.owner, task.id)
                val evidenceText = store.readableDatabase.rawQuery(
                    "SELECT evidence_json FROM plans WHERE task_id=?", arrayOf(task.id)).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0)
                }
                val evidence = evidenceText?.let { Json.parseToJsonElement(it).jsonObject }
                val math = store.readableDatabase.rawQuery(
                    "SELECT state,result_json FROM calls WHERE task_id=? AND tool='math_verify'", arrayOf(task.id)).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getString(1)
                }
                val steps = store.readableDatabase.rawQuery(
                    "SELECT step_id,tool,state FROM plan_steps WHERE task_id=?", arrayOf(task.id)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) + ":" + cursor.getString(1) + ":" + cursor.getString(2)) }
                }
                if (math != null && evidence == null) error("state=${task.state} steps=$steps math=${math.first} result=${math.second?.take(180)}")
                val mathStatus = math?.second?.let { raw ->
                    val text = Json.decodeFromString<List<UIMessagePart>>(raw).filterIsInstance<UIMessagePart.Text>().single().text
                    Json.parseToJsonElement(text).jsonObject["status"]?.jsonPrimitive?.content
                }
                Observed(task.state, evidence?.get("proof_kind")?.jsonPrimitive?.content,
                    evidence?.get("mathematics_status")?.jsonPrimitive?.content,
                    evidence?.get("claim_promoted")?.jsonPrimitive?.booleanOrNull,
                    if (math == null) 0 else 1, math?.first, mathStatus,
                    plan.plan?.acceptance?.singleOrNull()?.kind)
            }
        } finally {
            service.stopGeneration(id)
            repo.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
