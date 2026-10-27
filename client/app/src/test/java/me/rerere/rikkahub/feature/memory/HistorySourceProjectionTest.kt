package me.rerere.rikkahub.feature.memory

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.feature.studychat.ChatContextBudget
import org.junit.Assert.*
import org.junit.Test

class HistorySourceProjectionTest {
    @Test fun deletedTurnIncludesAllFollowingAssistantMessagesUntilNextUser() {
        val old = UIMessage.user("old")
        val messages = listOf(old, UIMessage.assistant("answer"), UIMessage.assistant("continued answer"),
            UIMessage.user("new"), UIMessage.assistant("new answer"))
        assertEquals(messages.takeLast(2), HistorySourceProjection.filter(messages, setOf(old.id.toString()), false))
        assertEquals(5, messages.size)
    }

    @Test fun untraceableSummaryCannotResetADeletedTurnIntoVisibleContext() {
        val old = UIMessage.user("old")
        val summary = ChatContextBudget.summaryMessage("derived from old")
        val messages = listOf(old, UIMessage.assistant("old answer"), summary,
            UIMessage.assistant("summary answer"), UIMessage.user("current"))
        assertEquals(listOf(messages.last()), HistorySourceProjection.filter(messages, setOf(old.id.toString()), true))
    }

    @Test fun healthySummariesAndSystemDataRemainUnchanged() {
        val messages = listOf(UIMessage.system("instruction"), ChatContextBudget.summaryMessage("safe history"),
            UIMessage.assistant("answer"), UIMessage.user("current"))
        assertEquals(messages, HistorySourceProjection.filter(messages, emptySet(), false))
    }

    @Test fun sameTextWithNewIdentityIsNotGuessedToBeDeleted() {
        val old = UIMessage.user("identical words")
        val fresh = UIMessage.user("identical words")
        assertNotEquals(old.id, fresh.id)
        assertEquals(listOf(fresh), HistorySourceProjection.filter(listOf(old, UIMessage.assistant("old answer"), fresh),
            setOf(old.id.toString()), true))
    }

    @Test fun protectedSummaryOnlyHistoryProjectsToEmptyWithoutModifyingIt() {
        val messages = listOf(ChatContextBudget.summaryMessage("unknown source"), UIMessage.assistant("unknown derived answer"))
        assertTrue(HistorySourceProjection.filter(messages, emptySet(), true).isEmpty())
        assertEquals("unknown derived answer", messages.last().toText())
    }
}
