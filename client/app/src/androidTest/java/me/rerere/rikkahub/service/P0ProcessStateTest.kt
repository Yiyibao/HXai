package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class P0ProcessStateTest {
    @Test fun editedBranchSearchAndChartSurviveActualProcessRestart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments(); val id = Uuid.parse(args.getString("fixtureId")!!)
        val koin = GlobalContext.get(); val repository = koin.get<ConversationRepository>(); val store = koin.get<SettingsStore>()
        val settings = store.settingsFlow.first { !it.init }; val service = koin.get<ChatService>()
        val facade = StudyHostFacade(InstrumentationRegistry.getInstrumentation().targetContext, store, repository, service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        val spec = """{"version":3,"title":"Restart chart","components":[{"id":"chart","type":"bar_chart","selectable":true,"points":[[1,2],[2,3]]}]}"""
        val revision = InteractiveSurface.hash(spec)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("qa_process_pid", android.os.Process.myPid().toString()) })
        try {
            if (args.getString("phase") == "seed") {
                assertNull(repository.getConversationById(id))
                val output = me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(spec)) })
                val input = kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(spec)) }.toString()
                val response = UIMessage.assistant("").copy(generationState = "COMPLETED", parts = listOf(UIMessagePart.Tool("restart", "render_interactive_ui", input, output, executionState = ToolExecutionState.COMPLETED)))
                val conversation = Conversation(id = id, assistantId = settings.assistantId, profileId = settings.activeProfileId, title = "P0 restart", messageNodes = listOf(UIMessage.user("P0_ORIGINAL_$id").toMessageNode(), response.toMessageNode()))
                repository.insertConversation(conversation)
                service.addConversationReference(id); service.initializeConversation(id)
                facade.surfaceAction(settings.activeProfileId, id.toString(), response.id.toString(), "restart", revision, "{\"chart\":1}", null, "seed", 1)
                facade.editMessage(settings.activeProfileId, id.toString(), conversation.currentMessages.first().id.toString(), "P0_EDITED_$id", "restart-edit", 1)
            } else {
                assertEquals("verify", args.getString("phase"))
                val saved = repository.getConversationById(id)!!
                assertEquals("P0_EDITED_$id", saved.currentMessages.first().toText())
                assertEquals(2, saved.messageNodes.first().messages.size)
                assertEquals("P0_ORIGINAL_$id", saved.messageNodes.first().messages.first().toText())
                assertEquals(id.toString(), facade.searchConversations(settings.activeProfileId, "P0_EDITED_$id", 50, 0, false).single().id)
                val archiveId = facade.searchConversations(settings.activeProfileId, "P0_ORIGINAL_$id", 50, 0, false).single { it.id != id.toString() }.id
                val archive = repository.getConversationById(Uuid.parse(archiveId))!!
                val archivedTool = archive.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()
                val state = archivedTool.metadata?.get(InteractiveSurface.STATE_KEY) as kotlinx.serialization.json.JsonObject
                assertEquals("{\"chart\":1}", state["values"].toString())
                repository.deleteConversation(archive)
                repository.deleteConversation(saved)
            }
        } finally { facade.close(); service.removeConversationReference(id) }
        Unit
    }
}
