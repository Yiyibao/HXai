package me.rerere.rikkahub.feature.agent

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryFingerprints
import me.rerere.rikkahub.feature.memory.MemorySource
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

class EmptySuppressionKeyRuntimeTest {
    @Test fun emptySuppressionDoesNotBypassMissingKeyOrEraseSourceData(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"empty-suppression-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object: ContextWrapper(app) {
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val owner="qa-empty-${UUID.randomUUID()}";val db=GlobalContext.get().get<AppDatabase>()
        val fingerprints=MemoryFingerprints(context);val store=LearningMemoryStore(db,fingerprints)
        val message=UIMessage.user("synthetic retained source")
        try {
            val hash=fingerprints.of(owner,"content:${message.toText()}")
            val source=MemorySource(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                message.id.toString(),hash,1,spanEnd=message.toText().length)
            store.change(owner) {it.copy(sources=listOf(source),fingerprintVersion=1) to Unit}
            assertEquals(listOf(message),store.projectHistory(owner,listOf(message)))
            assertTrue(store.read(owner).suppressedSources.isEmpty())
            val reference=File(context.filesDir,"memory-protection.ref")
            assertTrue(reference.delete()) // Only this new fixture's reference, never the application's key.
            assertTrue(runCatching {store.projectHistory(owner,listOf(message))}.isFailure)
            assertFalse(reference.exists())
            assertNotNull(db.learningMemoryDao().get(owner))
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id=?",arrayOf(owner))
            root.deleteRecursively()
        }
    }
}
