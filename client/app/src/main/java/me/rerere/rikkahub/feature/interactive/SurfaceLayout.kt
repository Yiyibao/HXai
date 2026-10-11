package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*

internal object SurfaceLayout {
    const val PROMPT = "v4样式：组件可加variant:plain/subtle/emphasis/outline，tone:neutral/info/success/warning/error，density:compact/comfortable。顶层presentation:inline(默认，融入正文)/panel(独立工具)，density，template:analysis/comparison/explanation/exploration。analysis自动分组指标，comparison用于方案，exploration分组参数，explanation用于说明。复杂布局用composition替代layout：组件id字符串或{type:column/row/grid/tabs/accordion,children:[id或布局节点],label可选}；row可加weights正整数数组，grid可加columns:1/2/3。tabs/accordion的每个child必须为带label的布局节点。所有组件恰好引用一次，最多5层64节点；窄屏自动纵排。保持克制层级，不把每个段落包装成卡片。"
    val styleFields = setOf("variant", "tone", "density")
    fun validateStyle(c: JsonObject) {
        mapOf("variant" to setOf("plain", "subtle", "emphasis", "outline"), "tone" to setOf("neutral", "info", "success", "warning", "error"), "density" to setOf("compact", "comfortable")).forEach { (key, values) ->
            c[key]?.jsonPrimitive?.let { require(it.isString && it.content in values) }
        }
    }
    fun validateComposition(root: JsonElement, ids: Set<String>) {
        var count = 0
        val used = mutableListOf<String>()
        fun visit(node: JsonElement, depth: Int) {
            require(++count <= 64 && depth <= 5)
            if (node is JsonPrimitive) { require(node.isString && node.content in ids); used += node.content; return }
            val n = node.jsonObject
            require(n.keys.all { it in setOf("type", "children", "label", "weights", "columns") })
            val type = n.getValue("type").jsonPrimitive.content
            require(type in setOf("column", "row", "grid", "tabs", "accordion"))
            val children = n.getValue("children").jsonArray
            require(children.size in 1..24)
            n["label"]?.jsonPrimitive?.let { require(it.isString && it.content.length <= 200) }
            n["weights"]?.jsonArray?.let { weights -> require(type == "row" && weights.size == children.size); weights.forEach { require(!it.jsonPrimitive.isString && it.jsonPrimitive.int in 1..12) } }
            n["columns"]?.jsonPrimitive?.let { require(type == "grid" && !it.isString && it.int in 1..3) }
            if (type in setOf("tabs", "accordion")) { require(children.size <= 8); children.forEach { require(it.jsonObject["label"]?.jsonPrimitive?.let { label -> label.isString && label.content.isNotEmpty() } == true) } }
            children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        require(used.size == ids.size && used.toSet().size == used.size && used.toSet() == ids)
    }
}
