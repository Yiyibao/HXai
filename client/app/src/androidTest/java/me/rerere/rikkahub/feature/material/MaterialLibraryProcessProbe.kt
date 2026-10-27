package me.rerere.rikkahub.feature.material

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.sync.PendingRestore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in only: an ordinary full suite must never kill its instrumentation process. */
class MaterialLibraryProcessProbe {
    private fun gate() { assumeTrue(InstrumentationRegistry.getArguments().getString("b04ProcessProbe") == "true") }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private val marker get() = File(context.noBackupFilesDir, "b04-library-process.json")
    private fun save(value: JsonObject) = PendingRestore.writeDurably(marker, value.toString())
    private fun read() = Json.parseToJsonElement(marker.readText()).jsonObject
    private fun die(): Nothing { Process.killProcess(Process.myPid()); error("Synthetic process death did not terminate instrumentation") }

    @Test fun writeThenDie(): Unit = runBlocking {
        gate(); check(!marker.exists())
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val original = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "original.md", "md")) {
            it.writeText("B04 ORIGINAL IMMUTABLE SOURCE\n"); "Synthetic process source"
        }
        val pending = MaterialFile(UUID.randomUUID().toString(), owner, "unpublished.md", "md", output = true, sourceId = original.id)
        store.runTask(pending) { target ->
            target.writeText("UNPUBLISHED OUTPUT MUST NOT BE EXPOSED\n")
            java.io.FileOutputStream(target, true).use { it.fd.sync() }
            save(buildJsonObject {
                put("phase", "unpublished_write"); put("owner", owner); put("original", original.id)
                put("source_sha256", original.sha256); put("pending", pending.id)
            })
            die()
        }
    }

    @Test fun recoverThenRestoreAndDie(): Unit = runBlocking {
        gate()
        val before = read(); check(before.getValue("phase").jsonPrimitive.content == "unpublished_write")
        val owner = before.getValue("owner").jsonPrimitive.content; val id = before.getValue("original").jsonPrimitive.content
        val hash = before.getValue("source_sha256").jsonPrimitive.content; val store = MaterialFiles(context)
        val failed = store.get(owner, before.getValue("pending").jsonPrimitive.content)
        assertEquals("FAILED", failed.state); assertFalse(store.file(failed).exists())
        assertEquals(hash, MaterialFiles.digest(store.verifiedFile(owner, id)))
        assertTrue(File(context.filesDir, "materials/$owner").listFiles().orEmpty().none { it.extension == "part" })
        store.delete(owner, id, hash)
        val restored = store.restore(owner, id)
        assertEquals(hash, MaterialFiles.digest(store.verifiedFile(owner, restored.id)))
        save(JsonObject(before + mapOf("phase" to JsonPrimitive("restore_saved_no_ack"), "restored" to JsonPrimitive(restored.id))))
        die()
    }

    @Test fun verifyRecovery(): Unit = runBlocking {
        gate()
        val before = read(); check(before.getValue("phase").jsonPrimitive.content == "restore_saved_no_ack")
        val owner = before.getValue("owner").jsonPrimitive.content; val original = before.getValue("original").jsonPrimitive.content
        val restoredId = before.getValue("restored").jsonPrimitive.content; val hash = before.getValue("source_sha256").jsonPrimitive.content
        val store = MaterialFiles(context)
        assertTrue(runCatching { store.verifiedFile(owner, original) }.isFailure)
        assertTrue(store.trash(owner).any { it.id == original })
        assertEquals(hash, MaterialFiles.digest(store.verifiedFile(owner, restoredId)))
        val acknowledgement = store.restore(owner, original)
        assertEquals(restoredId, acknowledgement.id)
        assertEquals(listOf(restoredId), store.list(owner).filter { it.state == "SUCCEEDED" }.map { it.id })
        val result = JsonObject(before + mapOf("status" to JsonPrimitive("PASS"), "restored_sha256" to JsonPrimitive(MaterialFiles.digest(store.verifiedFile(owner, restoredId)))))
        PendingRestore.writeDurably(File(context.cacheDir, "b04-library-process-evidence.json"), result.toString())
    }
}
