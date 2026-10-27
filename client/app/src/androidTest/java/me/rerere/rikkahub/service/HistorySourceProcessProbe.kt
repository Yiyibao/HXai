package me.rerere.rikkahub.service

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryFingerprints
import me.rerere.rikkahub.feature.memory.MemoryValidation
import me.rerere.rikkahub.feature.studychat.ChatContextBudget
import org.junit.Assert.*
import org.junit.Test
import java.io.FileOutputStream

/** Explicit external driver; leaves synthetic state for independent cold reopen. */
class HistorySourceProcessProbe {
    @Test fun persistedOrphanSourceProjection(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("historyPhase")!!
        val fixture = args.getString("historyFixture")!!.also { require(Regex("[a-f0-9-]{36}").matches(it)) }
        require(phase in setOf("seed", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "qa-history-source-$fixture.db").build()
        val folder = context.noBackupFilesDir.resolve("qa-history-source-$fixture").apply { mkdirs() }
        val json = Json { encodeDefaults = true }
        val store = LearningMemoryStore(db, MemoryFingerprints(context))
        try {
            if (phase == "seed") {
                val old = UIMessage.user("synthetic identical source text")
                val messages = listOf(old, UIMessage.assistant("old synthetic answer"),
                    ChatContextBudget.summaryMessage("unknown synthetic source inventory"),
                    UIMessage.assistant("old summary answer"), UIMessage.user(old.toText()))
                val marker = store.fingerprint("owner", "message:${old.id}")
                store.change("owner") { it.copy(suppressedSources = setOf(marker), sources = emptyList()) to Unit }
                FileOutputStream(folder.resolve("messages.json")).use { it.write(json.encodeToString(messages).toByteArray()); it.fd.sync() }
                FileOutputStream(folder.resolve("payload.sha256")).use {
                    it.write(MemoryValidation.hash(db.learningMemoryDao().get("owner")!!.payload).toByteArray()); it.fd.sync()
                }
            }
            val messages = json.decodeFromString<List<UIMessage>>(folder.resolve("messages.json").readText())
            val before = db.learningMemoryDao().get("owner")!!.payload
            assertEquals(folder.resolve("payload.sha256").readText(), MemoryValidation.hash(before))
            assertTrue(store.read("owner").sources.isEmpty())
            assertEquals(listOf(messages.last()), store.projectHistory("owner", messages))
            assertEquals(messages, store.projectHistory("another-owner", messages))
            assertEquals(before, db.learningMemoryDao().get("owner")!!.payload)
            println("HISTORY_SOURCE_PROCESS_PASS $phase $fixture")
        } finally { db.close() }
    }
}
