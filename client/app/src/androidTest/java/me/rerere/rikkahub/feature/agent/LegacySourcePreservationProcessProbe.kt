package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** External force-stop driver, only unique synthetic nativeqa databases. */
class LegacySourcePreservationProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = InstrumentationRegistry.getArguments().getString("fixture")!!.also { require(Regex("[a-f0-9]{32}").matches(it)) }
    private fun db() = Room.databaseBuilder(context, AppDatabase::class.java, "qa-orphan-process-$fixture.db").build()
    private fun marker(phase: String) = File(context.cacheDir, "orphan-process-$fixture/$phase")
    private fun write(phase: String, value: String) {
        val file = marker(phase); check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        FileOutputStream(file).use { it.write(value.toByteArray()); it.fd.sync() }
    }

    @Test fun prepare(): Unit = runBlocking {
        val db = db()
        try {
            val fp = MemoryFingerprints(context)
            val store = LearningMemoryStore(db, fp)
            store.addManual("legacy", "keep", MemoryType.PREFERENCE, "管理记录保留哨兵")
            store.addManual("fresh", "keep", MemoryType.PREFERENCE, "其他档案哨兵")
            val state = store.read("legacy").copy(fingerprintVersion = 0,
                suppressedSources = setOf(fp.legacyOf("legacy", "message:orphan-$fixture")),
                jobs = listOf(ExtractionJob(conversationId = "old-chat", sourceIds = emptyList(), rangeHash = "hash", epoch = 0, expectedRevisions = emptyMap(), state = "RUNNING")))
            val payload = Json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", payload))
            write("seeded", MemoryValidation.hash(payload))
            while (true) delay(1000)
        } finally { db.close() }
    }

    @Test fun migrate(): Unit = runBlocking {
        val db = db()
        try {
            val original = db.learningMemoryDao().get("legacy")!!.payload
            assertEquals(marker("seeded").readText(), MemoryValidation.hash(original))
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            assertTrue(store.read("legacy").recoveryBlocked)
            assertEquals("CANCELLED", store.read("legacy").jobs.single().state)
            write("migrated", MemoryValidation.hash(db.learningMemoryDao().get("legacy")!!.payload))
        } finally { db.close() }
    }

    @Test fun verify(): Unit = runBlocking {
        val db = db()
        try {
            val payload = db.learningMemoryDao().get("legacy")!!.payload
            assertEquals(marker("migrated").readText(), MemoryValidation.hash(payload))
            val state = Json.decodeFromString<MemoryState>(payload)
            assertTrue(state.recoveryBlocked)
            assertEquals(1, Json.parseToJsonElement(payload).jsonObject.getValue("legacySuppressedSources").jsonArray.size)
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            assertEquals(state, store.read("legacy"))
            assertTrue(runCatching { store.projectHistory("legacy", listOf(UIMessage.user("old text"))) }.isFailure)
            assertEquals("", store.context("legacy", "chat", "哨兵", true))
            assertEquals(1, store.read("legacy").items.size)
            assertTrue(store.context("fresh", "chat", "哨兵", true).contains("其他档案哨兵"))
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
        } finally { db.close() }
    }
}
