import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../theme/study_theme.dart';
import '../widgets/study_ui.dart';
import '../widgets/math_markdown.dart';
import '../widgets/context_follow_up.dart';

class ArtifactPreviewPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const ArtifactPreviewPage({super.key, this.resource});
  Future<void> _action(
    BuildContext context,
    WidgetRef ref,
    ArtifactDto item,
    bool save,
  ) async {
    if (readResource(ref, resource, artifactsProvider, (item) => item.id) != item) return;
    try {
      final notifier = ref.read(artifactsProvider.notifier);
      if (save) {
        await notifier.saveArtifact(item.id);
      } else {
        await notifier.openArtifact(item.id);
      }
      if (save && context.mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('文件已保存')));
    } catch (e) {
      if (context.mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = StudyPalette.of(context);
    final data = ref.watch(artifactsProvider);
    final item = watchResource(ref, resource, artifactsProvider, (item) => item.id);
    final preview = item == null ? null : ref.watch(artifactPreviewProvider(item.id));
    final available = item != null && item.state == 'SUCCEEDED';
    return Scaffold(
      appBar: StudyHeader(
        '成果预览',
        actions: [
          IconButton(
            tooltip: '版本与修改',
            icon: const Icon(Icons.history_outlined),
            onPressed: item == null
                ? null
                : () => context.push(
                    '/artifacts/versions',
                    extra: resource,
                  ),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: data.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => StudyEmptyState(
                  icon: Icons.error_outline,
                  title: '无法加载成果',
                  description: '$e',
                  action: OutlinedButton(
                    onPressed: () =>
                        ref.read(artifactsProvider.notifier).refresh(),
                    child: const Text('重试'),
                  ),
                ),
                data: (_) => item == null
                    ? const StudyEmptyState(
                        icon: Icons.description_outlined,
                        title: '成果不可用',
                        description: '请从成果列表或聊天中的成果卡片打开。',
                      )
                    : SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              item.name,
                              style: TextStyle(
                                fontSize: 22,
                                fontWeight: FontWeight.w600,
                                height: 1.4,
                                color: c.textBlack,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '${item.extension.toUpperCase()} · ${formatFileSize(item.size)}',
                              style: TextStyle(fontSize: 12, color: c.textGray),
                            ),
                            const SizedBox(height: 20),
                            Divider(color: c.borderGray),
                            const SizedBox(height: 20),
                            if ((item.statusDetail ?? '').trim().isNotEmpty) ...[
                              Text('处理状态：${item.statusDetail}', style: TextStyle(color: c.textGray)),
                              const SizedBox(height: 16),
                            ],
                            if (preview?.isLoading == true)
                              const Center(child: CircularProgressIndicator())
                            else if (preview?.hasError == true)
                              Column(children: [
                                const Text('正文预览加载失败；可重试或使用系统应用打开。'),
                                TextButton(
                                  onPressed: () => ref.invalidate(artifactPreviewProvider(item.id)),
                                  child: const Text('重试预览'),
                                ),
                              ])
                            else if ((preview?.value?.previewContent ?? '').trim().isNotEmpty) ...[
                              Text('正文预览 · ${preview!.value!.previewCoverage ?? "提取文本，不代表原始排版"}',
                                  style: TextStyle(fontSize: 12, color: c.textGray)),
                              const SizedBox(height: 12),
                              if (item.extension == 'md')
                                MathMarkdownView(content: preview.value!.previewContent!)
                              else
                                SelectableText(preview.value!.previewContent!),
                            ] else ...[
                              if ((preview?.value?.previewCoverage ?? '').isNotEmpty)
                                Text(preview!.value!.previewCoverage!, style: TextStyle(color: c.textGray)),
                              Padding(
                                padding: const EdgeInsets.symmetric(vertical: 32),
                                child: Text(
                                  available
                                      ? '此格式请使用系统应用查看完整内容。'
                                      : '文件尚未就绪，请返回聊天查看任务状态。',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(fontSize: 15, height: 1.6, color: c.textGray),
                                ),
                              ),
                            ],
                            const SizedBox(height: 24),
                            Wrap(
                              spacing: 12,
                              runSpacing: 12,
                              children: [
                                ElevatedButton.icon(
                                  onPressed: available
                                      ? () => _action(context, ref, item, true)
                                      : null,
                                  icon: const Icon(
                                    Icons.download_outlined,
                                    size: 18,
                                  ),
                                  label: const Text('保存文件'),
                                ),
                                OutlinedButton.icon(
                                  onPressed: available
                                      ? () => _action(context, ref, item, false)
                                      : null,
                                  icon: const Icon(Icons.open_in_new, size: 18),
                                  label: const Text('系统打开'),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
              ),
            ),
            if (available)
              ContextFollowUp(
                hint: '继续修改这份文档',
                contextText: '成果 ${item.name}，文件 ID：${item.id}',
                isAvailable: () => readResource(ref, resource, artifactsProvider, (item) => item.id) == item,
                onSent: () => context.go('/chat'),
              ),
          ],
        ),
      ),
    );
  }
}

String formatFileSize(int bytes) => bytes >= 1048576
    ? '${(bytes / 1048576).toStringAsFixed(1)} MB'
    : bytes >= 1024
    ? '${(bytes / 1024).toStringAsFixed(1)} KB'
    : '$bytes B';
