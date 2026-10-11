package me.rerere.rikkahub.feature.studychat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class StudyThemeColors(
    val background: Color = Color(0xFFFFFFFF),
    val surface: Color = Color(0xFFFFFFFF),
    val actionContainer: Color = Color(0xFFF8D5DE),
    val softContainer: Color = Color(0xFFFFF1F5),
    val onAction: Color = Color(0xFF925568),
    val accent: Color = Color(0xFF925568),
    val text: Color = Color(0xFF29272B),
    val secondary: Color = Color(0xFF777178),
    val border: Color = Color(0xFFEFECEF),
    val cardBackground: Color = Color(0xFFFFFFFF),
    val cardSelectedBorder: Color = Color(0xFFF8D5DE),
    // 页芽规范颜色
    val mascotBody: Color = Color(0xFFF8D5DE),
    val mascotBack: Color = Color(0xFFFFF7FA),
    val mascotCorner: Color = Color(0xFFF0C698),
    val mascotCrease: Color = Color(0xFFE5A2B2),
    val mascotEye: Color = Color(0xFF29272B),
)

val LocalStudyThemeColors = staticCompositionLocalOf { StudyThemeColors() }

object StudyTheme {
    val colors: StudyThemeColors
        @Composable
        get() = LocalStudyThemeColors.current

    val cardShape = RoundedCornerShape(18.dp)
    val buttonShape = RoundedCornerShape(12.dp)
    val chipShape = RoundedCornerShape(10.dp)
    val inputShape = RoundedCornerShape(16.dp)
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}
