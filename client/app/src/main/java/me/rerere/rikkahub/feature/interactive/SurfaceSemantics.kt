package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*

internal object SurfaceSemantics {
    val fields = mapOf(
        "rich_text" to setOf("text"),
        "heading" to setOf("text", "level"),
        "metric" to setOf("label", "value", "unit", "delta"),
        "callout" to setOf("title", "text", "tone"),
        "steps" to setOf("items"),
        "comparison" to setOf("items"),
        "code_block" to setOf("code", "language", "collapsed"),
    )

    fun validate(c: JsonObject) {
        fun text(key: String, limit: Int = 4000, optional: Boolean = false) {
            if (optional && key !in c) return
            val value = c.getValue(key).jsonPrimitive
            require(value.isString && value.content.length <= limit) { "Invalid $key" }
        }
        when (c.getValue("type").jsonPrimitive.content) {
            "rich_text" -> text("text")
            "heading" -> {
                text("text", 200)
                c["level"]?.jsonPrimitive?.let { require(!it.isString && it.intOrNull?.let { level -> level in 1..3 } == true) }
            }
            "metric" -> {
                text("label", 200); text("unit", 40, true); text("delta", 200, true)
                val value = c.getValue("value").jsonPrimitive
                require(value.isString && value.content.length <= 200 || !value.isString && value.doubleOrNull?.isFinite() == true && kotlin.math.abs(value.double) <= 1e12)
            }
            "callout" -> {
                text("title", 200, true); text("text")
                c["tone"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("neutral", "info", "success", "warning", "error")) }
            }
            "steps", "comparison" -> {
                val items = c.getValue("items").jsonArray
                require(items.size in 1..12)
                items.forEach {
                    val item = it.jsonObject
                    require(item.keys == setOf("title", "text"))
                    require(item.getValue("title").jsonPrimitive.let { v -> v.isString && v.content.length in 1..200 })
                    require(item.getValue("text").jsonPrimitive.let { v -> v.isString && v.content.length <= 4000 })
                }
            }
            "code_block" -> {
                text("code", 16000); text("language", 40, true)
                c["collapsed"]?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) }
            }
        }
    }
}
