import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../theme/study_theme.dart';
import '../widgets/study_ui.dart';

class MemoryDetailPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const MemoryDetailPage({super.key, this.resource});
  Future<void> delete(BuildContext context, WidgetRef ref) async {
    if (readResource(ref, resource, memoriesProvider, (m) => m.id) == null) return;
    final deleted = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _MemoryDeleteSheet(resource: resource!),
    );
    if (deleted == true && context.mounted && resource!.isCurrent(ref)) {
      context.pop();
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final item = watchResource(ref, resource, memoriesProvider, (m) => m.id);
    final loading = resource?.isCurrent(ref) == true &&
        ref.watch(memoriesProvider).isLoading;
    return Scaffold(
      appBar: StudyHeader(
        '记忆详情',
        actions: [
          if (item != null)
            PopupMenuButton<String>(
              tooltip: '更多',
              onSelected: (_) => delete(context, ref),
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'delete', child: Text('删除记忆')),
              ],
            ),
        ],
      ),
      body: SafeArea(
        child: loading
            ? const Center(child: CircularProgressIndicator())
            : item == null
            ? const StudyEmptyState(
                icon: Icons.psychology_outlined,
                title: '记忆不可用',
                description: '记忆已删除或档案已变更，请从当前记忆列表重新打开。',
              )
            : Column(
                children: [
                  Expanded(
                    child: ListView(
                      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                      children: [
                        Text(
                          item.content,
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(height: 1.6),
                        ),
                        const SizedBox(height: 28),
                        StudySection(
                          title: '来源',
                          children: [
                            StudyRow(
                              icon: Icons.chat_bubble_outline,
                              title: item.sourceTopic.isEmpty
                                  ? '来源记录未提供'
                                  : item.sourceTopic,
                              subtitle: (item.sourceQuote ?? '').isNotEmpty
                                  ? item.sourceQuote
                                  : (item.sourceFact.isEmpty
                                        ? '没有可核对的来源原文'
                                        : item.sourceFact),
                            ),
                          ],
                        ),
                        StudySection(
                          title: '版本',
                          children: [
                            StudyRow(
                              title: '版本 ${item.revision}',
                              subtitle:
                                  '最后修改：${DateTime.fromMillisecondsSinceEpoch(item.updatedAt).toString().split('.').first}',
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                    child: Wrap(
                      spacing: 12,
                      runSpacing: 12,
                      children: [
                        ElevatedButton(
                          onPressed: () {
                            if (readResource(ref, resource, memoriesProvider,
                                (m) => m.id) == null) return;
                            context.push('/settings/memories/edit', extra: resource);
                          },
                          child: const Text('编辑'),
                        ),
                        OutlinedButton(
                          onPressed: (item.sourceConversationId ?? '').isEmpty
                              ? null
                              : () {
                                  final latest = readResource(ref, resource,
                                      memoriesProvider, (m) => m.id);
                                  if (latest == null) return;
                                  ref
                                      .read(
                                        activeConversationIdProvider.notifier,
                                      )
                                      .state = latest.sourceConversationId;
                                  context.go('/chat');
                                },
                          child: const Text('查看来源聊天'),
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

class _MemoryDeleteSheet extends ConsumerStatefulWidget {
  final ProfileResourceRoute resource;
  const _MemoryDeleteSheet({required this.resource});
  @override
  ConsumerState<_MemoryDeleteSheet> createState() => _MemoryDeleteSheetState();
}

class _MemoryDeleteSheetState extends ConsumerState<_MemoryDeleteSheet> {
  bool deleting = false;

  Future<void> confirm() async {
    final item = readResource(ref, widget.resource, memoriesProvider, (m) => m.id);
    if (item == null || deleting) return;
    setState(() => deleting = true);
    try {
      checkCommand(await bridgeApi.deleteMemory(
        widget.resource.profileId,
        item.id,
        'del_${DateTime.now().microsecondsSinceEpoch}',
      ));
      if (!mounted || !widget.resource.isCurrent(ref)) return;
      await ref.read(memoriesProvider.notifier).refresh();
      if (mounted && widget.resource.isCurrent(ref)) Navigator.pop(context, true);
    } catch (e) {
      if (mounted && readResource(ref, widget.resource, memoriesProvider,
          (m) => m.id) != null) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      }
    } finally {
      if (mounted) setState(() => deleting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final item = watchResource(ref, widget.resource, memoriesProvider, (m) => m.id);
    if (item == null) {
      return const SafeArea(
        child: StudyEmptyState(
          icon: Icons.psychology_outlined,
          title: '记忆不可用',
          description: '内容已撤销，请关闭此窗口并从当前列表重新打开。',
        ),
      );
    }
    return SafeArea(
      top: false,
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('删除这条记忆？', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 14),
            Text(item.content),
            const SizedBox(height: 12),
            const Text('删除后，后续回答不再使用这条记忆。聊天原文仍会保留。'),
            const SizedBox(height: 24),
            Row(children: [
              Expanded(child: OutlinedButton(
                onPressed: deleting ? null : () => Navigator.pop(context),
                child: const Text('取消'),
              )),
              const SizedBox(width: 12),
              Expanded(child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: StudyPalette.of(context).errorRed,
                  foregroundColor: Colors.white,
                ),
                onPressed: deleting ? null : confirm,
                child: Text(deleting ? '删除中…' : '删除'),
              )),
            ]),
          ],
        ),
      ),
    );
  }
}
