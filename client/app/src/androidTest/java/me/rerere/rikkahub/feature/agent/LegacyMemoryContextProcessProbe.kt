package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Explicit external driver only. Leaves a committed synthetic fixture for force-stop. */
class LegacyMemoryContextProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = InstrumentationRegistry.getArguments().getString("fixture")!!.also {
        require(Regex("[a-f0-9]{32}").matches(it))
    }
    private fun database() = Room.databaseBuilder(context, AppDatabase::class.java, "qa-legacy-context-$fixture.db").build()
    private fun ready() = File(context.cacheDir, "legacy-context-$fixture/ready")

    @Test fun prepare(): Unit = runBlocking {
        val db = database()
        try {
            val fingerprints = MemoryFingerprints(context)
            val store = LearningMemoryStore(db, fingerprints)
            store.addManual("legacy", "original", MemoryType.PREFERENCE, "旧约束哨兵")
            store.addManual("fresh", "original", MemoryType.PREFERENCE, "新档案哨兵")
            val state = store.read("legacy").copy(fingerprintVersion = 0,
                suppressedFacts = setOf(fingerprints.legacyOf("legacy", "opaque-deleted-fact")),
                suppressedTopics = setOf(fingerprints.legacyOf("legacy", "opaque-deleted-topic")))
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", Json.encodeToString(state)))
            assertTrue(store.read("legacy").recoveryBlocked)
            val hash = MemoryValidation.hash(db.learningMemoryDao().get("legacy")!!.payload)
            val marker = ready(); marker.parentFile!!.mkdirs()
            FileOutputStream(marker).use { it.write(hash.toByteArray()); it.fd.sync() }
            while (true) delay(1000)
        } finally { db.close() }
    }

    @Test fun verify(): Unit = runBlocking {
        val db = database()
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val payload = db.learningMemoryDao().get("legacy")!!.payload
            assertEquals(ready().readText(), MemoryValidation.hash(payload))
            val state = store.read("legacy")
            assertTrue(state.recoveryBlocked)
            assertEquals("旧约束哨兵", state.items.single().content)
            assertTrue(store.relevant("legacy", "哨兵").isEmpty())
            assertEquals("", store.context("legacy", "chat", "哨兵", true))
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
            assertTrue(store.context("fresh", "chat", "哨兵", true).contains("新档案哨兵"))
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
        } finally { db.close() }
    }
}
