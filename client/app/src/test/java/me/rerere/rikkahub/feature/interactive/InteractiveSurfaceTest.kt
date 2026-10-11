package me.rerere.rikkahub.feature.interactive

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class InteractiveSurfaceTest {
    private val definition = """{"version":1,"title":"预算","components":[{"id":"amount","type":"slider","label":"人数","min":1,"max":10,"value":2},{"id":"total","type":"calculation","label":"合计","parameter":"amount","a":100,"b":20},{"id":"go","type":"button","label":"继续分析","action":"continue"}]}"""
    @Test fun acceptedResultKeepsVersionAndStableDigest() = runBlocking {
        val output = interactiveUiTool().execute(buildJsonObject { put("definition", definition) }).single() as UIMessagePart.Text
        val result = Json.parseToJsonElement(output.text).jsonObject
        assertEquals("interactive_surface", result["kind"]?.jsonPrimitive?.content)
        assertEquals(InteractiveSurface.hash(definition), result["revision"]?.jsonPrimitive?.content)
        assertEquals(1, result["definition"]?.jsonObject?.get("version")?.jsonPrimitive?.int)
    }
    @Test fun rejectsCodeUnknownActionsAndCrossSurfaceReferences() {
        listOf(definition.replace("\"continue\"", "\"execute_shell\""), definition.replace("\"parameter\":\"amount\"", "\"parameter\":\"other\""), definition.replace("\"type\":\"slider\"", "\"type\":\"html\""), definition.replace("\"max\":10", "\"max\":1"), definition.replace("\"version\":1", "\"version\":5")).forEach {
            assertTrue(runCatching { InteractiveSurface.validate(it) }.isFailure)
        }
    }
    @Test fun stateCannotAddFieldsOrEscapeLimits() {
        val surface = InteractiveSurface.validate(definition)
        assertEquals(3, InteractiveSurface.validateState(surface, buildJsonObject { put("amount", 3) })["amount"]?.jsonPrimitive?.int)
        listOf("{}", "{\"amount\":11}", "{\"amount\":3,\"credential\":\"x\"}", "{\"amount\":\"3\"}").forEach {
            assertTrue(runCatching { InteractiveSurface.validateState(surface, Json.parseToJsonElement(it).jsonObject) }.isFailure)
        }
    }
    @Test fun streamCheckpointPreservesClientStateOnlyForSameDefinition() {
        val values = buildJsonObject { put("amount", 7) }
        val tool = UIMessagePart.Tool("surface", InteractiveSurface.TOOL, definition, metadata = buildJsonObject { put(InteractiveSurface.STATE_KEY, values) })
        val original = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool))
        val stale = original.copy(parts = listOf(tool.copy(metadata = null)))
        val restored = InteractiveSurface.mergeClientState(listOf(stale), listOf(original)).single().parts.single() as UIMessagePart.Tool
        assertEquals(values, restored.metadata?.get(InteractiveSurface.STATE_KEY))
        val changed = stale.copy(parts = listOf(tool.copy(input = "other", metadata = null)))
        val different = InteractiveSurface.mergeClientState(listOf(changed), listOf(original)).single().parts.single() as UIMessagePart.Tool
        assertNull(different.metadata)
    }
    @Test fun invalidToolDefinitionReturnsRecoverableFailureWithoutSurface() = runBlocking {
        val result = interactiveUiTool().execute(buildJsonObject { put("definition", "{invalid}") }).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("FAILED", payload.getValue("status").jsonPrimitive.content)
        assertNull(payload["kind"])
    }

    @Test fun legacyReferenceUsesToolIdentityAndSharedStateProtocol() {
        val args = Json.parseToJsonElement(definition).jsonObject
        val ref = UIMessagePart.InteractiveSurfaceRef("old_surface_id", "预算", definition)
        val tool = UIMessagePart.Tool("actual_tool_id", InteractiveSurface.CREATE_TOOL, args.toString(),
            output = listOf(ref), executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED)
        val output = Json.parseToJsonElement(InteractiveSurface.clientOutput(tool)).jsonObject
        assertEquals(InteractiveSurface.hash(definition), output.getValue("revision").jsonPrimitive.content)
        assertEquals(InteractiveSurface.validate(definition), output.getValue("definition"))
        val values = buildJsonObject { put("amount", 7) }
        val message = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool.copy(metadata = buildJsonObject { put(InteractiveSurface.STATE_KEY, values) })))
        val updated = message.copy(parts = listOf(tool))
        val merged = InteractiveSurface.mergeClientState(listOf(updated), listOf(message)).single().parts.single() as UIMessagePart.Tool
        assertEquals(values, merged.metadata?.get(InteractiveSurface.STATE_KEY))
        assertTrue(runCatching { InteractiveSurface.clientOutput(tool.copy(output = emptyList())) }.isFailure)
    }

}
