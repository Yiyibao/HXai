import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import '../widgets/study_ui.dart';

class SkillDetailPage extends ConsumerStatefulWidget {
  final SkillDto? skill;

  const SkillDetailPage({super.key, this.skill});

  @override
  ConsumerState<SkillDetailPage> createState() => _SkillDetailPageState();
}

class _SkillDetailPageState extends ConsumerState<SkillDetailPage> {
  late bool _enabled;
  bool _filesExpanded = false;

  @override
  void initState() {
    super.initState();
    _enabled = widget.skill?.enabled ?? true;
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.skill;
    if (s == null)
      return const Scaffold(
        appBar: StudyHeader('Skill 详情'),
        body: StudyEmptyState(
          icon: Icons.extension_outlined,
          title: '未选择 Skill',
          description: '请从 Skills 列表打开。',
        ),
      );
    final name = s.name;
    final description = s.description;
    final source = s.builtin == true ? '内置技能包' : '本地导入技能包';
    final license = (s.license ?? '').isEmpty ? '未提供许可信息' : s.license!;
    final supportScope = s.state == 'READY'
        ? '说明和资源可读取'
        : '说明和资源可读取（脚本运行需核查环境）';
    final dependencies = s.compatibility ?? '部分依赖未配置（运行时按需检查）';
    final files = (s.files.isNotEmpty) ? s.files : <String>[];

    return Scaffold(
      backgroundColor: StudyPalette.of(context).bgWhite,
      appBar: AppBar(
        backgroundColor: StudyPalette.of(context).bgWhite,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back,
            color: StudyPalette.of(context).textBlack,
          ),
          onPressed: () => context.pop(),
        ),
        title: Text(
          '技能详情',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
        actions: [
          PopupMenuButton<String>(
            icon: Icon(
              Icons.more_horiz,
              color: StudyPalette.of(context).textBlack,
            ),
            onSelected: (val) {
              if (val == 'uninstall') {
                bridgeApi
                    .openNativePage('skills')
                    .then((result) {
                      checkCommand(result);
                    })
                    .catchError((Object e) {
                      if (context.mounted)
                        ScaffoldMessenger.of(context)
                            .showSnackBar(SnackBar(content: Text('$e')));
                    });
              }
            },
            itemBuilder: (context) => [
              if (s.builtin != true)
                const PopupMenuItem(value: 'uninstall', child: Text('管理与卸载')),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 12,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Header title & description
                    Text(
                      name,
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: StudyPalette.of(context).textBlack,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      description,
                      style: TextStyle(
                        fontSize: 14,
                        color: StudyPalette.of(context).textGray,
                        height: 1.4,
                      ),
                    ),
                    Divider(
                      height: 28,
                      color: StudyPalette.of(context).borderGray,
                    ),

                    // Section 1: Source & License
                    _buildMetaRow('来源', source),
                    const SizedBox(height: 12),
                    _buildMetaRow('许可', license),
                    const SizedBox(height: 12),
                    _buildMetaRow('支持范围', supportScope),
                    const SizedBox(height: 12),
                    _buildMetaRow('所需依赖', dependencies),
                    Divider(
                      height: 28,
                      color: StudyPalette.of(context).borderGray,
                    ),

                    // Section 2: Package Files
                    InkWell(
                      onTap: () =>
                          setState(() => _filesExpanded = !_filesExpanded),
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              '包内文件 (${files.length})',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: StudyPalette.of(context).textBlack,
                              ),
                            ),
                            Icon(
                              _filesExpanded
                                  ? Icons.keyboard_arrow_up
                                  : Icons.keyboard_arrow_down,
                              size: 18,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ],
                        ),
                      ),
                    ),
                    if (_filesExpanded) ...[
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: StudyPalette.of(context).surfaceBackground,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: StudyPalette.of(context).borderGray,
                            width: 0.6,
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: files.map((f) {
                            return Padding(
                              padding: const EdgeInsets.symmetric(vertical: 4),
                              child: Row(
                                children: [
                                  Icon(
                                    f.endsWith('/')
                                        ? Icons.folder_outlined
                                        : Icons.insert_drive_file_outlined,
                                    size: 16,
                                    color: StudyPalette.of(context).textGray,
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    f,
                                    style: TextStyle(
                                      fontSize: 13,
                                      color: StudyPalette.of(context).textBlack,
                                      fontFamily: 'monospace',
                                    ),
                                  ),
                                ],
                              ),
                            );
                          }).toList(),
                        ),
                      ),
                    ],
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),

            // Bottom action bar
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: StudyPalette.of(context).bgWhite,
                border: Border(
                  top: BorderSide(
                    color: StudyPalette.of(context).borderGray,
                    width: 0.8,
                  ),
                ),
              ),
              child: Row(
                children: [
                  Row(
                    children: [
                      Switch(
                        value: _enabled,
                        onChanged: (val) async {
                          setState(() => _enabled = val);
                          {
                            try {
                              await ref
                                  .read(skillsProvider.notifier)
                                  .setEnabled(s.name, val);
                            } catch (e) {
                              if (mounted) setState(() => _enabled = !val);
                              if (context.mounted)
                                ScaffoldMessenger.of(
                                  context,
                                ).showSnackBar(SnackBar(content: Text('$e')));
                            }
                          }
                        },
                      ),
                      const SizedBox(width: 4),
                      Text(
                        '启用技能',
                        style: TextStyle(
                          fontSize: 14,
                          color: StudyPalette.of(context).textBlack,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                  const Spacer(),
                  OutlinedButton(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: StudyPalette.of(context).textBlack,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 18,
                        vertical: 12,
                      ),
                      side: BorderSide(
                        color: StudyPalette.of(context).borderGray,
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                    onPressed: () {
                      showDialog(
                        context: context,
                        builder: (ctx) => AlertDialog(
                          title: const Text('技能说明 (SKILL.md)'),
                          content: SingleChildScrollView(
                            child: Text(
                              s.detail.isNotEmpty == true
                                  ? s.detail
                                  : '暂无详细 Markdown 文档。',
                            ),
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(ctx),
                              child: const Text('关闭'),
                            ),
                          ],
                        ),
                      );
                    },
                    child: const Text('查看说明'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetaRow(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 12,
            color: StudyPalette.of(context).textGray,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          value,
          style: TextStyle(
            fontSize: 14,
            color: StudyPalette.of(context).textBlack,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }
}
