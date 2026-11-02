package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import java.util.UUID

/** Authenticated authority observations, not new local dispatches. Source request identities
 * are namespaced by authority, original task and physical request; polling cannot double count.
 */
internal object HostedUsage {
    private val roles=setOf("planning","main","semantic_acceptance","credential_validation")
    fun ingest(store: AgentTaskStore,owner: String,id: String,authority: String,configuration: JsonObject,rows: JsonArray) {
        var changed=false
        store.accountTransaction { db ->
            val task=store.get(owner,id)
            for(element in rows) {
                val row=element.jsonObject
                val remoteId=row.getValue("request_id").jsonPrimitive.content
                require(remoteId.length in 1..160)
                val role=row.getValue("role").jsonPrimitive.content
                require(role in roles)
                val local=UUID.nameUUIDFromBytes("hosted:$authority:$id:$remoteId".toByteArray()).toString()
                fun count(name: String): Int?=row[name]?.takeUnless { it==JsonNull }?.jsonPrimitive?.int.also { require(it==null || it>=0) }
                val usage=TokenUsage(promptTokens=count("input_tokens"),completionTokens=count("output_tokens"),cachedTokens=count("cached_tokens"),
                    totalTokens=count("total_tokens"),costUsd=row["costUsd"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content,
                    costSource=row["costSource"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content)
                val provenance=when(row.getValue("provenance").jsonPrimitive.content) {
                    "provider_reported" -> "reported"
                    "estimated" -> "estimated"
                    "unknown" -> "unknown"
                    else -> error("无效云端用量来源")
                }
                val values=listOf(usage.promptTokens,usage.completionTokens,usage.cachedTokens,usage.totalTokens)
                val old=db.rawQuery("SELECT task_id,provenance,prompt_tokens,completion_tokens,cached_tokens,total_tokens FROM usage WHERE request_id=?",arrayOf(local)).use { cursor ->
                    if(!cursor.moveToFirst())null else {
                        require(cursor.getString(0)==id)
                        cursor.getString(1) to (2..5).map { if(cursor.isNull(it))null else cursor.getInt(it) }
                    }
                }
                val source=configuration.getValue("endpoint").jsonPrimitive.content
                // Supplier-reported money has a protocol/origin contract. No current phone
                // tariff is retroactively applied to a hosted request that did not freeze one.
                val basis=if(source=="https://openrouter.ai/api/v1/chat/completions" && configuration["protocol"]==JsonPrimitive("openai_chat"))
                    TrustedCost.basis("https://openrouter.ai/api/v1",configuration.getValue("wireModel").jsonPrimitive.content,JsonObject(emptyMap()))
                    else TrustedCost.unknown("hosted_tariff_not_frozen")
                if(old==null) {
                    db.insertOrThrow("usage",null,ContentValues().apply {
                        put("request_id",local);put("task_id",id);put("role","cloud_$role");put("provenance",provenance)
                        put("prompt_tokens",usage.promptTokens);put("completion_tokens",usage.completionTokens)
                        put("cached_tokens",usage.cachedTokens);put("total_tokens",usage.totalTokens)
                        put("created_at",row.getValue("createdAtMs").jsonPrimitive.long)
                        put("provider_id",task.providerId);put("model_id",task.modelId);put("reasoning",task.reasoning)
                        put("accepted_configuration_reference",task.configurationReference)
                        put("configuration_reference","hosted:"+AgentTaskStore.hash(authority+":"+remoteId+":"+row.getValue("configurationHash").toString()))
                    })
                    AgentCostLedger.begin(db,local,basis)
                    changed=true
                } else {
                    if(old.first==provenance)require(old.second==values) { "云端重复回执改写了已记录用量" }
                    else if(provenance!="unknown") {
                        require(old.first=="unknown" || old.first=="estimated" && provenance=="reported") { "云端用量来源不能降级" }
                        db.update("usage",ContentValues().apply { put("provenance",provenance)
                            put("prompt_tokens",usage.promptTokens);put("completion_tokens",usage.completionTokens)
                            put("cached_tokens",usage.cachedTokens);put("total_tokens",usage.totalTokens)
                        },"request_id=? AND task_id=?",arrayOf(local,id))
                        changed=true
                    }
                }
                if(row.getValue("settled").jsonPrimitive.boolean) {
                    val before=AgentCostLedger.receipt(db,local)
                    AgentCostLedger.finish(db,local,usage)
                    if(AgentCostLedger.receipt(db,local)!=before)changed=true
                }
            }
        }
        if(changed)AgentTaskStore.usageChanges.tryEmit(Unit)
    }
}
