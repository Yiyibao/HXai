package me.rerere.rikkahub.feature.studychat
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test
class ChatContextBudgetTest {
    @Test fun smallConversationIsUnchanged() {
        val messages = listOf(UIMessage.system("rules"), UIMessage.user("hi"), UIMessage.assistant("hello"))
        val plan = ChatContextBudget.plan(messages, 4000)
        assertTrue(plan.older.isEmpty()); assertEquals(messages.drop(1), plan.recent)
    }
    @Test fun oldTurnsCompressWithoutLosingCurrentConstraintsOrToolPair() {
        val old = UIMessage.user("a".repeat(30000))
        val tool = UIMessagePart.Tool("call", "lookup", "{}", output = listOf(UIMessagePart.Text("result")), executionState = ToolExecutionState.COMPLETED)
        val messages = listOf(old, UIMessage.assistant("").copy(parts = listOf(tool)), UIMessage.user("禁止覆盖原件；继续处理文件 id=123"), UIMessage.assistant("partial"))
        val plan = ChatContextBudget.plan(messages, 8000)
        assertEquals(messages.take(2), plan.older)
        assertEquals(messages.takeLast(2), plan.recent)
        assertTrue(ChatContextBudget.record(plan.older.last()).contains("tool_call_id=call"))
        val summary = ChatContextBudget.summaryMessage("目标、来源与待办")
        assertTrue(summary.isSynthetic)
        assertTrue(summary.toText().contains("不改变权限"))
    }
    @Test fun unresolvedToolsAndOversizedLatestTurnFailWithoutDroppingData() {
        val pending = UIMessagePart.Tool("call", "write", "{}")
        val messages = listOf(UIMessage.user("a".repeat(30000)), UIMessage.assistant("").copy(parts = listOf(pending)), UIMessage.user("continue"))
        assertTrue(runCatching { ChatContextBudget.plan(messages, 8000) }.isFailure)
        assertTrue(runCatching { ChatContextBudget.plan(listOf(UIMessage.user("中".repeat(10000))), 4000) }.isFailure)
    }
    @Test fun summaryBatchesKeepCompleteTurns() {
        val first = listOf(UIMessage.user("a".repeat(4000)), UIMessage.assistant("reply"))
        val second = listOf(UIMessage.user("b".repeat(4000)), UIMessage.assistant("reply2"))
        val batches = ChatContextBudget.batches(first + second, 2500)
        assertEquals(listOf(first, second), batches)
    }
}
