package me.rerere.rikkahub.feature.diagnostics

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DiagnosticsPanel() {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("诊断与反馈") }
    if (open) DiagnosticsDialog { open = false }
}

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf("正在读取运行信息…") }
    var feedback by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { summary = withContext(Dispatchers.IO) { Diagnostics.summary(context) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingPath?.let(::File)
        pendingPath = null
        if (uri == null || file == null) { busy = false; message = "已取消保存" }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                    fun digest(input: java.io.InputStream): ByteArray = input.use {
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        while (true) { val count = it.read(buffer); if (count == -1) break; hash.update(buffer, 0, count) }
                        hash.digest()
                    }
                    check(digest(requireNotNull(context.contentResolver.openInputStream(uri))).contentEquals(digest(file.inputStream())))
                }
                message = "诊断包已保存，可通过手机发送给开发者"
            } catch (_: Exception) { message = "保存失败，请检查目标位置和剩余空间；目标位置可能有未完成文件" }
            finally { busy = false }
        }
    }
    fun export(share: Boolean) {
        busy = true
        message = "正在生成诊断包…"
        val text = feedback
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { Diagnostics.export(context, text) }
                if (share) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("diagnostic", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享诊断包"))
                    message = "已打开分享面板，请选择接收者；尚未确认发送"
                    busy = false
                } else {
                    pendingPath = file.absolutePath
                    save.launch(file.name)
                }
            } catch (_: Exception) { busy = false; pendingPath = null; message = "无法导出，请检查存储空间或改用保存诊断包" }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("诊断与反馈") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary)
                Text("自动记录仅含运行元数据，不包含聊天、密钥和材料内容。诊断包只在本机生成，不会自动上传。")
                OutlinedTextField(value = feedback, onValueChange = { feedback = it.take(2000) }, enabled = !busy,
                    label = { Text("复现说明（选填）") },
                    placeholder = { Text("发生时间、操作步骤、预期结果、实际结果；是否切后台或断网") },
                    supportingText = { Text("${feedback.length}/2000；手动填写的文字会原样加入诊断包，请勿填写密钥。截图或录屏可另行分享。") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = { export(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存诊断包") }
                OutlinedButton(onClick = { export(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("分享诊断包") }
                TextButton(onClick = { confirmClear = true }, enabled = !busy) { Text("清除本机诊断记录") }
                if (message.isNotEmpty()) Text(message)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }
    )
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除诊断记录？") },
        text = { Text("删除本机运行记录、最近崩溃及临时诊断包。已保存或分享的副本不会删除，学习数据不受影响。") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { Diagnostics.clear(context) }; summary = withContext(Dispatchers.IO) { Diagnostics.summary(context) }; message = "本机诊断记录已清除" }
                catch (_: Exception) { message = "清除失败，请稍后重试" }
                finally { busy = false }
            }
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
