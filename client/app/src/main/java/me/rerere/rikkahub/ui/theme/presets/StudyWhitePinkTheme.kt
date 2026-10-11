package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.PresetTheme

val StudyWhitePinkThemePreset by lazy {
    PresetTheme(
        id = "study_white_pink",
        name = {
            Text("白色淡粉 · MathStudy")
        },
        standardLight = studyLightScheme,
        standardDark = studyDarkScheme,
    )
}

//region Study White-Pink Theme Colors
// 基础规范 Tokens
val StudyColorBackground = Color(0xFFFFFFFF)
val StudyColorSurface = Color(0xFFFFFFFF)
val StudyColorActionContainer = Color(0xFFF8D5DE)
val StudyColorSoftContainer = Color(0xFFFFF1F5)
val StudyColorOnAction = Color(0xFF925568)
val StudyColorAccent = Color(0xFF925568)
val StudyColorText = Color(0xFF29272B)
val StudyColorSecondaryText = Color(0xFF777178)
val StudyColorBorder = Color(0xFFEFECEF)

// 页芽特征颜色
val StudyColorMascotBody = Color(0xFFF8D5DE)
val StudyColorMascotBack = Color(0xFFFFF7FA)
val StudyColorMascotCorner = Color(0xFFF0C698)
val StudyColorMascotCrease = Color(0xFFE5A2B2)
val StudyColorMascotEye = Color(0xFF29272B)

private val studyLightScheme = lightColorScheme(
    primary = StudyColorOnAction,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = StudyColorActionContainer,
    onPrimaryContainer = StudyColorOnAction,
    secondary = StudyColorOnAction,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = StudyColorSoftContainer,
    onSecondaryContainer = StudyColorText,
    tertiary = Color(0xFFB57082),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEBF0),
    onTertiaryContainer = StudyColorOnAction,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = StudyColorBackground,
    onBackground = StudyColorText,
    surface = StudyColorSurface,
    onSurface = StudyColorText,
    surfaceVariant = StudyColorSoftContainer,
    onSurfaceVariant = StudyColorSecondaryText,
    outline = StudyColorBorder,
    outlineVariant = Color(0xFFF2E6EA),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF382E2F),
    inverseOnSurface = Color(0xFFFEEDED),
    inversePrimary = Color(0xFFFFB2BC),
    surfaceDim = Color(0xFFF8F5F7),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = StudyColorSoftContainer,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFF7FA),
    surfaceContainerHighest = Color(0xFFFBEAEB),
)

// 保留既有深色设置，不进行简单全局反相
private val studyDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB2BC),
    onPrimary = Color(0xFF561D28),
    primaryContainer = Color(0xFF72333E),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFFE5BDC1),
    onSecondary = Color(0xFF43292D),
    secondaryContainer = Color(0xFF5C3F43),
    onSecondaryContainer = Color(0xFFFFD9DD),
    tertiary = Color(0xFFEABF8F),
    onTertiary = Color(0xFF452B07),
    tertiaryContainer = Color(0xFF5E411C),
    onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1E1719),
    onBackground = Color(0xFFF0DEDF),
    surface = Color(0xFF1E1719),
    onSurface = Color(0xFFF0DEDF),
    surfaceVariant = Color(0xFF524345),
    onSurfaceVariant = Color(0xFFD7C1C3),
    outline = Color(0xFF9F8C8E),
    outlineVariant = Color(0xFF524345),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF0DEDF),
    inverseOnSurface = Color(0xFF382E2F),
    inversePrimary = Color(0xFF8E4955),
    surfaceDim = Color(0xFF1A1112),
    surfaceBright = Color(0xFF413738),
    surfaceContainerLowest = Color(0xFF140C0D),
    surfaceContainerLow = Color(0xFF22191A),
    surfaceContainer = Color(0xFF261D1E),
    surfaceContainerHigh = Color(0xFF312828),
    surfaceContainerHighest = Color(0xFF3D3233),
)
