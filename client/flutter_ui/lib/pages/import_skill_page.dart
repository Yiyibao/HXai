import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class ImportSkillPage extends ConsumerWidget {
  const ImportSkillPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: const StudyHeader('导入 Skill'),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          StudySection(
            title: '导入方式',
            children: [
              const StudyRow(
                icon: Icons.folder_zip_outlined,
                title: '选择技能包',
                subtitle: '使用现有技能管理工具选择文件或目录',
              ),
              const StudyRow(
                icon: Icons.fact_check_outlined,
                title: '检查内容与权限',
                subtitle: '导入前阅读 SKILL.md、脚本和所需权限',
              ),
            ],
          ),
          Text(
            '导入完成不代表所有脚本可运行；运行依赖和权限需要单独确认。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 24),
          ElevatedButton.icon(
            icon: const Icon(Icons.open_in_new, size: 18),
            label: const Text('打开技能导入工具'),
            onPressed: () async {
              try {
                checkCommand(await bridgeApi.openNativePage('skills'));
              } catch (e) {
                if (context.mounted)
                  ScaffoldMessenger.of(context)
                      .showSnackBar(SnackBar(content: Text('$e')));
              }
            },
          ),
        ],
      ),
    ),
  );
}
