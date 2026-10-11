package me.rerere.rikkahub.feature.material

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp

@Composable fun DocumentServicePanel(owner: String, materials: List<MaterialFile>, repository: DocumentServiceRepository = koinInject()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val change by repository.changes.collectAsState()
    val config = remember(owner, revision) { repository.config(owner) }
    var configure by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<MaterialFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<ParsedDocument?>(null) }
    var documentTaskId by remember { mutableStateOf<String?>(null) }
    val tasks = remember(owner, revision, change) { repository.tasks(owner) }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { me.rerere.rikkahub.feature.diagnostics.Diagnostics.track(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.DOCUMENT) { action() } }
            catch (_: kotlinx.coroutines.CancellationException) { throw kotlinx.coroutines.CancellationException() }
            catch (e: Exception) { message = e.message?.take(180) ?: "文档服务操作失败" }
            finally { busy = false; revision++ }
        }
    }
    TextButton(onClick = { configure = true }, enabled = !busy) { Text(if (config.enabled) "自建增强解析：已启用" else "设置自建增强解析") }
    if (config.enabled) {
        Text("增强处理会把选定原文件上传到你的服务器，服务端仅解析指定页。CPU Basic · 使用 MinerU；公式仍需核对原页。")
        TextButton(enabled = !busy, onClick = { run {
            val capability = repository.capabilities(owner)
            message = if (capability["ready"]?.toString() == "true") "服务已连接，Basic 解析可用；每次最多5页，原文件最多30 MiB。" else "服务已连接，解析暂未就绪。"
        } }) { Text("检查连接与能力") }
        materials.filter { it.state == "SUCCEEDED" && it.extension in setOf("pdf", "png", "jpg", "jpeg", "webp") }.forEach { material ->
            TextButton(enabled = !busy, onClick = { chosen = material }) { Text("增强解析：${material.name}") }
        }
        tasks.takeLast(10).forEach { task ->
            Text("${materials.firstOrNull { it.id == task.sourceId }?.name ?: "材料"} · 物理页 ${task.start}–${task.end} · ${task.state}${if (task.downloaded) " · 已校验下载" else ""}")
            Row {
                if (task.remoteId != null && !task.downloaded) TextButton(enabled = !busy, onClick = { run { message = repository.refresh(owner, task.id).state } }) { Text("刷新") }
                if (task.state in setOf("SUBMITTING", "QUEUED", "RUNNING", "CANCELLING")) {
                    TextButton(enabled = !busy, onClick = { run { repository.cancel(owner, task.id); message = "已请求取消" } }) { Text(if (task.cancelRequested) "重试取消" else "取消") }
                    if (task.remoteId == null && !task.cancelRequested) TextButton(enabled = !busy, onClick = { run { repository.resumeSubmission(owner, task.id); message = "已按原请求重试提交" } }) { Text("重试提交") }
                }
                if (!task.cancelRequested && (task.downloaded || task.state in setOf("SUCCEEDED", "PARTIAL"))) TextButton(enabled = !busy, onClick = { run {
                    document = repository.download(owner, task.id); documentTaskId = task.id; message = "已读取校验后的结果；不是识别准确性认证"
                } }) { Text("查看结果") }
                if (task.cleanupPending && task.remoteId != null) TextButton(enabled = !busy, onClick = { run {
                    repository.cleanupRemote(owner, task.id); message = "服务器清理已请求，等待实际删除"
                } }) { Text("清理服务器副本") }
            }
        }
    }
    message?.let { Text(it) }
    if (busy) Text("正在与文档服务通信；离开页面后服务器任务仍可能运行。")
    if (configure) {
        var url by remember { mutableStateOf(config.baseUrl.ifBlank { "https://hxnf.top/documents" }) }
        var token by remember { mutableStateOf("") }
        var enabled by remember { mutableStateOf(config.enabled) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { configure = false }, title = { Text("当前档案的自建文档服务") }, text = { Column {
            Text("启用后，你明确要求的增强处理将上传原文件到此服务。服务器默认保留结果24小时，最长任务寿命48小时；完整下载后请求提前清理。凭据不进入普通备份。")
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS 服务地址") })
            OutlinedTextField(token, { token = it }, label = { Text(if (config.credentialReference.isNotBlank()) "新凭据（留空保持原凭据）" else "服务凭据") }, visualTransformation = PasswordVisualTransformation())
            Row { Checkbox(enabled, { enabled = it }); Text("信任并启用当前服务") }
            error?.let { Text(it) }
        } }, confirmButton = { TextButton(onClick = {
            try { repository.configure(owner, url, token, enabled); token = ""; configure = false; revision++ }
            catch (e: Exception) { error = e.message ?: "配置无效" }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { token = ""; configure = false }) { Text("取消") } })
    }
    chosen?.let { material ->
        var start by remember(material.id) { mutableStateOf("1") }
        var end by remember(material.id) { mutableStateOf("1") }
        AlertDialog(onDismissRequest = { chosen = null }, title = { Text("解析 ${material.name}") }, text = { Column {
            Text("上传原文件，只处理选定物理页，每次最多5页。图片选择1–1。")
            OutlinedTextField(start, { start = it }, label = { Text("起始页") })
            OutlinedTextField(end, { end = it }, label = { Text("结束页") })
        } }, confirmButton = { TextButton(onClick = { chosen = null; run {
            val task = repository.submit(owner, material.id, start.toInt(), end.toInt()); message = "已提交：${task.state}；稍后刷新任务"
        } }) { Text("提交增强解析") } }, dismissButton = { TextButton(onClick = { chosen = null }) { Text("取消") } })
    }
    document?.let { result ->
        var page by remember(result) { mutableIntStateOf(0) }
        val selected = result.processedPages.getOrNull(page)
        AlertDialog(onDismissRequest = { document = null }, title = { Text("Basic 解析结果") }, text = { Column {
            Text("实际物理页：${result.processedPages.joinToString()} / 共 ${result.totalPages} 页；${if (result.complete) "全文范围" else "局部范围"}")
            Text("文字、公式和表格由 OCR 识别，请核对原页。当前支持定位到物理页，未提供精确框选。")
            if (result.failedPages.isNotEmpty()) Text("未完成的物理页：${result.failedPages.joinToString()}")
            if (selected in result.blankPages) Text("该物理页为空白页，没有提取正文。")
            Row {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
                Text("物理页 ${selected ?: "无"}")
                TextButton(enabled = page < result.processedPages.lastIndex, onClick = { page++ }) { Text("下一页") }
            }
            LazyColumn { result.blocks.filter { it.page == selected }.forEach { block -> item {
                Text(when (block.type) { "equation" -> "识别公式（LaTeX）"; "table_body" -> "识别表格"; else -> "识别文字" }, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    if (block.type == "table_body") Column {
                        block.text.lines().forEach { line -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                            line.split('\t').forEach { cell -> Text(cell, Modifier.widthIn(min = 90.dp, max = 180.dp).padding(4.dp)) }
                        } }
                    } else Text(block.text)
                }
                TextButton(enabled = !busy, onClick = { run {
                    val parts = repository.sourcePage(owner, documentTaskId ?: error("缺少来源任务"), block.blockId)
                    val image = parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Image>().first()
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image.url.toUri().toFile())
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } }) { Text("核对原材料第 ${block.page} 页") }
            } } }
        } }, confirmButton = { TextButton(onClick = { document = null }) { Text("关闭") } })
    }
}
