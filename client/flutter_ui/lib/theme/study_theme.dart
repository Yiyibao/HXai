import 'package:flutter/material.dart';

enum AccentColor {
  blue('蓝色', Color(0xFF175CD3), Color(0xFF8AB4FF)),
  green('绿色', Color(0xFF16734B), Color(0xFF75D5A6)),
  yellow('黄色', Color(0xFF805B00), Color(0xFFF0CF65)),
  pink('粉色', Color(0xFF925568), Color(0xFFEAB2C4)),
  orange('橙色', Color(0xFFAD4C12), Color(0xFFFFB581)),
  purple('紫色', Color(0xFF7041B4), Color(0xFFC7A4F4)),
  neutral('黑色', Color(0xFF171717), Color(0xFFF3F3F3));

  const AccentColor(this.label, this.light, this.dark);
  final String label;
  final Color light;
  final Color dark;
  String labelFor(bool isDark) => this == neutral && isDark ? '白色' : label;
  static AccentColor parse(Object? value) =>
      values.where((color) => color.name == value).firstOrNull ?? pink;
}

class StudyAccent extends ThemeExtension<StudyAccent> {
  const StudyAccent(this.color);
  final AccentColor color;
  @override
  StudyAccent copyWith({AccentColor? color}) =>
      StudyAccent(color ?? this.color);
  @override
  StudyAccent lerp(covariant StudyAccent? other, double t) =>
      t < 0.5 ? this : other ?? this;
}

/// Design tokens following ChatGPT-inspired neutral white/black/gray design
/// Reference: OPUS55_SMART_CHAT_IMPLEMENTATION_HANDOFF.md
class StudyColors {
  // ===== New Neutral Design System (ChatGPT-inspired) =====
  // Main background / Assistant text background
  static const Color bgWhite = Color(0xFFFFFFFF);

  // Primary text / Main operations
  static const Color textBlack = Color(0xFF171717);

  // Secondary text
  static const Color textGray = Color(0xFF737373);

  // User bubble / Input box background
  static const Color bubbleGray = Color(0xFFF4F4F4);
  static const Color userBubble = Color(0xFFF1F1F1);

  // Card borders / Dividers
  static const Color borderGray = Color(0xFFE5E5E5);

  // Chart data color (restrained gray-blue)
  static const Color chartBlue = Color(0xFF426A92);
  static const Color chartColor = chartBlue;
  static const Color surfaceBackground = bubbleGray;

  // States
  static const Color successGreen = Color(0xFF2E7D32);
  static const Color warningOrange = Color(0xFFE65100);
  static const Color errorRed = Color(0xFFB00020);

  // Compatibility aliases resolve to the adopted neutral palette.
  static const Color pureWhite = Color(0xFFFFFFFF);
  static const Color palePinkBg = bubbleGray;
  static const Color palePinkCard = bubbleGray;
  static const Color deepRose = textBlack;
  static const Color roseDark = textBlack;
  static const Color textPrimary = textBlack;
  static const Color textSecondary = textGray;
  static const Color textTertiary = textGray;
  static const Color surfaceBorder = borderGray;
  static const Color surfaceCard = Color(0xFFFFFFFF);
  static const Color divider = borderGray;

  // Dark mode counterparts
  static const Color darkBg = Color(0xFF171717);
  static const Color darkSurface = Color(0xFF202020);
  static const Color darkCard = Color(0xFF202020);
  static const Color darkPalePink = Color(0xFF2D2D2D);
  static const Color darkDeepRose = Color(0xFFF3F3F3);
  static const Color darkTextPrimary = Color(0xFFF3F3F3);
  static const Color darkTextSecondary = Color(0xFFA3A3A3);
}

/// Semantic colors resolved from the current theme on every build.
/// Legacy names map to neutral tokens while older components are migrated.
class StudyPalette {
  final bool isDark;
  final AccentColor accentColor;
  const StudyPalette(this.isDark, [this.accentColor = AccentColor.neutral]);
  static StudyPalette of(BuildContext context) => StudyPalette(
    Theme.of(context).brightness == Brightness.dark,
    Theme.of(context).extension<StudyAccent>()?.color ?? AccentColor.neutral,
  );
  Color get accent => isDark ? accentColor.dark : accentColor.light;
  Color get selectionFill => accentColor == AccentColor.pink && !isDark
      ? const Color(0xFFFFF1F5)
      : Color.alphaBlend(
          accent.withValues(alpha: isDark ? 0.16 : 0.10),
          bgWhite,
        );
  Color get bgWhite => isDark ? const Color(0xFF171717) : StudyColors.bgWhite;
  Color get textBlack =>
      isDark ? const Color(0xFFF3F3F3) : StudyColors.textBlack;
  Color get textGray => isDark ? const Color(0xFFA3A3A3) : StudyColors.textGray;
  Color get textTertiary => textGray;
  Color get bubbleGray =>
      isDark ? const Color(0xFF2D2D2D) : StudyColors.bubbleGray;
  Color get userBubble => selectionFill;
  Color get surfaceBackground => bubbleGray;
  Color get borderGray =>
      isDark ? const Color(0xFF404040) : StudyColors.borderGray;
  Color get card => isDark ? const Color(0xFF202020) : StudyColors.bgWhite;
  Color get onAccent => isDark ? const Color(0xFF171717) : Colors.white;
  Color get chartBlue =>
      isDark ? const Color(0xFF8AAAC9) : StudyColors.chartBlue;
  Color get chartColor => chartBlue;
  Color get errorRed =>
      isDark ? const Color(0xFFE69898) : const Color(0xFF9B3737);
  Color get warningOrange => textGray;
  Color get successGreen => textGray;
  Color get deepRose => textBlack;
  Color get palePinkCard => bubbleGray;
  Color get palePinkBg => bubbleGray;
  Color get surfaceBorder => borderGray;
  Color get darkSurface => card;
}

