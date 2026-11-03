package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OcrFailureBoundaryTest {
    @Test
    fun `private provider failures never become recognized text`() = runBlocking {
        val privateDetails = "file:///private/profile-a/scan.png recognized secret api-key=fixture"
        val result = ocrTextOrFailure { throw IllegalStateException(privateDetails) }
        assertFalse(result.contains(privateDetails))
        assertFalse(result.contains("profile-a"))
        assertFalse(result.contains("recognized secret"))
        assertFalse(result.contains("api-key"))
    }

    @Test
    fun `cancellation preserves control flow and original exception`() = runBlocking {
        val cancellation = CancellationException("synthetic private path")
        try {
            ocrTextOrFailure { throw cancellation }
            throw AssertionError("OCR cancellation was converted to normal text")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `cancelling a suspended recognizer cannot continue downstream`() = runBlocking {
        var continued = false
        val job = launch {
            ocrTextOrFailure { awaitCancellation() }
            continued = true
        }
        yield()
        job.cancel()
        job.join()
        assertFalse(continued)
    }
}
