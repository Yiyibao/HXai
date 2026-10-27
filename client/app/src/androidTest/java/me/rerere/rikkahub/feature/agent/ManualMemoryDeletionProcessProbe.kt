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
class ManualMemoryDeletionProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = InstrumentationRegistry.getArguments().getString("fixture")!!.also {
        require(Regex("[a-f0-9]{32}").matches(it))
    }
    private fun database() = Room.databaseBuilder(context, AppDatabase::class.java, "qa-manual-process-$fixture.db").build()
    private fun ready() = File(context.cacheDir, "manual-deletion-$fixture/ready")

    @Test fun prepare(): Unit = runBlocking {
        val db = database()
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val id = store.addManual("owner-a", "deleted-request", MemoryType.PREFERENCE, "先解释定义")
            store.delete("owner-a", id, 1)
            store.addManual("owner-a", "new-request", MemoryType.PREFERENCE, "先解释定义")
            store.addManual("owner-b", "deleted-request", MemoryType.BACKGROUND, "先解释定义")
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
            MemoryType.entries.forEach { type ->
                try {
                    store.addManual("owner-a", "deleted-request", type, "先解释定义")
                    fail("Deleted request revived as $type after process exit")
                } catch (_: IllegalArgumentException) { }
            }
            assertEquals(before, db.learningMemoryDao().get("owner-a")!!.payload)
            assertEquals(1, store.read("owner-a").items.size)
            assertEquals(1, store.read("owner-b").items.size)
            assertEquals(store.read("owner-b").items.single().id,
                store.addManual("owner-b", "deleted-request", MemoryType.BACKGROUND, "先解释定义"))
        } finally { db.close() }
    }
}
