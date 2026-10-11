import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/study_ui.dart';

class DocTaskDetailPage extends ConsumerStatefulWidget {
  final ProfileResourceRoute? resource;
  const DocTaskDetailPage({super.key, this.resource});
  @override
  ConsumerState<DocTaskDetailPage> createState() => _DocTaskDetailPageState();
}

class _DocTaskDetailPageState extends ConsumerState<DocTaskDetailPage> {
  bool busy = false;
  Future<void> act(DocumentTaskInfo task, String action) async {
    if (busy || readResource(ref, widget.resource, documentTasksProvider,
        (t) => t.id) == null) return;
    final owner = widget.resource!.profileId, epoch = widget.resource!.epoch;
    bool current() => mounted && widget.resource!.isCurrent(ref) &&
        readResource(ref, widget.resource, documentTasksProvider, (t) => t.id) != null;
    setState(() => busy = true);
    try {
      await FrontendFeatures.fromWidget(ref, 'docTaskAction', {
        'id': task.id,
        'action': action,
        'profileId': owner,
        'epoch': epoch,
      });
      if (current()) {
        ref.invalidate(documentTasksProvider);
        await ref.read(documentTasksProvider.future);
      }
    } catch (e) {
      if (current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = watchResource(ref, widget.resource, documentTasksProvider, (t) => t.id);
    final loading = widget.resource?.isCurrent(ref) == true &&
        ref.watch(documentTasksProvider).isLoading;
    return Scaffold(
      appBar: const StudyHeader('任务详情'),
      body: SafeArea(
        child: loading
            ? const Center(child: CircularProgressIndicator())
            : t == null
            ? const StudyEmptyState(
                icon: Icons.description_outlined,
                title: '任务不可用',
                description: '任务不存在或档案已变更，请从当前任务列表重新打开。',
              )
            : ListView(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                children: [
                  Text(
                    t.fileName,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 24),
                  StudySection(
                    title: '处理范围与状态',
                    children: [
                      StudyRow(title: '处理范围', subtitle: t.range),
                      StudyRow(title: '当前状态', subtitle: t.label),
                      StudyRow(
                        title: '结果下载',
                        subtitle: t.downloaded ? '结果已下载并校验' : '结果尚未下载',
                      ),
                      if (t.error != null)
                        StudyRow(title: '错误信息', subtitle: t.error),
                    ],
                  ),
                  if (t.state == 'PARTIAL')
                    Padding(
                      padding: const EdgeInsets.only(bottom: 20),
                      child: Text(
                        '仅部分范围已处理，请核对结果；不代表整个文件已完成。',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      OutlinedButton(
                        onPressed: busy ? null : () => act(t, 'refresh'),
                        child: const Text('刷新状态'),
                      ),
                      if (t.state == 'SUCCEEDED' || t.state == 'PARTIAL')
                        ElevatedButton(
                          onPressed: busy ? null : () => act(t, 'download'),
                          child: Text(t.downloaded ? '校验结果' : '下载结果'),
                        ),
                      if (t.running)
                        OutlinedButton(
                          onPressed: busy ? null : () => act(t, 'cancel'),
                          child: const Text('取消任务'),
                        ),
                    ],
                  ),
                  const SizedBox(height: 24),
                  TextButton(
                    onPressed: () => context.go('/chat'),
                    child: const Text('返回聊天'),
                  ),
                ],
              ),
      ),
    );
  }
}
