package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationHistoryToolLifecycleTest {
    @Test fun fullTextSearchRetainsHealthyMultiwordQueries() = exercise(false)
    @Test fun deletedTurnsAndUntrackedTitlesCannotLeaveThroughHistoryTools() = exercise(true)

    private fun exercise(delete: Boolean): Unit = runBlocking {
        val k = GlobalContext.get(); val repo = k.get<ConversationRepository>(); val memories = k.get<LearningMemoryStore>()
        val settings = withTimeout(20_000) { k.get<SettingsStore>().settingsFlow.first { !it.init } }
        val owner = settings.activeProfileId; val previous = memories.read(owner)
        val id = Uuid.random(); val assistant = Uuid.random()
        val old = UIMessage.user("OLD_HISTORY_TOOL_SECRET")
        val fresh = UIMessage.user("linear algebra and a useful counterexample")
        val conversation = Conversation(id = id, assistantId = assistant, profileId = owner,
            title = "OLD_GENERATED_TITLE_SECRET", messageNodes = listOf(old, UIMessage.assistant("OLD_DERIVED_ANSWER_SECRET"), fresh).map { it.toMessageNode() })
        try {
            memories.change(owner) { me.rerere.rikkahub.feature.memory.MemoryState(fingerprintVersion = 1) to Unit }
            repo.insertConversation(conversation)
            if (delete) memories.change(owner) { it.copy(suppressedSources = setOf(memories.fingerprint(owner, "message:${old.id}"))) to Unit }
            val tools = createConversationTools(repo, assistant, owner).associateBy { it.name }
            val query = if (delete) "OLD_HISTORY_TOOL_SECRET" else "linear counterexample"
            val output = tools.getValue("conversation_search").execute(buildJsonObject { put("query", query) })
            val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonArray
            if (delete) assertTrue(result.isEmpty()) else {
                assertEquals(1, result.size)
                assertTrue(result.single().jsonObject.getValue("snippet").jsonPrimitive.content.contains("counterexample"))
            }
            val recent = tools.getValue("recent_chats").execute(buildJsonObject {})
            val text = (recent.single() as UIMessagePart.Text).text
            assertEquals(!delete, "OLD_GENERATED_TITLE_SECRET" in text)
            assertEquals(conversation.messageNodes, repo.getConversationById(id)!!.messageNodes)
        } finally {
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            memories.change(owner) { previous to Unit }
        }
    }

    @Test fun oldHistoryToolReceiptsAreRedactedAfterSourceProtectionChanges(): Unit = runBlocking {
        val k = GlobalContext.get(); val memories = k.get<LearningMemoryStore>(); val db = k.get<AppDatabase>()
        val owner = "qa-history-receipt-${Uuid.random()}"
        val receipt = UIMessagePart.Tool("old", "conversation_search", "{\"query\":\"old\"}",
            output = listOf(UIMessagePart.Text("OLD_EXTERNAL_HISTORY_SECRET")), executionState = ToolExecutionState.COMPLETED)
        val messages = listOf(UIMessage.user("search history"), UIMessage.assistant("").copy(parts = listOf(receipt)), UIMessage.user("fresh task"))
        try {
            assertEquals(messages, memories.projectHistory(owner, messages))
            memories.change(owner) { it.copy(suppressedSources = setOf(memories.fingerprint(owner, "message:another-chat-source"))) to Unit }
            val projected = memories.projectHistory(owner, messages)
            val tool = projected[1].parts.single() as UIMessagePart.Tool
            assertFalse(tool.output.toString().contains("OLD_EXTERNAL_HISTORY_SECRET"))
            val current = receipt.copy(metadata = buildJsonObject { put("history_protection_key", memories.historyProtectionKey(owner)) })
            val fresh = messages.toMutableList().also { it[1] = it[1].copy(parts = listOf(current)) }
            assertEquals(fresh, memories.projectHistory(owner, fresh))
            assertEquals("OLD_EXTERNAL_HISTORY_SECRET", (receipt.output.single() as UIMessagePart.Text).text)
        } finally { db.openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(owner)) }
    }
}
