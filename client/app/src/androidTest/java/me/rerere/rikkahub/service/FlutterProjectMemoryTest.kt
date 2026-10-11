package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class FlutterProjectMemoryTest {
    @Test(timeout = 60_000) fun projectsPersistMovesAndDeletionKeepsConversations() = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val memories = koin.get<LearningMemoryStore>()
        val originalMemory = memories.read(owner)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), memories, koin.get(), koin.get(), pickFiles = { emptyList() })
        val epoch = facade.getBootstrap(owner, 1).contextEpoch
        val projects = mutableListOf<String>()
        val chats = mutableListOf<String>()
        try {
            val first = facade.saveProject(owner, null, "专项需求测试", epoch)
            assertTrue(first.success)
            val a = first.idResult!!; projects += a
            val b = facade.saveProject(owner, null, "第二项目", epoch).idResult!!; projects += b
            val chat = facade.createProjectChat(owner, a, epoch)
            assertTrue(chat.success)
            val id = chat.idResult!!; chats += id
            facade.getConversationDetails(owner, id) // Create an active in-memory session before moving.
            assertEquals(id, facade.getProjects(owner).single { it.id == a }.conversations.single().id)
            assertTrue(facade.saveProject(owner, a, "重命名项目", epoch).success)
            assertEquals("重命名项目", facade.getProjects(owner).single { it.id == a }.name)
            assertFalse(facade.moveConversationToProject("other-profile", id, b, epoch).success)
            assertFalse(facade.deleteProject(owner, a, epoch + 1).success)
            assertTrue(facade.moveConversationToProject(owner, id, b, epoch).success)
            val service = koin.get<ChatService>()
            service.saveConversation(Uuid.parse(id), service.getConversationFlow(Uuid.parse(id)).value.copy(title = "移动后继续聊天"))
            assertTrue(facade.getProjects(owner).single { it.id == a }.conversations.isEmpty())
            assertEquals(id, facade.getProjects(owner).single { it.id == b }.conversations.single().id)
            assertTrue(facade.moveConversationToProject(owner, id, null, epoch).success)
            assertTrue(facade.getProjects(owner).single { it.id == b }.conversations.isEmpty())
            assertTrue(facade.moveConversationToProject(owner, id, b, epoch).success)
            assertTrue(facade.deleteProject(owner, b, epoch).success)
            assertTrue(facade.getProjects(owner).none { it.id == b })
            assertNotNull(facade.getConversationDetails(owner, id))
            service.saveConversation(Uuid.parse(id), service.getConversationFlow(Uuid.parse(id)).value.copy(title = "删除分组后继续聊天"))
            assertNull(koin.get<ConversationRepository>().getConversationById(Uuid.parse(id))!!.folderId)
        } finally {
            projects.forEach { facade.deleteProject(owner, it, epoch) }
            chats.forEach { facade.deleteConversation(owner, it) }
            memories.change(owner) { originalMemory to Unit }
            facade.close()
        }
    }

    @Test(timeout = 60_000) fun manualMemoryPersistsRetrievesEditsAndDeletesWithoutChat() = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val store = koin.get<LearningMemoryStore>()
        val original = store.read(owner)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), store, koin.get(), koin.get(), pickFiles = { emptyList() })
        val epoch = facade.getBootstrap(owner, 1).contextEpoch
        val request = "test-${Uuid.random()}"
        try {
            assertFalse(facade.addMemory(owner, "偏好", "PREFERENCE", request, epoch + 1).success)
            assertFalse(facade.addMemory("other-profile", "偏好", "PREFERENCE", request, epoch).success)
            val result = facade.addMemory(owner, "回答先给结论", "PREFERENCE", request, epoch)
            assertTrue(result.success)
            val id = result.idResult!!
            assertEquals(id, facade.addMemory(owner, "回答先给结论", "PREFERENCE", request, epoch).idResult)
            val item = facade.getMemories(owner, "").single { it.id == id }
            assertEquals("手动添加", item.sourceTopic)
            assertNull(item.sourceConversationId)
            assertTrue(store.relevant(owner, "回答").any { it.id == id })
            assertTrue(facade.updateMemory(owner, id, "回答先给解释", item.revision, "edit").success)
            assertFalse(facade.updateMemory(owner, id, "旧草稿", item.revision, "stale").success)
            assertEquals("USER_MANUAL", store.read(owner).items.single { it.id == id }.origin)
            assertTrue(facade.deleteMemory(owner, id, "delete").success)
            assertFalse(facade.addMemory(owner, "回答先给结论", "PREFERENCE", request, epoch).success)
            assertTrue(facade.getMemories(owner, "").none { it.id == id })
            assertTrue(store.relevant(owner, "回答").none { it.id == id })
        } finally {
            store.change(owner) { original to Unit }
            facade.close()
        }
    }
}
