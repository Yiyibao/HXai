package me.rerere.rikkahub.feature.memory

import androidx.room.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation

/** Separate from raw metadata: hiding an obsolete derivative never destroys its source. */
@Entity(tableName = "derived_metadata_evidence", primaryKeys = ["owner", "conversationId", "kind"])
data class DerivedMetadataEntity(val owner: String, val conversationId: String, val kind: String,
    val lifecycleRevision: Long, val valueHash: String, val sourceTokens: String, val manual: Boolean = false)

@Dao interface DerivedMetadataDAO {
    @Query("SELECT * FROM derived_metadata_evidence WHERE owner=:owner AND conversationId=:id AND kind=:kind")
    suspend fun get(owner: String, id: String, kind: String): DerivedMetadataEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: DerivedMetadataEntity)
}

internal class DerivedMetadataEvidence(private val db: AppDatabase, private val memories: LearningMemoryStore) {
    private val json = Json
    suspend fun record(conversation: Conversation, kind: String, value: String, visible: List<UIMessage>) {
        require(visible.isNotEmpty())
        val tokens = visible.filter { it.role == MessageRole.USER }.flatMap { message ->
            listOf(memories.fingerprint(conversation.profileId, "message:${message.id}"),
                memories.fingerprint(conversation.profileId, "${message.id}:${memories.fingerprint(conversation.profileId, "content:${message.toText()}")}"))
        }.toSet() + if (visible.any { it.isHistorySummary }) setOf("UNTRACKED_SUMMARY") else emptySet()
        db.derivedMetadataDao().put(DerivedMetadataEntity(conversation.profileId, conversation.id.toString(), kind,
            conversation.lifecycleRevision, MemoryValidation.hash(value), json.encodeToString(tokens)))
    }
    suspend fun recordManual(conversation: Conversation) {
        db.derivedMetadataDao().put(DerivedMetadataEntity(conversation.profileId, conversation.id.toString(), "title",
            conversation.lifecycleRevision, MemoryValidation.hash(conversation.title), "[]", manual = true))
    }
    suspend fun allowed(owner: String, id: String, lifecycle: Long, kind: String, value: String): Boolean {
        val state = memories.read(owner)
        val constrained = state.recoveryBlocked || state.legacySuppressedSources.isNotEmpty() || state.suppressedSources.isNotEmpty()
        val proof = db.derivedMetadataDao().get(owner, id, kind) ?: return !constrained
        if (proof.lifecycleRevision != lifecycle || proof.valueHash != MemoryValidation.hash(value)) return !constrained
        if (proof.manual) return kind == "title"
        if (state.recoveryBlocked || state.legacySuppressedSources.isNotEmpty()) return false
        val tokens = runCatching { json.decodeFromString<Set<String>>(proof.sourceTokens) }.getOrNull() ?: return false
        return tokens.isNotEmpty() && (!constrained || "UNTRACKED_SUMMARY" !in tokens) && tokens.none { it in state.suppressedSources }
    }
    suspend fun project(conversation: Conversation): Conversation = conversation.copy(
        title = conversation.title.takeIf { allowed(conversation.profileId, conversation.id.toString(),
            conversation.lifecycleRevision, "title", it) }.orEmpty(),
        chatSuggestions = conversation.chatSuggestions.takeIf { allowed(conversation.profileId, conversation.id.toString(),
            conversation.lifecycleRevision, "suggestion", json.encodeToString(it)) }.orEmpty())
}
