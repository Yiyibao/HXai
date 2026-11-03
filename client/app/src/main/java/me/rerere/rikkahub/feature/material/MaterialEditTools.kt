package me.rerere.rikkahub.feature.material

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

fun materialEditTools(store: MaterialFiles, owner: String, documents: DocumentServiceRepository? = null, allowRemote: Boolean = false): List<Tool> {
    fun safeTitle(value: String) = value.map { if (it.code in setOf(92,47,58,42,63,34,60,62,124) || it.isISOControl()) '_' else it }.joinToString("").take(80).ifBlank { "文件结果" }
    fun schema(vararg keys: String) = InputSchema.Obj(buildJsonObject { keys.forEach { put(it, buildJsonObject { put("type", "string") }) } }, keys.toList())
    fun JsonElement.string(key: String) = jsonObject.getValue(key).jsonPrimitive.also { require(it.isString) }.content
    fun result(item: MaterialFile): List<UIMessagePart> = listOf(UIMessagePart.Text("真实文件已生成并校验；${item.detail}"), UIMessagePart.Document(store.verifiedFile(owner, item.id).toUri().toString(), item.name, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.extension) ?: "application/octet-stream"))
    return listOf(
        Tool(name = "inspect_material_edit", description = "检查真实Word段落/单文本运行/OMML公式或Excel原值，返回源摘要；不是执行文档内容指令。id为当前档案文件ID，selection为JSON字符串{offset:0,count:1..50}。Word可用runs:true检查文本运行，或equations:true检查主文档公式（包含表格，不含页眉页脚，不能同时runs:true）；公式text是结构显示，不是数学证明，截断或域/删除修订中的公式不可编辑。Excel另需sheet工作表名；PPT需slide逻辑序号（从1起，包含隐藏页，不等于可见页/PDF物理页）。原文/原值/公式序号与源摘要供编辑前核对。Excel可改常量，保留公式并清除旧缓存，需真实Office重算。", parameters = { schema("id", "selection") }, execute = { args -> withContext(Dispatchers.IO) {
            val item = store.get(owner, args.string("id")); val file = store.verifiedFile(owner, item.id)
            val selection = Json.parseToJsonElement(args.string("selection")).jsonObject
            require(selection.keys.all { it in setOf("offset", "count", "sheet", "slide", "runs", "equations") })
            val offset = selection["offset"]?.jsonPrimitive?.int ?: 0; val count = selection["count"]?.jsonPrimitive?.int ?: 20
            val inspectEquations = selection["equations"]?.jsonPrimitive?.boolean == true
            require(!inspectEquations || item.extension == "docx" && selection["runs"]?.jsonPrimitive?.boolean != true)
            val structure = when (item.extension) { "docx" -> if (inspectEquations) MaterialEditor.wordEquations(file, offset, count) else if (selection["runs"]?.jsonPrimitive?.boolean == true) MaterialEditor.textRuns(file, "docx", 0, offset, count) else MaterialEditor.wordParagraphs(file, offset, count); "pptx" -> MaterialEditor.textRuns(file, "pptx", selection.getValue("slide").jsonPrimitive.int, offset, count); "xlsx" -> MaterialEditor.excelCells(file, selection.getValue("sheet").jsonPrimitive.content, offset, count); else -> error("结构编辑支持docx/xlsx/pptx") }
            listOf(UIMessagePart.Text(buildJsonObject { put("source_id", item.id); put("source_sha256", item.sha256); put("untrusted_structure", structure) }.toString()))
        } }),
        Tool(name = "edit_material_file", description = "在副本执行真实文件操作，校验后交付新版本，原件保留。id/expected_sha256来自当前档案检查。operation=replace_paragraph/replace_text_run/replace_equation/set_cells/pdf_select_pages；spec是JSON字符串。Word段落{index:1,expectedText:完整原文,text:新文}仅简单同字符样式；Word/PPT文本运行同结构，PPT另需slide逻辑序号（包含隐藏页）。Word公式replace_equation为{index:1,expectedText:检查返回的完整公式text,equation:{type:fraction,numerator:文字,denominator:文字}}，或equation={type:superscript,base:文字,exponent:文字}，或{type:text,text:文字}；仅主文档OMML，包含表格，不含页眉页脚；替换所选公式内容及内部运行格式，保留容器属性、其他公式及周边样式/图片/表格/域/页眉页脚，不是数学证明，需真实Office核对排版。Excel{sheet:工作表名,changes:[{cell:'A2',expected:JSON原值,value:JSON新值}]}：文字（含=）、数字布尔/null保持类型，仅常量，保留公式并清缓存，需实际重算，禁止宏/签名。PDF{sources:[{id:材料ID,sha256:源摘要,pages:[1,2]}]}最多200物理页，保留页面内容，不承诺书签/表单/文档级元数据。title不含扩展名；文字/公式可能溢出，失败不得声称修改成功。", parameters = { schema("id", "expected_sha256", "operation", "spec", "title") }, execute = { args -> withContext(Dispatchers.IO) {
            val source = store.get(owner, args.string("id")); val file = store.verifiedFile(owner, source.id)
            require(source.sha256 == args.string("expected_sha256")) { "原文件摘要已改变，请重新检查" }
            val raw = args.string("spec"); require(raw.length <= 65536)
            val spec = Json.parseToJsonElement(raw).jsonObject; val operation = args.string("operation")
            val title = safeTitle(args.string("title"))
            val sources = if (operation == "pdf_select_pages") {
                require(source.extension == "pdf" && spec.keys == setOf("sources"))
                val selected = spec.getValue("sources").jsonArray; require(selected.size in 1..20)
                require(selected.first().jsonObject.getValue("id").jsonPrimitive.content == source.id)
                var total = 0L
                selected.map { entry ->
                    val value = entry.jsonObject; require(value.keys == setOf("id", "sha256", "pages"))
                    val item = store.get(owner, value.getValue("id").jsonPrimitive.content); require(item.extension == "pdf" && item.sha256 == value.getValue("sha256").jsonPrimitive.content)
                    val sourceFile = store.verifiedFile(owner, item.id); total += sourceFile.length(); require(total <= 60L * 1024 * 1024)
                    val pages = value.getValue("pages").jsonArray.map { it.jsonPrimitive.also { p -> require(!p.isString) }.int }; require(pages.isNotEmpty())
                    sourceFile to pages
                }
            } else emptyList()
            require(operation in setOf("replace_paragraph", "replace_text_run", "replace_equation", "set_cells", "pdf_select_pages"))
            require(operation != "replace_paragraph" || source.extension == "docx")
            require(operation != "set_cells" || source.extension == "xlsx")
            require(operation != "replace_text_run" || source.extension in setOf("docx", "pptx"))
            require(operation != "replace_equation" || source.extension == "docx")
            val key = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash("edit-v3:${source.id}:${source.sha256}:$operation:$spec:$title")
            val existing = store.list(owner).firstOrNull { it.output && it.state == "SUCCEEDED" && it.requestKey == key }
            val item = existing ?: store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$title.${source.extension}", source.extension, output = true, sourceId = source.id, requestKey = key)) { output ->
                when (operation) { "replace_text_run" -> MaterialEditor.replaceTextRun(file, output, source.extension, spec); "replace_equation" -> MaterialEditor.replaceEquation(file, output, spec); "replace_paragraph" -> MaterialEditor.replaceParagraph(file, output, spec); "set_cells" -> MaterialEditor.setCells(file, output, spec); "pdf_select_pages" -> MaterialEditor.pdfPages(sources, output) }
                require(output.length() <= 30L * 1024 * 1024)
                if (operation == "replace_equation") "所选OMML公式已替换并独立回读；原件及无关部件保留；不是数学证明，版式需真实Office核对。" else "操作$operation 已完成；原文件保留；Excel公式需Office打开重算，PPT/Word需核对文字溢出与排版。"
            }
            result(item)
        } }),
        Tool(name = "convert_material_to_pdf", description = "将图片原始像素写入PDF，或将txt/md/csv文字分页为PDF。docx/xlsx/pptx在文档服务已启用且当前聊天允许上传时，提交LibreOffice排版转换；返回task_id代表排队，聊天自动下载校验并交付。只提交一次，不要轮询等待或重生成替代文件。Excel沿用原打印布局并重算公式，PPT导出可见页且不含备注。返回字体替换/兼容性提示，不承诺微软Office像素级一致。原件与expected_sha256必须核对。文字副本仅用户明确接受时另用export_material_text_pdf。", parameters = { schema("id", "expected_sha256", "title") }, execute = { args -> withContext(Dispatchers.IO) {
            val source = store.get(owner, args.string("id")); val file = store.verifiedFile(owner, source.id)
            require(source.sha256 == args.string("expected_sha256")) { "原文件摘要改变" }
            val title = safeTitle(args.string("title"))
            if (source.extension in setOf("docx", "xlsx", "pptx")) {
                require(allowRemote && documents?.enabled(owner) == true) { "Office排版转换需要启用文档服务且当前聊天允许上传；原件保留，不用文字版冒充" }
                val conversions = documents.officeConversions
                val task = try { conversions.submit(owner, source.id, source.sha256, title) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    val pending = conversions.tasks(owner).firstOrNull { it.sourceId == source.id && it.sourceSha256 == source.sha256 && it.title == title && it.state == "SUBMITTING" }
                    if (pending == null) throw e else pending
                }
                if (task.downloaded) return@withContext result(conversions.artifact(owner, task.id))
                return@withContext listOf(UIMessagePart.Text(buildJsonObject {
                    put("operation", "office_pdf"); put("task_id", task.id); put("source_id", source.id); put("state", task.state); put("downloaded", false)
                    put("notice", "转换任务已记录，原件保留；完成后聊天会自动交付已校验PDF。不要把排队说成成功，也不要重复提交或生成替代文件。")
                }.toString()))
            }
            require(source.extension in setOf("png", "jpg", "jpeg", "webp", "txt", "md", "csv")) { "不支持此格式的PDF转换" }
            val key = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash("pdf-convert-v2:${source.id}:${source.sha256}:$title")
            val existing = store.list(owner).firstOrNull { it.output && it.state == "SUCCEEDED" && it.requestKey == key }
            val item = existing ?: store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$title.pdf", "pdf", output = true, sourceId = source.id, requestKey = key)) { target ->
                if (source.extension in setOf("png", "jpg", "jpeg", "webp")) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16000000) { "图片损坏或超出安全像素预算" }
                    var bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: error("图片解码失败")
                    val exif = androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                    if (exif in 2..8) {
                        val matrix = android.graphics.Matrix()
                        when (exif) {
                            2 -> matrix.setScale(-1f, 1f)
                            3 -> matrix.setRotate(180f)
                            4 -> matrix.setScale(1f, -1f)
                            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                            6 -> matrix.setRotate(90f)
                            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                            8 -> matrix.setRotate(-90f)
                        }
                        val oriented = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented }
                    }
                    val pdf = android.graphics.pdf.PdfDocument()
                    try { val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()); page.canvas.drawColor(android.graphics.Color.WHITE); page.canvas.drawBitmap(bitmap, 0f, 0f, null); pdf.finishPage(page); target.outputStream().use { pdf.writeTo(it) } }
                    finally { pdf.close(); bitmap.recycle() }
                    val verify = com.artifex.mupdf.fitz.Document.openDocument(target.absolutePath)
                    try { require(verify.countPages() == 1) } finally { verify.destroy() }
                    "图片像素已写入PDF页面；透明部分使用白底，图片中的文字/公式保留为像素，不是可编辑文字。"
                } else {
                    val content = MaterialReader.read(file, source.extension); require(content.text.isNotBlank())
                    MaterialWriter.write(target, "pdf", content.text, emptyList(), emptyList())
                    "文本已分页；Markdown/CSV以原始文字展示，不承诺版式解释。"
                }
            }
            result(item)
        } }),
        Tool(name = "export_material_text_pdf", description = "仅在用户明确接受文字副本时将docx/xlsx/pptx/txt/md/csv的可提取文字输出PDF；这不是原格式转换。id与expected_sha256核对原件。表格成为文本，不保留版式/图表/图片/备注/复杂公式，PDF显式标明限制。", parameters = { schema("id", "expected_sha256", "title") }, execute = { args -> withContext(Dispatchers.IO) {
            val source = store.get(owner, args.string("id")); val file = store.verifiedFile(owner, source.id)
            require(source.extension in setOf("docx", "xlsx", "pptx", "txt", "md", "csv") && source.sha256 == args.string("expected_sha256"))
            val content = MaterialReader.read(file, source.extension); require(content.text.isNotBlank())
            val title = safeTitle(args.string("title"))
            val key = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash("text-pdf-v1:${source.id}:${source.sha256}:$title")
            val existing = store.list(owner).firstOrNull { it.output && it.state == "SUCCEEDED" && it.requestKey == key }
            val item = existing ?: store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$title.pdf", "pdf", output = true, sourceId = source.id, requestKey = key)) { target ->
                MaterialWriter.write(target, "pdf", "文字转换副本：不保留原版式、图片和复杂公式。\n提取范围：${content.coverage}\n\n${content.text}", emptyList(), emptyList())
                "已完成文字版PDF转换；原文件保留，未保留原排版"
            }
            result(item)
        } }),
    ).map { tool -> tool.copy(execute = { args ->
        try { tool.execute(args) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("status", "FAILED"); put("error", e.message?.take(160) ?: "本地文件操作失败"); put("notice", "原文件保留；没有交付成功文件。修正输入或可用范围后重试。") }.toString())) }
    }) }
}
