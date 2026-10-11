package me.rerere.rikkahub.feature.memory

import org.junit.Assert.*
import org.junit.Test

class LearningLoopTest {
    private fun record(state: MemoryState, id: String, type: MemoryType, evidence: EvidenceType, text: String): MemoryState {
        val source = MemorySource(id, "chat", "node-$id", "message-$id", "hash-$id", 1)
        val job = ExtractionJob(id = "job-$id", conversationId = "chat", sourceIds = listOf(id), rangeHash = id,
            epoch = state.epoch, expectedRevisions = emptyMap(), state = "RUNNING", attempts = 1)
        return MemoryReducer.commit(state.copy(sources = state.sources + source, jobs = state.jobs + job), job,
            listOf(MemoryCandidate(type, "链式法则", id, text, evidence, listOf(id))), state.items.size.toLong()) { it }
    }

    @Test fun wrongHintReattemptReadingAndReviewRemainTraceable() {
        var state = record(MemoryState(), "wrong", MemoryType.DIFFICULTY, EvidenceType.SELF_REPORTED, "我漏乘了内层导数")
        state = record(state, "hint", MemoryType.PERFORMANCE, EvidenceType.WITH_HINT, "提示后我重新求导")
        state = record(state, "reading", MemoryType.PERFORMANCE, EvidenceType.READ_ONLY, "看懂了解析")
        state = record(state, "retry", MemoryType.PERFORMANCE, EvidenceType.INDEPENDENT_ATTEMPT, "这次不看提示求导")
        state = record(state, "review", MemoryType.REVIEW_SUGGESTION, EvidenceType.SELF_REPORTED, "下次复习复合函数求导")
        val progress = state.learningProgress()
        assertEquals(1, progress.difficulties.size)
        assertEquals(listOf(EvidenceType.INDEPENDENT_ATTEMPT, EvidenceType.READ_ONLY, EvidenceType.WITH_HINT), progress.evidence.map { it.evidenceType })
        assertTrue(progress.evidence.all { it.status == "CANDIDATE" })
        assertEquals("ACTIVE", progress.difficulties.single().status)
        assertEquals(1, progress.reviews.size)
        assertTrue(state.jobs.all { it.state == "SUCCEEDED" })
        assertTrue(state.items.all { it.sourceIds.single() in state.sources.map { source -> source.id } })
        assertEquals(LearningProgress(emptyList(), emptyList(), emptyList()), MemoryState().learningProgress())
        val invalidated = MemoryReducer.reconcile(state, "chat", emptyList())
        assertTrue(invalidated.learningProgress().evidence.isEmpty())
        assertTrue(invalidated.learningProgress().reviews.isEmpty())
    }

    @Test fun mixedInvalidSourcesAndDisputesAreNotProgress() {
        val state = record(MemoryState(), "a", MemoryType.PERFORMANCE, EvidenceType.WITH_HINT, "重答")
        val mixed = state.items.single().copy(sourceIds = listOf("a", "missing"))
        assertTrue(state.copy(items = listOf(mixed)).learningProgress().evidence.isEmpty())
        assertTrue(state.copy(items = listOf(state.items.single().copy(status = "DISPUTED"))).learningProgress().evidence.isEmpty())
    }

    @Test fun identicalTextCannotMergeDifferentEvidenceLevels() {
        val state = record(MemoryState(), "a", MemoryType.PERFORMANCE, EvidenceType.READ_ONLY, "完成")
        val job = ExtractionJob(conversationId = "chat", sourceIds = listOf("a"), rangeHash = "new", epoch = 0,
            expectedRevisions = emptyMap(), state = "RUNNING", attempts = 1)
        val next = MemoryReducer.commit(state.copy(jobs = state.jobs + job), job,
            listOf(MemoryCandidate(MemoryType.PERFORMANCE, "链式法则", "b", "完成", EvidenceType.WITH_HINT, listOf("a"))), 2) { it }
        assertEquals(2, next.items.size)
        assertEquals(setOf(EvidenceType.READ_ONLY, EvidenceType.WITH_HINT), next.items.map { it.evidenceType }.toSet())
    }
}
