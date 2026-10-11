package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LearningLoopRealModelTest {
    @Test fun actualTeachingAndEvidenceExtraction() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        require(original.getCurrentChatModel() != null)
        val profileId = settings.createLearnerProfile("QA S3 教学闭环")
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val store = koin.get<LearningMemoryStore>()
        val runner = koin.get<MemoryExtractionRunner>()
        val id = Uuid.random()
        val transcript = StringBuilder()
        runner.chatStarted() // Keep automatic extraction from racing this fixture.
        try {
            withTimeout(20_000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == profileId } } }
            settings.setLearnerMemoryEnabled(profileId, true)
            service.switchLearnerProfile(profileId)
            service.addConversationReference(id); service.initializeConversation(id)
            suspend fun round(text: String) {
                val before = repository.getConversationById(id, profileId)?.currentMessages?.size ?: 0
                service.sendMessage(id, listOf(UIMessagePart.Text(text)))
                withTimeout(150_000) {
                    service.getConversationFlow(id).first { it.currentMessages.size > before &&
                        it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED", "FAILED") }
                    while (service.getGenerationJobStateFlow(id).first() != null) delay(100)
                }
                val chat = repository.getConversationById(id, profileId)!!
                assertEquals("COMPLETED", chat.currentMessages.last().generationState)
                val answer = chat.currentMessages.last().toText()
                assertTrue(answer.isNotBlank())
                transcript.append("\n用户：$text\n\n助手：$answer\n")
            }
            round("我是数学师范生。我求导漏乘内层导数了：f(x)=(x²+1)³，我写f′=3(x²+1)²。请检查第一处错误并只给一级提示，等待我重答。")
            round("根据刚才的提示，我重答：f′=6x(x²+1)²。请检查，并给一道同型练习，先不要给答案。")
            round("这次不看提示，我尝试求(x³+1)²的导数，结果是6x²(x³+1)，还没有核对。请检查我的步骤。")
            round("我看懂了解析，但还没做迁移题。我要下次复习复合函数求导。请给复习建议并区分提示后作答、独立尝试和仅阅读。")
            val chat = repository.getConversationById(id, profileId)!!
            store.enqueue(chat, true)
            while (true) {
                val job = store.claim(profileId, true, System.currentTimeMillis()) ?: break
                runner.process(profileId, job)
            }
            val state = store.read(profileId)
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s3-real-teaching.md").writeText(transcript.toString())
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s3-real-records.json").writeText(kotlinx.serialization.json.Json.encodeToString(MemoryState.serializer(), state))
            assertTrue(state.jobs.isNotEmpty() && state.jobs.all { it.state == "SUCCEEDED" })
            val progress = state.learningProgress()
            assertTrue(progress.difficulties.isNotEmpty())
            assertTrue(progress.reviews.isNotEmpty())
            val levels = progress.evidence.map { it.evidenceType }.toSet()
            assertTrue(EvidenceType.WITH_HINT in levels)
            assertTrue(EvidenceType.INDEPENDENT_ATTEMPT in levels)
            assertTrue(EvidenceType.READ_ONLY in levels)
            assertTrue(progress.evidence.all { it.status == "CANDIDATE" })
            assertTrue(state.sources.all { source -> chat.currentMessages.any { it.id.toString() == source.messageId && it.role == me.rerere.ai.core.MessageRole.USER } })
            assertTrue(store.read(original.activeProfileId).items.none { it.id in state.items.map { item -> item.id } })
        } finally {
            service.stopGeneration(id)
            repository.getConversationById(id, profileId)?.let { repository.deleteConversation(it) }
            service.removeConversationReference(id)
            koin.get<AppDatabase>().openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(profileId))
            restoreMemoryProfileFixture(original, settings)
            runner.chatFinished()
        }
    }
}
