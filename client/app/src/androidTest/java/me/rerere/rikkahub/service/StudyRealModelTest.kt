package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.StudyMode
import me.rerere.rikkahub.feature.studychat.StudyTutorDefaults
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Explicitly opt-in; real requests use the device's existing encrypted connection configuration. */
@RunWith(AndroidJUnit4::class)
class StudyRealModelTest {
    @Test fun coldStartEncryptedConfigurationAndFourTeachingModes() = runScenarios(true)

    @Test fun realApprovalDecisionsAndBoundaries() = runScenarios(false)

    private fun runScenarios(includeTeachingModes: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val model = original.getCurrentChatModel() ?: error("Configure a real chat model first")
        val assistant = Assistant(name = "QA S1 teaching", chatModelId = model.id,
            systemPrompt = StudyTutorDefaults.systemPrompt,
            localTools = listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AskUser))
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val owner = store.createLearnerProfile("QA冷启动与审批")
        val ids = mutableListOf<Uuid>()
        val evidence = StringBuilder()
        val evidenceRun = QaEvidenceRun("StudyRealModelTest.${if (includeTeachingModes) "coldStartEncryptedConfigurationAndFourTeachingModes" else "realApprovalDecisionsAndBoundaries"}")
        val evidenceFile = evidenceRun.file("transcript.md")
        var evidenceStatus = "FAILED"
        fun stage(name: String) {
            evidenceFile.writeText(evidence.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("review_stage", name); putString("review_evidence", evidenceFile.name) })
        }
        val scenario = androidx.test.core.app.ActivityScenario.launch(me.rerere.rikkahub.RouteActivity::class.java)
        try {
            store.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } }
            service.switchLearnerProfile(owner)
            store.update(original.copy(assistants = original.assistants + assistant,
                assistantId = assistant.id, enableSuggestion = false))
            store.settingsFlow.first { it.assistantId == assistant.id }
            if (includeTeachingModes) StudyMode.entries.forEach { mode ->
                val id = Uuid.random()
                ids += id
                service.addConversationReference(id)
                service.initializeConversation(id)
                service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, profileId = owner,
                    title = "QA S1 ${mode.label}", messageNodes = emptyList(), studyMode = mode))
                val question = when (mode) {
                    StudyMode.CHAT -> "请列出三项出行准备事项，限100字。"
                    StudyMode.GUIDED -> "我想理解定积分，已知道面积概念。请围绕一个卡点开始，限80字。"
                    StudyMode.HINT -> "我卡在积分∫₀¹x dx 的原函数，请给一个提示，限80字。"
                    StudyMode.CHECK -> "检查我的步骤：∫₀¹x dx = [x]₀¹ = 1。指出第一处错误，限100字。"
                    StudyMode.EXPLAIN -> "完整讲解∫₀¹x dx，使用LaTeX，并用一个两列表格写出原函数和结果，限160字。"
                }
                service.sendMessage(id, listOf(UIMessagePart.Text(question)))
                withTimeout(15_000) {
                    service.getConversationFlow(id).first { it.currentMessages.isNotEmpty() }
                }
                val completed = withTimeout(120_000) {
                    service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED", "FAILED", "INTERRUPTED") }
                    while (service.getGenerationJobStateFlow(id).first() != null) delay(50)
                    repository.getConversationById(id)!!
                }
                assertEquals("COMPLETED", completed.currentMessages.last().generationState)
                val answer = completed.currentMessages.last().toText()
                assertTrue("Model returned no text", answer.isNotBlank())
                assertEquals(mode, completed.studyMode)
                evidence.appendLine("## ${mode.label}\n\n$question\n\n$answer\n")
                stage("mode-${mode.name}-complete")
            }
            for (approved in listOf(false, true)) {
                val approvalId = Uuid.random()
                ids += approvalId
                service.addConversationReference(approvalId)
                service.initializeConversation(approvalId)
                service.saveConversation(approvalId, Conversation(id = approvalId, assistantId = assistant.id,
                    profileId = owner, title = "QA真实审批", messageNodes = emptyList()))
                assertEquals(assistant.id, service.getConversationFlow(approvalId).first().assistantId)
                val decision = if (approved) "approve" else "deny"
                stage("approval-$decision-request")
                service.sendMessage(approvalId, listOf(UIMessagePart.Text(
                    "在继续讲解前，请用 ask_user 询问我是否愿意学习定积分的一个例题，提供“继续”选项。收到我的选择后，简短确认即可，不需要继续提问。"
                )))
                val waiting = withTimeout(120_000) {
                    val response = service.getConversationFlow(approvalId).first {
                        it.currentMessages.any { message -> message.getTools().any { tool -> tool.isPending } } ||
                            it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED", "FAILED", "INTERRUPTED")
                    }
                    while (service.getGenerationJobStateFlow(approvalId).first() != null) delay(50)
                    evidence.appendLine("## 审批请求的真实回复\n\n${response.currentMessages.lastOrNull()?.toText().orEmpty()}\n")
                    stage("approval-response-${response.currentMessages.lastOrNull()?.generationState}")
                    assertTrue("Actual model response must include a pending ask_user tool", response.currentMessages.any {
                        message -> message.getTools().any { tool -> tool.isPending }
                    })
                    repository.getConversationById(approvalId)!!
                }
                assertEquals("WAITING_TOOL", waiting.currentMessages.last().generationState)
                val pending = waiting.currentMessages.flatMap { it.getTools() }.single { it.isPending }
                val callId = pending.toolCallId
                stage("approval-$decision-pending")
                val answer = if (approved) buildJsonObject {
                    pending.inputAsJson().jsonObject["questions"]!!.jsonArray.forEach { question ->
                        put(question.jsonObject["id"]!!.jsonPrimitive.content, "继续")
                    }
                }.toString() else null
                service.handleToolApproval(approvalId, callId, approved, "测试拒绝，不继续讲解", answer)
                service.handleToolApproval(approvalId, callId, approved, "重复提交", answer)
                service.handleToolApproval(approvalId, callId, !approved, "冲突的重复提交")
                val completedApproval = withTimeout(120_000) {
                    service.getConversationFlow(approvalId).first { it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED", "FAILED", "INTERRUPTED") }
                    while (service.getGenerationJobStateFlow(approvalId).first() != null) delay(50)
                    repository.getConversationById(approvalId)!!
                }
                assertEquals("COMPLETED", completedApproval.currentMessages.last().generationState)
                val executed = completedApproval.currentMessages.flatMap { it.getTools() }.single { it.toolCallId == callId }
                assertTrue(executed.isExecuted)
                assertEquals(if (approved) me.rerere.ai.ui.ToolApprovalState.Answered(answer!!) else me.rerere.ai.ui.ToolApprovalState.Denied("测试拒绝，不继续讲解"), executed.approvalState)
                service.handleToolApproval(approvalId, callId, !approved) // late decision must not change a completed turn
                withTimeout(20_000) { while (service.getGenerationJobStateFlow(approvalId).first() != null) delay(50) }
                assertEquals(completedApproval.currentMessages, repository.getConversationById(approvalId)!!.currentMessages)
                assertNull(service.getGenerationJobStateFlow(approvalId).first())
                assertTrue(completedApproval.currentMessages.last().toText().isNotBlank())
                evidence.appendLine("## 真实审批 $decision、重复／冲突提交与迟到审批\n\n通过；完成后迟到审批没有改变消息。\n")
                stage("approval-$decision-complete")
            }
            evidenceStatus = "PASS"
        } finally {
            evidenceFile.writeText(evidence.toString())
            evidenceRun.finish(evidenceStatus)
            service.errors.value.mapNotNull { it.error as? me.rerere.ai.util.HttpException }.forEach { error ->
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("qa_http_status", error.statusCode?.toString())
                    putString("qa_rejection_reason", error.rejectionReason?.name ?: "UNKNOWN")
                })
            }
            ids.forEach { id ->
                service.stopGeneration(id)
                repository.getConversationById(id)?.let { repository.deleteConversation(it) }
                service.removeConversationReference(id)
            }
            restoreMemoryProfileFixture(original, store)
            service.clearAllErrors()
            scenario.close()
        }
    }
}
