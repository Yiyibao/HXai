package me.rerere.rikkahub.feature.material.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.studychat.MotionPolicy

/**
 * P03 / P07 可复用材料与成果卡片
 * 遵守白底淡粉规范，中性/粉色线条文件图标，技术字段收拢于可展开次级信息
 */
@Composable
fun StudyFileCard(
    file: MaterialFile,
    isArtifact: Boolean = false,
    motionPolicy: MotionPolicy? = null,
    onViewContent: () -> Unit = {},
    onCheckSymbol: (() -> Unit)? = null,
    onSaveAs: () -> Unit = {},
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var expandedDetails by remember { mutableStateOf(false) }
    val duration = motionPolicy?.transitionDuration(200) ?: 200

    val extension = file.name.substringAfterLast('.', "TXT").uppercase()
    val scopeText = when {
        file.detail.contains("页") -> file.detail
        file.detail.isNotBlank() -> file.detail
        else -> "全部内容"
    }

    Surface(
        color = Color(0xFFFFFFFF),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = Color(0xFFEFECEF), shape = RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶行：文件类型图标 + 文件名 + 状态徽标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 中性粉色线条格式图标
                Surface(
                    color = Color(0xFFFFF1F5),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = extension.take(4),
                            color = Color(0xFF925568),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF29272B),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "提取范围：$scopeText",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF777178),
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 状态指示
                Surface(
                    color = when (if (isArtifact || file.state != "SUCCEEDED") file.state else file.processingState) {
                        "SUCCEEDED" -> Color(0xFFFFF1F5)
                        "FAILED" -> Color(0xFFFFDAD6)
                        else -> Color(0xFFF8F5F7)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when (if (isArtifact || file.state != "SUCCEEDED") file.state else file.processingState) {
                            "SUCCEEDED" -> if (isArtifact) "已登记" else "已解析"
                            "FAILED" -> "解析失败"
                            "RUNNING" -> "处理中"
                            "CANCELLED" -> "已取消"
                            "NOT_STARTED" -> "未解析"
                            "PENDING" -> "等待处理"
                            else -> "状态未知"
                        },
                        color = when (if (isArtifact || file.state != "SUCCEEDED") file.state else file.processingState) {
                            "FAILED" -> Color(0xFF93000A)
                            else -> Color(0xFF925568)
                        },
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 操作按钮行（统一淡粉交互色）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onViewContent,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8D5DE),
                        contentColor = Color(0xFF925568)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (isArtifact) "预览成果" else "查看内容", fontSize = 12.sp)
                }

                if (onCheckSymbol != null) {
                    OutlinedButton(
                        onClick = onCheckSymbol,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF925568)
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF8D5DE))
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("核对符号", fontSize = 12.sp)
                    }
                }

                if (isArtifact) {
                    Button(
                        onClick = onSaveAs,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFF1F5),
                            contentColor = Color(0xFF925568)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("另存为", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFF1F5),
                            contentColor = Color(0xFF925568)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("分享", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.weight(1f))

                // 展开技术字段开关
                Text(
                    text = if (expandedDetails) "收起详情" else "技术详情",
                    color = Color(0xFF777178),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { expandedDetails = !expandedDetails }
                        .padding(4.dp)
                )
            }

            // 可折叠技术字段（文件大小、SHA-256、ID）
            AnimatedVisibility(
                visible = expandedDetails,
                enter = if (duration > 0) fadeIn(tween(duration)) + expandVertically(tween(duration)) else fadeIn(tween(0)),
                exit = if (duration > 0) fadeOut(tween(duration)) + shrinkVertically(tween(duration)) else fadeOut(tween(0))
            ) {
                Surface(
                    color = Color(0xFFFFF7FA),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("文件 ID: ${file.id}", fontSize = 11.sp, color = Color(0xFF777178))
                        Text("大小: ${file.size} 字节", fontSize = 11.sp, color = Color(0xFF777178))
                        if (file.sha256.isNotBlank()) {
                            Text("校验码 SHA256: ${file.sha256.take(16)}…", fontSize = 11.sp, color = Color(0xFF777178))
                        }
                    }
                }
            }
        }
    }
}
