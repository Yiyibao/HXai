package me.rerere.rikkahub.feature.memory.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.feature.memory.LearningMemory
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryState
import me.rerere.rikkahub.feature.studychat.StudyCompanion
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

/**
 * P06 记忆详情页面
 * 属于返回式子页面（移除主底部导航条）
 * 事实、来源、证据类型、后续作答分层展示，底部更正/删除始终可达
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyMemoryDetailPage(
    memoryId: String,
    conversationId: String,
) {
    val nav = LocalNavController.current
    val store = koinInject<LearningMemoryStore>()
    val settings = LocalSettings.current
    val profileId = settings.activeProfileId
    val scope = rememberCoroutineScope()

    val flow = remember(profileId) { store.observe(profileId) }
    val memoryState by flow.collectAsState(initial = MemoryState())

    val memory = memoryState.items.firstOrNull { it.id == memoryId }
    val sources = remember(memory, memoryState) {
        memory?.sourceIds?.mapNotNull { sId -> memoryState.sources.firstOrNull { it.id == sId } }.orEmpty()
    }

    var editDialog by rememberSaveable(profileId, memoryId) { mutableStateOf(false) }
    var deleteDialog by rememberSaveable(profileId, memoryId) { mutableStateOf(false) }
    var editText by rememberSaveable(profileId, memoryId) { mutableStateOf("") }
    var editRevision by rememberSaveable(profileId, memoryId) { mutableIntStateOf(0) }
    var deleteRevision by rememberSaveable(profileId, memoryId) { mutableIntStateOf(0) }
    var editError by rememberSaveable(profileId, memoryId) { mutableStateOf<String?>(null) }
    var submitting by remember(profileId, memoryId) { mutableStateOf(false) }

    LaunchedEffect(memory) {
        if (!editDialog && memory != null) editText = memory.content
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "记忆详情",
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFFFFFFFF)
    ) { padding ->
        if (memory == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未找到该条学习记忆，可能已被删除或抑制。", color = Color(0xFF777178))
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { nav.popBackStack() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF8D5DE),
                            contentColor = Color(0xFF925568)
                        )
                    ) {
                        Text("返回列表")
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶层摘要卡片
            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color(0xFFF8D5DE), shape = RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "主题：" + memory.topicKey,
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color(0xFF925568),
                                fontWeight = FontWeight.Bold
                            )
                        )

                        Surface(
                            color = Color(0xFFFFFFFF),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.border(0.5.dp, Color(0xFFF8D5DE), RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = when (memory.status) {
                                    "ACTIVE" -> "已保存事实"
                                    "CANDIDATE" -> "待核对候选"
                                    "CONFLICT" -> "存在冲突"
                                    else -> memory.status
                                },
                                color = Color(0xFF925568),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = memory.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = Color(0xFF29272B),
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    )
                }
            }

            // 证据层级与真实作答
            Surface(
                color = Color(0xFFFFFFFF),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "原始作答与依据类型",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF29272B),
                            fontWeight = FontWeight.SemiBold
                        )
                    )

                    Text(
                        text = "证据类型: " + when (memory.evidenceType.name) {
                            "INDEPENDENT_ATTEMPT" -> "独立尝试完成（高置信）"
                            "WITH_HINT" -> "获得提示后完成"
                            "AFTER_EXPLANATION" -> "讲解后复述"
                            "SELF_REPORTED" -> "自述看懂（仅作阅读反馈）"
                            else -> "待验证作答"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF777178))
                    )

                    Text(
                        text = "事实键: ${memory.factKey}",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                    )
                }
            }

            // 关联来源会话卡片
            Surface(
                color = Color(0xFFFFFFFF),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "溯源会话与上下文",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF29272B),
                            fontWeight = FontWeight.SemiBold
                        )
                    )

                    if (sources.isEmpty()) {
                        Text(
                            text = "来源会话已归档或无直接文本锚点，原始记录仍可安全参考。",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                        )
                    } else {
                        sources.forEach { source ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "记录时间: ${source.recordedAtLocal.ifBlank { "会话对话中" }}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF29272B))
                                    )
                                    Text(
                                        text = "会话 ID: ${source.conversationId.take(12)}…",
                                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        nav.navigate(Screen.Chat(source.conversationId))
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF925568))
                                ) {
                                    Text("回看会话", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部操作区（始终可达，自适应换行/堆叠）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        deleteRevision = memory.revision
                        deleteDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFBA1A1A)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFDAD6))
                ) {
                    Text("删除记忆", fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        editText = memory.content
                        editRevision = memory.revision
                        editError = null
                        editDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("更正记忆", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 更正编辑对话框
    if (editDialog) {
        AlertDialog(
            onDismissRequest = { if (!submitting) editDialog = false },
            title = { Text("更正记忆内容") },
            text = {
                Column {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    enabled = !submitting,
                    label = { Text("更正后的事实描述") },
                    modifier = Modifier.fillMaxWidth()
                )
                editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !submitting,
                    onClick = {
                        val trimmed = editText.trim()
                        if (trimmed.isBlank()) {
                            editError = "更正内容不能为空"
                            return@Button
                        }
                        if (me.rerere.rikkahub.feature.memory.MemoryValidation.sensitive(trimmed)) {
                            editError = "不能将敏感凭据保存为学习记忆"
                            return@Button
                        }
                        if (submitting) return@Button
                        submitting = true
                        scope.launch {
                            try {
                                store.edit(profileId, memoryId, editRevision, trimmed)
                                editDialog = false
                                snackbarHostState.showSnackbar("记忆已成功更正")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                editError = (e.message ?: "更正失败，请稍后重试") + "。草稿已保留；如有版本冲突，请取消后重新编辑。"
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("保存更正")
                }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = { editDialog = false }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // 删除确认对话框（遵守删除抑制与保护）
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!submitting) deleteDialog = false },
            title = { Text("确认删除该记忆？") },
            text = {
                Text(
                    "删除后该条记忆不再参与后续教学，并自动触发历史重提取保护（不重复抽取已删除事实）。",
                    fontSize = 13.sp,
                    color = Color(0xFF777178)
                )
            },
            confirmButton = {
                Button(
                    enabled = !submitting,
                    onClick = {
                        if (submitting) return@Button
                        submitting = true
                        scope.launch {
                            try {
                                store.delete(profileId, memoryId, deleteRevision)
                                deleteDialog = false
                                nav.popBackStack()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(e.message ?: "删除失败，请稍后重试")
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFDAD6),
                        contentColor = Color(0xFF93000A)
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(enabled = !submitting, onClick = { deleteDialog = false }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
