import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/app_settings_providers.dart';
import '../theme/study_theme.dart';
import '../widgets/study_ui.dart';
import '../widgets/accent_color_picker.dart';

class AppearancePage extends ConsumerWidget {
  const AppearancePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(themeModeProvider);
    final scale = ref.watch(fontScaleProvider);
    final c = StudyPalette.of(context);
    return Scaffold(
      appBar: const StudyHeader('外观与交互'),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          children: [
            StudySection(
              title: '主题',
              children: [
                const AccentColorSetting(),
                for (final entry in {
                  '跟随系统': ThemeMode.system,
                  '浅色': ThemeMode.light,
                  '深色': ThemeMode.dark,
                }.entries)
                  StudyRow(
                    title: entry.key,
                    trailing: mode == entry.value
                        ? const Icon(Icons.check, size: 20)
                        : const SizedBox(width: 20),
                    onTap: () => ref
                        .read(themeModeProvider.notifier)
                        .setThemeMode(entry.value),
                  ),
              ],
            ),
            StudySection(
              title: '显示与交互',
              children: [
                StudyRow(
                  title: '跟随系统字体大小',
                  subtitle: '保留系统设置的文字缩放',
                  trailing: Switch(
                    value: ref.watch(followSystemFontProvider),
                    onChanged: (v) => ref
                        .read(followSystemFontProvider.notifier)
                        .setFollow(v),
                  ),
                ),
                StudyRow(
                  title: '增大文字',
                  subtitle: '在当前字号基础上放大 25%',
                  trailing: Switch(
                    value: scale > 1,
                    onChanged: (v) => ref
                        .read(fontScaleProvider.notifier)
                        .setScale(v ? 1.25 : 1),
                  ),
                ),
                StudyRow(
                  title: '减少动态效果',
                  trailing: Switch(
                    value: ref.watch(reduceMotionSettingProvider),
                    onChanged: (v) => ref
                        .read(reduceMotionSettingProvider.notifier)
                        .setReduceMotion(v),
                  ),
                ),
                StudyRow(
                  title: '生成时跟随滚动',
                  subtitle: '向上阅读时不自动滚动',
                  trailing: Switch(
                    value: ref.watch(autoFollowScrollProvider),
                    onChanged: (v) => ref
                        .read(autoFollowScrollProvider.notifier)
                        .setAutoFollow(v),
                  ),
                ),
              ],
            ),
            Text('聊天预览', style: TextStyle(fontSize: 13, color: c.textGray)),
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: c.card,
                border: Border.all(color: c.borderGray),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Align(
                    alignment: Alignment.centerRight,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: c.userBubble,
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: const Text(
                        '先给结论，再说明理由。',
                        style: TextStyle(fontSize: 15),
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  const Text(
                    '好的，我会先说明结论，再按需要展开背景和步骤。',
                    style: TextStyle(fontSize: 16, height: 1.65),
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Icon(Icons.copy_outlined, size: 18, color: c.textGray),
                      const SizedBox(width: 24),
                      Icon(
                        Icons.thumb_up_outlined,
                        size: 18,
                        color: c.textGray,
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
