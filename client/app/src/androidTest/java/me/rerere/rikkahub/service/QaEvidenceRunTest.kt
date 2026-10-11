package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

/** No remote model request. Exercises the same evidence writer used by real methods. */
class QaEvidenceRunTest {
    @Test fun freshSyntheticEvidence() {
        val run = QaEvidenceRun("QaEvidenceRunTest.freshSyntheticEvidence")
        run.file("transcript.md").writeText("Synthetic current-run evidence; ${run.runId}")
        assertTrue(run.directory.name == run.runId)
        assertTrue(runCatching { run.file("../outside.md") }.isFailure)
        run.finish("PASS")
    }

    @Test fun failureEvidenceIsRetainedInSeparateMethodDirectory() {
        val run = QaEvidenceRun("QaEvidenceRunTest.failureEvidenceIsRetainedInSeparateMethodDirectory")
        run.file("transcript.md").writeText("Synthetic first failure is retained; ${run.runId}")
        assertTrue(run.directory.parentFile.name != "QaEvidenceRunTest.freshSyntheticEvidence")
        run.finish("FAILED") // Collector must preserve this status even though this assertion test passes.
    }
}
