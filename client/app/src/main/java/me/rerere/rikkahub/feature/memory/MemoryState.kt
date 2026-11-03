package me.rerere.rikkahub.feature.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

enum class MemoryType { BACKGROUND, PREFERENCE, PROGRESS, DIFFICULTY, PERFORMANCE, REVIEW_SUGGESTION }
enum class EvidenceType { SELF_REPORTED, READ_ONLY, AFTER_EXPLANATION, WITH_HINT, INDEPENDENT_ATTEMPT, UNVERIFIED }

@Serializable data class MemorySource(
    val id: String, val conversationId: String, val nodeId: String, val messageId: String,
    val hash: String, val revision: Int, val valid: Boolean = true,
    val spanStart: Int = 0, val spanEnd: Int = 0,
    val explicitRequest: Boolean = false,
    // Upstream stores local time without an offset; do not invent UTC precision.
    val recordedAtLocal: String = "",
)
@Serializable data class MemoryRevision(val revision: Int, val content: String, val changedAt: Long,
                                       val status: String = "ACTIVE", val origin: String = "AUTO_EXTRACTED", val sourceIds: List<String> = emptyList())
@Serializable data class LearningMemory(
    val id: String = UUID.randomUUID().toString(), val type: MemoryType,
    val topicKey: String, val factKey: String, val content: String,
    val evidenceType: EvidenceType, val sourceIds: List<String>,
    val origin: String = "AUTO_EXTRACTED", val status: String = "CANDIDATE",
    val revision: Int = 1, val createdAt: Long, val updatedAt: Long,
    val history: List<MemoryRevision> = emptyList(),
)
fun LearningMemory.snapshot() = MemoryRevision(revision, content, updatedAt, status, origin, sourceIds)
@Serializable data class ExtractionJob(
    val id: String = UUID.randomUUID().toString(), val conversationId: String,
    val sourceIds: List<String>, val rangeHash: String, val epoch: Long,
    val expectedRevisions: Map<String, Int>, val state: String = "PENDING",
    val attempts: Int = 0, val nextAttemptAt: Long = 0, val leaseUntil: Long = 0,
    val savedCount: Int = 0, val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
)
@Serializable data class MemoryState(
    val schemaVersion: Int = 1, val epoch: Long = 0,
    val fingerprintVersion: Int = 0,
    val recoveryBlocked: Boolean = false,
    val requestDay: Long = -1,
    val dailyRequests: Int = 0,
    val processedSources: Set<String> = emptySet(),
    val sources: List<MemorySource> = emptyList(), val items: List<LearningMemory> = emptyList(),
    val jobs: List<ExtractionJob> = emptyList(),
    // HMAC fingerprints only; no deleted content is retained.
    val suppressedSources: Set<String> = emptySet(), val suppressedFacts: Set<String> = emptySet(),
    val suppressedTopics: Set<String> = emptySet(),
    val usedMemories: Map<String, List<String>> = emptyMap(),
)

@Serializable data class MemoryCandidate(
    val type: MemoryType, @SerialName("topic_key") val topicKey: String,
    @SerialName("fact_key") val factKey: String, val content: String,
    @SerialName("evidence_type") val evidenceType: EvidenceType,
    @SerialName("source_ids") val sourceIds: List<String>,
)
@Serializable private data class CandidateBatch(@SerialName("schema_version") val version: Int, val candidates: List<MemoryCandidate>)

object MemoryValidation {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    fun sensitive(content: String) = Regex("(?i)(sk-[a-z0-9_-]{10,}|bearer\\s+\\S+|api[_ -]?key\\s*[:=]|(密码|口令|密钥)\\s*(是|为|[:：=])|authorization\\s*[:=])").containsMatchIn(content)
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun searchText(value: String): String {
        var text = value.lowercase()
        listOf("线代" to "线性代数", "求导" to "导数", "微分" to "导数", "积分换元" to "积分变量替换",
            "换元积分" to "积分变量替换", "换元法" to "变量替换", "特征根" to "特征值", "反例优先" to "先看反例").forEach { (alias, canonical) -> text = text.replace(alias, canonical) }
        return text
    }

