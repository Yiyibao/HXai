package me.rerere.rikkahub.feature.material

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.feature.material.ui.StudyFileCard
import me.rerere.rikkahub.feature.studychat.StudyCompanion
import me.rerere.rikkahub.feature.studychat.ui.StudySymbolCheckSheet
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun MaterialPage(outputs: Boolean, conversationId: String) {
    val owner = LocalSettings.current.activeProfileId
    key(owner) { ProfileFilesPage(owner, outputs, conversationId) }
}

@Composable
private fun ProfileFilesPage(owner: String, outputs: Boolean, conversationId: String) {
    val store = koinInject<MaterialFiles>()
    val files = koinInject<FilesManager>()
    val settingsStore = koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
    val settings = LocalSettings.current
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val change by store.changes.collectAsState()
    val items = remember(change, owner, outputs) { store.list(owner).filter { it.output == outputs } }

    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewPage by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var selected by remember { mutableStateOf<MaterialFile?>(null) }
    var checkSymbolTarget by remember { mutableStateOf<MaterialFile?>(null) }
    var symbolPreview by remember { mutableStateOf("") }
    var filterState by remember { mutableStateOf("ALL") }

    val allowed = settings.learnerProfiles.any { it.id == owner }

    val filteredItems = remember(items, filterState) {
        when (filterState) {
            "PROCESSING" -> items.filter { it.state == "RUNNING" || it.processingState == "RUNNING" }
            "DONE" -> items.filter { if (outputs) it.state == "SUCCEEDED" else it.processingState == "SUCCEEDED" }
            else -> items
        }
    }

    fun run(action: suspend () -> Unit) {
        retry = action
        job = scope.launch {
            try {
                status = "正在处理"
                action()
            } catch (e: CancellationException) {
                status = "已取消；可重试"
                throw e
            } catch (e: Exception) {
                status = e.message?.take(180) ?: "失败；可重试"
            } finally {
                job = null
            }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = selected
        selected = null
        if (uri == null) {
            status = "已取消另存，本地文件保留"
        } else if (item != null && item.profileId == settingsStore.settingsFlow.value.activeProfileId) {
            run {
                store.saveVerified(item.profileId, item.id, uri) { settingsStore.settingsFlow.value.activeProfileId == item.profileId }
                if (settingsStore.settingsFlow.value.activeProfileId == item.profileId) status = "已另存并重新读取校验"
            }
        } else {
            status = "另存取消：学习者或成果已改变，请重新选择"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            status = "已取消选择"
        } else {
            run {
                for (uri in uris) {
                    val item = store.import(owner, uri, files.getFileNameFromUri(uri) ?: "file.txt")
                    status = "已上传 ${item.name}；回到聊天描述处理需求，Agent 可按 ID 读取"
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (outputs) "真实文件成果" else "文件与讲义材料",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFF29272B)
                    )
                    Text(
                        text = "当前学习者：${settings.learnerProfiles.firstOrNull { it.id == owner }?.name ?: "未知档案"}",
                        color = Color(0xFF777178)
                    )
                }
                StudyCompanion()
            }

            Text(
                text = "属于当前学习者。文件内容、模型回答及成果不计为本人学习表现，不自动标记掌握。",
                fontSize = 12.sp,
                color = Color(0xFF777178)
            )

            // 筛选标签行
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterState == "ALL",
                    onClick = { filterState = "ALL" },
                    label = { Text("全部 (${items.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFF8D5DE),
                        selectedLabelColor = Color(0xFF925568)
                    )
                )
                FilterChip(
                    selected = filterState == "PROCESSING",
                    onClick = { filterState = "PROCESSING" },
                    label = { Text("处理中") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFF8D5DE),
                        selectedLabelColor = Color(0xFF925568)
                    )
                )
                FilterChip(
                    selected = filterState == "DONE",
                    onClick = { filterState = "DONE" },
                    label = { Text(if (outputs) "已登记" else "已解析") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFF8D5DE),
                        selectedLabelColor = Color(0xFF925568)
                    )
                )
            }

            if (!allowed) Text("档案不可用，请选择有效学习者。", color = MaterialTheme.colorScheme.error)
            if (!outputs && allowed) DocumentServicePanel(owner, items)

            if (!outputs) {
                Button(
                    enabled = allowed && job == null,
                    onClick = { picker.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("上传文件或图片", fontSize = 13.sp)
                }
            }

            status?.let { Text(it, color = Color(0xFF925568), fontSize = 12.sp) }
            if (job != null) {
                TextButton(onClick = { job?.cancel() }) { Text("取消处理") }
            } else if (retry != null) {
                TextButton(onClick = { retry?.let { run(it) } }) { Text("重试上次操作") }
            }

            if (outputs) {
                Text(
                    text = "图片生成模型（选择配置不代表接口已验证）：",
                    fontSize = 13.sp,
                    color = Color(0xFF777178)
                )
                me.rerere.rikkahub.ui.components.ai.ModelSelector(
                    modelId = settings.imageGenerationModelId,
                    providers = settings.providers,
                    type = me.rerere.ai.provider.ModelType.IMAGE,
                    onSelect = { model ->
                        scope.launch {
                            settingsStore.update { it.copy(imageGenerationModelId = model.id) }
                        }
                    },
                )
            }
        }

        items(filteredItems, key = { it.id }) { item ->
            StudyFileCard(
                file = item,
                isArtifact = outputs,
                onViewContent = {
                    if (outputs) {
                        nav.navigate(Screen.StudyArtifactPreview(item.id, conversationId))
                    } else {
                        run {
                            val content = store.readContent(owner, item.id)
                            preview = content.coverage + "\n\n" + content.text
                            previewPage = 0
                            status = "已完成文本提取，请检查处理范围"
                        }
                    }
                },
                onCheckSymbol = if (!outputs) { {
                    run {
                        symbolPreview = try {
                            val content = store.readContent(owner, item.id)
                            content.coverage + "\n\n" + content.text.take(4000)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "未取得可提取文本，请打开原文件核对后手动输入；尚无自动识别候选。" }
                        checkSymbolTarget = store.get(owner, item.id)
                    }
                } } else null,
                onSaveAs = {
                    selected = item
                    save.launch(item.name)
                },
                onShare = {
                    run {
                        val file = withContext(Dispatchers.IO) { store.verifiedFile(owner, item.id) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = MaterialWriter.mime(item.extension)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = android.content.ClipData.newRawUri(item.name, uri)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享真实文件"))
                        status = "已打开分享选择器；未确认接收方已收到"
                    }
                }
            )
        }
    }

    // P04 原图核对 BottomSheet 实例
    checkSymbolTarget?.let { target ->
        StudySymbolCheckSheet(
            visible = true,
            onDismissRequest = { checkSymbolTarget = null },
            questionTitle = "核对关键推导符号",
            sourceContext = "材料《${target.name}》的实际提取内容；请对照原文件手动核对。尚无自动识别候选。",
            previewSymbol = symbolPreview,
            candidate1 = "",
            candidate2 = "",
            initialManualInput = target.symbolCorrection?.takeIf { target.symbolSourceSha256 == target.sha256 }.orEmpty(),
            onConfirm = { symbol ->
                run {
                    withContext(Dispatchers.IO) { store.confirmSymbol(owner, target.id, target.sha256, symbol) }
                    status = "已保存人工核对符号：$symbol"
                    checkSymbolTarget = null
                }
            },
            onPostpone = {
                run {
                    withContext(Dispatchers.IO) { store.confirmSymbol(owner, target.id, target.sha256, null) }
                    status = "已保留待核对状态"
                    checkSymbolTarget = null
                }
            }
        )
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("内容及限制") },
            text = {
                val pages = remember(text) { text.chunked(6000) }
                Column {
                    Text("第 ${previewPage + 1} / ${pages.size} 页 · 提取内容分页，非原文件页码")
                    Row {
                        TextButton(enabled = previewPage > 0, onClick = { previewPage-- }) { Text("上一页") }
                        TextButton(enabled = previewPage < pages.lastIndex, onClick = { previewPage++ }) { Text("下一页") }
                    }
                    key(previewPage) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            LazyColumn {
                                items(pages[previewPage].lines()) { line ->
                                    if ('\t' in line) {
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            line.split('\t').forEach { cell ->
                                                Text(cell, Modifier.widthIn(min = 120.dp, max = 240.dp).padding(6.dp))
                                            }
                                        }
                                    } else {
                                        Text(line)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }
        )
    }
}
