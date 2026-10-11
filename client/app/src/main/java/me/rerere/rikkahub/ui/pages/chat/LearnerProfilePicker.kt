package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings

@Composable
fun LearnerProfilePicker(settings: Settings, vm: ChatVM, onSwitched: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val active = settings.learnerProfiles.firstOrNull { it.id == settings.activeProfileId }

    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("学习者：${active?.name ?: "默认学习者"} · 管理档案")
    }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) { open = false; editing = false; error = null } },
        title = { Text(if (editing) "${if (editingId == null) "创建" else "重命名"}学习者" else "学习者档案") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (editing) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("名称（1–40 个字符）") }, singleLine = true, enabled = !busy)
                } else {
                    Text("切换会停止当前回复并保留已收到的内容，待发送消息暂停。接口配置由此应用共享。")
                    settings.learnerProfiles.forEach { profile ->
                        TextButton(enabled = !busy, onClick = {
                            if (profile.id == settings.activeProfileId) { open = false }
                            else scope.launch {
                                busy = true; error = null
                                try {
                                    vm.switchLearnerProfile(profile.id)
                                    open = false
                                    onSwitched()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = "切换未完成，请重试" }
                                finally { busy = false }
                            }
                        }) { Text("${if (profile.id == settings.activeProfileId) "✓ " else ""}${profile.name}") }
                        TextButton(enabled = !busy, onClick = {
                            editingId = profile.id; name = profile.name; editing = true; error = null
                        }) { Text("重命名 ${profile.name}") }
                    }
                    TextButton(enabled = !busy, onClick = {
                        editingId = null; name = ""; editing = true; error = null
                    }) { Text("创建学习者") }
                }
                if (busy) Text("正在保存并切换…")
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            if (editing) TextButton(enabled = !busy && name.trim().isNotEmpty() && name.trim().length <= 40,
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val id = editingId
                            if (id == null) vm.createLearnerProfile(name) else vm.renameLearnerProfile(id, name)
                            editing = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = "保存失败，请重试" }
                        finally { busy = false }
                    }
                }) { Text("保存") }
            else TextButton(enabled = !busy, onClick = { open = false }) { Text("完成") }
        },
        dismissButton = {
            if (editing) TextButton(enabled = !busy, onClick = { editing = false; error = null }) { Text("返回") }
        },
    )
}
