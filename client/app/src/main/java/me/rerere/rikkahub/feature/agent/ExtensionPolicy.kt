package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigDecimal

@Serializable
internal data class ExtensionScope(val pointer: String, val kind: String, val values: List<String>)

@Serializable
internal data class ExtensionToolPin(
    val name: String,
    val schema: JsonObject,
    val scopes: List<ExtensionScope> = emptyList(),
    // User-authored permission to read again; annotations from the server are never used.
    val verification: String = "consistent_read",
    val imageSha256: String? = null,
)

@Serializable
internal data class ExtensionOAuthBinding(val issuer: String,val resource: String,val clientId: String,val scopes: List<String>,val expiresAt: Long)

@Serializable
internal data class ExtensionConfig(
    val version: Int = 1,
    val name: String,
    val endpoint: String,
    val serverName: String,
    val serverVersion: String,
    val protocol: String = "2025-11-25",
    val tools: List<ExtensionToolPin>,
    val oauth: ExtensionOAuthBinding? = null,
) {
    fun validate(allowLoopback: Boolean = false) {
        require(version == 1 && name.length in 1..100 && serverName.length in 1..100 && serverVersion.length in 1..80)
        ExtensionPolicy.endpoint(endpoint, allowLoopback)
        oauth?.let {
            ExtensionPolicy.endpoint(it.issuer,allowLoopback)
            require(it.resource==endpoint && it.clientId.length in 1..2048 && it.scopes.size<=100 && it.scopes.all { scope -> scope.length in 1..200 } && it.expiresAt>0)
        }
        require(protocol in setOf("2025-03-26", "2025-06-18", "2025-11-25"))
        require(tools.size in 0..32 && tools.map { it.name }.distinct().size == tools.size)
        tools.forEach { tool ->
            require(tool.name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            require(tool.verification in setOf("consistent_read", "sandbox_receipt"))
            ExtensionPolicy.schema(tool.schema)
            require(tool.schema["type"] == JsonPrimitive("object"))
            require(tool.scopes.size <= 32)
            tool.scopes.forEach { rule ->
                require(rule.pointer.startsWith("/") && rule.pointer.length <= 200)
                require(rule.kind in setOf("exact", "directory", "origin"))
                require(rule.values.size in 1..32 && rule.values.all { it.length in 1..2048 })
                if (rule.kind == "directory") rule.values.forEach { ExtensionPolicy.path(it) }
                if (rule.kind == "origin") rule.values.forEach { require(ExtensionPolicy.origin(it) == it) }
            }
            if (tool.verification == "sandbox_receipt") {
                require(tool.name == "run_python")
                require(tool.imageSha256?.matches(Regex("sha256:[a-f0-9]{64}")) == true) { "沙箱镜像及其依赖需明确确认" }
                val props = tool.schema.getValue("properties").jsonObject
                require(props.keys == setOf("script_id", "script_sha256", "input_json", "run_id"))
                require(props["run_id"] == buildJsonObject { put("type","string");put("minLength",64);put("maxLength",64) })
                for (key in listOf("script_id", "script_sha256")) {
                    require(props.getValue(key).jsonObject["enum"]?.jsonArray?.size == 1)
                }
                require(props.getValue("script_sha256").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            }
        }
    }
}

/** A deliberately bounded schema subset. Unsupported validation keywords fail closed. */
internal object ExtensionPolicy {
    private val keywords = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "const",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "description", "title", "default")
    fun endpoint(value: String, allowLoopback: Boolean) {
        require(value.length <= 2048)
        val url = value.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null)
        require(url.isHttps || (allowLoopback && url.scheme == "http" && url.host == "127.0.0.1")) { "扩展需 HTTPS 精确端点；仅隔离 QA 允许本机 HTTP" }
    }
    fun origin(value: String): String {
        val u = value.toHttpUrl()
        require(u.isHttps && u.username.isEmpty() && u.password.isEmpty() && u.fragment == null)
        return "${u.scheme}://${u.host}${if (u.port == 443) "" else ":${u.port}"}"
    }
    fun path(value: String): String {
        require(value.startsWith("/") && !value.contains('\\') && !value.contains('%') && !value.contains('\u0000'))
        require(value.split('/').none { it in setOf(".", "..") })
        return value.trimEnd('/').ifEmpty { "/" }
    }
    fun schema(s: JsonObject, depth: Int = 0) {
        require(depth <= 8 && s.keys.all { it in keywords } && s.toString().length <= 20000) { "不支持的工具 schema；未放宽验证" }
        val type = s["type"]?.jsonPrimitive?.content
        require(type in setOf("object", "array", "string", "integer", "number", "boolean", "null"))
        s["enum"]?.let { require(it is JsonArray && it.size in 1..100) }
        for (key in listOf("minLength", "maxLength", "minItems", "maxItems")) s[key]?.let { require(it.jsonPrimitive.int in 0..65536) }
        for (key in listOf("minimum", "maximum")) s[key]?.let { BigDecimal(it.jsonPrimitive.content) }
        if (type == "object") {
            val properties = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
            require(properties.size <= 64)
            s["required"]?.jsonArray?.forEach { require(it.jsonPrimitive.content in properties) }
            require(s["additionalProperties"] == null || s["additionalProperties"] == JsonPrimitive(false))
            properties.values.forEach { schema(it.jsonObject, depth + 1) }
        }
        if (type == "array") schema(s.getValue("items").jsonObject, depth + 1)
    }
    fun arguments(pin: ExtensionToolPin, args: JsonObject) {
        require(args.toString().toByteArray().size <= 65536)
        validate(pin.schema, args)
        pin.scopes.forEach { scope ->
            var v: JsonElement = args
            scope.pointer.drop(1).split('/').forEach { token ->
                v = (v as? JsonObject)?.get(token.replace("~1", "/").replace("~0", "~")) ?: error("缺少授权范围字段")
            }
            val text = v.jsonPrimitive.also { require(it.isString) }.content
            require(when (scope.kind) {
                "exact" -> text in scope.values
                "directory" -> path(text).let { p -> scope.values.any { path(it).let { root -> p == root || p.startsWith(if (root == "/") root else "$root/") } } }
                "origin" -> origin(text) in scope.values
                else -> false
            }) { "参数超出扩展授权范围" }
        }
    }
    private fun validate(s: JsonObject, v: JsonElement) {
        s["enum"]?.let { require(v in it.jsonArray) }
        s["const"]?.let { require(v == it) }
        when (s.getValue("type").jsonPrimitive.content) {
            "object" -> {
                require(v is JsonObject)
                val properties = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
                require(v.keys.all { it in properties })
                require(s["required"]?.jsonArray.orEmpty().all { it.jsonPrimitive.content in v })
                v.forEach { (key, value) -> validate(properties.getValue(key).jsonObject, value) }
            }
            "array" -> { require(v is JsonArray); bounds(s, v.size, "minItems", "maxItems"); v.forEach { validate(s.getValue("items").jsonObject, it) } }
            "string" -> { require(v is JsonPrimitive && v.isString); bounds(s, v.content.length, "minLength", "maxLength") }
            "boolean" -> require(v is JsonPrimitive && !v.isString && v.booleanOrNull != null)
            "null" -> require(v == JsonNull)
            else -> {
                require(v is JsonPrimitive && !v.isString && v != JsonNull)
                val number = BigDecimal(v.content)
                if (s.getValue("type") == JsonPrimitive("integer")) require(number.stripTrailingZeros().scale() <= 0)
                s["minimum"]?.let { require(number >= BigDecimal(it.jsonPrimitive.content)) }
                s["maximum"]?.let { require(number <= BigDecimal(it.jsonPrimitive.content)) }
            }
        }
    }
    private fun bounds(s: JsonObject, n: Int, min: String, max: String) {
        require(n >= (s[min]?.jsonPrimitive?.int ?: 0) && n <= (s[max]?.jsonPrimitive?.int ?: 65536))
    }
}
