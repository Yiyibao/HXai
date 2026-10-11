import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../providers/frontend_feature_providers.dart';
import 'study_ui.dart';
import 'context_follow_up.dart';

final artifactVersionFamilyProvider = FutureProvider.autoDispose
    .family<List<Map>, String>((ref, id) async {
      ref.watch(activeProfileIdProvider);
      ref.watch(contextEpochProvider);
      final values = await FrontendFeatures.call<List>(
        ref,
        'artifactVersions',
        {'id': id},
      );
      return (values ?? []).whereType<Map>().toList();
    });

class ArtifactVersionsContent extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const ArtifactVersionsContent({super.key, this.resource});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final artifact = watchResource(ref, resource, artifactsProvider, (item) => item.id);
    if (artifact == null)
      return const Scaffold(body: Center(child: Text('成果不可用')));
    final artifactId = artifact.id;
    final versions = ref.watch(artifactVersionFamilyProvider(artifactId));
    return Scaffold(
      appBar: const StudyHeader('版本与修改'),
      body: SafeArea(
        child: versions.when(
          skipLoadingOnRefresh: false,
          skipLoadingOnReload: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('版本记录加载失败'),
                TextButton(
                  onPressed: () => ref.invalidate(
                    artifactVersionFamilyProvider(artifactId),
                  ),
                  child: const Text('重试'),
                ),
              ],
            ),
          ),
          data: (items) {
            final current = items
                .where((v) => v['id'] == artifactId)
                .firstOrNull;
            if (current == null) return const Center(child: Text('未找到成果'));
            return Column(
              children: [
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.all(20),
                    children: [
                      Text(
                        current['name'] as String,
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      const Text('以下版本依据真实文件来源关联；修改会生成新文件，原件保留。'),
                      for (var i = 0; i < items.length; i++)
                        ListTile(
                          leading: const Icon(Icons.history),
                          title: Text('版本 ${i + 1} · ${items[i]['name']}'),
                          subtitle: Text(
                            items[i]['id'] == artifactId
                                ? '当前查看的版本'
                                : items[i]['state'].toString(),
                          ),
                          trailing: items[i]['state'] == 'SUCCEEDED'
                              ? const Icon(Icons.open_in_new)
                              : null,
                          onTap: items[i]['state'] == 'SUCCEEDED'
                              ? () => context.push(
                                  '/artifacts/preview',
                                  extra: ProfileResourceRoute.capture(ref, items[i]['id'] as String),
                                )
                              : null,
                        ),
                    ],
                  ),
                ),
                ContextFollowUp(
                  hint: '提出修改要求',
                  contextText:
                      '成果 ${current['name']}，文件 ID：${current['id']}，expected_sha256=${current['sha256']}。请基于这个版本修改并保留原件。',
                  isAvailable: () => readResource(ref, resource, artifactsProvider, (item) => item.id) == artifact,
                  onSent: () => context.go('/chat'),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
