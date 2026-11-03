import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class BackupPage extends ConsumerWidget {
  const BackupPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: const StudyHeader('备份与恢复'),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          StudySection(
            title: '创建备份',
            children: [
              StudyRow(
                icon: Icons.backup_outlined,
                title: '备份当前数据',
                subtitle: '备份范围和加密方式以现有备份工具提供的选项为准。',
              ),
            ],
          ),
          ElevatedButton.icon(
            onPressed: () => _openBackup(context),
            icon: const Icon(Icons.download_outlined, size: 18),
            label: const Text('打开备份工具'),
          ),
          const SizedBox(height: 28),
          StudySection(
            title: '恢复备份',
            children: [
              StudyRow(
                icon: Icons.restore_outlined,
                title: '从已有备份恢复',
                subtitle: '选择文件、检查内容和确认恢复均由现有备份工具完成。',
              ),
            ],
          ),
          OutlinedButton(
            onPressed: () => context.push('/settings/backup/restore'),
            child: const Text('恢复备份'),
          ),
          const SizedBox(height: 24),
          Text(
            '备份和恢复由原生工具执行；本页不会在未操作时显示备份或恢复成功。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    ),
  );
}

Future<void> openBackupTool(BuildContext context) => _openBackup(context);
Future<void> _openBackup(BuildContext context) async {
  try {
    checkCommand(await bridgeApi.openNativePage('backup'));
  } catch (e) {
    if (context.mounted)
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
  }
}
