import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../widgets/profile_picker_sheet.dart';
import '../widgets/study_ui.dart';
import '../widgets/chat_context_settings.dart';
import '../widgets/accent_color_picker.dart';

class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = ref.watch(activeProfileIdProvider);
    final profile = ref
        .watch(profilesListProvider)
        .where((p) => p.id == id)
        .firstOrNull;
    return Scaffold(
      appBar: const StudyHeader('设置'),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          children: [
            StudySection(
              children: [
                StudyRow(
                  icon: Icons.person_outline,
                  title: profile?.name ?? '当前档案',
                  subtitle: '当前档案的数据独立保存',
                  onTap: () => ProfilePickerSheet.show(context),
                ),
              ],
            ),
            StudySection(
              children: [
                StudyRow(
                  icon: Icons.tune_outlined,
                  title: '模型与提供商',
                  onTap: () => context.push('/settings/providers'),
                ),
                StudyRow(
                  icon: Icons.notes_outlined,
                  title: '上下文预算',
                  subtitle: '长对话整理与任务连续性',
                  onTap: () => ChatContextSettings.show(context),
                ),
                StudyRow(
                  icon: Icons.extension_outlined,
                  title: 'Skills',
                  onTap: () => context.push('/settings/skills'),
                ),
                StudyRow(
                  icon: Icons.psychology_outlined,
                  title: '记忆',
                  onTap: () => context.push('/settings/memories'),
                ),
                StudyRow(
                  icon: Icons.description_outlined,
                  title: '文件与文档服务',
                  onTap: () => context.push('/settings/doc-service'),
                ),
              ],
            ),
            StudySection(
              children: [
                StudyRow(
                  icon: Icons.contrast_outlined,
                  title: '外观与交互',
                  onTap: () => context.push('/settings/appearance'),
                ),
                const AccentColorSetting(),
                StudyRow(
                  icon: Icons.backup_outlined,
                  title: '备份与恢复',
                  onTap: () => context.push('/settings/backup'),
                ),
                StudyRow(
                  icon: Icons.terminal_outlined,
                  title: '运行诊断',
                  onTap: () => context.push('/settings/diagnostics'),
                ),
                StudyRow(
                  icon: Icons.info_outline,
                  title: '关于',
                  onTap: () => showAboutDialog(
                    context: context,
                    applicationName: 'MathStudyAgent',
                    applicationLegalese: '通用 AI Agent 助手',
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
