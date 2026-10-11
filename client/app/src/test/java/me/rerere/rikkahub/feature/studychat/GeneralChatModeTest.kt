package me.rerere.rikkahub.feature.studychat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GeneralChatModeTest {
    @Test fun newConversationUsesChatAndLegacyModesRoundTrip() {
        val conversation = Conversation.ofId(Uuid.random())
        assertEquals(StudyMode.CHAT, conversation.studyMode)
        for (mode in StudyMode.entries) {
            val encoded = Json.encodeToString(conversation.copy(studyMode = mode))
            assertEquals(mode, Json.decodeFromString<Conversation>(encoded).studyMode)
        }
    }

    @Test fun defaultPromptDirectlyServesGeneralRequests() {
        assertTrue(StudyTutorDefaults.systemPrompt.contains("个人通用 AI 助手"))
        assertFalse(StudyTutorDefaults.systemPrompt.contains("默认采用引导学习"))
        assertEquals(StudyTutorDefaults.systemPrompt, StudyTutorDefaults.effectivePrompt(StudyTutorDefaults.legacySystemPrompt, StudyMode.CHAT))
        assertEquals("custom", StudyTutorDefaults.effectivePrompt("custom", StudyMode.CHAT))
        assertEquals(StudyTutorDefaults.legacySystemPrompt, StudyTutorDefaults.effectivePrompt(StudyTutorDefaults.legacySystemPrompt, StudyMode.GUIDED))
    }
}
