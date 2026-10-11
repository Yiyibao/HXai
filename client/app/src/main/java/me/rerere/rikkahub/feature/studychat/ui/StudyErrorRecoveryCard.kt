package me.rerere.rikkahub.feature.studychat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P10 错误与恢复卡片 (StudyErrorRecoveryCard)
 * 区分真实终态：INTERRUPTED (中断), FAILED (失败), CANCELLED (已停止)
 * 保留已生成内容，静态图标，无剧烈全屏红色/晃动，真实重试接线
 */
@Composable
fun StudyErrorRecoveryCard(
    state: String, // "INTERRUPTED", "FAILED", "CANCELLED"
    errorMessage: String? = null,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val title = when (state) {
        "INTERRUPTED" -> "回复中断 · 内容已保留"
        "CANCELLED" -> "生成已停止 · 内容已保留"
        else -> "请求遇到问题"
    }

    val description = when (state) {
        "INTERRUPTED" -> errorMessage ?: "网络连接波动或设备中断导致当前回答未完全接收，已有推导已安全留存。"
        "CANCELLED" -> "已根据你的指示停止当前生成，你可以查看上方内容或重新提出问题。"
        else -> errorMessage ?: "AI 服务返回异常或连接超时，你可以点击重试重新生成回答。"
    }

    val canRetry = state == "INTERRUPTED" || state == "FAILED"

    Surface(
        color = Color(0xFFFFF7FA),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(width = 1.dp, color = Color(0xFFF8D5DE), shape = RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 静态状态标识
                Surface(
                    color = Color(0xFFF8D5DE),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (state == "CANCELLED") "■" else "!",
                            color = Color(0xFF925568),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color(0xFF29272B),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF777178),
                    fontSize = 13.sp
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDismiss != null) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF777178))
                    ) {
                        Text("关闭提示", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                if (canRetry) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF8D5DE),
                            contentColor = Color(0xFF925568)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("重新生成", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
