package me.rerere.rikkahub.feature.memory

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MemoryContractTest {
    @Test fun manualMemorySurvivesChatReconciliationAndEditing() {
        val manual = MemoryReducer.addManual(MemoryState(), "manual", MemoryType.PREFERENCE, " 先给结论 ", 1)
        assertEquals("先给结论", manual.items.single().content)
        assertEquals("ACTIVE", manual.items.single().status)
        assertTrue(manual.sources.isEmpty())
        val edited = MemoryReducer.edit(manual, "manual", 1, "先解释原因", 2)
        assertEquals("USER_MANUAL", edited.items.single().origin)
        assertEquals(edited, MemoryReducer.reconcile(edited, "unrelated-chat", emptyList()))
        assertEquals(manual, MemoryReducer.addManual(manual, "manual", MemoryType.PREFERENCE, "先给结论", 5))
        rejected { MemoryReducer.addManual(manual, "manual", MemoryType.PREFERENCE, "其他内容", 5) }
        assertEquals(edited, Json.decodeFromString<MemoryState>(Json.encodeToString(edited)))
    }
    @Test fun manualMemoryRejectsEmptyLongAndSensitiveContent() {
        listOf(" ", "长".repeat(2001), "api_key=secret").forEach { content ->
            rejected { MemoryReducer.addManual(MemoryState(), "manual", MemoryType.PREFERENCE, content, 1) }
        }
    }
    @Test fun userEditRejectsStaleRevisionWithoutChangingRecord() {
        val stored = commit()
        val memory = stored.items.single()
        val changed = MemoryReducer.edit(stored, memory.id, memory.revision, "新的事实", 2)
        rejected { MemoryReducer.edit(changed, memory.id, memory.revision, "旧草稿", 3) }
        assertEquals("新的事实", changed.items.single().content)
        assertEquals(memory.revision + 1, changed.items.single().revision)
        assertEquals(memory.snapshot(), changed.items.single().history.last())
    }

    private val source = MemorySource("source", "chat", "node", "message", "hash", 1)
    private val job = ExtractionJob(conversationId = "chat", sourceIds = listOf("source"), rangeHash = "range", epoch = 0, expectedRevisions = emptyMap(), state = "RUNNING", attempts = 1)
    private val candidate = MemoryCandidate(MemoryType.PREFERENCE, "教学", "顺序", "先看反例", EvidenceType.SELF_REPORTED, listOf("source"))
    private val state = MemoryState(sources = listOf(source), jobs = listOf(job))
    private val payload = """{"schema_version":1,"candidates":[{"type":"PREFERENCE","topic_key":"教学","fact_key":"顺序","content":"先看反例","evidence_type":"SELF_REPORTED","source_ids":["source"]}]}"""
    private fun commit(s: MemoryState = state, c: List<MemoryCandidate> = listOf(candidate), j: ExtractionJob = job) = MemoryReducer.commit(s, j, c, 1) { it }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun strictValidBatch() { assertEquals(listOf(candidate), MemoryValidation.parse(payload, setOf("source"))) }
    @Test fun unknownTopLevelField() = rejected { MemoryValidation.parse(payload.dropLast(1) + ",\"profile_id\":\"other\"}", setOf("source")) }
    @Test fun unknownCandidateField() = rejected { MemoryValidation.parse(payload.replace("\"type\":", "\"status\":\"ACTIVE\",\"type\":"), setOf("source")) }
    @Test fun duplicateRootKeys() = rejected { MemoryValidation.parse(payload.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"), setOf("source")) }
    @Test fun duplicateEscapedKeys() = rejected { MemoryValidation.parse(payload.replace("\"type\":", "\"ty\\u0070e\":\"PREFERENCE\",\"type\":"), setOf("source")) }
    @Test fun duplicateFacts() = rejected { MemoryValidation.parse(payload.replace("}]}", "}," + payload.substringAfter("[").substringBeforeLast("]}") + "]}"), setOf("source")) }
    @Test fun absentEvidence() = rejected { MemoryValidation.parse(payload.replace("\"source_ids\":[\"source\"]", "\"source_ids\":[]"), setOf("source")) }
    @Test fun inventedSource() = rejected { MemoryValidation.parse(payload, setOf("other")) }
    @Test fun badEnum() = rejected { MemoryValidation.parse(payload.replace("PREFERENCE", "MASTERY"), setOf("source")) }
    @Test fun truncation() = rejected { MemoryValidation.parse(payload.dropLast(3), setOf("source")) }
    @Test fun codeFenceRejected() = rejected { MemoryValidation.parse("```json\n$payload\n```", setOf("source")) }
    @Test fun emptyBatchSuccess() { val next = commit(c = emptyList()); assertEquals("SUCCEEDED", next.jobs.single().state); assertEquals(0, next.jobs.single().savedCount) }
    @Test fun activeSelfReportWithEvidence() { val next = commit(); assertEquals("ACTIVE", next.items.single().status); assertEquals(listOf("source"), next.items.single().sourceIds) }
    @Test fun inferenceCandidate() { assertEquals("CANDIDATE", commit(c = listOf(candidate.copy(evidenceType = EvidenceType.UNVERIFIED))).items.single().status) }
    @Test fun performanceNeverMastery() { assertEquals("CANDIDATE", commit(c = listOf(candidate.copy(type = MemoryType.PERFORMANCE))).items.single().status) }
    @Test fun readOnlyCandidate() { assertEquals("CANDIDATE", commit(c = listOf(candidate.copy(evidenceType = EvidenceType.READ_ONLY))).items.single().status) }
    @Test fun withHintCandidate() { assertEquals("CANDIDATE", commit(c = listOf(candidate.copy(evidenceType = EvidenceType.WITH_HINT))).items.single().status) }
    @Test fun completedJobCannotReplay() = rejected { val next = commit(); commit(next) }
    @Test fun deletionEpochRejectsLateResult() = rejected { commit(state.copy(epoch = 1)) }
    @Test fun expiredLeaseClaimRejectsPreviousAttempt() = rejected { commit(state.copy(jobs = listOf(job.copy(attempts = 2)))) }
    @Test fun invalidSourceRejectsEntireBatch() = rejected { commit(state.copy(sources = listOf(source.copy(valid = false)))) }
    @Test fun sourceDeletionInvalidatesDerivedAndPending() {
        val next = commit()
        val changed = MemoryReducer.reconcile(next.copy(jobs = listOf(job)), "chat", emptyList())
        assertEquals("SUPERSEDED", changed.items.single().status); assertEquals("CANCELLED", changed.jobs.single().state)
    }
    @Test fun sourceEditInvalidatesOldVersion() {
        val changed = MemoryReducer.reconcile(commit(), "chat", listOf(source.copy(id = "new", hash = "edited", revision = 2)))
        assertFalse(changed.sources.first().valid); assertEquals("SUPERSEDED", changed.items.single().status)
    }
    @Test fun otherConversationUnchanged() { assertEquals(state, MemoryReducer.reconcile(state, "other", emptyList())) }
    @Test fun suppressionPreventsOldSourceWithNewFactKey() { assertTrue(commit(state.copy(suppressedSources = setOf("message:hash")), listOf(candidate.copy(factKey = "changed"))).items.isEmpty()) }
    @Test fun factSuppressionPreventsOtherSource() { assertTrue(commit(state.copy(suppressedFacts = setOf("PREFERENCE:教学:顺序"))).items.isEmpty()) }
    @Test fun topicSuppression() { assertTrue(commit(state.copy(suppressedTopics = setOf("教学"))).items.isEmpty()) }
    @Test fun deletedMessageAllEditedVersionsSuppressed() { assertTrue(commit(state.copy(sources = listOf(source.copy(hash = "edited")), suppressedSources = setOf("message:message"))).items.isEmpty()) }
    @Test fun validButOutOfJobSourceRejected() = rejected { commit(state.copy(sources = listOf(source, source.copy(id = "other"))), listOf(candidate.copy(sourceIds = listOf("other")))) }
    @Test fun duplicateFactNoDuplicateRow() {
        val previous = commit().items.single()
        val next = commit(state.copy(items = listOf(previous)))
        assertEquals(1, next.items.size); assertEquals(0, next.jobs.single().savedCount)
    }
    @Test fun automaticConflictWithdrawsBothFromFactRetrieval() {
        val previous = commit().items.single()
        val next = commit(state.copy(items = listOf(previous)), listOf(candidate.copy(content = "先看定义")))
        assertEquals(listOf("DISPUTED", "DISPUTED"), next.items.map { it.status })
    }
    @Test fun humanCorrectionWinsConflict() {
        val human = commit().items.single().copy(origin = "USER_EDITED", content = "先看定义", revision = 2)
        val next = commit(state.copy(items = listOf(human)))
        assertEquals(human, next.items.first()); assertEquals("DISPUTED", next.items.last().status)
    }
    @Test fun humanRevisionRaceRejectsBatch() = rejected {
        val human = commit().items.single().copy(revision = 2)
        val guarded = job.copy(expectedRevisions = mapOf(human.id to 1))
        commit(state.copy(items = listOf(human), jobs = listOf(guarded)), j = guarded)
    }
    @Test fun serializationRetainsProvenanceAndJobs() { val next = commit(); assertEquals(next, Json.decodeFromString<MemoryState>(Json.encodeToString(next))) }
    @Test fun sensitiveContentRejected() = rejected { MemoryValidation.parse(payload.replace("先看反例", "api_key=secret"), setOf("source")) }
    @Test fun chineseMathematicalAliases() { assertEquals("线性代数导数特征值", MemoryValidation.searchText("线代求导特征根")) }
}
