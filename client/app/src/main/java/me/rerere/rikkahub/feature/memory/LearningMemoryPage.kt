package me.rerere.rikkahub.feature.memory

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.OneShotFeedback
import me.rerere.rikkahub.feature.studychat.OneShotFeedbackEvent
import me.rerere.rikkahub.feature.studychat.StudyCompanion
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun LearningMemoryPage(conversationId: String) {
    val profileId = LocalSettings.current.activeProfileId
    key(profileId) { ProfileMemoryPage(profileId, conversationId) }
}

@Composable
private fun ProfileMemoryPage(profileId: String, conversationId: String) {
    val store = koinInject<LearningMemoryStore>()
    val settingsStore = koinInject<SettingsStore>()
    val runner = koinInject<MemoryExtractionRunner>()
    val repository = koinInject<ConversationRepository>()
    val flow = remember(profileId) { store.observe(profileId) }
    val state by flow.collectAsState(initial = MemoryState(), context = kotlinx.coroutines.Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val profile = LocalSettings.current.learnerProfiles.firstOrNull { it.id == profileId }

    var consent by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LearningMemory?>(null) }
    var deleting by remember { mutableStateOf<LearningMemory?>(null) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                message = error.message?.take(120) ?: "操作失败，请重试"
            }
        }
    }

    // A restored snapshot is history, not a newly committed success event.
    LaunchedEffect(profileId) {
        var firstSnapshot = true
        flow.collect { snapshot ->
        snapshot.jobs.filter { it.state == "SUCCEEDED" && it.savedCount > 0 }.forEach { lastSuccess ->
            val event = OneShotFeedbackEvent(
                profileId = profileId,
                taskId = lastSuccess.id,
                version = lastSuccess.updatedAt,
                eventKey = "memory_job_${lastSuccess.id}",
                eventType = OneShotFeedbackEvent.EventType.MEMORY_SAVED
            )
            val consumed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                OneShotFeedback.tryConsume(event, profileId)
            }
            if (!firstSnapshot && consumed) {
                message = "抽取成功：已提交 ${lastSuccess.savedCount} 条新记忆"
            }
        }
        firstSnapshot = false
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
                        text = "${profile?.name ?: "学习者"}的学习记忆",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFF29272B)
                    )
                    Text(
                        text = "用于延续教学；候选与冲突不会作为事实检索，不表示正式掌握度。",
                        fontSize = 12.sp,
                        color = Color(0xFF777178)
                    )
                }
                StudyCompanion()
            }

            Text(
                text = "自动抽取每个档案每天最多 20 次请求，失败尝试也计入。达到上限后次日继续。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777178)
            )

            if (state.recoveryBlocked) {
                Text(
                    text = "旧删除约束已保留，自动抽取暂停；已有记忆仍可查看、更正和删除。请建立新学习档案继续自动记忆。",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }

            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动抽取长期记忆", fontWeight = FontWeight.SemiBold, color = Color(0xFF29272B), fontSize = 14.sp)
                        Text("完成对话后自动在后台分析生成卡点与表现", fontSize = 11.sp, color = Color(0xFF777178))
                    }
                    Switch(
                        enabled = !state.recoveryBlocked,
                        checked = profile?.memoryEnabled == true && !state.recoveryBlocked,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                consent = true
                            } else {
                                runAction {
                                    store.claim(profileId, false, System.currentTimeMillis())
                                    settingsStore.setLearnerMemoryEnabled(profileId, false)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFFFFFF),
                            checkedTrackColor = Color(0xFFF8D5DE),
                            uncheckedThumbColor = Color(0xFF777178),
                            uncheckedTrackColor = Color(0xFFEFECEF)
                        )
                    )
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索记忆或卡点主题") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    onClick = { runAction { store.retry(profileId); runner.wake() } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF8D5DE), contentColor = Color(0xFF925568)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("重试失败抽取", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        runAction {
                            val conversation = repository.getConversationById(Uuid.parse(conversationId), profileId) ?: error("当前会话不属于此学习者")
                            val enabled = settingsStore.settingsFlow.value.learnerProfiles.any { it.id == profileId && it.memoryEnabled }
                            check(enabled) { "请先启用自动抽取" }
                            val count = store.enqueue(conversation, true)
                            message = if (count > 0) "已排队，尚未保存" else "没有新增的已完成来源"
                            runner.wake()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF1F5), contentColor = Color(0xFF925568)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("处理当前会话", fontSize = 12.sp)
                }
            }

            message?.let {
                Text(it, color = Color(0xFF925568), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        item {
            val progress = state.learningProgress()
            Surface(
                color = Color(0xFFFFFFFF),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("学习进展跟踪", style = MaterialTheme.typography.titleMedium, color = Color(0xFF29272B), fontWeight = FontWeight.Bold)
                    Text("只展示有有效来源的近期记录；待核对的表现不表示已掌握。", fontSize = 12.sp, color = Color(0xFF777178))

                    listOf(
                        "当前卡点" to progress.difficulties,
                        "近期作答证据" to progress.evidence,
                        "下一步复习建议" to progress.reviews
                    ).forEach { (label, records) ->
                        Text(label, style = MaterialTheme.typography.labelLarge, color = Color(0xFF925568))
                        if (records.isEmpty()) {
                            Text("暂无记录", color = Color(0xFF777178), fontSize = 12.sp)
                        }
                        records.forEach { record ->
                            Surface(
                                color = Color(0xFFFFF7FA),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(
                                        text = "${record.content} · ${evidenceLabel(record.evidenceType)}",
                                        fontSize = 13.sp,
                                        color = Color(0xFF29272B)
                                    )
                                    record.sourceIds.forEach { id ->
                                        state.sources.firstOrNull { it.id == id }?.let { source ->
                                            Text(
                                                text = "来源: ${source.recordedAtLocal.ifBlank { "会话记录" }}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF777178)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val filtered = state.items.filter { search.isBlank() || search in it.content || search in it.topicKey }.sortedByDescending { it.updatedAt }
        if (filtered.isEmpty()) {
            item {
                Text("暂无学习记忆。开启后，完成聊天会自动抽取并呈现于此。", color = Color(0xFF777178))
            }
        }

        items(filtered, key = { it.id }) { memory ->
            Surface(
                color = Color(0xFFFFFFFF),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEFECEF), RoundedCornerShape(14.dp))
                    .clickable {
                        // P06 记忆详情路由跳转
                        nav.navigate(Screen.StudyMemoryDetail(memory.id, conversationId))
                    }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${typeLabel(memory.type)} · ${memory.topicKey}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = Color(0xFF925568),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Surface(
                            color = Color(0xFFFFF1F5),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = statusLabel(memory.status),
                                color = Color(0xFF925568),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = memory.content,
                        color = Color(0xFF29272B),
                        fontSize = 14.sp
                    )

                    Text(
                        text = "依据：${evidenceLabel(memory.evidenceType)} · 修订 v${memory.revision}",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777178))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "查看详情与更正 →",
                            color = Color(0xFF925568),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text("启用自动学习记忆") },
            text = {
                Text("将使用当前聊天模型抽取已完成聊天中的用户消息（包括已有会话），产生额外 API 请求。记忆保存在本机、按学习者隔离，可查看来源、纠正和删除。不会自动提高正式掌握度。已有聊天也会补处理；可通过“处理当前会话”立即排队。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        consent = false
                        runAction {
                            settingsStore.setLearnerMemoryEnabled(profileId, true)
                            runner.wake()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    )
                ) {
                    Text("启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { consent = false }) { Text("取消") }
            },
            containerColor = Color(0xFFFFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun typeLabel(type: MemoryType) = when (type) {
    MemoryType.BACKGROUND -> "学习背景"
    MemoryType.PREFERENCE -> "学习偏好"
    MemoryType.PROGRESS -> "讨论进度"
    MemoryType.DIFFICULTY -> "卡点/错因"
    MemoryType.PERFORMANCE -> "学习表现"
    MemoryType.REVIEW_SUGGESTION -> "复习事项"
}

private fun statusLabel(status: String) = when (status) {
    "ACTIVE" -> "已保存"
    "CANDIDATE" -> "待核对"
    "DISPUTED" -> "存在冲突"
    else -> "来源失效"
}

private fun evidenceLabel(type: EvidenceType) = when (type) {
    EvidenceType.SELF_REPORTED -> "本人陈述"
    EvidenceType.READ_ONLY -> "仅阅读/看懂"
    EvidenceType.AFTER_EXPLANATION -> "讲解后完成"
    EvidenceType.WITH_HINT -> "提示后完成"
    EvidenceType.INDEPENDENT_ATTEMPT -> "独立尝试"
    EvidenceType.UNVERIFIED -> "待核对"
}
