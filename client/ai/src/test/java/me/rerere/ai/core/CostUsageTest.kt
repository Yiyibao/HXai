package me.rerere.ai.core

import org.junit.Assert.*
import org.junit.Test
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.stream.SseEvent

class CostUsageTest {
    @Test fun decoderParsesMoneyAsDecimalTextWithoutInventingTrustedOrigin() {
        val result=ChatCompletionsStreamDecoder().accept(SseEvent(data="""{"choices":[],"usage":{"cost":0.000123}}"""))
        val usage=result.chunks.filterIsInstance<me.rerere.ai.ui.StreamChunk.Usage>().single().usage
        assertEquals("0.000123",usage.costUsd);assertNull(usage.costSource)
    }
    @Test fun lateCostOnlyChunkPreservesCountersWithoutInventingOrigin() {
        val merged=TokenUsage(promptTokens=10,completionTokens=2).merge(TokenUsage(costUsd="0.001"))
        assertEquals(10,merged.promptTokens);assertEquals("0.001",merged.costUsd);assertNull(merged.costSource)
    }
    @Test fun changedCountersDropAnEarlierCostWithoutNewEvidence() {
        val merged=TokenUsage(promptTokens=10,costUsd="0.1",costSource="old").merge(TokenUsage(promptTokens=20))
        assertNull(merged.costUsd);assertNull(merged.costSource)
    }
}
