package me.rerere.rikkahub.feature.studychat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 学习伙伴（页芽）组件
 * 遵守已采纳规范：纯粉色纸片、暖杏折角、米白后页、细折痕、V缺口、小墨点眼睛；每页最多一个，正常36dp
 */
@Composable
fun StudyCompanion(
    modifier: Modifier = Modifier,
    state: CompanionVisualState = CompanionVisualState.IDLE,
    isVisible: Boolean = true,
) {
    val settings = LocalSettings.current
    val motionPolicy = rememberMotionPolicy(
        reduceMotion = settings.displaySetting.reduceMotion,
        isVisible = isVisible
    )

    StudyCompanionCanvas(
        modifier = modifier,
        size = 36.dp,
        state = state,
        motionPolicy = motionPolicy,
        contentDesc = "粉色页芽学习伙伴"
    )
}

/**
 * 学习会话顶栏状态条 (StudyStatusBar)
 * 纯白底板，深玫瑰及浅粉交互元素
 */
@Composable
fun StudyStatusBar(
    conversation: Conversation,
    running: Boolean,
    onStop: () -> Unit,
    onMode: (StudyMode) -> Unit,
    visualState: CompanionVisualState? = null
) {
    var chooseMode by remember { mutableStateOf(false) }
    val settings = LocalSettings.current
    val profile = settings.learnerProfiles.firstOrNull { it.id == conversation.profileId }?.name ?: "学习者"
    val last = conversation.currentMessages.lastOrNull()

    val computedVisualState = visualState ?: when {
        last?.getTools()?.any { it.isPending } == true -> CompanionVisualState.WAITING_USER
        running -> CompanionVisualState.GENERATING
        last?.generationState == "INTERRUPTED" -> CompanionVisualState.INTERRUPTED
        last?.generationState == "CANCELLED" -> CompanionVisualState.CANCELLED
        last?.generationState == "FAILED" -> CompanionVisualState.FAILED
        else -> CompanionVisualState.IDLE
    }

    val statusText = when {
        last?.getTools()?.any { it.isPending } == true -> "等待你核对符号"
        running -> "正在生成解答…"
        last?.generationState == "INTERRUPTED" -> "回复中断 · 可在卡片中恢复"
        last?.generationState == "CANCELLED" -> "已停止 · 内容完整保留"
        last?.generationState == "FAILED" -> "请求失败 · 可重试"
        else -> "静候提问"
    }

    Surface(
        color = Color(0xFFFFFFFF),
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = Color(0xFFEFECEF))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudyCompanion(state = computedVisualState)

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = profile,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFF925568),
                        fontSize = 13.sp
                    )
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF777178),
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }

            // 教学模式选择按钮
            TextButton(
                onClick = { chooseMode = true },
                enabled = !running,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color(0xFFFFF1F5),
                    contentColor = Color(0xFF925568)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = conversation.studyMode.label,
                    fontSize = 13.sp
                )
            }

            // 运行中停止按钮（浅粉填充 + 深玫瑰字）
            if (running) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "停止",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    if (chooseMode) {
        AlertDialog(
            onDismissRequest = { chooseMode = false },
            title = {
                Text(
                    text = "教学方式",
                    color = Color(0xFF29272B),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "选择本轮对话的讲解策略：",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777178)
                    )
                    StudyMode.entries.forEach { mode ->
                        val isSelected = mode == conversation.studyMode
                        Surface(
                            onClick = {
                                onMode(mode)
                                chooseMode = false
                            },
                            color = if (isSelected) Color(0xFFF8D5DE) else Color(0xFFFFF1F5),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isSelected) "● ${mode.label}" else "○ ${mode.label}",
                                    color = Color(0xFF925568),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = mode.instruction,
                                    color = Color(0xFF777178),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { chooseMode = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF925568))
                ) {
                    Text("关闭")
                }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private val studySections = listOf("聊天", "材料", "记忆", "成果")

/**
 * 主页面四入口底部导航条 (StudyNavigation)
 * 仅用于 P01, P02, P03, P05, P07
 * 纯白底板 #FFFFFF，浅粉选中指示器 #F8D5DE，深玫瑰交互文字 #925568
 */
@Composable
fun StudyNavigation(selected: String, conversationId: String) {
    val nav = LocalNavController.current

    Surface(
        color = Color(0xFFFFFFFF),
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = Color(0xFFEFECEF))
    ) {
        NavigationBar(
            containerColor = Color(0xFFFFFFFF),
            contentColor = Color(0xFF925568)
        ) {
            studySections.forEach { section ->
                val isSelected = section == selected
                NavigationBarItem(
                    selected = isSelected,
                    icon = {
                        Text(
                            text = when (section) {
                                "聊天" -> "聊"
                                "材料" -> "册"
                                "记忆" -> "记"
                                else -> "稿"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = if (isSelected) Color(0xFF925568) else Color(0xFF777178)
                            )
                        )
                    },
                    label = {
                        Text(
                            text = section,
                            color = if (isSelected) Color(0xFF925568) else Color(0xFF777178),
                            fontSize = 12.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF925568),
                        selectedTextColor = Color(0xFF925568),
                        indicatorColor = Color(0xFFF8D5DE),
                        unselectedIconColor = Color(0xFF777178),
                        unselectedTextColor = Color(0xFF777178),
                    ),
                    onClick = {
                        if (section != selected) {
                            nav.navigate(
                                if (section == "聊天") Screen.Chat(conversationId)
                                else Screen.StudySection(section, conversationId)
                            ) {
                                popUpTo(Screen.Chat(conversationId))
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun StudySectionPage(section: String, conversationId: String) {
    Scaffold(
        containerColor = Color(0xFFFFFFFF),
        bottomBar = { StudyNavigation(section, conversationId) }
    ) { padding ->
        if (section == "记忆") {
            Box(Modifier.fillMaxSize().padding(padding)) {
                me.rerere.rikkahub.feature.memory.LearningMemoryPage(conversationId)
            }
            return@Scaffold
        }
        if (section == "材料" || section == "成果") {
            Box(Modifier.fillMaxSize().padding(padding)) {
                me.rerere.rikkahub.feature.material.MaterialPage(section == "成果", conversationId)
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(section, style = MaterialTheme.typography.headlineMedium, color = Color(0xFF29272B))
            Text(
                "当前学习者：" + (LocalSettings.current.learnerProfiles.firstOrNull { it.id == LocalSettings.current.activeProfileId }?.name ?: "学习者"),
                color = Color(0xFF777178)
            )
            StudyCompanion()
            Text(
                when (section) {
                    "材料" -> "材料库尚未开放。现在可以回到聊天，用附件入口发送题图或文件。"
                    "记忆" -> "学习记忆尚未启用。当前聊天不会自动写入长期记忆。"
                    else -> "成果库尚未开放。当前不会将聊天回复标记为已导出的文件。"
                },
                color = Color(0xFF777178)
            )
        }
    }
}
