import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';

class SkillsPage extends ConsumerStatefulWidget {
  const SkillsPage({super.key});

  @override
  ConsumerState<SkillsPage> createState() => _SkillsPageState();
}

class _SkillsPageState extends ConsumerState<SkillsPage>
    with WidgetsBindingObserver {
  final Set<String> _busy = {};

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
    if (state == AppLifecycleState.resumed) {
      ref.read(skillsProvider.notifier).refresh();
    }
  }

  Future<void> _toggleSkill(SkillDto skill, bool enabled) async {
    setState(() => _busy.add(skill.name));
    try {
      await ref.read(skillsProvider.notifier).setEnabled(skill.name, enabled);
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => _busy.remove(skill.name));
    }
  }

  @override
  Widget build(BuildContext context) {
    final skillsAsync = ref.watch(skillsProvider);

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
          'Skills',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
        actions: [
          IconButton(
            icon: Icon(
              Icons.add,
              color: StudyPalette.of(context).textBlack,
              size: 22,
            ),
            tooltip: '导入技能',
            onPressed: () => context.push('/settings/skills/import'),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Top note
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                children: [
                  Icon(
                    Icons.info_outline,
                    size: 14,
                    color: StudyPalette.of(context).textGray,
                  ),
                  SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '按任务使用已启用的技能，支持范围受环境约束',
                      style: TextStyle(
                        fontSize: 12,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),

            // Skills list
            Expanded(
              child: skillsAsync.when(
                loading: () => Center(
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                error: (err, _) => Center(
                  child: Text(
                    '加载失败: $err',
                    style: TextStyle(color: StudyPalette.of(context).textGray),
                  ),
                ),
                data: (skills) {
                  if (skills.isEmpty) {
                    return Center(
                      child: Text(
                        '暂无技能，点击右上角加号导入',
                        style: TextStyle(
                          color: StudyPalette.of(context).textGray,
                        ),
                      ),
                    );
                  }

                  return ListView.separated(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    itemCount: skills.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 12),
                    itemBuilder: (context, index) {
                      final skill = skills[index];
                      return _buildSkillCard(context, skill);
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSkillCard(BuildContext context, SkillDto skill) {
    final isBusy = _busy.contains(skill.name);
    final statusText = skill.builtin
        ? '内置 · 说明和资源可读取'
        : (skill.state == 'READY' ? '已导入 · 说明和资源可读取' : '已导入 · 部分支持');

    return Container(
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: InkWell(
                  onTap: () =>
                      context.push('/settings/skills/detail', extra: skill),
                  borderRadius: BorderRadius.circular(8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            skill.name,
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: StudyPalette.of(context).textBlack,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            statusText,
                            style: TextStyle(
                              fontSize: 11,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        skill.description,
                        style: TextStyle(
                          fontSize: 13,
                          color: StudyPalette.of(context).textGray,
                          height: 1.3,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (skill.detail.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          skill.detail,
                          style: TextStyle(
                            fontSize: 11,
                            color: StudyPalette.of(context).textGray,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              Switch(
                value: skill.enabled,
                onChanged: isBusy ? null : (val) => _toggleSkill(skill, val),
              ),
            ],
          ),
          Divider(height: 18, color: StudyPalette.of(context).borderGray),
          InkWell(
            onTap: () => context.push('/settings/skills/detail', extra: skill),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    skill.state != 'READY' ? '查看依赖与声明' : '查看技能详情',
                    style: TextStyle(
                      fontSize: 12,
                      color: StudyPalette.of(context).chartColor,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  Icon(
                    Icons.chevron_right,
                    size: 16,
                    color: StudyPalette.of(context).textGray,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
