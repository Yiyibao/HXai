package me.rerere.rikkahub.feature.studychat

import android.content.Context
import android.annotation.SuppressLint
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.feature.interactive.InteractiveSurface

/** Derived cache keyed by the exact visible branch. Deleted/suppressed data cannot match it. */
class ChatContextCache(context: Context) {
    private val prefs = context.getSharedPreferences("chat_context_cache", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("chat_context_settings", Context.MODE_PRIVATE)
    fun budget(profile: String): Int = settings.getInt("budget:$profile", 16000).coerceIn(4000, 64000)
    @SuppressLint("UseKtx") // Keep commit's Boolean result; KTX edit discards it.
    fun setBudget(profile: String, budget: Int) {
        require(budget in 4000..64000)
        check(settings.edit().putInt("budget:$profile", budget).commit()) { "上下文设置保存失败" }
    }
    fun key(profile: String, conversation: String) = InteractiveSurface.hash("$profile:$conversation")
    fun fingerprint(messages: List<UIMessage>, model: String, system: List<UIMessage>): String = InteractiveSurface.hash(buildJsonObject {
        put("model", model)
        put("system", buildJsonArray { system.forEach { add(it.toText()) } })
        put("messages", buildJsonArray { messages.forEach { add(ChatContextBudget.record(it)) } })
    }.toString())
    fun read(key: String, fingerprint: String): String? = prefs.getString(key, null)?.let { raw -> runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        obj.getValue("summary").jsonPrimitive.content.takeIf { obj.getValue("fingerprint").jsonPrimitive.content == fingerprint }
    }.getOrNull() }
    @SuppressLint("UseKtx") // Failed persistence must be reported, not silently ignored.
    fun write(key: String, fingerprint: String, summary: String) {
        require(summary.isNotBlank() && summary.length <= 12000)
        check(prefs.edit().putString(key, buildJsonObject { put("fingerprint", fingerprint); put("summary", summary) }.toString()).commit()) { "摘要缓存保存失败，原聊天保留" }
    }
}
