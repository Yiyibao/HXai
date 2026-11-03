package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QualityEvidenceFilesTest {
    private fun folder() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "qa-evidence-files/${UUID.randomUUID()}").also { check(it.mkdirs()) }

    @Test fun interruptionBeforePublicationLeavesNoFalseFinalRecord() {
        for (point in listOf("after_half_write", "after_file_sync")) {
            val file = File(folder(), "RESPONSE.json")
            try {
                QualityEvidenceFiles.write(file, "{\"answer\":\"complete\"}".toByteArray()) {
                    if (it == point) throw IllegalStateException("synthetic interrupted write")
                }
                fail("Expected interruption")
            } catch (_: IllegalStateException) { }
            assertFalse(file.exists())
            assertEquals(listOf(".evidence.lock"), file.parentFile!!.list()!!.toList())
        }
    }

    @Test fun lostAcknowledgementKeepsCompleteImmutablePublishedBytes() {
        val file = File(folder(), "RESPONSE.json")
        val bytes = "{\"answer\":\"数学完整回答\"}".toByteArray(Charsets.UTF_8)
        try { QualityEvidenceFiles.write(file, bytes) { if (it == "after_publish") error("lost ACK") } }
        catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, file.readBytes())
        try { QualityEvidenceFiles.write(file, "replacement".toByteArray()); fail("Overwrite accepted") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun concurrentSameNameHasOnlyOneCompleteWinner() {
        val file = File(folder(), "RECEIPT.json")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val values = listOf("A".repeat(10000), "B".repeat(10000)).map { it.toByteArray() }
            val attempts = values.map { bytes -> pool.submit<Boolean> {
                start.await()
                try { QualityEvidenceFiles.write(file, bytes); true } catch (_: Exception) { false }
            } }
            start.countDown()
            assertEquals(1, attempts.count { it.get(20, TimeUnit.SECONDS) })
            assertTrue(values.any { it.contentEquals(file.readBytes()) })
            assertEquals(setOf("RECEIPT.json", ".evidence.lock"), file.parentFile!!.list()!!.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun legacyEmptyRecordAndNonAsciiInputArePreserved() {
        val old = File(folder(), "OLD.json").also { it.writeBytes(byteArrayOf()) }
        try { QualityEvidenceFiles.write(old, "{}".toByteArray()); fail("Legacy record replaced") }
        catch (_: IllegalStateException) { }
        assertEquals(0L, old.length())
        val file = File(folder(), "NORMAL.json")
        val bytes = "{\"text\":\"∫与上标 x²\"}".toByteArray(Charsets.UTF_8)
        QualityEvidenceFiles.write(file, bytes)
        assertArrayEquals(bytes, file.readBytes())
    }
}
