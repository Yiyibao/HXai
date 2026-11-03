package me.rerere.rikkahub.feature.agent

import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.feature.material.MaterialFiles

/** Persisted message receipt and local artifact readback are independent of tool return values. */
internal class TaskResultVerifier(private val materials: MaterialFiles,private val memories: LearningMemoryStore,
    private val documents: me.rerere.rikkahub.feature.material.DocumentServiceRepository) {
    suspend fun verify(owner: String, persisted: Conversation, completed: UIMessagePart.Tool): Boolean {
        require(persisted.profileId == owner)
        val receipt = persisted.currentMessages.flatMap { it.getTools() }.singleOrNull {
            it.toolCallId == completed.toolCallId && it.toolName == completed.toolName && it.input == completed.input
        } ?: error("Missing durable tool receipt")
        require(receipt.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED && receipt.output == completed.output) { "Durable result differs from executed result" }
        val contract=TrustedToolRegistry.require(receipt.toolName)
        val failed = receipt.output.filterIsInstance<UIMessagePart.Text>().any { text ->
            val value = runCatching { Json.parseToJsonElement(text.text) as? JsonObject }.getOrNull()
            value?.containsKey("error") == true || value?.get("status")?.jsonPrimitive?.content in setOf("FAILED","UNSUPPORTED","DISPROVED","unknown")
        }
        if(failed) return false
        if(receipt.toolName=="save_explicit_memory") {
            val args=Json.parseToJsonElement(receipt.input).jsonObject
            val result=receipt.output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.let {
                Json.parseToJsonElement(it.text).jsonObject
            } ?: return false
            if(result["saved"]?.jsonPrimitive?.booleanOrNull!=true) return false
            val state=memories.read(owner)
            if(state.recoveryBlocked) return false
            val item=state.items.singleOrNull { it.id==result["memory_id"]?.jsonPrimitive?.content } ?: return false
            if(item.origin!="USER_EXPLICIT" || item.status !in setOf("ACTIVE","CANDIDATE") ||
                item.type.name!=args["type"]?.jsonPrimitive?.content || item.topicKey!=args["topic_key"]?.jsonPrimitive?.content ||
                item.factKey!=args["fact_key"]?.jsonPrimitive?.content || item.content!=args["content"]?.jsonPrimitive?.content) return false
            return item.sourceIds.isNotEmpty() && item.sourceIds.all { id ->
                val source=state.sources.singleOrNull { it.id==id && it.valid && it.explicitRequest &&
                    it.conversationId==persisted.id.toString() } ?: return@all false
                val user=persisted.currentMessages.singleOrNull { it.id.toString()==source.messageId &&
                    it.role==me.rerere.ai.core.MessageRole.USER } ?: return@all false
                source.hash==memories.fingerprint(owner,"content:${user.toText()}") &&
                    Regex("^(请)?(重新)?记住").containsMatchIn(user.toText().trim()) && item.content in user.toText()
            }
        }
        if(receipt.toolName=="parse_material_enhanced") {
            val args=Json.parseToJsonElement(receipt.input).jsonObject
            val result=receipt.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.let {
                Json.parseToJsonElement(it.text).jsonObject
            } ?: return false
            val first=documents.tasks(owner).singleOrNull { it.id==result["task_id"]?.jsonPrimitive?.content } ?: return false
            val batchId=result["batch_id"]?.jsonPrimitive?.content
            val tasks=if(batchId==null) listOf(first) else {
                val batch=documents.batch(owner,batchId)
                if(batch.cancelled || batch.sourceId!=first.sourceId) return false
                documents.batchTasks(owner,batch)
            }
            val start=args.getValue("start_page").jsonPrimitive.content.toInt()
            val end=args.getValue("end_page").jsonPrimitive.content.toInt()
            val pages=mutableListOf<Int>()
            for(task in tasks) {
                if(task.sourceId!=args.getValue("id").jsonPrimitive.content || task.cancelRequested || !task.downloaded) return false
                val document=documents.read(owner,task.id)
                if(document.failedPages.isNotEmpty()) return false
                pages+=document.processedPages
            }
            return pages== (start..end).toList()
        }
        val documents = receipt.output.filterIsInstance<UIMessagePart.Document>()
        val images = receipt.output.filterIsInstance<UIMessagePart.Image>()
        val urls = documents.map { it.url } + images.map { it.url }
        if(urls.isNotEmpty()) {
            val owned = materials.list(owner)
            urls.forEach { url ->
                val item = owned.singleOrNull { materials.file(it).toUri().toString() == url } ?: return false
                materials.verifiedFile(owner,item.id)
                if(item.state != "SUCCEEDED") return false
                val officeOperation=me.rerere.rikkahub.feature.material.remoteOfficeOperation(receipt.toolName)
                if(officeOperation!=null) {
                    val args=Json.parseToJsonElement(receipt.input).jsonObject
                    val source=materials.get(owner,args.getValue("id").jsonPrimitive.content)
                    materials.verifiedFile(owner,source.id)
                    val extension=if(officeOperation=="xlsx_recalculate") "xlsx" else "pdf"
                    if(source.sha256!=args.getValue("expected_sha256").jsonPrimitive.content ||
                        item.sourceId!=source.id || !item.output || item.extension!=extension) return false
                    if(officeOperation=="xlsx_recalculate" || source.extension in setOf("docx","xlsx","pptx")) {
                        val task=this.documents.officeConversions.tasks(owner).singleOrNull {
                            it.operation==officeOperation && it.outputId==item.id && it.sourceId==source.id &&
                                it.sourceSha256==source.sha256 && it.downloaded && !it.cancelRequested
                        } ?: return false
                        if(this.documents.officeConversions.artifact(owner,task.id).id!=item.id) return false
                    }
                }
            }
            // This verifies the step's owned bytes, not the goal. TaskPlanner separately reads
            // actual content and evaluates the complete goal before TaskStore can succeed.
            return contract.verifier==TrustedToolRegistry.Verifier.ARTIFACT_READBACK ||
                me.rerere.rikkahub.feature.material.remoteOfficeOperation(receipt.toolName)!=null && contract.verifier==TrustedToolRegistry.Verifier.REMOTE_READBACK
        }
        // Pure local read/presentation tools have no unverified external material effect.
        return contract.verifier in setOf(TrustedToolRegistry.Verifier.LOCAL_RECEIPT,TrustedToolRegistry.Verifier.INTERACTION) &&
            contract.risk in setOf(TrustedToolRegistry.Risk.READ,TrustedToolRegistry.Risk.COMPUTE,TrustedToolRegistry.Risk.PRESENTATION)
    }
}
