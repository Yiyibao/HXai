package me.rerere.rikkahub.feature.interactive

import org.junit.Assert.*
import org.junit.Test

class SurfaceRenderingV4Test {
    @Test fun multiSeriesAndTableMetadataAreBounded() {
        val chart = surface("""{"id":"x","type":"line_chart","selectable":true,"series":[{"label":"Actual","points":[[1,12],[2,16]]},{"label":"Plan","points":[[1,10],[2,20]]}],"yUnit":"USD","digits":1,"annotations":[{"axis":"y","value":15,"label":"Target"}]}""")
        val parsed = InteractiveSurface.validate(chart)
        InteractiveSurface.validateState(parsed, kotlinx.serialization.json.buildJsonObject { put("x", kotlinx.serialization.json.JsonPrimitive(3)) })
        assertTrue(runCatching { InteractiveSurface.validateState(parsed, kotlinx.serialization.json.buildJsonObject { put("x", kotlinx.serialization.json.JsonPrimitive(4)) }) }.isFailure)
        val table = surface("""{"id":"x","type":"table","columns":["Name","Cost"],"rows":[["A",12]],"columnTypes":["text","currency"],"units":["","USD"],"highlight":[{"row":0,"column":1,"tone":"warning"}]}""")
        assertTrue(runCatching { InteractiveSurface.validate(table) }.isSuccess)
        assertTrue(runCatching { InteractiveSurface.validate(table.replace("\"row\":0", "\"row\":1")) }.isFailure)
        assertTrue(runCatching { InteractiveSurface.validate(chart.replace("\"digits\":1", "\"digits\":7")) }.isFailure)
    }
    @Test fun layoutsAndStylesValidateAllReferences() {
        val raw = """{"version":4,"title":"Layout","presentation":"inline","density":"compact","template":"analysis","components":[{"id":"a","type":"metric","label":"A","value":12,"tone":"info","variant":"emphasis"},{"id":"b","type":"text","text":"B"}],"composition":{"type":"row","weights":[2,1],"children":["a","b"]}}"""
        assertTrue(runCatching { InteractiveSurface.validate(raw) }.isSuccess)
        listOf(raw.replace("\"a\",\"b\"", "\"a\",\"a\""), raw.replace("[2,1]", "[0,1]"), raw.replace("\"compact\"", "\"tiny\""), raw.replace("\"info\"", "\"purple\"")).forEach { assertTrue(runCatching { InteractiveSurface.validate(it) }.isFailure) }
    }
    private fun surface(component: String, version: Int = 4) = """{"version":$version,"title":"Analysis","components":[$component]}"""
    @Test fun semanticComponentsAreVersionedAndValidated() {
        listOf(
            """{"id":"x","type":"rich_text","text":"**Data**"}""",
            """{"id":"x","type":"heading","text":"Title","level":2}""",
            """{"id":"x","type":"metric","label":"Total","value":12,"unit":"USD"}""",
            """{"id":"x","type":"callout","text":"Check","tone":"warning"}""",
            """{"id":"x","type":"steps","items":[{"title":"First","text":"Data"}]}""",
            """{"id":"x","type":"comparison","items":[{"title":"A","text":"Data"}]}""",
            """{"id":"x","type":"code_block","code":"print(1)","language":"python","collapsed":true}""",
        ).forEach { assertTrue(runCatching { InteractiveSurface.validate(surface(it)) }.isSuccess); assertTrue(runCatching { InteractiveSurface.validate(surface(it, 3)) }.isFailure) }
        listOf(
            """{"id":"x","type":"heading","text":"Title","level":4}""",
            """{"id":"x","type":"metric","label":"Total","value":null}""",
            """{"id":"x","type":"steps","items":[{"title":"A","text":"B","html":true}]}""",
            """{"id":"x","type":"code_block","code":"x","collapsed":"true"}""",
        ).forEach { assertTrue(runCatching { InteractiveSurface.validate(surface(it)) }.isFailure) }
    }
}
