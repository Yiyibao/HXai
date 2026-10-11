package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*

internal object SurfaceData {
    val chartFields = setOf("series", "xUnit", "yUnit", "digits", "annotations")
    val tableFields = setOf("columnTypes", "units", "digits", "highlight")
    val imageFields = setOf("sourceUrl", "sourceTitle", "aspectRatio", "fit")
    const val PROMPT = "v4图表可用series:[{label,points:[[x,y],...]}]代替points，1–6序列合计最多500点，支持图例切换、点选提示；选择序号按序列顺序展平。可加xUnit/yUnit字符串,digits:0..6,annotations:[{axis:x/y,value:数值,label}]最多8个。table可加columnTypes:[text/number/currency/percent/date]、units字符串数组，长度等于列数，digits:0..6；percent将小数乘100显示；highlight:[{row:原始行下标,column:列下标,tone:info/success/warning/error}]最多32个。表头支持本地排序，固定表头，数字右对齐。image可加sourceUrl真实来源页、sourceTitle、aspectRatio:0.2..5、fit:contain/cover；完整查看保留原图。"
    fun pointCount(c: JsonObject) = c["series"]?.jsonArray?.sumOf { it.jsonObject.getValue("points").jsonArray.size } ?: c.getValue("points").jsonArray.size
    fun points(c: JsonObject): JsonArray = c["series"]?.jsonArray?.let { series -> JsonArray(series.flatMap { it.jsonObject.getValue("points").jsonArray }) } ?: c.getValue("points").jsonArray
    fun validateChart(c: JsonObject) {
        require(("series" in c) != ("points" in c))
        c["series"]?.jsonArray?.let { series ->
            require(series.size in 1..6)
            series.forEach { item ->
                val s = item.jsonObject; require(s.keys == setOf("label", "points"))
                require(s.getValue("label").jsonPrimitive.let { it.isString && it.content.length in 1..200 })
                require(s.getValue("points").jsonArray.isNotEmpty())
            }
        }
        require(pointCount(c) in 1..500)
        c["annotations"]?.jsonArray?.let { annotations ->
            require(annotations.size <= 8)
            annotations.forEach {
                val a = it.jsonObject; require(a.keys == setOf("axis", "value", "label"))
                require(a.getValue("axis").jsonPrimitive.let { v -> v.isString && v.content in setOf("x", "y") })
                require(a.getValue("value").jsonPrimitive.let { v -> !v.isString && v.double.isFinite() && kotlin.math.abs(v.double) <= 1e12 })
                require(a.getValue("label").jsonPrimitive.let { v -> v.isString && v.content.length <= 200 })
            }
        }
        listOf("xUnit", "yUnit").forEach { key -> c[key]?.jsonPrimitive?.let { require(it.isString && it.content.length <= 40) } }
        digits(c)
    }
    fun digits(c: JsonObject) { c["digits"]?.jsonPrimitive?.let { require(!it.isString && it.int in 0..6) } }
    fun validateTable(c: JsonObject) {
        val count = c.getValue("columns").jsonArray.size
        c["columnTypes"]?.jsonArray?.let { types -> require(types.size == count); types.forEach { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("text", "number", "currency", "percent", "date")) } }
        c["units"]?.jsonArray?.let { units -> require(units.size == count); units.forEach { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 40) } }
        c["highlight"]?.jsonArray?.let { cells ->
            require(cells.size <= 32)
            cells.forEach {
                val cell = it.jsonObject; require(cell.keys == setOf("row", "column", "tone"))
                require(cell.getValue("row").jsonPrimitive.let { v -> !v.isString && v.int in c.getValue("rows").jsonArray.indices })
                require(cell.getValue("column").jsonPrimitive.let { v -> !v.isString && v.int in 0 until count })
                require(cell.getValue("tone").jsonPrimitive.let { v -> v.isString && v.content in setOf("info", "success", "warning", "error") })
            }
        }
        digits(c)
    }
    fun validateImage(c: JsonObject) {
        c["sourceUrl"]?.jsonPrimitive?.let { val uri = java.net.URI(it.content); require(it.isString && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && it.content.length <= 16000) }
        c["sourceTitle"]?.jsonPrimitive?.let { require(it.isString && it.content.length <= 200) }
        c["fit"]?.jsonPrimitive?.let { require(it.isString && it.content in setOf("contain", "cover")) }
        c["aspectRatio"]?.jsonPrimitive?.let { require(!it.isString && it.double.isFinite() && it.double in 0.2..5.0) }
    }
}
