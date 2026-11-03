package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/** Explicitly invoked alone by the host. Never batch-run the terminating prepare method. */
class QualityEvidenceProcessProbe {
    private val bytes = ("{\"answer\":\"" + "完整数学答卷".repeat(2000) + "\"}").toByteArray(Charsets.UTF_8)
    private fun inputs(): Pair<File, String> {
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("evidenceFixture"))
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val point = requireNotNull(args.getString("evidencePoint"))
        require(point in setOf("after_half_write", "after_file_sync", "after_publish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        return File(context.noBackupFilesDir, "qa-evidence-process/$id") to point
    }
    @Test fun prepare() {
        val (folder, point) = inputs()
        check(!folder.exists() && folder.mkdirs())
        QualityEvidenceFiles.write(File(folder, "RESPONSE.json"), bytes) {
            if (it == point) {
                FileOutputStream(File(folder, "READY")).use { stream ->
                    stream.write("$point:${android.os.Process.myPid()}".toByteArray()); stream.fd.sync()
                }
                exitProcess(77)
            }
        }
        fail("Process exit was not reached")
    }
    @Test fun verify() {
        val (folder, point) = inputs()
        val marker = File(folder, "READY").readText().split(':')
        assertEquals(point, marker[0])
        assertNotEquals(marker[1].toInt(), android.os.Process.myPid())
        val final = File(folder, "RESPONSE.json")
        if (point == "after_publish") assertArrayEquals(bytes, final.readBytes())
        else assertFalse(final.exists())
    }
}
