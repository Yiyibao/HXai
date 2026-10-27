package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Only invoked explicitly by the external process driver; uses a synthetic QA DB. */
class MemoryContextSuppressionProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = InstrumentationRegistry.getArguments().getString("fixture")!!.also {
        require(Regex("[a-f0-9]{32}").matches(it))
    }
    private fun database() = Room.databaseBuilder(context, AppDatabase::class.java, "qa-memory-context-process-$fixture.db").build()
    private fun ready() = File(context.cacheDir, "memory-context-$fixture/ready")

    @Test fun prepare(): Unit = runBlocking {
        val db = database()
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val id = store.addManual("owner-a", "deleted-request", MemoryType.PREFERENCE, "旧记录哨兵")
            val old = store.read("owner-a").items.single()
            store.delete("owner-a", id, 1)
            store.addManual("owner-a", "new-request", MemoryType.PREFERENCE, "合法新请求哨兵")
            store.addManual("owner-b", "deleted-request", MemoryType.BACKGROUND, "其他档案哨兵")
            val live = store.read("owner-a")
            val legacy = live.copy(usedMemories = mapOf("chat" to listOf(live.items.single().id)),
                items = live.items + old.copy(type = MemoryType.BACKGROUND),
                suppressedFacts = setOf(store.fingerprint("owner-a", "PREFERENCE:手动添加:$id")))
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner-a", kotlinx.serialization.json.Json.encodeToString(legacy)))
            val hash = MemoryValidation.hash(db.learningMemoryDao().get("owner-a")!!.payload)
            val file = ready(); file.parentFile!!.mkdirs()
            FileOutputStream(file).use { it.write(hash.toByteArray()); it.fd.sync() }
            while (true) delay(1000) // The driver observes the committed marker then force-stops this process.
        } finally { db.close() }
    }

    @Test fun verify(): Unit = runBlocking {
        val db = database()
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val before = db.learningMemoryDao().get("owner-a")!!.payload
            assertEquals(ready().readText(), MemoryValidation.hash(before))
            val state = store.read("owner-a")
            val fresh = state.items.single { it.content == "合法新请求哨兵" }
            assertEquals(listOf(fresh.id), store.relevant("owner-a", "哨兵").map { it.id })
            assertEquals(before, db.learningMemoryDao().get("owner-a")!!.payload)
            assertEquals("其他档案哨兵", store.relevant("owner-b", "哨兵").single().content)
            val projected = store.context("owner-a", "chat", "哨兵", true)
            assertFalse(projected.contains("旧记录哨兵"))
            assertTrue(projected.contains("合法新请求哨兵"))
            assertEquals(state.copy(usedMemories = state.usedMemories + ("chat" to listOf(fresh.id))), store.read("owner-a"))
            assertEquals(before, db.learningMemoryDao().get("owner-a")!!.payload)

        } finally { db.close() }
    }
}
