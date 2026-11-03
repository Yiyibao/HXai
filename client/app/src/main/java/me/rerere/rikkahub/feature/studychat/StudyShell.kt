package me.rerere.rikkahub.feature.studychat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.feature.learner.LearnerProfile

@Composable
fun StudyShell(
    settings: Settings,
    conversationOwner: String,
    onSettingsChange: (Settings) -> Unit,
    chat: @Composable () -> Unit,
) {
    var entry by rememberSaveable { mutableIntStateOf(0) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var nickname by rememberSaveable { mutableStateOf("") }
    val profiles = settings.learnerProfiles
    val active = profiles.firstOrNull { it.id == settings.activeLearnerId }
    val owner = profiles.firstOrNull { it.id == conversationOwner }
    val entries = listOf("聊天", "材料", "记忆", "成果")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            // Original static bookmark companion; animation comes in a later batch.
            Row(Modifier.padding(vertical = 6.dp)) {
                BookmarkCompanion()
                Text("页芽", modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            }
            TextButton(onClick = { showProfiles = true }) {
                Text(active?.nickname ?: "选择学习档案")
            }
        }
        if (entry == 0) {
            Text("本会话：${owner?.nickname ?: "未识别档案"}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entry == 0) chat() else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(entries[entry], style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("学习档案：${active?.nickname ?: "未选择"}")
                    Spacer(Modifier.height(8.dp))
                    Text(when (entry) {
                        1 -> "还没有学习材料。当前可回到聊天，通过附件按钮发送材料。材料库将在后续批次接入。"
                        2 -> "还没有学习记忆。自动提取与档案记忆管理将在后续批次接入。"
                        else -> "还没有学习成果。笔记预览和文件导出将在后续批次接入。"
                    })
                    TextButton(onClick = { entry = 0 }) { Text("回到聊天") }
                }
            }
        }
        NavigationBar {
            entries.forEachIndexed { index, label ->
                NavigationBarItem(selected = entry == index, onClick = { entry = index },
                    icon = { Text(listOf("聊", "材", "忆", "果")[index]) }, label = { Text(label) })
            }
        }
    }

    if (showProfiles) {
        AlertDialog(onDismissRequest = { showProfiles = false },
            title = { Text("学习档案") },
            text = {
                Column {
                    Text("切换后，新会话使用所选档案。当前会话和进行中的回答保留原归属。")
                    profiles.forEach { profile ->
                        TextButton(onClick = {
                            onSettingsChange(settings.copy(activeLearnerId = profile.id))
                            showProfiles = false
                        }) { Text((if (profile.id == settings.activeLearnerId) "✓ " else "") + profile.nickname) }
                    }
                    if (profiles.size < 2) {
                        OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(40) },
                            label = { Text("第二位学习者昵称") }, singleLine = true)
                        TextButton(enabled = nickname.isNotBlank(), onClick = {
                            val profile = LearnerProfile(nickname = nickname.trim())
                            onSettingsChange(settings.copy(learnerProfiles = profiles + profile, activeLearnerId = profile.id))
                            nickname = ""
                            showProfiles = false
                        }) { Text("创建档案") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showProfiles = false }) { Text("完成") } })
    }
}

@Composable
private fun BookmarkCompanion() {
    Canvas(Modifier.size(36.dp).semantics { contentDescription = "页芽，静候" }) {
        val w = size.width
        val h = size.height
        val paper = Path().apply {
            moveTo(w * .22f, h * .08f)
            lineTo(w * .62f, h * .08f)
            lineTo(w * .8f, h * .27f)
            lineTo(w * .8f, h * .94f)
            lineTo(w * .5f, h * .8f)
            lineTo(w * .22f, h * .94f)
            close()
        }
        drawPath(paper, Color(0xFF91C9B6))
        val fold = Path().apply {
            moveTo(w * .62f, h * .08f)
            lineTo(w * .62f, h * .27f)
            lineTo(w * .8f, h * .27f)
            close()
        }
        drawPath(fold, Color(0xFFEAC99E))
        drawCircle(Color(0xFF202C29), w * .035f, Offset(w * .4f, h * .46f))
        drawCircle(Color(0xFF202C29), w * .035f, Offset(w * .62f, h * .46f))
    }
}
