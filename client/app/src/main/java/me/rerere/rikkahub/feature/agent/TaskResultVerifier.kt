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
        if(contract.verifier==TrustedToolRegistry.Verifier.EXTENSION_READBACK) {
            return ExtensionMcpRuntime.verify(owner,receipt)
        }
        if(contract.verifier==TrustedToolRegistry.Verifier.MCP_FIXTURE_READBACK) {
            return SyntheticMcpRuntime.verify(owner,receipt)
        }
        if(contract.verifier==TrustedToolRegistry.Verifier.SOURCE_READBACK) {
            if(contract.risk!=TrustedToolRegistry.Risk.READ || receipt.output.size!=1) return false
            val text=receipt.output.singleOrNull() as? UIMessagePart.Text ?: return false
            val args=Json.parseToJsonElement(receipt.input).jsonObject
            fun value(key: String)=args.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val result=Json.parseToJsonElement(text.text).jsonObject
            if(result["status"]?.jsonPrimitive?.content!="SUCCEEDED") return false
            val fresh=when(receipt.toolName) {
                "read_math_work" -> materials.mathWorkInputs.readForTool(owner, args)
                "review_math_work" -> materials.mathWorkInputs.review(owner, args)
                "read_material_source_location" -> {
                    require(args.keys==setOf("id","expected_sha256","kind","number","offset","count"))
                    materials.readSourceLocation(owner,value("id"),value("expected_sha256"),value("kind"),
                        value("number").toInt(),value("offset").toInt(),value("count").toInt())
                }
                "search_material_text" -> {
                    require(args.keys==setOf("id","expected_sha256","query","offset","limit"))
                    materials.searchSourceText(owner,value("id"),value("expected_sha256"),value("query"),
                        value("offset").toInt(),value("limit").toInt())
                }
                "search_material_library" -> {
                    require(args.keys==setOf("query","cursor","limit","source_limit"))
                    materials.searchLibraryText(owner,value("query"),value("cursor"),value("limit").toInt(),value("source_limit").toInt())
                }
                else -> return false
            }
            // Compare the complete page, not just hit receipts: empty results, scanned sources,
            // omissions, catalog identity, coverage and pagination are part of the result too.
            return fresh["status"]?.jsonPrimitive?.content=="SUCCEEDED" && result==fresh
        }
        if(contract.verifier==TrustedToolRegistry.Verifier.EXACT_MATH) {
            if(receipt.toolName!="math_verify" || contract.risk!=TrustedToolRegistry.Risk.COMPUTE || receipt.output.size!=1) return false
            val text=receipt.output.singleOrNull() as? UIMessagePart.Text ?: return false
            return try {
                me.rerere.rikkahub.feature.math.MathVerification.accepts(
                    Json.parseToJsonElement(receipt.input).jsonObject, Json.parseToJsonElement(text.text).jsonObject)
            } catch(_: IllegalArgumentException) { false }
        }
        val failed = receipt.output.filterIsInstance<UIMessagePart.Text>().any { text ->
            val value = runCatching { Json.parseToJsonElement(text.text) as? JsonObject }.getOrNull()
            value?.containsKey("error") == true || value?.get("status")?.jsonPrimitive?.content in setOf("FAILED","UNSUPPORTED","DISPROVED","unknown")
        }
        if(failed) return false
        if(receipt.toolName in setOf("index_material_chapters","read_material_chapter","search_material_chapter")) {
            val args=Json.parseToJsonElement(receipt.input).jsonObject
            fun value(key: String)=args.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val result=receipt.output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.let {
                Json.parseToJsonElement(it.text).jsonObject
            } ?: return false
            val fresh=when(receipt.toolName) {
                "index_material_chapters" -> materials.chapterIndex(owner,value("id"),value("expected_sha256"),value("offset").toInt(),value("limit").toInt())
                "read_material_chapter" -> materials.readChapter(owner,value("id"),value("expected_sha256"),value("index_sha256"),
                    value("chapter_id"),value("offset").toInt(),value("count").toInt())
                else -> materials.searchChapter(owner,value("id"),value("expected_sha256"),value("index_sha256"),
                    value("chapter_id"),value("query"),value("offset").toInt(),value("limit").toInt())
            }
            if(result["status"]?.jsonPrimitive?.content!="SUCCEEDED" || fresh["status"]?.jsonPrimitive?.content!="SUCCEEDED") return false
            if(receipt.toolName=="index_material_chapters") {
                if(result["index_storage"]?.jsonPrimitive?.content !in setOf("CREATED","REVALIDATED","REBUILT_FROM_VERIFIED_SOURCE")) return false
                return JsonObject(result-"index_storage")==JsonObject(fresh-"index_storage")
            }
            return result==fresh
        }
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
        if (receipt.toolName == me.rerere.rikkahub.feature.interactive.InteractiveSurface.MATH_TOOL) {
            return me.rerere.rikkahub.feature.interactive.verifyMathPlotDelivery(materials, owner,
                Json.parseToJsonElement(receipt.input).jsonObject, receipt.output)
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
                me.rerere.rikkahub.feature.material.MaterialProvenance.verify(materials, owner, item.sourceReferences)
                if (receipt.toolName == "create_material_file") {
                    val args = Json.parseToJsonElement(receipt.input).jsonObject
                    val content = Json.parseToJsonElement(args.getValue("content").jsonPrimitive.content).jsonObject
                    val parentId = content["parent_id"]?.jsonPrimitive?.content
                    if (item.sourceId != parentId) return false
                    val parent = parentId?.let { materials.get(owner, it) }
                    if (parent != null) {
                        materials.verifiedFile(owner, parent.id)
                        if (parent.sha256 != content["parent_sha256"]?.jsonPrimitive?.content) return false
                    }
                    val references = content["sources"]?.let {
                        me.rerere.rikkahub.feature.material.MaterialProvenance.bind(materials, owner, it as kotlinx.serialization.json.JsonArray)
                    } ?: parent?.sourceReferences.orEmpty()
                    if (item.sourceReferences != references) return false
                }
                if (receipt.toolName == "edit_material_file") {
                    val args = Json.parseToJsonElement(receipt.input).jsonObject
                    val source = materials.get(owner, args.getValue("id").jsonPrimitive.content)
                    materials.verifiedFile(owner, source.id)
                    if (item.sourceId != source.id || source.sha256 != args.getValue("expected_sha256").jsonPrimitive.content) return false
                    if (args.getValue("operation").jsonPrimitive.content == "pdf_select_pages") {
                        val spec = Json.parseToJsonElement(args.getValue("spec").jsonPrimitive.content).jsonObject
                        val inputs = (spec.getValue("sources") as kotlinx.serialization.json.JsonArray).map { value ->
                            val entry = value.jsonObject
                            val id = entry.getValue("id").jsonPrimitive.content
                            val current = materials.get(owner, id)
                            if (current.sha256 != entry.getValue("sha256").jsonPrimitive.content) return false
                            materials.verifiedFile(owner, id) to (entry.getValue("pages") as kotlinx.serialization.json.JsonArray).map {
                                it.jsonPrimitive.content.toInt()
                            }
                        }
                        me.rerere.rikkahub.feature.material.MaterialPdfAssembly.verify(inputs, materials.verifiedFile(owner, item.id))
                    }
                }
                val operation=me.rerere.rikkahub.feature.material.remoteOfficeOperation(receipt.toolName)
                if(operation!=null) {
                    val args=Json.parseToJsonElement(receipt.input).jsonObject
                    val source=materials.get(owner,args.getValue("id").jsonPrimitive.content)
                    materials.verifiedFile(owner,source.id)
                    val extension=if(operation==me.rerere.rikkahub.feature.material.XLSX_RECALCULATE) "xlsx" else "pdf"
                    if(source.sha256!=args.getValue("expected_sha256").jsonPrimitive.content ||
                        item.sourceId!=source.id || !item.output || item.extension!=extension) return false
                    if(operation==me.rerere.rikkahub.feature.material.XLSX_RECALCULATE || source.extension in setOf("docx","xlsx","pptx")) {
                        val metadata=receipt.output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.let {
                            Json.parseToJsonElement(it.text).jsonObject
                        } ?: return false
                        val remoteTask=this.documents.officeConversions.tasks(owner).singleOrNull {
                            it.id==metadata["task_id"]?.jsonPrimitive?.content && it.operation==operation &&
                                it.outputId==item.id && it.sourceId==source.id && it.sourceSha256==source.sha256 &&
                                it.downloaded && !it.cancelRequested
                        } ?: return false
                        if(metadata["operation"]?.jsonPrimitive?.content!=operation ||
                            metadata["source_id"]?.jsonPrimitive?.content!=source.id ||
                            this.documents.officeConversions.artifact(owner,remoteTask.id).id!=item.id) return false
                        if(operation==me.rerere.rikkahub.feature.material.XLSX_RECALCULATE &&
                            metadata["calculation"]!=this.documents.officeConversions.calculationEvidence(owner,remoteTask.id)) return false
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
