package me.rerere.tts.provider

import org.junit.Assert.*
import org.junit.Test

class TTSPrivacyTest {
    @Test fun remoteBodyAndCauseAreExcludedWhileRetryStatusSurvives() {
        val error = TTSProviderException("BODY_SECRET_CANARY", 429, IllegalStateException("CAUSE_SECRET_CANARY"))
        assertTrue(error.isRetryable)
        assertEquals(429, error.statusCode)
        assertFalse(error.stackTraceToString().contains("SECRET_CANARY"))
        assertNull(error.cause)
        assertFalse(TTSProviderException("ignored", 401).isRetryable)
    }
}
