import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class ProvidersPage extends ConsumerWidget {
  const ProvidersPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final providers = ref.watch(providersConfigProvider);
    return Scaffold(
      appBar: StudyHeader(
        '模型与提供商',
        actions: [
          IconButton(
            tooltip: '添加提供商',
            icon: const Icon(Icons.add),
            onPressed: () => context.push('/settings/providers/edit'),
          ),
        ],
      ),
      body: SafeArea(
        child: providers.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => StudyEmptyState(
            icon: Icons.error_outline,
            title: '加载失败',
            description: '$e',
          ),
          data: (all) => ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
            children: [
              Text(
                '启用不代表连接测试通过。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 20),
              if (all.isEmpty)
                const StudyEmptyState(
                  icon: Icons.tune_outlined,
                  title: '暂无提供商',
                  description: '添加提供商后即可选择模型。',
                ),
              for (final p in all)
                StudySection(
                  children: [
                    StudyRow(
                      icon: Icons.dns_outlined,
                      title: p.name,
                      subtitle: '${p.models.length} 个模型 · ${p.type}',
                      onTap: () =>
                          context.push('/settings/providers/edit', extra: p),
                      trailing: Switch(
                        value: p.enabled,
                        onChanged: (v) async {
                          try {
                            final next = ProviderDto(
                              id: p.id,
                              name: p.name,
                              type: p.type,
                              endpoint: p.endpoint,
                              apiKeyMasked: p.apiKeyMasked,
                              enabled: v,
                              isPrimary: p.isPrimary,
                              models: p.models,
                            );
                            checkCommand(await bridgeApi.saveProvider(next));
                            ref.invalidate(providersConfigProvider);
                          } catch (e) {
                            if (context.mounted)
                              ScaffoldMessenger.of(context)
                                  .showSnackBar(SnackBar(content: Text('$e')));
                          }
                        },
                      ),
                    ),
                    StudyRow(
                      title: '管理模型',
                      subtitle: p.endpoint.isEmpty ? '服务地址未配置' : p.endpoint,
                      onTap: () =>
                          context.push('/settings/providers/models', extra: p),
                    ),
                  ],
                ),
              OutlinedButton.icon(
                onPressed: () => context.push('/settings/providers/edit'),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('添加提供商'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
