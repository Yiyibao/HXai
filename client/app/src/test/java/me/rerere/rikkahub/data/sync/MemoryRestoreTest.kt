package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test

class MemoryRestoreTest {
    @Test fun manualMemoryRestorePreservesNewerEditAndDeletion() {
        val original = MemoryReducer.addManual(MemoryState(fingerprintVersion = 1), "manual", MemoryType.PREFERENCE, "先给结论", 1)
        val edited = MemoryReducer.edit(original, "manual", 1, "先给解释", 2)
        assertEquals(edited.items, MemoryRestore.merge(original, edited) { it }.items)
        val deleted = edited.copy(items = emptyList(), suppressedFacts = setOf("PREFERENCE:手动添加:manual"))
        assertTrue(MemoryRestore.merge(original, deleted) { it }.items.isEmpty())
    }
    private fun item(id: String) = LearningMemory(id = id, type = MemoryType.PREFERENCE, topicKey = "教学", factKey = id,
        content = "喜欢反例", evidenceType = EvidenceType.SELF_REPORTED, sourceIds = listOf(id), createdAt = 1, updatedAt = 1, status = "ACTIVE")
    private fun source(id: String) = MemorySource(id, "chat", "node", "message-$id", "hash", 1)
    @Test fun oldSnapshotCannotReviveNewerFactTopicOrSourceDeletions() {
        val saved = MemoryState(fingerprintVersion = 1, sources = listOf(source("a"), source("b")), items = listOf(item("a"), item("b")))
        val live = MemoryState(fingerprintVersion = 1, epoch = 9, suppressedSources = setOf("message:message-a"), suppressedFacts = setOf("PREFERENCE:教学:b"))
        val merged = MemoryRestore.merge(saved, live) { it }
        assertTrue(merged.items.isEmpty()); assertEquals(10L, merged.epoch)
        assertEquals(live.suppressedFacts, merged.suppressedFacts)
        assertTrue(MemoryRestore.merge(saved, live.copy(suppressedSources = emptySet(), suppressedFacts = emptySet(), suppressedTopics = setOf("教学"))) { it }.items.isEmpty())
    }
    @Test fun newerManualCorrectionWinsAndArchivedWorkIsCancelled() {
        val old = item("a"); val edited = old.copy(content = "更喜欢定义", origin = "USER_EDITED", revision = 2)
        val job = ExtractionJob(conversationId = "chat", sourceIds = listOf("a"), rangeHash = "x", epoch = 0, expectedRevisions = emptyMap(), state = "RUNNING")
        val merged = MemoryRestore.merge(MemoryState(fingerprintVersion = 1, items = listOf(old), jobs = listOf(job)),
            MemoryState(fingerprintVersion = 1, items = listOf(edited))) { it }
        assertEquals(edited, merged.items.single()); assertEquals("CANCELLED", merged.jobs.single().state); assertEquals(0L, merged.jobs.single().leaseUntil)
    }
    @Test fun legacyDeletionLockCannotReviveMissingRecords() {
        val merged = MemoryRestore.merge(MemoryState(fingerprintVersion = 1, items = listOf(item("deleted"))),
            MemoryState(fingerprintVersion = 1, recoveryBlocked = true)) { it }
        assertTrue(merged.recoveryBlocked); assertTrue(merged.items.isEmpty())
    }

    @Test fun oldBackupCannotDiscardUnmappedLiveSourcesOrReviveMissingRecords() {
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(item("deleted")))
        val live = MemoryState(fingerprintVersion = 1, legacySuppressedSources = setOf("opaque-live-v0"))
        val merged = MemoryRestore.merge(saved, live) { it }
        assertEquals(setOf("opaque-live-v0"), merged.legacySuppressedSources)
        assertTrue(merged.recoveryBlocked)
        assertTrue(merged.items.isEmpty())
        assertEquals(2, merged.schemaVersion)
    }

    @Test fun emptyInstallationPreservesArchivedManagementRecordsAndOpaqueSources() {
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(item("kept")),
            legacySuppressedSources = setOf("opaque-backup-v0"))
        val merged = MemoryRestore.merge(saved, MemoryState(fingerprintVersion = 1)) { it }
        assertEquals(saved.items, merged.items)
        assertEquals(saved.legacySuppressedSources, merged.legacySuppressedSources)
        assertTrue(merged.recoveryBlocked)
        assertEquals(2, merged.schemaVersion)
        val roundTrip = kotlinx.serialization.json.Json.decodeFromString<MemoryState>(
            kotlinx.serialization.json.Json.encodeToString(merged))
        assertEquals(merged, roundTrip)
    }

    @Test fun deletionMergeUnionsBothOpaqueSetsAndNeverClearsProtection() {
        val saved = MemoryState(fingerprintVersion = 1, legacySuppressedSources = setOf("saved", "both"))
        val live = MemoryState(fingerprintVersion = 1, legacySuppressedSources = setOf("live", "both"))
        val merged = MemoryRestore.merge(saved, live) { it }
        assertEquals(setOf("saved", "live", "both"), merged.legacySuppressedSources)
        assertTrue(merged.recoveryBlocked)
        assertEquals(merged.legacySuppressedSources,
            MemoryRestore.merge(merged, MemoryState(fingerprintVersion = 1)) { it }.legacySuppressedSources)
    }

    @Test fun oldJsonWithoutOpaqueFieldRemainsReadable() {
        val old = """{"fingerprintVersion":1,"epoch":3}"""
        val state = kotlinx.serialization.json.Json.decodeFromString<MemoryState>(old)
        assertTrue(state.legacySuppressedSources.isEmpty())
        assertFalse(state.recoveryBlocked)
        assertEquals(3L, state.epoch)
    }

    @Test fun futurePayloadVersionIsRejectedInsteadOfDowngraded() {
        val future = MemoryState(schemaVersion = 3, fingerprintVersion = 1)
        assertTrue(runCatching { MemoryRestore.merge(future, MemoryState(fingerprintVersion = 1)) { it } }.isFailure)
        assertTrue(runCatching { MemoryRestore.merge(MemoryState(fingerprintVersion = 1), future) { it } }.isFailure)
    }
}
