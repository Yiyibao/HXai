package me.rerere.rikkahub.feature.studychat

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 动效控制策略 (MotionPolicy)
 * 遵守交接文档第4节：通用动画合同与生命周期规则
 *
 * 允许装饰动画 = 应用reduceMotion关闭 ∧ 系统动画缩放非零 ∧ 生命周期处于前台RESUMED ∧ 页面可见；
 * 待机还需要未输入、未滚动、未长文阅读。
 * 默认reduceMotion开启（不运行装饰循环）。
 */
class MotionPolicy(
    val reduceMotion: Boolean,
    val isSystemAnimationEnabled: Boolean,
    val isResumed: Boolean,
    val isVisible: Boolean,
    val isTyping: Boolean = false,
    val isScrolling: Boolean = false,
    val isReadingLongText: Boolean = false,
) {
    /** 是否允许装饰性动画过渡（如淡变、轻移、折角变化等） */
    val allowDecorations: Boolean
        get() = !reduceMotion && isSystemAnimationEnabled && isResumed && isVisible

    /** 是否允许待机待命循环动画（如每6-10s眨眼） */
    val allowIdleLoop: Boolean
        get() = allowDecorations && !isTyping && !isScrolling && !isReadingLongText

    /** 持续时间转换：若禁止装饰动画，跳过动画过程直接进入终态 (0ms) */
    fun transitionDuration(durationMs: Int): Int =
        if (allowDecorations) durationMs else 0

    companion object {
        fun isSystemAnimationEnabled(context: Context): Boolean {
            return try {
                val scale = Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1.0f
                )
                scale > 0f
            } catch (_: Exception) {
                true
            }
        }
    }
}

@Composable
fun rememberMotionPolicy(
    reduceMotion: Boolean,
    isVisible: Boolean = true,
    isTyping: Boolean = false,
    isScrolling: Boolean = false,
    isReadingLongText: Boolean = false,
): MotionPolicy {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState = lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isResumed = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
    val isSystemAnimationEnabled = remember(context) {
        MotionPolicy.isSystemAnimationEnabled(context)
    }

    return remember(
        reduceMotion,
        isSystemAnimationEnabled,
        isResumed,
        isVisible,
        isTyping,
        isScrolling,
        isReadingLongText
    ) {
        MotionPolicy(
            reduceMotion = reduceMotion,
            isSystemAnimationEnabled = isSystemAnimationEnabled,
            isResumed = isResumed,
            isVisible = isVisible,
            isTyping = isTyping,
            isScrolling = isScrolling,
            isReadingLongText = isReadingLongText
        )
    }
}
