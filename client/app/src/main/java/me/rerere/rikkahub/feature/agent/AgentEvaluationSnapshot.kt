package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/** Explicit local evidence projection. Never loads credentials, calls a provider or synchronizes cloud state. */
internal object AgentEvaluationSnapshot {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun capture(db: SQLiteDatabase, owner: String, id: String, revision: Long, fence: Long): JsonObject {
        val task = db.rawQuery("SELECT conversation_id,request_id,provider_id,model_id,reasoning,configuration_revision,state,revision,fence,manual_pause,intent_hash FROM tasks WHERE owner=? AND id=?", arrayOf(owner,id)).use { row ->
            check(row.moveToFirst()) { "任务不属于当前档案" }
            require(row.getLong(7)==revision && row.getLong(8)==fence) { "任务修订已变化，请刷新详情" }
            require(!row.getString(1).startsWith("role:")) { "辅助角色不能冒充主Chat评测" }
            buildJsonObject {
                put("id",id); put("owner",owner); put("conversationId",row.getString(0)); put("requestId",row.getString(1))
                put("providerId",row.getString(2)); put("modelId",row.getString(3)); put("reasoning",row.getString(4))
                put("configRevision",row.getString(5)); put("state",row.getString(6)); put("revision",revision.toString())
                put("fence",fence.toString()); put("manualPaused",row.getInt(9)!=0); put("intentSha256",row.getString(10))
            }
        }
        var budget = MAX_BYTES.toLong()
        fun rows(table: String, columns: List<String>, order: String, maximum: Int): JsonArray {
            val sizes = columns.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }
            db.rawQuery("SELECT COUNT(*),COALESCE(SUM($sizes),0),COALESCE(MAX($sizes),0) FROM $table WHERE task_id=?",arrayOf(id)).use {
                check(it.moveToFirst())
                require(it.getLong(0)<=maximum && it.getLong(1)<=budget && it.getLong(2)<=262144) { "完整评测证据过大，未截断" }
                budget-=it.getLong(1)
            }
            return buildJsonArray {
                db.rawQuery("SELECT ${columns.joinToString(",")} FROM $table WHERE task_id=? ORDER BY $order",arrayOf(id)).use { row ->
                    while(row.moveToNext()) add(buildJsonObject {
                        columns.forEachIndexed { index, key -> put(key,if(row.isNull(index)) JsonNull else JsonPrimitive(row.getString(index))) }
                    })
                }
            }
        }
        val plan = rows("plans",listOf("goal","goal_revision","planned_revision","plan_json","acceptance_verified"),"task_id",1)
        val control = rows("task_control",listOf("authorization","grant_epoch","input_blocked","device_blocked","device_confirmation","cancel_requested"),"task_id",1)
        val steps = rows("plan_steps",listOf("step_id","tool","purpose","ordinal","call_id","state"),"ordinal",128)
        // Raw tool results may contain private documents or arbitrary remote data. Export references only.
        val calls = rows("calls",listOf("call_id","tool","input_hash","state","result_hash","capability","grant_epoch"),"rowid",1000)
        val usage = AgentUsageSnapshot.capture(db,owner,id,null,null,"requests","evaluation-local")
        return buildJsonObject {
            put("task",task); put("plans",plan); put("control",control); put("steps",steps); put("calls",calls)
            put("usage",JsonObject(usage - "capturedAtMs"))
        }.also { require(AgentUsageSnapshot.utf8(it.toString()).size<=MAX_BYTES) { "完整评测证据过大，未截断" } }
    }

    /** Selected branch only. Other tasks, user attachments, hidden reasoning and tool bodies are excluded. */
    fun answer(conversation: Conversation?, owner: String, id: String, conversationId: String): JsonObject {
        if(conversation==null) return buildJsonObject { put("status","CONVERSATION_UNAVAILABLE"); put("messages",JsonArray(emptyList())) }
        require(conversation.profileId==owner && conversation.id.toString()==conversationId) { "会话归属变化" }
        val messages = conversation.currentMessages.filter { it.role==MessageRole.ASSISTANT && it.generationTaskId==id }
        fun visibleText(message: me.rerere.ai.ui.UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        require(messages.size<=200 && messages.sumOf { visibleText(it).length }<=200000) { "当前回答过长，未截断" }
        return buildJsonObject {
            put("status",if(messages.isEmpty()) "ANSWER_UNAVAILABLE_ON_SELECTED_BRANCH" else "SELECTED_BRANCH_SNAPSHOT")
            put("textProjection","TEXT_PARTS_ONLY")
            put("messages",buildJsonArray {
                for(message in messages) add(buildJsonObject {
                    put("id",message.id.toString()); put("generationState",message.generationState?.let(::JsonPrimitive) ?: JsonNull)
                    put("text",visibleText(message)); put("textSha256",AgentTaskStore.hash(visibleText(message)))
                    put("toolCallIds",JsonArray(message.parts.filterIsInstance<UIMessagePart.Tool>().map { JsonPrimitive(it.toolCallId) }))
                })
            })
        }
    }

    fun assemble(evidence: JsonObject, answer: JsonObject): JsonObject = buildJsonObject {
        put("contractVersion",1); put("kind","hxai_agent_evaluation_snapshot")
        put("scope","NATIVE_PERSISTED_TASK_AND_SELECTED_VISIBLE_ANSWER")
        put("capturedAtMs",System.currentTimeMillis().toString()); put("evidence",evidence); put("answer",answer)
        put("executionAttestation","LOCAL_EXPORT_NOT_PROVIDER_OR_CLOUD_ATTESTATION")
        put("modelQuality","NOT_EVALUATED"); put("independentReview","NOT_EVALUATED")
        put("endpoint","NOT_EXPORTED"); put("toolResults","HASH_REFERENCES_ONLY; verify original artifacts separately")
    }
}
