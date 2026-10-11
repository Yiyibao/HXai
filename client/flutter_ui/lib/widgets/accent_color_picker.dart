import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/app_settings_providers.dart';
import '../theme/study_theme.dart';

class AccentColorSetting extends ConsumerWidget {
  const AccentColorSetting({super.key});

  static void show(BuildContext context) => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _AccentColorPicker(),
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final color = ref.watch(accentColorProvider);
    final c = StudyPalette.of(context);
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: const Icon(Icons.palette_outlined),
      title: const Text('高亮色'),
      subtitle: const Text('用于选中项、消息气泡与主要按钮'),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _ColorDot(color: c.accent),
          const SizedBox(width: 8),
          Text(color.labelFor(c.isDark)),
          const SizedBox(width: 4),
          const Icon(Icons.chevron_right, size: 20),
        ],
      ),
      onTap: () => show(context),
    );
  }
}

class _AccentColorPicker extends ConsumerWidget {
  const _AccentColorPicker();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(accentColorProvider);
    final c = StudyPalette(
      Theme.of(context).brightness == Brightness.dark,
      selected,
    );
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.85,
        ),
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    '高亮色',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('完成'),
                ),
              ],
            ),
            Text('选择后立即生效，浅色与深色主题分别适配。', style: TextStyle(color: c.textGray)),
            const SizedBox(height: 16),
            Container(
              key: const ValueKey('accent-preview'),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: c.selectionFill,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '选中对话 · 消息气泡',
                      style: TextStyle(color: c.textBlack),
                    ),
                  ),
                  const SizedBox(width: 8),
                  CircleAvatar(
                    backgroundColor: c.accent,
                    child: Icon(Icons.arrow_upward, color: c.onAccent),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            for (final color in AccentColor.values)
              ListTile(
                key: ValueKey('accent-${color.name}'),
                contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                leading: _ColorDot(color: c.isDark ? color.dark : color.light),
                title: Text(color.labelFor(c.isDark)),
                selected: selected == color,
                selectedColor: c.textBlack,
                trailing: selected == color
                    ? Icon(Icons.check, color: c.accent)
                    : null,
                onTap: () =>
                    ref.read(accentColorProvider.notifier).setColor(color),
              ),
          ],
        ),
      ),
    );
  }
}

class _ColorDot extends StatelessWidget {
  const _ColorDot({required this.color});
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    width: 22,
    height: 22,
    decoration: BoxDecoration(
      color: color,
      shape: BoxShape.circle,
      border: Border.all(color: Theme.of(context).dividerColor),
    ),
  );
}
