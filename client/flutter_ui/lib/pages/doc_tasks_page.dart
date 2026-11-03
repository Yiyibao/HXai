import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/study_ui.dart';

class DocTasksPage extends ConsumerStatefulWidget {
  const DocTasksPage({super.key});
  @override
  ConsumerState<DocTasksPage> createState() => _DocTasksPageState();
}

class _DocTasksPageState extends ConsumerState<DocTasksPage> {
  String filter = '全部';
  @override
  Widget build(BuildContext context) {
    final tasks = ref.watch(documentTasksProvider);
    return Scaffold(
      appBar: StudyHeader(
        '文档任务',
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(documentTasksProvider),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: ['全部', '进行中', '已完成', '失败']
                      .map(
                        (label) => ChoiceChip(
                          label: Text(label),
                          selected: filter == label,
                          showCheckmark: false,
                          onSelected: (_) => setState(() => filter = label),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
            Expanded(
              child: tasks.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => StudyEmptyState(
                  icon: Icons.error_outline,
                  title: '任务加载失败',
                  description: '$e',
                  action: OutlinedButton(
                    onPressed: () => ref.invalidate(documentTasksProvider),
                    child: const Text('重试'),
                  ),
                ),
                data: (all) {
                  final list = all
                      .where(
                        (t) =>
                            filter == '全部' ||
                            (filter == '进行中' && t.running) ||
                            (filter == '已完成' && t.downloaded) ||
                            (filter == '失败' && t.state == 'FAILED'),
                      )
                      .toList();
                  if (list.isEmpty)
                    return StudyEmptyState(
                      icon: Icons.description_outlined,
                      title: filter == '全部' ? '还没有文档任务' : '没有符合条件的任务',
                      description: '在聊天中提出文件处理要求后，可在这里查看实际处理范围与状态。',
                    );
                  return RefreshIndicator(
                    onRefresh: () async {
                      ref.invalidate(documentTasksProvider);
                      await ref.read(documentTasksProvider.future);
                    },
                    child: ListView.separated(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      itemCount: list.length,
                      separatorBuilder: (_, _) => const Divider(),
                      itemBuilder: (context, i) => StudyRow(
                        icon: Icons.description_outlined,
                        title: list[i].fileName,
                        subtitle: '${list[i].range} · ${list[i].label}',
                        onTap: () => context.push(
                          '/settings/doc-service/task-detail',
                          extra: ProfileResourceRoute.capture(ref, list[i].id),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