class StudyTheme {
  static ThemeData lightTheme({AccentColor accent = AccentColor.pink}) =>
      _theme(Brightness.light, accent);
  static ThemeData darkTheme({AccentColor accent = AccentColor.pink}) =>
      _theme(Brightness.dark, accent);

  static ThemeData _theme(Brightness brightness, AccentColor accent) {
    final c = StudyPalette(brightness == Brightness.dark, accent);
    final scheme = ColorScheme.fromSeed(
      seedColor: c.accent,
      brightness: brightness,
      primary: c.accent,
      onPrimary: c.onAccent,
      primaryContainer: c.selectionFill,
      onPrimaryContainer: c.textBlack,
      secondary: c.textGray,
      onSecondary: c.bgWhite,
      surface: c.bgWhite,
      onSurface: c.textBlack,
      error: c.errorRed,
      outline: c.borderGray,
      outlineVariant: c.borderGray,
      surfaceContainerHighest: c.bubbleGray,
    );
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide(color: c.borderGray),
    );
    final buttonShape = roundedCornerShape(12);
    return ThemeData(
      useMaterial3: true,
      extensions: [StudyAccent(accent)],
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: c.accent,
        selectionColor: c.accent.withValues(alpha: 0.25),
        selectionHandleColor: c.accent,
      ),
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: c.bgWhite,
      canvasColor: c.bgWhite,
      dividerColor: c.borderGray,
      splashFactory: InkRipple.splashFactory,
      visualDensity: VisualDensity.standard,
      iconTheme: IconThemeData(color: c.textBlack, size: 22),
      textTheme: TextTheme(
        headlineMedium: TextStyle(
          fontSize: 24,
          height: 1.3,
          fontWeight: FontWeight.w600,
          color: c.textBlack,
        ),
        titleLarge: TextStyle(
          fontSize: 18,
          height: 1.4,
          fontWeight: FontWeight.w600,
          color: c.textBlack,
        ),
        titleMedium: TextStyle(
          fontSize: 16,
          height: 1.4,
          fontWeight: FontWeight.w600,
          color: c.textBlack,
        ),
        bodyLarge: TextStyle(fontSize: 16, height: 1.65, color: c.textBlack),
        bodyMedium: TextStyle(fontSize: 15, height: 1.5, color: c.textBlack),
        bodySmall: TextStyle(fontSize: 12, height: 1.5, color: c.textGray),
        labelLarge: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w500,
          color: c.textBlack,
        ),
        labelSmall: TextStyle(fontSize: 12, color: c.textGray),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: c.bgWhite,
        foregroundColor: c.textBlack,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        centerTitle: true,
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w600,
          color: c.textBlack,
        ),
      ),
      cardTheme: CardThemeData(
        color: c.card,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: roundedCornerShape(14, side: BorderSide(color: c.borderGray)),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: c.bubbleGray,
        hintStyle: TextStyle(color: c.textGray, fontSize: 15),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 14,
        ),
        border: border,
        enabledBorder: border,
        focusedBorder: border.copyWith(borderSide: BorderSide(color: c.accent)),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: c.accent,
          foregroundColor: c.onAccent,
          elevation: 0,
          minimumSize: const Size(48, 48),
          shape: buttonShape,
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: c.accent,
          foregroundColor: c.onAccent,
          minimumSize: const Size(48, 48),
          shape: buttonShape,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: c.textBlack,
          side: BorderSide(color: c.borderGray),
          minimumSize: const Size(48, 48),
          shape: buttonShape,
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: c.textBlack,
          minimumSize: const Size(48, 48),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          foregroundColor: c.textBlack,
          minimumSize: const Size(48, 48),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? c.onAccent : c.textGray,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? c.accent : c.bubbleGray,
        ),
        trackOutlineColor: WidgetStatePropertyAll(c.borderGray),
      ),
      checkboxTheme: CheckboxThemeData(
        fillColor: WidgetStateProperty.resolveWith(
          (s) =>
              s.contains(WidgetState.selected) ? c.accent : Colors.transparent,
        ),
        checkColor: WidgetStatePropertyAll(c.onAccent),
        side: BorderSide(color: c.textGray),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: c.bgWhite,
        selectedColor: c.selectionFill,
        side: BorderSide(color: c.borderGray),
        shape: roundedCornerShape(10),
        labelStyle: TextStyle(fontSize: 13, color: c.textBlack),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: c.bgWhite,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: c.card,
        surfaceTintColor: Colors.transparent,
        shape: roundedCornerShape(20),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: c.card,
        surfaceTintColor: Colors.transparent,
        shape: roundedCornerShape(14),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: c.textBlack,
        contentTextStyle: TextStyle(color: c.onAccent),
        shape: roundedCornerShape(12),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: c.textBlack),
      dividerTheme: DividerThemeData(
        color: c.borderGray,
        thickness: 0.7,
        space: 1,
      ),
    );
  }

  static RoundedRectangleBorder roundedCornerShape(
    double radius, {
    BorderSide side = BorderSide.none,
  }) => RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(radius),
    side: side,
  );
}
