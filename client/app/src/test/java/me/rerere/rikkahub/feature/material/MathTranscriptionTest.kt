package me.rerere.rikkahub.feature.material

import org.junit.Assert.*
import org.junit.Test

class MathTranscriptionTest {
    @Test fun invisibleOrInvalidTextCannotBecomeConfirmedInput() {
        for(text in listOf("", " \t\r\n", "\u00a0\u200b\u2060", "\u0000", "\uD800"))
            assertTrue(text.length.toString(),runCatching { MathTranscription.requireContent(text,4000) }.isFailure)
    }
    @Test fun originalUnicodeAndLineBreaksArePreserved() {
        for(text in listOf("x² − 1 = 0", "🙂\n证明\t∀x", "x\u200dy", "a".repeat(4000)))
            MathTranscription.requireContent(text,4000)
        assertTrue(runCatching { MathTranscription.requireContent("a".repeat(4001),4000) }.isFailure)
    }
}
