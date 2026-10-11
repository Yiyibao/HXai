import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class ProviderModelsPage extends ConsumerStatefulWidget {
  final ProviderDto? provider;
  const ProviderModelsPage({super.key, this.provider});
  @override
  ConsumerState<ProviderModelsPage> createState() => _ProviderModelsPageState();
}

class _ProviderModelsPageState extends ConsumerState<ProviderModelsPage> {
  String query = '';
  bool busy = false;
  Future<void> manage() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      checkCommand(await bridgeApi.openNativePage('providers'));
      ref.invalidate(providersConfigProvider);
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final configured = ref.watch(providersConfigProvider).value;
    final current =
        configured?.where((p) => p.id == widget.provider?.id).firstOrNull ??
        widget.provider;
    final active = ref.watch(bootstrapProvider).value?.activeModelName;
    final models = (current?.models ?? <ModelDto>[])
        .where((m) => m.name.toLowerCase().contains(query.toLowerCase()))
        .toList();
    return Scaffold(
      appBar: StudyHeader(
        '${current?.name ?? '提供商'} · 模型',
        actions: [
          IconButton(
            tooltip: '管理模型',
            onPressed: busy ? null : manage,
            icon: const Icon(Icons.add),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: TextField(
                onChanged: (v) => setState(() => query = v.trim()),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: '搜索已配置模型',
                ),
              ),
            ),
            Expanded(
              child: models.isEmpty
                  ? const StudyEmptyState(
                      icon: Icons.search,
                      title: '没有匹配的模型',
                      description: '可在提供商管理中添加或获取模型列表。',
                    )
                  : ListView(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      children: [
                        StudySection(
                          children: models
                              .map(
                                (m) => StudyRow(
                                  title: m.name,
                                  subtitle: [
                                    if (m.supportText) '文本',
                                    if (m.supportImage) '图片',
                                    if (m.supportTool) '工具',
                                    if (!m.enabled) '已停用',
                                  ].join(' · '),
                                  trailing: m.name == active
                                      ? const Icon(Icons.check, size: 20)
                                      : const Icon(Icons.chevron_right),
                                  onTap: () => context.push(
                                    '/settings/providers/model-detail',
                                    extra: {'provider': current, 'model': m},
                                  ),
                                ),
                              )
                              .toList(),
                        ),
                      ],
                    ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: busy ? null : manage,
                  child: const Text('管理与获取模型'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
