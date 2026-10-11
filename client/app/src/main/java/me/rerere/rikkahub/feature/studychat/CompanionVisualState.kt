package me.rerere.rikkahub.feature.studychat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 页芽（StudyCompanion）视觉状态
 * 遵守交接文档第4节状态映射：
 * IDLE -> 静候
 * PREPARING -> 折角收拢
 * GENERATING / TOOL_RUNNING -> 专注，有限折角≤2dp，状态点约2s一周期
 * WAITING_USER -> 朝向核对卡并静止
 * COMPLETED -> 恢复安静
 * FAILED / INTERRUPTED -> 平静收拢
 * CANCELLED -> 停止循环、保留内容
 */
enum class CompanionVisualState {
    IDLE,
    PREPARING,
    GENERATING,
    TOOL_RUNNING,
    WAITING_USER,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    CANCELLED
}

@Composable
fun StudyCompanionCanvas(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    state: CompanionVisualState = CompanionVisualState.IDLE,
    motionPolicy: MotionPolicy? = null,
    contentDesc: String = "粉色页芽学习伙伴",
) {
    // 眨眼动画（仅在 IDLE 且 allowIdleLoop 时运行）
    val blinkAnim = remember { Animatable(1f) }
    // 专注/思考微动（仅在 GENERATING/TOOL_RUNNING 且 allowDecorations 时运行）
    val pulseAnim = remember { Animatable(0f) }

    val allowIdle = motionPolicy?.allowIdleLoop == true
    val allowDecorations = motionPolicy?.allowDecorations == true

    LaunchedEffect(state, allowIdle) {
        if (state == CompanionVisualState.IDLE && allowIdle) {
            while (true) {
                // 6–10s 间隔
                delay(7000L)
                // 120–160ms 眨眼
                blinkAnim.animateTo(0.1f, tween(70, easing = LinearEasing))
                blinkAnim.animateTo(1f, tween(70, easing = LinearEasing))
            }
        } else {
            blinkAnim.snapTo(1f)
        }
    }

    LaunchedEffect(state, allowDecorations) {
        if ((state == CompanionVisualState.GENERATING || state == CompanionVisualState.TOOL_RUNNING) && allowDecorations) {
            pulseAnim.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        } else {
            pulseAnim.snapTo(0f)
        }
    }

    val eyeScaleY = blinkAnim.value
    val pulsePhase = pulseAnim.value

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = contentDesc }
    ) {
        drawStudyCompanion(
            state = state,
            eyeScaleY = eyeScaleY,
            pulsePhase = pulsePhase
        )
    }
}

/**
 * Canvas 精确矢量重构：粉色纸片、暖杏折角、米白后页、细折痕、V缺口、小墨点眼睛
 */
fun DrawScope.drawStudyCompanion(
    state: CompanionVisualState,
    eyeScaleY: Float = 1f,
    pulsePhase: Float = 0f,
) {
    val w = size.width
    val h = size.height

    // 状态对折角微调（≤2dp幅度）
    val cornerOffset = when (state) {
        CompanionVisualState.PREPARING -> 0.04f
        CompanionVisualState.GENERATING, CompanionVisualState.TOOL_RUNNING -> 0.03f * (1f - pulsePhase * 0.3f)
        CompanionVisualState.FAILED, CompanionVisualState.INTERRUPTED -> -0.02f
        else -> 0f
    }

    // 1. 米白后页 (Rear Page)
    val backPath = Path().apply {
        moveTo(w * 0.20f, h * 0.12f)
        lineTo(w * 0.60f, h * 0.12f)
        lineTo(w * 0.76f, h * 0.28f)
        lineTo(w * 0.76f, h * 0.92f)
        lineTo(w * 0.48f, h * 0.81f)
        lineTo(w * 0.20f, h * 0.92f)
        close()
    }
    drawPath(backPath, Color(0xFFFFF7FA))
    drawPath(backPath, Color(0xFFEFECEF), style = Stroke(width = 1.5f))

    // 2. 粉色主书签身 (Pink Body)
    val cornerFoldX = w * (0.64f - cornerOffset)
    val cornerFoldY = h * (0.10f + cornerOffset * 0.5f)
    val cornerEdgeX = w * 0.80f
    val cornerEdgeY = h * 0.26f

    val bodyPath = Path().apply {
        moveTo(w * 0.23f, h * 0.10f)
        lineTo(cornerFoldX, cornerFoldY)
        lineTo(cornerEdgeX, cornerEdgeY)
        lineTo(cornerEdgeX, h * 0.90f)
        // 底部 V 缺口
        lineTo(w * 0.515f, h * 0.79f)
        lineTo(w * 0.23f, h * 0.90f)
        close()
    }
    drawPath(bodyPath, Color(0xFFF8D5DE))
    drawPath(bodyPath, Color(0xFFEDB0C0), style = Stroke(width = 1.2f))

    // 3. 暖杏色折角 (Apricot Folded Corner)
    val cornerPath = Path().apply {
        moveTo(cornerFoldX, cornerFoldY)
        lineTo(cornerFoldX, cornerEdgeY)
        lineTo(cornerEdgeX, cornerEdgeY)
        close()
    }
    drawPath(cornerPath, Color(0xFFF0C698))
    drawPath(cornerPath, Color(0xFFE5A2B2), style = Stroke(width = 1f))

    // 4. 小墨点眼睛 (Ink Dot Eyes)
    val eyeColor = Color(0xFF29272B)
    val eyeRadius = w * 0.038f

    val eyeLeftCenter = when (state) {
        CompanionVisualState.WAITING_USER -> Offset(w * 0.38f, h * 0.46f)
        else -> Offset(w * 0.40f, h * 0.44f)
    }
    val eyeRightCenter = when (state) {
        CompanionVisualState.WAITING_USER -> Offset(w * 0.60f, h * 0.46f)
        else -> Offset(w * 0.62f, h * 0.44f)
    }

    if (eyeScaleY < 0.25f) {
        // 闭眼 / 眨眼（横线）
        drawLine(
            color = eyeColor,
            start = Offset(eyeLeftCenter.x - eyeRadius, eyeLeftCenter.y),
            end = Offset(eyeLeftCenter.x + eyeRadius, eyeLeftCenter.y),
            strokeWidth = 2f
        )
        drawLine(
            color = eyeColor,
            start = Offset(eyeRightCenter.x - eyeRadius, eyeRightCenter.y),
            end = Offset(eyeRightCenter.x + eyeRadius, eyeRightCenter.y),
            strokeWidth = 2f
        )
    } else {
        // 正常墨点眼
        drawOval(
            color = eyeColor,
            topLeft = Offset(eyeLeftCenter.x - eyeRadius, eyeLeftCenter.y - eyeRadius * eyeScaleY),
            size = Size(eyeRadius * 2, eyeRadius * 2 * eyeScaleY)
        )
        drawOval(
            color = eyeColor,
            topLeft = Offset(eyeRightCenter.x - eyeRadius, eyeRightCenter.y - eyeRadius * eyeScaleY),
            size = Size(eyeRadius * 2, eyeRadius * 2 * eyeScaleY)
        )
    }

    // 5. 状态点（生成中周期指示点）
    if (state == CompanionVisualState.GENERATING || state == CompanionVisualState.TOOL_RUNNING) {
        val dotRadius = w * 0.025f * (0.8f + pulsePhase * 0.4f)
        drawCircle(
            color = Color(0xFF925568),
            radius = dotRadius,
            center = Offset(w * 0.515f, h * 0.60f)
        )
    }
}
