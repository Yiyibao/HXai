package me.rerere.rikkahub.feature.studychat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.feature.studychat.MotionPolicy

/**
 * P04 原图与符号核对 ModalBottomSheet
 * 遮盖主导航；原图预览、两个未预选候选、手动输入、确认与稍后处理齐全
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySymbolCheckSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    questionTitle: String = "核对关键推导符号",
    sourceContext: String = "请对照原材料手动核对；尚无自动识别候选。",
    previewSymbol: String = "未取得可提取的符号片段",
    candidate1: String = "",
    candidate2: String = "",
    initialManualInput: String = "",
    onConfirm: (selectedSymbol: String) -> Unit,
    onPostpone: () -> Unit,
) {
    if (!visible) return

    var selectedOption by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf(initialManualInput) }

    val isConfirmEnabled = selectedOption != null || manualInput.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Text(
                text = questionTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = Color(0xFF29272B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            )

            // 来源说明
            Text(
                text = sourceContext,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF777178),
                    fontSize = 14.sp
                )
            )

            // 原图 / 识别区域模拟卡片
            Surface(
                color = Color(0xFFFFF1F5),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color(0xFFF8D5DE), shape = RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "材料提取片段（请对照原文件）：",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF925568))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = previewSymbol,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF29272B),
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Text(
                text = if (candidate1.isBlank() && candidate2.isBlank()) "请输入人工核对后的符号或公式：" else "请核对正确推导符号（默认未选）：",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color(0xFF925568),
                    fontWeight = FontWeight.SemiBold
                )
            )

            // 候选选项 1
            if (candidate1.isNotBlank()) CandidateOptionRow(
                text = candidate1,
                isSelected = selectedOption == candidate1,
                onClick = {
                    selectedOption = if (selectedOption == candidate1) null else candidate1
                }
            )

            // 候选选项 2
            if (candidate2.isNotBlank()) CandidateOptionRow(
                text = candidate2,
                isSelected = selectedOption == candidate2,
                onClick = {
                    selectedOption = if (selectedOption == candidate2) null else candidate2
                }
            )

            // 手动输入
            OutlinedTextField(
                value = manualInput,
                onValueChange = {
                    manualInput = it
                    if (it.isNotBlank()) selectedOption = null
                },
                label = { Text("手动输入正确符号或公式 (LaTeX)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFF8D5DE),
                    unfocusedBorderColor = Color(0xFFEFECEF),
                    focusedLabelColor = Color(0xFF925568)
                )
            )

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 稍后处理
                OutlinedButton(
                    onClick = onPostpone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF777178)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEFECEF))
                ) {
                    Text("稍后处理", fontSize = 14.sp)
                }

                // 确认
                Button(
                    onClick = {
                        val finalChoice = manualInput.ifBlank { selectedOption ?: "" }
                        onConfirm(finalChoice)
                    },
                    enabled = isConfirmEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568),
                        disabledContainerColor = Color(0xFFFFF7FA),
                        disabledContentColor = Color(0xFFB0A8AD)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认提交", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CandidateOptionRow(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) Color(0xFFFFF1F5) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFFF8D5DE) else Color(0xFFEFECEF),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isSelected) "●" else "○",
                color = if (isSelected) Color(0xFF925568) else Color(0xFF777178),
                fontSize = 16.sp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                color = Color(0xFF29272B),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
