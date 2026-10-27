package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MaterialEquationXmlTest {
    private val m = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun equation(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun root(value: JsonObject) = SafeOfficeXml.parse("<m:oMath xmlns:m=\"$m\">${MaterialEquationXml.body(value)}</m:oMath>".toByteArray())
    private fun text(value: JsonObject) = MaterialWordContent.text(root(value))

    @Test fun legacyEquationsRemainNativeAndEscapedTextRemainsLiteral() {
        assertEquals("[公式 (a)/(b)]", text(equation("""{"type":"fraction","numerator":"a","denominator":"b"}""")))
        assertEquals("[公式 (x)^(2)]", text(equation("""{"type":"superscript","base":"x","exponent":"2"}""")))
        val literal = "  <m:f>&\"𝑥  "
        val output = root(buildJsonObject { put("type", "text"); put("text", literal) })
        assertEquals(literal, output.textContent)
        assertEquals(0, output.getElementsByTagNameNS(m, "f").length)
        assertEquals("preserve", (output.getElementsByTagNameNS(m, "t").item(0) as org.w3c.dom.Element).getAttributeNS("http://www.w3.org/XML/1998/namespace", "space"))
    }
    @Test fun nestedFractionsPowersRadicalsAndRowsHaveRealEditableStructure() {
        val value = equation("""{"type":"fraction","numerator":{"type":"subsuperscript","base":"x","subscript":"n","exponent":"2"},"denominator":{"type":"radical","degree":"3","radicand":{"type":"row","items":["a","+",{"type":"subscript","base":"b","subscript":"i"}]}}}""")
        val result = root(value)
        assertEquals("[公式 ((x)_(n)^(2))/(root[3](a+(b)_(i)))]", MaterialWordContent.text(result))
        for (tag in listOf("f", "sSubSup", "rad", "sSub")) assertEquals(tag, 1, result.getElementsByTagNameNS(m, tag).length)
        val squareRoot = root(equation("""{"type":"radical","radicand":"x"}"""))
        assertEquals("[公式 root[2](x)]", MaterialWordContent.text(squareRoot))
        assertEquals("1", (squareRoot.getElementsByTagNameNS(m, "degHide").item(0) as org.w3c.dom.Element).getAttributeNS(m, "val"))
    }
    @Test fun unknownKeysAndNonTextLeavesCannotBeSilentlyDiscarded() {
        val invalid = listOf(
            """{"type":"text","text":"x","xml":"<m:f/>"}""", """{"type":"fraction","numerator":"x"}""",
            """{"type":"text","text":{"type":"text","text":"x"}}""", """{"type":"text","text":12}""",
            """{"type":"superscript","base":null,"exponent":"2"}""", """{"type":"row","items":[true]}""",
            """{"type":"row","items":[]}""", """{"type":"row","items":"x"}""", """{"type":"unknown","text":"x"}"""
        )
        invalid.forEach { value -> assertTrue(value, runCatching { MaterialEquationXml.body(equation(value)) }.isFailure) }
    }
    @Test fun depthNodesAndCharacterBoundariesAreEnforcedWithoutRejectingTheirLimits() {
        fun row(items: List<JsonElement>) = buildJsonObject { put("type", "row"); put("items", JsonArray(items)) }
        var deep: JsonElement = JsonPrimitive("x")
        repeat(7) { deep = row(listOf(deep)) }
        assertEquals("[公式 x]", text(deep.jsonObject))
        assertTrue(runCatching { MaterialEquationXml.body(row(listOf(deep))) }.isFailure)
        assertEquals("[公式 ${"x".repeat(31)}]", text(row(List(31) { row(listOf(JsonPrimitive("x"))) })))
        assertTrue(runCatching { MaterialEquationXml.body(row(List(32) { row(listOf(JsonPrimitive("x"))) })) }.isFailure)
        assertTrue(runCatching { MaterialEquationXml.body(row(List(33) { JsonPrimitive("x") })) }.isFailure)
        assertEquals("[公式 ${"x".repeat(2000)}]", text(buildJsonObject { put("type", "text"); put("text", "x".repeat(2000)) }))
        assertTrue(runCatching { MaterialEquationXml.body(buildJsonObject { put("type", "text"); put("text", "x".repeat(2001)) }) }.isFailure)
    }
    @Test fun malformedUnicodeAndForbiddenXmlCharactersNeverBecomeReplacementCharacters() {
        for (value in listOf("\u0000", "\u0001", "\uD800", "\uDC00", "\uFFFE", "\uFFFF")) {
            assertTrue(runCatching { MaterialEquationXml.body(buildJsonObject { put("type", "text"); put("text", value) }) }.isFailure)
        }
        val allowed = "𝑥\tα\nβ\rγ"
        assertEquals(allowed.replace('\r', '\n'), root(buildJsonObject { put("type", "text"); put("text", allowed) }).textContent)
    }
}
