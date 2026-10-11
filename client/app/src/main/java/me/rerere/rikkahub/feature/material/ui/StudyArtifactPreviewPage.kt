package me.rerere.rikkahub.feature.material.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.feature.material.MaterialReader
import me.rerere.rikkahub.feature.material.MaterialWriter
import kotlinx.coroutines.CancellationException
import coil3.compose.AsyncImage
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

/**
 * P08 成果预览页面
 * 属于返回式子页面（移除主底部导航条）
 * 按档案清空预览状态；文本按格式提取，Markdown 使用数学渲染器
 * 草稿/已导出状态明确分离，打开/另存/分享使用真实安全逻辑
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyArtifactPreviewPage(
    fileId: String,
    conversationId: String,
) {
    val nav = LocalNavController.current
    val store = koinInject<MaterialFiles>()
    val settingsStore = koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
    val settings = LocalSettings.current
    val owner = settings.activeProfileId
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val changes by store.changes.collectAsState()
    val file = remember(changes, owner, fileId) {
        store.list(owner).firstOrNull { it.id == fileId }
    }

    var contentText by remember(owner, fileId, file?.sha256, file?.state) { mutableStateOf<String?>(null) }
    var previewStatus by remember(owner, fileId, file?.sha256, file?.state) { mutableStateOf("正在加载成果") }
    var imageFile by remember(owner, fileId, file?.sha256, file?.state) { mutableStateOf<java.io.File?>(null) }
    var operationStatus by remember(fileId) { mutableStateOf<String?>(null) }
    var pendingSave by remember(owner, fileId) { mutableStateOf<MaterialFile?>(null) }

    LaunchedEffect(owner, fileId) {
        // Preserve a rejection from the activity result that arrived before resume
        // delivered the new owner to Compose; clear all older operation statuses.
        if (operationStatus?.startsWith("另存取消：") != true) operationStatus = null
    }

    LaunchedEffect(owner, fileId, file?.sha256, file?.state) {
        if (file == null) {
            previewStatus = "成果不存在或不属于当前学习者"
        } else {
            try {
                val result = withContext(Dispatchers.IO) {
                    val local = store.verifiedFile(owner, file.id)
                    if (file.extension in setOf("png", "jpg", "jpeg", "webp")) {
                        local to null
                    } else {
                        null to MaterialReader.read(local, file.extension)
                    }
                }
                imageFile = result.first
                contentText = result.second?.text
                previewStatus = result.second?.coverage ?: "原始图片预览；未进行文字或公式识别"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewStatus = "预览失败：${e.message?.take(160) ?: "无法读取成果"}"
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = pendingSave
        pendingSave = null
        if (uri == null) {
            operationStatus = "已取消另存，本地文件完整保留"
        } else if (target == null || target.profileId != settingsStore.settingsFlow.value.activeProfileId) {
            operationStatus = "另存取消：学习者或成果已改变，请重新选择"
        } else {
            scope.launch {
                try {
                    store.saveVerified(target.profileId, target.id, uri) { settingsStore.settingsFlow.value.activeProfileId == target.profileId }
                    if (settingsStore.settingsFlow.value.activeProfileId == target.profileId) operationStatus = "已另存并重新读取校验"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    operationStatus = if (settingsStore.settingsFlow.value.activeProfileId != target.profileId) "另存取消：学习者或成果已改变，请重新选择" else "另存失败：${e.message?.take(80)}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file?.name ?: "成果预览",
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFFFFFF)
                )
            )
        },
        containerColor = Color(0xFFFFFFFF)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态横幅
            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFF8D5DE), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = when (file?.state) {
                                "SUCCEEDED" -> if (file.output) "状态：已正式登记成果" else "状态：已上传材料"
                                "RUNNING" -> "状态：处理中"
                                "FAILED" -> "状态：处理失败"
                                "CANCELLED" -> "状态：已取消"
                                null -> "状态：成果不可用"
                                else -> "状态：等待处理"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color(0xFF925568),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "版本：v1 · 属于学习者档案",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                        )
                    }

                    Surface(
                        color = Color(0xFFFFFFFF),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.border(0.5.dp, Color(0xFFF8D5DE), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = file?.name?.substringAfterLast('.', "MD")?.uppercase() ?: "MD",
                            color = Color(0xFF925568),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 真实 Markdown 与数学公式渲染正文卡片
            Surface(
                color = Color(0xFFFFFFFF),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(previewStatus, style = MaterialTheme.typography.bodySmall)
                    imageFile?.let { image ->
                        AsyncImage(model = image, contentDescription = "成果原始图片", modifier = Modifier.fillMaxWidth())
                    }
                    contentText?.let { text ->
                        if (text.isBlank()) Text("成果没有可预览的文本")
                        else if (file?.extension == "md") MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
                        else Text(text, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (operationStatus != null) {
                Text(
                    text = operationStatus.orEmpty(),
                    color = Color(0xFF925568),
                    fontSize = 13.sp
                )
            }

            // 底部操作区（自适应大字体或窄屏，打开/另存/分享）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 打开
                Button(
                    onClick = {
                        if (file != null) {
                            try {
                                val local = store.verifiedFile(owner, file.id)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, MaterialWriter.mime(file.extension))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "打开文件"))
                                operationStatus = "已打开文件选择器；未确认外部查看器读取成功"
                            } catch (e: Exception) {
                                operationStatus = "打开失败：${e.message?.take(80)}"
                            }
                        }
                    },
                    enabled = file?.state == "SUCCEEDED",
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("打开", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // 另存为
                Button(
                    onClick = {
                        file?.let { target ->
                            pendingSave = target
                            saveLauncher.launch(target.name)
                        }
                    },
                    enabled = file?.state == "SUCCEEDED",
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFF1F5),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("另存为", fontSize = 13.sp)
                }

                // 分享
                Button(
                    onClick = {
                        if (file != null) {
                            try {
                                val local = store.verifiedFile(owner, file.id)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = MaterialWriter.mime(file.extension)
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享成果文件"))
                                operationStatus = "已打开分享选择器；未确认接收方已收到"
                            } catch (e: Exception) {
                                operationStatus = "分享失败：${e.message?.take(80)}"
                            }
                        }
                    },
                    enabled = file?.state == "SUCCEEDED",
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFF1F5),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("分享", fontSize = 13.sp)
                }
            }
        }
    }
}
