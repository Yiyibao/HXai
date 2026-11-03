import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router.dart';
import 'theme/study_theme.dart';
import 'providers/app_settings_providers.dart';
import 'providers/study_providers.dart';
import 'providers/frontend_feature_providers.dart';
import 'providers/image_creation_providers.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppearanceStorage.load();
  runApp(const ProviderScope(child: MathStudyApp()));
}

class MathStudyApp extends ConsumerStatefulWidget {
  const MathStudyApp({super.key});
  @override
  ConsumerState<MathStudyApp> createState() => _MathStudyAppState();
}

class _MathStudyAppState extends ConsumerState<MathStudyApp>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    // Native backup tools and file operations may have changed identity or data.
    // Revoke cached detail content before any resumed frame can expose it.
    ref.invalidate(bootstrapProvider);
    ref.invalidate(materialsProvider);
    ref.invalidate(memoriesProvider);
    ref.invalidate(documentTasksProvider);
    ref.invalidate(artifactsProvider);
    ref.invalidate(imageTasksProvider);
    ref.invalidate(imageConfigProvider);
  }

  @override
  Widget build(BuildContext context) {
    final themeMode = ref.watch(themeModeProvider);
    final accent = ref.watch(accentColorProvider);
    final fontScale = ref.watch(fontScaleProvider);
    final followSystemFont = ref.watch(followSystemFontProvider);
    final reduceMotion = ref.watch(reduceMotionSettingProvider);

    return MaterialApp.router(
      title: 'MathStudyAgent',
      debugShowCheckedModeBanner: false,
      theme: StudyTheme.lightTheme(accent: accent),
      darkTheme: StudyTheme.darkTheme(accent: accent),
      themeMode: themeMode,
      builder: (context, child) {
        final dark = Theme.of(context).brightness == Brightness.dark;
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: SystemUiOverlayStyle(
            statusBarColor: Colors.transparent,
            statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
            systemNavigationBarColor: dark
                ? const Color(0xFF171717)
                : Colors.white,
            systemNavigationBarIconBrightness: dark
                ? Brightness.light
                : Brightness.dark,
          ),
          child: MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(
                (followSystemFont
                        ? MediaQuery.textScalerOf(context).scale(1)
                        : 1) *
                    fontScale,
              ),
              disableAnimations:
                  MediaQuery.of(context).disableAnimations || reduceMotion,
            ),
            child: child ?? const SizedBox.shrink(),
          ),
        );
      },
      routerConfig: router,
    );
  }
}
