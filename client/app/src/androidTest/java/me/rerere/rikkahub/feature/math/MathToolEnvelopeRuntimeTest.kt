package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.feature.agent.TaskResultVerifier
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class MathToolEnvelopeRuntimeTest {
    @Test fun actualToolUsesTwoRequiredFieldsAndIndependentHostRecomputesWireReceipt(): Unit = runBlocking {
        val tool=mathVerifyTool();val schema=tool.parameters() as InputSchema.Obj
        assertEquals(setOf("operation","request"),schema.properties.keys)
        assertEquals(setOf("operation","request"),schema.required!!.toSet())
        val input=buildJsonObject {put("operation","identity");put("request","""{"left":"x+x","right":"2*x"}""")}
        val output=tool.execute(input)
        assertEquals(JsonPrimitive("VERIFIED"),Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject["status"])
        val owner="qa-envelope-${Uuid.random()}"
        val receipt=UIMessagePart.Tool("wire-call","math_verify",input.toString(),output,executionState=ToolExecutionState.COMPLETED)
        val conversation=Conversation(assistantId=Uuid.random(),profileId=owner,
            messageNodes=listOf(UIMessage.assistant("").copy(parts=listOf(receipt)).toMessageNode()))
        val k=GlobalContext.get();val verifier=TaskResultVerifier(k.get(),k.get(),k.get())
        assertTrue(verifier.verify(owner,conversation,receipt))
        val changed=buildJsonObject {put("operation","identity");put("request","""{"left":"x+x","right":"3*x"}""")}
        assertFalse(runCatching { verifier.verify(owner,conversation,receipt.copy(input=changed.toString())) }.getOrDefault(false))
    }
}
