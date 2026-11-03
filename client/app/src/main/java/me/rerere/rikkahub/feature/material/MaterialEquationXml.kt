package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** The same bounded OMML codec is usable by Android writing and standalone Office editors. */
internal object MaterialEquationXml {
    fun body(eq: JsonObject): String {
        require(eq.values.sumOf { it.jsonPrimitive.content.length } <= 2000)
        fun value(key: String) = eq[key]?.jsonPrimitive?.content ?: error("公式缺少 $key")
        fun run(text: String): String {
            require(text.none { it.code < 32 && it !in "\n\r\t" }) { "正文含无效控制字符" }
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return "<m:r><m:t>$escaped</m:t></m:r>"
        }
        return when (value("type")) {
            "fraction" -> "<m:f><m:num>${run(value("numerator"))}</m:num><m:den>${run(value("denominator"))}</m:den></m:f>"
            "superscript" -> "<m:sSup><m:e>${run(value("base"))}</m:e><m:sup>${run(value("exponent"))}</m:sup></m:sSup>"
            "text" -> run(value("text"))
            else -> error("支持 fraction/superscript/text 公式")
        }
    }
}
