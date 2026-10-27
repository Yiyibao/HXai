package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.feature.studychat.ChatContextBudget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class DerivedMetadataLifecycleTest {
    @Test fun oldMetadataIsHiddenWithoutDestroyingRawHistory() = exercise("old")
    @Test fun newlyGeneratedMetadataOverFreshSourceRemainsUsableAfterDeletion() = exercise("fresh")
    @Test fun deletingBoundSourceInvalidatesItsTitleAndSuggestions() = exercise("bound")
    @Test fun untraceableSummaryCannotAuthenticateMetadataAfterDeletion() = exercise("summary")
    @Test fun explicitManualTitleRemainsUsableWhileSuggestionsAreProtected() = exercise("manual")
    @Test fun otherOwnerDeletionDoesNotInvalidateMetadata() = exercise("other-owner")
    @Test fun obsoleteLifecycleAndChangedOutputCannotBorrowProvenance() = exercise("changed")

    private fun exercise(mode: String): Unit = runBlocking {
        val k = GlobalContext.get(); val db = k.get<AppDatabase>(); val memories = k.get<LearningMemoryStore>()
        val owner = "qa-derived-${Uuid.random()}"
        val old = UIMessage.user("synthetic deleted source")
        val fresh = UIMessage.user("synthetic fresh source")
        val message = if (mode == "summary") ChatContextBudget.summaryMessage("untracked old summary") else fresh
        val original = Conversation(assistantId = Uuid.random(), profileId = owner,
            title = "synthetic generated title", chatSuggestions = listOf("synthetic generated suggestion"),
            messageNodes = listOf(message.toMessageNode()))
        val proof = DerivedMetadataEvidence(db, memories)
        try {
            if (mode != "old") {
                proof.record(original, "title", original.title, listOf(message))
                proof.record(original, "suggestion", kotlinx.serialization.json.Json.encodeToString(original.chatSuggestions), listOf(message))
            }
            if (mode == "manual") proof.recordManual(original)
            val targetOwner = if (mode == "other-owner") "$owner-other" else owner
            val deleted = if (mode == "bound") fresh else old
            memories.change(targetOwner) { it.copy(suppressedSources = setOf(memories.fingerprint(targetOwner, "message:${deleted.id}"))) to Unit }
            val candidate = if (mode == "changed") original.copy(lifecycleRevision = 1, title = "changed generated title") else original
            val projected = proof.project(candidate)
            val titleAllowed = mode in setOf("fresh", "manual", "other-owner")
            assertEquals(if (titleAllowed) candidate.title else "", projected.title)
            val suggestionsAllowed = mode in setOf("fresh", "manual", "other-owner") || mode == "changed"
            // Lifecycle changes invalidate both pieces of evidence, even when text is unchanged.
            assertEquals(if (suggestionsAllowed && mode != "changed") candidate.chatSuggestions else emptyList<String>(), projected.chatSuggestions)
            assertEquals("synthetic generated title", original.title)
            assertEquals(listOf(message), original.currentMessages)
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM derived_metadata_evidence WHERE owner = ?", arrayOf(owner))
            db.openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id IN (?, ?)", arrayOf(owner, "$owner-other"))
        }
    }
}
