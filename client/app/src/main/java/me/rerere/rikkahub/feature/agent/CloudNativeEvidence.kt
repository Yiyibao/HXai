package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.material.MaterialFiles

/** Only called after the original TaskResultVerifier accepted a durable tool message. */
internal object CloudNativeEvidence {
    suspend fun result(owner: String,task: AgentTask,inputHash: String,tool: UIMessagePart.Tool,
        materials: MaterialFiles): JsonObject {
        val urls=tool.output.mapNotNull { when(it) {
            is UIMessagePart.Document -> it.url
            is UIMessagePart.Image -> it.url
            else -> null
        } }.toSet()
        val artifacts=materials.list(owner).filter {
            android.net.Uri.fromFile(materials.file(it)).toString() in urls
        }.map { item ->
            materials.verifiedFile(owner,item.id)
            val content=if(item.extension in setOf("txt","md","docx","xlsx","pptx","pdf")) materials.readContent(owner,item.id).text else ""
            buildJsonObject {
                put("id",item.id);put("format",item.extension);put("sha256",item.sha256);put("size",item.size)
                put("sourceId",item.sourceId?.let(::JsonPrimitive) ?: JsonNull)
                put("sourceHash",item.sourceId?.let { materials.get(owner,it).sha256 }?.let(::JsonPrimitive) ?: JsonNull)
                put("content",content.take(16000));put("contentComplete",content.length<=16000)
            }
        }
        return buildJsonObject {
            put("tool",tool.toolName);put("inputHash",inputHash);put("nativeTaskId",task.id);put("nativeFence",task.fence)
            put("output",Json.encodeToJsonElement(tool.output));put("artifacts",JsonArray(artifacts))
            put("verifier","ORIGINAL_NATIVE_VERIFIER")
        }
    }
}
