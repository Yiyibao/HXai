package me.rerere.rikkahub.feature.studychat.ui

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.rikkahub.feature.studychat.StudyModelIdentity
import me.rerere.rikkahub.feature.studychat.studyChatModels
import me.rerere.rikkahub.feature.studychat.commitStudyModelSelection

/**
 * P11 模型选择 ModalBottomSheet
 * 按提供商 (providerId) 准确分组，模型唯一身份为 (providerId, modelId)，同名不合并
 * 本地搜索、能力标签、选中底板、当前在途生成冻结提示、提供商管理跳转
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyModelPickerSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    providers: List<ProviderSetting>,
    currentModelId: Uuid?,
    isGenerating: Boolean = false,
    onSelectModel: suspend (Model) -> Unit,
    onManageProviders: () -> Unit,
) {
    if (!visible) return

    var searchQuery by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 过滤可用提供商与聊天模型
    val enabledProviders = remember(providers) {
        providers.filter { it.enabled }
    }

    // 按提供商分组过滤
    val groupedModels = remember(enabledProviders, searchQuery) {
        studyChatModels(enabledProviders, searchQuery)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismissRequest() },
        containerColor = Color(0xFFFFFFFF),
        scrimColor = Color(0x66000000),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = Color(0xFFEFECEF)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp)
                .imePadding()
        ) {
            // 顶栏：标题与提供商管理入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择对话模型",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )

                TextButton(
                    enabled = !saving,
                    onClick = {
                        onDismissRequest()
                        onManageProviders()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF925568))
                ) {
                    Text("管理提供商", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 在途生成状态提示
            if (isGenerating) {
                Surface(
                    color = Color(0xFFFFF1F5),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = "当前有正在生成的回答，切换模型将在下一轮提问生效。",
                        color = Color(0xFF925568),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // 搜索输入框
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saving) Text("正在保存模型选择…", color = Color(0xFF925568))
            OutlinedTextField(
                enabled = !saving,
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索模型名称、ID 或提供商…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFF8D5DE),
                    unfocusedBorderColor = Color(0xFFEFECEF),
                    focusedContainerColor = Color(0xFFFFFFFF),
                    unfocusedContainerColor = Color(0xFFFFF7FA)
                )
            )

            // 模型列表
            if (groupedModels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (enabledProviders.isEmpty()) "暂无已启用的服务提供商" else "未匹配到相关模型",
                            color = Color(0xFF777178),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = !saving,
                            onClick = {
                                onDismissRequest()
                                onManageProviders()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF8D5DE),
                                contentColor = Color(0xFF925568)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("配置提供商与模型", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    groupedModels.forEach { (provider, models) ->
                        item(key = "header_${provider.id}") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = Color(0xFF925568),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "(${models.size})",
                                    color = Color(0xFF777178),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        items(models, key = { StudyModelIdentity(provider.id, it.id).toString() }) { model ->
                            val isSelected = model.id == currentModelId

                            Surface(
                                enabled = !saving,
                                onClick = {
                                    if (!saving) {
                                        saving = true
                                        saveError = null
                                        scope.launch {
                                            try {
                                                val result = commitStudyModelSelection(model, onSelectModel)
                                                if (result.isSuccess) onDismissRequest()
                                                else saveError = "模型选择保存失败，原选择已保留，请重试。"
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                },
                                color = if (isSelected) Color(0xFFF8D5DE) else Color(0xFFFFFFFF),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF925568) else Color(0xFFEFECEF),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .semantics {
                                        contentDescription = "${model.displayName}, 提供商 ${provider.name}, ${if (isSelected) "当前已选中" else "未选中"}"
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    color = Color(0xFF29272B),
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 15.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isSelected) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "当前使用",
                                                    color = Color(0xFF925568),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = model.modelId,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = Color(0xFF777178),
                                                fontSize = 12.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        // 能力标签
                                        if (model.abilities.isNotEmpty() || model.inputModalities.contains(me.rerere.ai.provider.Modality.IMAGE)) {
                                            Spacer(Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (model.abilities.contains(ModelAbility.REASONING)) {
                                                    AbilityChip("深度思考")
                                                }
                                                if (model.inputModalities.contains(me.rerere.ai.provider.Modality.IMAGE)) {
                                                    AbilityChip("视觉识别")
                                                }
                                                if (model.abilities.contains(ModelAbility.TOOL)) {
                                                    AbilityChip("工具调用")
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = if (isSelected) "✓" else "",
                                        color = Color(0xFF925568),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
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

@Composable
private fun AbilityChip(label: String) {
    Surface(
        color = Color(0xFFFFF1F5),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF925568),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
