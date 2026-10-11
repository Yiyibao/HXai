import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';
import '../widgets/context_follow_up.dart';
import 'artifact_preview_page.dart';

class FileDetailPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const FileDetailPage({super.key, this.resource});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = watchResource(ref, resource, materialsProvider, (m) => m.id);
    final loading = resource?.isCurrent(ref) == true &&
        ref.watch(materialsProvider).isLoading;
    return Scaffold(
      appBar: const StudyHeader('文件详情'),
      body: SafeArea(
        child: loading
            ? const Center(child: CircularProgressIndicator())
            : current == null
            ? const StudyEmptyState(
                icon: Icons.description_outlined,
                title: '文件不可用',
                description: '文件已删除或档案已变更，请从当前文件库重新打开。',
              )
            : Column(
                children: [
                  Expanded(
                    child: ListView(
                      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                      children: [
                        Text(
                          current.name,
                          style: Theme.of(context).textTheme.headlineMedium,
                        ),
                        const SizedBox(height: 10),
                        Text(
                          '${current.extension.toUpperCase()} · ${formatFileSize(current.size)}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: 24),
                        StudySection(
                          title: '文件信息',
                          children: [
                            StudyRow(
                              title: '文件状态',
                              subtitle: fileStateLabel(current.state),
                            ),
                            StudyRow(
                              title: '处理信息',
                              subtitle: current.detail.isEmpty
                                  ? '尚无处理记录；添加文件不会自动解析全文。'
                                  : current.detail,
                            ),
                            if (current.pageNumber != null)
                              StudyRow(
                                title: '引用原页',
                                subtitle: '第 ${current.pageNumber} 页',
                              ),
                            StudyRow(
                              title: '添加时间',
                              subtitle: DateTime.fromMillisecondsSinceEpoch(
                                current.createdAt,
                              ).toString().split('.').first,
                            ),
                          ],
                        ),
                        if (current.symbolCheckPending)
                          StudySection(
                            children: [
                              StudyRow(
                                icon: Icons.fact_check_outlined,
                                title: '原页核对',
                                subtitle: '对照原文件，确认提取的符号与文字',
                                onTap: () {
                                  if (readResource(ref, resource, materialsProvider,
                                      (m) => m.id) == null) return;
                                  context.push('/files/symbol-check', extra: resource);
                                },
                              ),
                            ],
                          ),
                        if ((current.symbolCorrection ?? '').isNotEmpty)
                          StudySection(
                            title: '已核对内容',
                            children: [
                              StudyRow(title: current.symbolCorrection!),
                            ],
                          ),
                        OutlinedButton.icon(
                          icon: const Icon(Icons.open_in_new, size: 18),
                          label: const Text('查看原文件'),
                          onPressed: () async {
                            if (readResource(ref, resource, materialsProvider,
                                (m) => m.id) == null) return;
                            try {
                              checkCommand(
                                await bridgeApi.openNativePage('files'),
                              );
                            } catch (e) {
                              if (context.mounted &&
                                  readResource(ref, resource, materialsProvider,
                                      (m) => m.id) != null)
                                ScaffoldMessenger.of(
                                  context,
                                ).showSnackBar(SnackBar(content: Text('$e')));
                            }
                          },
                        ),
                      ],
                    ),
                  ),
                  ContextFollowUp(
                    key: ValueKey('${resource?.profileId}:${resource?.epoch}:${current.id}'),
                    hint: '针对这个文件提问',
                    materialId: current.id,
                    contextTextBuilder: () {
                      final latest = readResource(ref, resource, materialsProvider,
                          (m) => m.id);
                      return latest == null ? '' : '文件 ${latest.name}，文件 ID：${latest.id}';
                    },
                    isAvailable: () => readResource(ref, resource,
                        materialsProvider, (m) => m.id) != null,
                    onSent: () {
                      if (readResource(ref, resource, materialsProvider,
                          (m) => m.id) != null) context.go('/chat');
                    },
                  ),
                ],
              ),
      ),
    );
  }
}

String fileStateLabel(String state) => switch (state.toUpperCase()) {
  'SUCCEEDED' => '文件已保存',
  'RUNNING' => '正在处理',
  'QUEUED' || 'WAITING' => '等待处理',
  'FAILED' => '处理失败',
  'CANCELLED' || 'CANCELED' => '已取消',
  'PARTIAL' => '部分完成',
  _ => state,
};
