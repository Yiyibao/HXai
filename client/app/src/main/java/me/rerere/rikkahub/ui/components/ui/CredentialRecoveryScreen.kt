package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore

@Composable
fun CredentialRecoveryScreen(store: SettingsStore) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("连接配置无法解密", style = MaterialTheme.typography.headlineSmall)
        Text("设备密钥或加密记录可能不可用。原数据仍然保留，可以先重试。")
        Button(onClick = store::retryCredentialRead, enabled = !working) { Text("重试读取") }
        TextButton(onClick = { confirm = true }, enabled = !working) { Text("重置失效配置") }
        if (failed) Text("恢复失败，原数据已保留。请重试。", color = MaterialTheme.colorScheme.error)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!working) confirm = false },
        title = { Text("重置无法解密的配置？") },
        text = { Text("仅重置读取失败的设置分区；可能包括接口、助手提示、网络及语音配置。需要重新配置连接。学习者档案和聊天历史保留，Web 服务关闭。原加密引用将保存用于恢复。") },
        dismissButton = { TextButton(onClick = { confirm = false }, enabled = !working) { Text("取消") } },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    try {
                        store.resetUnavailableCredentials()
                        confirm = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        working = false
                    }
                }
            }) { Text(if (working) "正在恢复…" else "确认重置") }
        },
    )
}
