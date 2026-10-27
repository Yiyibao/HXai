package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** Shared by creation and editing. A bounded presentation tree, never executable XML or a proof. */
internal object MaterialEquationXml {
    fun body(eq: JsonObject): String = Encoder().apply { node(eq, 1) }.output.toString()

    private class Encoder {
        val output = StringBuilder()
        private var nodes = 0
        private var characters = 0

        private fun part(name: String, value: JsonElement, depth: Int) {
            output.append("<m:").append(name).append('>')
            node(value, depth + 1)
            output.append("</m:").append(name).append('>')
        }

        fun node(value: JsonElement, depth: Int) {
            require(depth <= 8 && ++nodes <= 64) { "office_equation_tree_limit" }
            if (value is JsonPrimitive) {
                require(value.isString) { "office_equation_text_required" }
                val text = value.content
                characters += text.length
                require(characters <= 2000) { "office_equation_text_limit" }
                var offset = 0
                while (offset < text.length) {
                    val code = Character.codePointAt(text, offset)
                    require(code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF || code == 9 || code == 10 || code == 13) { "office_equation_invalid_character" }
                    offset += Character.charCount(code)
                }
                output.append("<m:r><m:t xml:space=\"preserve\">")
                text.forEach { character ->
                    when (character) {
                        '&' -> output.append("&amp;")
                        '<' -> output.append("&lt;")
                        '>' -> output.append("&gt;")
                        '"' -> output.append("&quot;")
                        else -> output.append(character)
                    }
                }
                output.append("</m:t></m:r>")
                return
            }
            val equation = value as? JsonObject ?: error("office_equation_object_required")
            val type = equation.getValue("type").jsonPrimitive.also { require(it.isString) }.content
            val keys = when (type) {
                "fraction" -> setOf("type", "numerator", "denominator")
                "superscript" -> setOf("type", "base", "exponent")
                "subscript" -> setOf("type", "base", "subscript")
                "subsuperscript" -> setOf("type", "base", "subscript", "exponent")
                "radical" -> if ("degree" in equation) setOf("type", "degree", "radicand") else setOf("type", "radicand")
                "row" -> setOf("type", "items")
                "text" -> setOf("type", "text")
                else -> error("office_equation_type_unsupported")
            }
            require(equation.keys == keys) { "office_equation_keys_invalid" }
            when (type) {
                "fraction" -> {
                    output.append("<m:f>")
                    part("num", equation.getValue("numerator"), depth)
                    part("den", equation.getValue("denominator"), depth)
                    output.append("</m:f>")
                }
                "superscript", "subscript", "subsuperscript" -> {
                    val tag = when (type) { "superscript" -> "sSup"; "subscript" -> "sSub"; else -> "sSubSup" }
                    output.append("<m:").append(tag).append('>')
                    part("e", equation.getValue("base"), depth)
                    if (type != "superscript") part("sub", equation.getValue("subscript"), depth)
                    if (type != "subscript") part("sup", equation.getValue("exponent"), depth)
                    output.append("</m:").append(tag).append('>')
                }
                "radical" -> {
                    output.append("<m:rad>")
                    if ("degree" in equation) part("deg", equation.getValue("degree"), depth)
                    else output.append("<m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/>")
                    part("e", equation.getValue("radicand"), depth)
                    output.append("</m:rad>")
                }
                "row" -> {
                    val items = equation.getValue("items").jsonArray
                    require(items.size in 1..32) { "office_equation_row_limit" }
                    items.forEach { node(it, depth + 1) }
                }
                "text" -> {
                    val text = equation.getValue("text").jsonPrimitive
                    require(text.isString)
                    node(text, depth + 1)
                }
            }
        }
    }
}