    /** Scan all object keys before decoding: kotlinx JSON otherwise accepts duplicate keys. */
    private fun rejectDuplicateKeys(text: String) {
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> stack.add(mutableSetOf())
                '[' -> stack.add(null)
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    require(i < text.length)
                    val end = i + 1
                    var next = end
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val key = json.decodeFromString<String>(text.substring(start, end))
                        require(stack.lastOrNull()?.add(key) == true) { "Duplicate JSON key" }
                    }
                }
            }
            i++
        }
        require(stack.isEmpty())
    }
    fun parse(text: String, allowed: Set<String>): List<MemoryCandidate> {
        require(text.length <= 48_000)
        rejectDuplicateKeys(text)
        val batch = json.decodeFromString<CandidateBatch>(text)
        require(batch.version == 1)
        validate(batch.candidates, allowed)
        return batch.candidates
    }
    fun validate(candidates: List<MemoryCandidate>, allowed: Set<String>) {
        require(candidates.size <= 20)
        val facts = mutableSetOf<String>()
        candidates.forEach {
            require(it.content.isNotBlank() && it.content.codePointCount(0, it.content.length) <= 2000)
            require(it.topicKey.isNotBlank() && it.topicKey.length <= 100 && it.factKey.isNotBlank() && it.factKey.length <= 100)
            require(facts.add("${it.type}:${it.topicKey}:${it.factKey}"))
            require(it.sourceIds.isNotEmpty() && it.sourceIds.distinct().size == it.sourceIds.size && it.sourceIds.all { id -> id in allowed })
            require(!sensitive(it.content))
        }
    }
}

/** Pure transitions shared by the transactional store and adversarial tests. */
object MemoryReducer {
    fun addManual(state: MemoryState, id: String, type: MemoryType, content: String, now: Long): MemoryState {
        require(content.isNotBlank() && content.length <= 2000 && !MemoryValidation.sensitive(content)) { "记忆应为1–2000字，且不能包含密钥或密码" }
        val existing = state.items.firstOrNull { it.id == id }
        if (existing != null) {
            require(existing.origin == "USER_MANUAL" && existing.type == type && existing.content == content.trim()) { "请求已用于其他记忆" }
            return state
        }
        val item = LearningMemory(id = id, type = type, topicKey = "手动添加", factKey = id,
            content = content.trim(), evidenceType = EvidenceType.SELF_REPORTED,
            sourceIds = emptyList(), origin = "USER_MANUAL", status = "ACTIVE", createdAt = now, updatedAt = now)
        return state.copy(epoch = state.epoch + 1, items = state.items + item)
    }

    fun edit(state: MemoryState, id: String, revision: Int, content: String, now: Long): MemoryState {
        require(content.isNotBlank() && content.length <= 2000 && !MemoryValidation.sensitive(content))
        val old = state.items.single { it.id == id }
        require(old.revision == revision) { "记录已改变，请刷新后重试" }
        require(old.origin == "USER_MANUAL" || old.sourceIds.any { source -> state.sources.any { it.id == source && it.valid } }) { "来源已失效" }
        val next = old.copy(content = content.trim(), revision = revision + 1, origin = if (old.origin == "USER_MANUAL") "USER_MANUAL" else "USER_EDITED",
            status = "ACTIVE", updatedAt = now, history = old.history + old.snapshot())
        return state.copy(epoch = state.epoch + 1, items = state.items.map { if (it.id == id) next else it })
    }

