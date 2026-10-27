package me.rerere.rikkahub.feature.material

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class MaterialSourceBinding(val id: String, val sha256: String,
    val receipt: String? = null, val quote: String? = null)

/** References are created from independently extracted bytes, never model-supplied page labels. */
internal object MaterialProvenance {
    suspend fun bind(store: MaterialFiles, owner: String, entries: JsonArray): List<MaterialSourceBinding> {
        require(entries.size <= 20) { "每份成果最多20个来源区间" }
        return entries.map { entry ->
            val value = entry.jsonObject
            require(value.keys == setOf("id", "sha256", "offset", "count", "quote")) { "来源须含id/sha256/offset/count/quote" }
            fun text(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val page = store.readSourceTextPage(owner, text("id"), text("sha256"),
                value.getValue("offset").jsonPrimitive.int, value.getValue("count").jsonPrimitive.int)
            require(text("quote").isNotBlank() && page.getValue("untrusted_file_data").jsonPrimitive.content == text("quote")) {
                "来源逐字引用与真实提取区间不符"
            }
            MaterialSourceBinding(text("id"), text("sha256"), page.getValue("source_receipt").toString(), text("quote"))
        }.also { require(it.sumOf { binding -> binding.quote?.length ?: 0 } <= 24000) { "引用总长超限" } }
    }
    suspend fun verify(store: MaterialFiles, owner: String, references: List<MaterialSourceBinding>): JsonArray {
        require(references.size <= 20)
        return JsonArray(references.map { binding ->
            val source = store.get(owner, binding.id)
            require(source.sha256 == binding.sha256) { "成果引用的来源版本已改变" }
            store.verifiedFile(owner, binding.id)
            if (binding.receipt != null) {
                val receipt = Json.parseToJsonElement(binding.receipt).jsonObject
                val locator = receipt.getValue("locator").jsonObject
                val start = locator.getValue("start").jsonPrimitive.int
                val end = locator.getValue("end").jsonPrimitive.int
                require(receipt.getValue("owner").jsonPrimitive.content == owner &&
                    receipt.getValue("material_id").jsonPrimitive.content == binding.id &&
                    receipt.getValue("source_sha256").jsonPrimitive.content == binding.sha256)
                store.revalidateSourceTextPage(owner, binding.id, binding.sha256, start, end - start,
                    receipt, requireNotNull(binding.quote)).getValue("source_receipt")
            } else buildJsonObject {
                put("owner", owner); put("material_id", binding.id); put("source_sha256", binding.sha256)
                put("scope", "immutable_source_bytes_only")
            }
        })
    }
    fun citations(references: List<MaterialSourceBinding>): String = references.mapIndexed { index, binding ->
        val receipt = binding.receipt?.let { Json.parseToJsonElement(it).jsonObject }
        val locations = receipt?.get("source_locations")?.jsonArray.orEmpty().map { it.jsonObject }.map {
            "${it.getValue("kind").jsonPrimitive.content}:${it.getValue("number").jsonPrimitive.int}"
        }.distinct().joinToString(", ")
        "[${index + 1}] version=${binding.id}; SHA256=${binding.sha256}; " +
            (if (locations.isNotEmpty()) locations else "extracted UTF16 ${receipt?.get("locator")}; no physical/printed page claim") +
            "; coverage=${receipt?.get("coverage")?.jsonPrimitive?.content.orEmpty()}"
    }.joinToString("\n")
}
