import 'package:flutter/material.dart';

import '../widgets/study_ui.dart';
import 'backup_page.dart';

class RestoreBackupPage extends StatelessWidget {
  final String? backupFileName;
  const RestoreBackupPage({super.key, this.backupFileName});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: const StudyHeader('恢复备份'),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          StudySection(
            title: '恢复前确认',
            children: [
              StudyRow(
                icon: Icons.folder_open_outlined,
                title: backupFileName ?? '尚未选择备份文件',
                subtitle: '在现有备份工具中选择并验证文件',
              ),
              const StudyRow(
                title: '确认恢复范围',
                subtitle: '恢复会影响当前数据。先核对备份内容、恢复范围以及工具要求的密码。',
              ),
            ],
          ),
          ElevatedButton(
            onPressed: () => openBackupTool(context),
            child: const Text('选择备份并检查'),
          ),
          const SizedBox(height: 20),
          Text(
            '在原生工具中明确确认后才会开始恢复。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    ),
  );
}
