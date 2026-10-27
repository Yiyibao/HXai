package me.rerere.rikkahub.feature.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

/** Only in nativeqa, non-exported, trusted foreground management; never registered as a tool. */
class McpFixtureManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "org.hxsi.agent.nativeqa")
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        setContent {
            val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
            val owner = settings.activeProfileId
            var snapshot by remember(owner) { mutableStateOf<McpInstallation?>(null) }
            var notice by remember(owner) { mutableStateOf("") }
            var port by remember(owner) { mutableStateOf("") }
            var record by remember(owner) { mutableStateOf("fixture") }
            var hash by remember(owner) { mutableStateOf("") }
            val scroll = rememberScrollState()
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            fun current(block: (McpInstallationStore) -> Unit) {
                // A callback captured before a profile change cannot mutate the new/old owner.
                if (settingsStore.settingsFlow.value.init || settingsStore.settingsFlow.value.activeProfileId != owner) {
                    notice = "档案已变化，请重新读取。"; return
                }
                try {
                    McpInstallationStore(this).use { store -> block(store); snapshot = store.get(owner) }
                    focus.clearFocus(); keyboard?.hide()
                    notice = "已读回持久状态。"
                } catch (_: Exception) {
                    notice = "命令未确认：配置、版本或授权已变化，请刷新；没有恢复任务。"
                }
            }
            LaunchedEffect(owner, settings.init) { scroll.scrollTo(0); if (!settings.init) current { } }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("合成 MCP 管理（仅 QA）", style = MaterialTheme.typography.headlineSmall)
                        Text("档案：$owner")
                        Text("仅配置本机固定只读记录。授权只发送 record_id 和摘要；不传文件、凭据或脚本。每个任务仍需单独审批。")
                        Text("状态：${snapshot?.state ?: "未安装"} · 版本 ${snapshot?.revision ?: 0}", Modifier.testTag("mcp_fixture_status"))
                        snapshot?.let { Text("服务 ${it.config.serverName}/${it.config.serverVersion}\n安装 ${it.id}\n授权版本 ${it.grantEpoch}") }
                        OutlinedTextField(port, { port = it }, label = { Text("127.0.0.1 端口") }, modifier = Modifier.fillMaxWidth().testTag("mcp_fixture_port"))
                        OutlinedTextField(record, { record = it }, label = { Text("固定记录 ID") }, modifier = Modifier.fillMaxWidth().testTag("mcp_fixture_record"))
                        OutlinedTextField(hash, { hash = it }, label = { Text("预期内容 SHA256") }, modifier = Modifier.fillMaxWidth().testTag("mcp_fixture_hash"))
                        Button(onClick = { current { store -> snapshot = store.install(owner, snapshot?.revision ?: 0, McpPinnedConfig(port.toInt(), record, hash)) } },
                            enabled = !settings.init && snapshot?.state != "ENABLED", modifier = Modifier.testTag("mcp_fixture_install")) { Text("保存安装（不授权）") }
                        for ((action, label) in listOf("enable" to "授权此固定只读工具", "disable" to "停用", "revoke" to "撤销安装授权", "uninstall" to "卸载并保留撤权记录")) {
                            val enabled = when (action) {
                                "enable" -> snapshot?.state == "DISABLED"
                                "disable" -> snapshot?.state == "ENABLED"
                                "revoke" -> snapshot?.state in setOf("ENABLED", "DISABLED")
                                else -> snapshot != null && snapshot?.state != "UNINSTALLED"
                            }
                            Button(onClick = { val pin = snapshot; if (pin != null) current { it.command(owner, pin.id, pin.revision, action) } },
                                enabled = !settings.init && enabled, modifier = Modifier.testTag("mcp_fixture_$action")) { Text(label) }
                        }
                        Text("重新启用/安装需新任务输入审批；撤权记录和未知请求不会清除。")
                        Text(notice, Modifier.testTag("mcp_fixture_notice"))
                        TextButton(onClick = { current { } }) { Text("刷新") }
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }
                }
            }
        }
    }
}
