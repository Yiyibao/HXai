package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test

class ManualMemoryDeletionTest {
    @Test fun independentIdentityTombstoneSurvivesFactReinstatementWithoutBlockingNewRecord() {
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(
            item(MemoryType.BACKGROUND), item(MemoryType.PREFERENCE, "new-explicit-id").copy(
                origin = "USER_EXPLICIT", factKey = "stable-request")))
        val live = MemoryState(fingerprintVersion = 1, suppressedFacts = setOf("manual-request:stable-request"))
        val merged = MemoryRestore.merge(saved, live) { it }
        assertEquals(listOf("new-explicit-id"), merged.items.map { it.id })
        assertEquals(live.suppressedFacts, merged.suppressedFacts)
    }
    private fun item(type: MemoryType, id: String = "stable-request") = LearningMemory(
        id = id, type = type, topicKey = "手动添加", factKey = id, content = "先解释定义",
        evidenceType = EvidenceType.SELF_REPORTED, sourceIds = emptyList(), origin = "USER_MANUAL",
        status = "ACTIVE", createdAt = 1, updatedAt = 1)
    private fun deleted(type: MemoryType) = MemoryState(fingerprintVersion = 1,
        suppressedFacts = setOf("$type:手动添加:stable-request"))

    @Test fun legacyTypeTombstoneBlocksEveryClassificationOfTheSameRequest() {
        MemoryType.entries.forEach { original -> MemoryType.entries.forEach { changed ->
            val live = deleted(original)
            val saved = MemoryState(fingerprintVersion = 1, items = listOf(item(changed)))
            val merged = MemoryRestore.merge(saved, live) { it }
            assertTrue("$original -> $changed", merged.items.isEmpty())
            assertEquals(live.suppressedFacts, merged.suppressedFacts)
        } }
    }

    @Test fun changingOriginOrTopicCannotDisguiseDeletedManualIdentity() {
        listOf("USER_MANUAL", "USER_EDITED", "USER_EXPLICIT", "AUTO_EXTRACTED").forEach { origin ->
            val altered = item(MemoryType.BACKGROUND).copy(origin = origin, topicKey = "新分类", factKey = "其他键")
            assertTrue(MemoryRestore.merge(MemoryState(fingerprintVersion = 1, items = listOf(altered)),
                deleted(MemoryType.PREFERENCE)) { it }.items.isEmpty())
        }
    }

    @Test fun freshRequestAndUnrelatedLegacyRecordsRemainRestorable() {
        val fresh = item(MemoryType.BACKGROUND, "new-request")
        val old = item(MemoryType.DIFFICULTY, "unrelated")
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(fresh, old))
        assertEquals(saved.items, MemoryRestore.merge(saved, deleted(MemoryType.PREFERENCE)) { it }.items)
    }

    @Test fun oldSerializedTombstoneSurvivesRoundtripAndRepeatedMergeWithoutMigration() {
        val live = Json.decodeFromString<MemoryState>("""{"fingerprintVersion":1,"suppressedFacts":["PREFERENCE:手动添加:stable-request"]}""")
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(item(MemoryType.BACKGROUND)))
        val first = MemoryRestore.merge(saved, live) { it }
        val second = MemoryRestore.merge(Json.decodeFromString(Json.encodeToString(first)), live) { it }
        assertTrue(second.items.isEmpty())
        assertEquals(live.suppressedFacts, second.suppressedFacts)
        assertEquals(1, second.schemaVersion); assertEquals(1, second.fingerprintVersion)
        assertEquals(saved.items.single().type, MemoryType.BACKGROUND)
    }

    @Test fun fingerprintDomainKeepsAnotherOwnerIndependent() {
        val item = item(MemoryType.BACKGROUND)
        val saved = MemoryState(fingerprintVersion = 1, items = listOf(item))
        val live = MemoryState(fingerprintVersion = 1,
            suppressedFacts = setOf(MemoryValidation.hash("owner-a\u0000PREFERENCE:手动添加:stable-request")))
        assertEquals(listOf(item), MemoryRestore.merge(saved, live) { MemoryValidation.hash("owner-b\u0000$it") }.items)
        assertTrue(MemoryRestore.merge(saved, live) { MemoryValidation.hash("owner-a\u0000$it") }.items.isEmpty())
    }
}
