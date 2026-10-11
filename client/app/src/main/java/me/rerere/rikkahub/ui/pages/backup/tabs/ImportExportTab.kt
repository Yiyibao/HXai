package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.sync.EncryptedBackup
import me.rerere.rikkahub.data.sync.RestorePreview
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun ImportExportTab(vm: BackupVM, onShowRestartDialog: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { password = ""; confirmation = "" }
        else scope.launch {
            busy = true
            val secret = password.toCharArray(); password = ""; confirmation = ""
            try {
                withContext(Dispatchers.IO) {
                    val archive = vm.exportEncrypted(secret)
                    try {
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                        output.use { out -> archive.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                        } }
                    } finally { archive.delete() }
                    vm.exportedSuccessfully()
                }
                message = "完整加密备份已保存。请妥善保管密码，应用无法找回。"
            } catch (error: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                if (error is CancellationException) throw error
                message = error.message ?: "备份保存失败，请重试"
            } finally { secret.fill('\u0000'); busy = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) password = ""
        else scope.launch {
            busy = true
            val secret = password.toCharArray(); password = ""
            try {
                preview = withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("selected-backup-", ".msab", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选备份")
                        input.use { source -> temporary.outputStream().use { target ->
                            val buffer = ByteArray(8192); var size = 0L
                            while (true) {
                                ensureActive(); val n = source.read(buffer); if (n < 0) break
                                size += n; require(size <= EncryptedBackup.maxBytes + 1024 * 1024) { "备份超出大小限制" }
                                target.write(buffer, 0, n)
                            }
                        } }
                        vm.previewEncrypted(temporary, secret)
                    } finally { temporary.delete() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = error.message ?: "备份校验失败，原数据未改变"
            } finally { secret.fill('\u0000'); busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("完整加密备份", style = MaterialTheme.typography.titleLarge) }
        item { Text("包含全部学习档案、聊天、记忆与来源、删除约束、材料、成果及 Skills。API 和文档服务凭据不包含，恢复后重新配置。") }
        item { Text("使用独立备份密码；忘记密码将无法恢复。请选择可靠的保存位置，并保留至少两份不同日期的备份。") }
        item { Button(enabled = !busy && preview == null, onClick = { password = ""; confirmation = ""; action = "save" }) { Text("创建完整加密备份") } }
        item { OutlinedButton(enabled = !busy && preview == null, onClick = { password = ""; action = "open" }) { Text("从加密备份恢复") } }
        if (busy) item { CircularProgressIndicator(); Text("正在处理，请等待完成") }
        message?.let { item { Text(it) } }
    }
    action?.let { selected ->
        AlertDialog(onDismissRequest = { action = null; password = ""; confirmation = "" },
            title = { Text(if (selected == "save") "设置备份密码" else "输入备份密码") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("密码至少 12 个字符。密码仅用于本次操作，不会保存。")
                OutlinedTextField(password, { password = it.take(1024) }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (selected == "save") OutlinedTextField(confirmation, { confirmation = it.take(1024) }, label = { Text("再次输入") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            } },
            confirmButton = { TextButton(enabled = password.length >= 12 && (selected != "save" || confirmation == password), onClick = {
                action = null
                if (selected == "save") save.launch("MathStudyAgent_${System.currentTimeMillis()}.msab")
                else open.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
            }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { action = null; password = ""; confirmation = "" }) { Text("取消") } })
    }
    preview?.let { value ->
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value.createdAt))
        AlertDialog(onDismissRequest = { if (!busy) { vm.discardEncryptedRestore(); preview = null } },
            title = { Text("确认恢复已校验的备份") },
            text = { Text("快照：$date\n档案：${value.profiles.joinToString()}\n条目：${value.entries}\n\n恢复将替换当前聊天与设置，并保留本机较新的记忆删除约束。重启后生效。新设备无法知道备份日期之后在其他设备上的删除。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                try { vm.confirmEncryptedRestore(); preview = null; onShowRestartDialog() }
                catch (error: Exception) { if (error is CancellationException) throw error; message = error.message ?: "恢复准备失败，原数据已保留" }
                finally { busy = false }
            } }) { Text("确认恢复") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { vm.discardEncryptedRestore(); preview = null }) { Text("取消") } })
    }
}
