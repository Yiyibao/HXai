package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.StudyMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ProjectReviewTeachingTest {
    @Test fun fourModesReturnActualModelAnswers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = settings.createLearnerProfile("QA审查四种教学")
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val ids = mutableListOf<Uuid>()
        val transcript = StringBuilder()
        val evidenceRun = QaEvidenceRun("ProjectReviewTeachingTest.fourModesReturnActualModelAnswers")
        val evidence = evidenceRun.file("teaching.md")
        var evidenceStatus = "FAILED"
        try {
            settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } }
            service.switchLearnerProfile(owner)
            for (mode in StudyMode.entries) {
                val id = Uuid.random(); ids += id
                service.addConversationReference(id); service.initializeConversation(id)
                service.saveConversation(id, Conversation(id=id, assistantId=original.assistantId, profileId=owner,
                    title="QA审查${mode.label}", studyMode=mode, messageNodes=emptyList()))
                val question = when(mode) {
                    StudyMode.CHAT -> "帮我列出三项出行准备事项，限100字。"
                    StudyMode.GUIDED -> "我想理解定积分，知道面积概念，请从一个卡点开始，限80字。"
                    StudyMode.HINT -> "我卡在积分∫₀¹x dx的原函数，请给一个提示，限80字。"
                    StudyMode.CHECK -> "检查我的步骤：∫₀¹x dx=[x]₀¹=1。指出第一处错误，限100字。"
                    StudyMode.EXPLAIN -> "完整讲解∫₀¹x dx，用LaTeX写出原函数及结果，限160字。"
                }
                service.sendMessage(id, listOf(UIMessagePart.Text(question)))
                val finished = withTimeout(150_000) {
                    service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED","FAILED","INTERRUPTED") }
                }
                val answer = finished.currentMessages.last()
                transcript.append("## ${mode.label} · ${answer.generationState}\n\n$question\n\n${answer.toText()}\n\n")
                evidence.writeText(transcript.toString())
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("review_mode",mode.name); putString("review_generation",answer.generationState) })
                withTimeout(15000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(50) }
                service.errors.value.lastOrNull { it.conversationId == id }?.let { failure -> instrumentation.sendStatus(2, android.os.Bundle().apply { putString("review_failure", me.rerere.rikkahub.feature.diagnostics.Diagnostics.failureFingerprint(failure.error).toString()) }) }
                assertEquals(mode.label, "COMPLETED", answer.generationState)
                assertTrue(answer.toText().isNotBlank())
                withTimeout(15_000) { while(service.getGenerationJobStateFlow(id).first()!=null) delay(50) }
            }
            evidenceStatus = "PASS"
        } finally {
            evidence.writeText(transcript.toString())
            evidenceRun.finish(evidenceStatus)
            for(id in ids) {
                service.stopGeneration(id)
                repository.getConversationById(id,owner)?.let { repository.deleteConversation(it) }
                service.removeConversationReference(id)
            }
            restoreMemoryProfileFixture(original,settings)
        }
    }
}
