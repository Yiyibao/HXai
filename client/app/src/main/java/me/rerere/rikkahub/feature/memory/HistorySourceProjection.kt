package me.rerere.rikkahub.feature.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/** A detached provider view. Never changes the durable conversation or its receipts. */
internal object HistorySourceProjection {
    fun filter(messages: List<UIMessage>, hiddenIds: Set<String>, suppressSummaries: Boolean): List<UIMessage> {
        var hiddenTurn = false
        return messages.filter { message ->
            if (message.role == MessageRole.USER) {
                hiddenTurn = message.id.toString() in hiddenIds || (suppressSummaries && message.isHistorySummary)
            }
            !hiddenTurn
        }
    }
}