    fun reconcile(state: MemoryState, conversationId: String, selected: List<MemorySource>): MemoryState {
        val current = selected.associateBy { it.messageId }
        val sources = state.sources.map { old ->
            if (old.conversationId != conversationId) old else old.copy(valid = current[old.messageId]?.hash == old.hash && current[old.messageId]?.nodeId == old.nodeId)
        }.toMutableList()
        selected.forEach { fresh -> if (sources.none { it.id == fresh.id }) sources.add(fresh) }
        val valid = sources.filter { it.valid }.map { it.id }.toSet()
        val items = state.items.map { item ->
            if (item.origin != "USER_MANUAL" && item.status != "SUPERSEDED" && item.sourceIds.none { it in valid })
                item.copy(status = "SUPERSEDED", revision = item.revision + 1, history = item.history + item.snapshot()) else item
        }
        return state.copy(sources = sources, items = items,
            jobs = state.jobs.map { job -> if (job.state in setOf("PENDING", "RUNNING", "RETRY_WAIT") && job.sourceIds.any { it !in valid }) job.copy(state = "CANCELLED", error = "来源已改变") else job })
    }

    fun commit(state: MemoryState, job: ExtractionJob, candidates: List<MemoryCandidate>, now: Long,
               fingerprint: (String) -> String): MemoryState {
        MemoryValidation.validate(candidates, job.sourceIds.toSet())
        require(state.jobs.any { it.id == job.id && it.state == "RUNNING" && it.attempts == job.attempts })
        require(state.epoch == job.epoch && job.sourceIds.all { id -> state.sources.any { it.id == id && it.valid } })
        require(job.expectedRevisions.all { (id, rev) -> state.items.any { it.id == id && it.revision == rev } })
        val items = state.items.toMutableList()
        var saved = 0
        candidates.forEach { c ->
            val fact = fingerprint("${c.type}:${c.topicKey}:${c.factKey}")
            if (fact in state.suppressedFacts || fingerprint(c.topicKey) in state.suppressedTopics || c.sourceIds.any { id ->
                    val source = state.sources.single { it.id == id }
                    fingerprint("${source.messageId}:${source.hash}") in state.suppressedSources || fingerprint("message:${source.messageId}") in state.suppressedSources
                }) return@forEach
            val old = items.filter { it.type == c.type && it.topicKey == c.topicKey && it.factKey == c.factKey && it.status != "SUPERSEDED" }
            val same = old.firstOrNull { it.content == c.content && it.evidenceType == c.evidenceType } ?: items.firstOrNull {
                it.status != "SUPERSEDED" && it.type == c.type && it.content == c.content && it.evidenceType == c.evidenceType && it.sourceIds.any { id -> id in c.sourceIds }
            }
            if (same != null) {
                val refs = (same.sourceIds + c.sourceIds).distinct()
                if (refs != same.sourceIds) {
                    items[items.indexOf(same)] = same.copy(sourceIds = refs, updatedAt = now, revision = same.revision + 1, history = same.history + same.snapshot())
                    saved++
                }
            } else {
                // Different assertions remain reviewable. Never overwrite an explicit or edited fact.
                old.filter { it.origin == "AUTO_EXTRACTED" }.forEach { previous ->
                    items[items.indexOf(previous)] = previous.copy(status = "DISPUTED", revision = previous.revision + 1, updatedAt = now, history = previous.history + previous.snapshot())
                }
                val status = if (old.isNotEmpty()) "DISPUTED" else if (c.evidenceType == EvidenceType.SELF_REPORTED && c.type != MemoryType.PERFORMANCE) "ACTIVE" else "CANDIDATE"
                val explicit = c.sourceIds.all { id -> state.sources.any { it.id == id && it.explicitRequest } }
                items.add(LearningMemory(type = c.type, topicKey = c.topicKey, factKey = c.factKey, content = c.content,
                    evidenceType = c.evidenceType, sourceIds = c.sourceIds, status = status, createdAt = now, updatedAt = now))
                if (explicit) items[items.lastIndex] = items.last().copy(origin = "USER_EXPLICIT")
                saved++
            }
        }
        return state.copy(items = items, jobs = state.jobs.map { if (it.id == job.id) it.copy(state = "SUCCEEDED", savedCount = saved, error = null, leaseUntil = 0, updatedAt = now) else it })
    }
}
