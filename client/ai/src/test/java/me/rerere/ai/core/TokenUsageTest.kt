package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageTest {
    @Test
    fun changingReportedCountsInvalidatesAnOlderTotal() {
        val updated = TokenUsage(promptTokens = 10, completionTokens = 2, totalTokens = 12)
            .merge(TokenUsage(completionTokens = 5))
        assertEquals(10, updated.promptTokens)
        assertEquals(5, updated.completionTokens)
        assertNull(updated.totalTokens)
    }

    @Test
    fun explicitZeroReplacesPreviousReportedCounts() {
        val updated = TokenUsage(promptTokens = 10, completionTokens = 2, cachedTokens = 4, totalTokens = 12)
            .merge(TokenUsage(promptTokens = 0, completionTokens = 0, cachedTokens = 0, totalTokens = 0))
        assertEquals(TokenUsage(0, 0, 0, 0), updated)
    }

    @Test
    fun cacheOnlyUpdateKeepsTheSameRequestTotal() {
        val updated = TokenUsage(promptTokens = 10, completionTokens = 2, totalTokens = 12)
            .merge(TokenUsage(cachedTokens = 3))
        assertEquals(10, updated.promptTokens)
        assertEquals(2, updated.completionTokens)
        assertEquals(3, updated.cachedTokens)
        assertEquals(12, updated.totalTokens)
    }

    @Test
    fun unknownOrOverflowingCountsCannotProduceAKnownAggregate() {
        assertEquals(20, sumTokenCounts(12, 8))
        assertNull(sumTokenCounts(null, 8))
        assertNull(sumTokenCounts(12, null))
        assertNull(sumTokenCounts(Int.MAX_VALUE, 1))
    }
}
