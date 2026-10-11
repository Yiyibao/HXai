package me.rerere.rikkahub.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LearningLoopPersistenceTest {
    @Test fun wrongHintRetryReviewIsolationAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-s3-${Uuid.random()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            var store = LearningMemoryStore(db, MemoryFingerprints(context))
            var chat = Conversation(id = Uuid.random(), assistantId = Uuid.random(), profileId = "qa-s3-a", messageNodes = emptyList())
            val rounds = listOf(
                Triple("我求导漏乘内层导数了", MemoryType.DIFFICULTY, EvidenceType.SELF_REPORTED),
                Triple("提示后我重答：(x²+1)³ 的导数是6x(x²+1)²", MemoryType.PERFORMANCE, EvidenceType.WITH_HINT),
                Triple("我看懂了解析但还没有自己做", MemoryType.PERFORMANCE, EvidenceType.READ_ONLY),
                Triple("不看提示重答：(x³+1)² 的导数是6x²(x³+1)", MemoryType.PERFORMANCE, EvidenceType.INDEPENDENT_ATTEMPT),
                Triple("我要下次复习复合函数求导", MemoryType.REVIEW_SUGGESTION, EvidenceType.SELF_REPORTED),
            )
            rounds.forEachIndexed { index, (text, type, evidence) ->
                val user = UIMessage.user(text)
                chat = chat.copy(messageNodes = chat.messageNodes + listOf(MessageNode(messages = listOf(user)),
                    MessageNode(messages = listOf(UIMessage.assistant("合成测试提示/检查；不能作为本人事实").copy(generationState = "COMPLETED")))))
                store.reconcile(chat)
                assertEquals(1, store.enqueue(chat, true))
                val job = store.claim(chat.profileId, true, System.currentTimeMillis())!!
                val source = store.read(chat.profileId).sources.single { it.messageId == user.id.toString() }
                assertEquals(listOf(source.id), job.sourceIds)
                store.commit(chat.profileId, job, listOf(MemoryCandidate(type, "复合函数求导", "round-$index", text, evidence, listOf(source.id))), true)
            }
            assertTrue(store.read("qa-s3-b").items.isEmpty())
            assertTrue(store.relevant("qa-s3-a", "求导").none { it.type == MemoryType.PERFORMANCE })
            db.close(); db = open(); store = LearningMemoryStore(db, MemoryFingerprints(context))
            val state = store.read(chat.profileId)
            assertEquals(5, state.items.size)
            assertEquals(3, state.learningProgress().evidence.size)
            assertEquals(1, state.learningProgress().reviews.size)
            assertTrue(state.items.filter { it.type == MemoryType.PERFORMANCE }.all { it.status == "CANDIDATE" })
            assertEquals(0, store.enqueue(chat, true))
            val difficulty = state.learningProgress().difficulties.single()
            store.delete(chat.profileId, difficulty.id, difficulty.revision)
            assertTrue(store.read(chat.profileId).learningProgress().difficulties.isEmpty())
            assertEquals(0, store.enqueue(chat, true))
            store.reconcile(chat.copy(messageNodes = emptyList()))
            assertTrue(store.read(chat.profileId).learningProgress().evidence.isEmpty())
            assertTrue(store.read(chat.profileId).learningProgress().reviews.isEmpty())
            val request = UIMessage.user("请记住我看懂了链式法则")
            val explicitChat = chat.copy(id = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(request))))
            store.reconcile(explicitChat)
            val explicitId = store.saveExplicit(chat.profileId, explicitChat.id.toString(), request.id.toString(), request.toText(),
                MemoryType.PERFORMANCE, "复合函数求导", "阅读", "我看懂了链式法则")
            assertEquals("CANDIDATE", store.read(chat.profileId).items.single { it.id == explicitId }.status)
            assertTrue(store.relevant(chat.profileId, "链式法则").none { it.id == explicitId })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
