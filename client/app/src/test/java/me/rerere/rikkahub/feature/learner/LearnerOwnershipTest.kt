package me.rerere.rikkahub.feature.learner

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.createForkConversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LearnerOwnershipTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConversationBelongsToDefaultLearner() {
        val conversation = json.decodeFromString<Conversation>(
            """{"id":"00000000-0000-0000-0000-000000000001","assistantId":"00000000-0000-0000-0000-000000000002","messageNodes":[]}"""
        )
        assertEquals(DEFAULT_LEARNER_ID, conversation.learnerProfileId)
    }

    @Test
    fun profileSwitchDoesNotMoveAnExistingConversation() {
        val first = LearnerProfile(nickname = "甲")
        val second = LearnerProfile(nickname = "乙")
        val existing = Conversation.ofId(Uuid.random(), learnerProfileId = first.id)
        val switched = Settings(learnerProfiles = listOf(first, second), activeLearnerId = second.id)
        val newConversation = Conversation.ofId(Uuid.random(), learnerProfileId = switched.activeLearnerId)
        assertEquals(first.id, existing.copy(title = "迟到的回答").learnerProfileId)
        assertEquals(first.id, createForkConversation(existing, emptyList()).learnerProfileId)
        assertEquals(second.id, newConversation.learnerProfileId)
        assertEquals(first.id, json.decodeFromString<Conversation>(json.encodeToString(existing)).learnerProfileId)
    }

    @Test
    fun profilesAndSelectionSurviveSettingsSerialization() {
        val second = LearnerProfile(nickname = "乙")
        val settings = Settings(learnerProfiles = listOf(DEFAULT_LEARNER, second), activeLearnerId = second.id)
        val restored = json.decodeFromString<Settings>(json.encodeToString(settings))
        assertEquals(settings.learnerProfiles, restored.learnerProfiles)
        assertEquals(second.id, restored.activeLearnerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNicknameIsRejected() {
        LearnerProfile(nickname = " ")
    }
}
