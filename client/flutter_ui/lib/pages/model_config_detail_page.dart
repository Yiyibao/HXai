import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class ModelConfigDetailPage extends ConsumerWidget {
  final ProviderDto? provider;
  final ModelDto? model;
  const ModelConfigDetailPage({super.key, this.provider, this.model});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = model;
    final active = ref.watch(bootstrapProvider).value?.activeModelId;
    return Scaffold(
      appBar: StudyHeader(m == null ? '添加模型' : '模型配置'),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
          children: [
            if (m != null) ...[
              StudySection(
                title: '模型信息',
                children: [
                  StudyRow(title: '显示名称', subtitle: m.name),
                  StudyRow(title: '模型标识', subtitle: m.id),
                  StudyRow(title: '提供商', subtitle: provider?.name ?? '未提供'),
                ],
              ),
              StudySection(
                title: '已配置能力',
                children: [
                  StudyRow(
                    title: '文本',
                    trailing: Icon(m.supportText ? Icons.check : Icons.remove),
                  ),
                  StudyRow(
                    title: '图片',
                    trailing: Icon(m.supportImage ? Icons.check : Icons.remove),
                  ),
                  StudyRow(
                    title: '工具',
                    trailing: Icon(m.supportTool ? Icons.check : Icons.remove),
                  ),
                ],
              ),
              ElevatedButton(
                onPressed: active == m.id || provider == null
                    ? null
                    : () async {
                        try {
                          await ref
                              .read(providersConfigProvider.notifier)
                              .selectActiveModel(provider!.id, m.id);
                        } catch (e) {
                          if (context.mounted)
                            ScaffoldMessenger.of(context)
                                .showSnackBar(SnackBar(content: Text('$e')));
                        }
                      },
                child: Text(active == m.id ? '当前模型' : '设为当前模型'),
              ),
              const SizedBox(height: 20),
            ],
            Text(
              '能力以实际配置为准。使用提供商管理工具添加模型或调整参数。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              icon: const Icon(Icons.open_in_new, size: 18),
              label: const Text('编辑模型与参数'),
              onPressed: () async {
                try {
                  checkCommand(await bridgeApi.openNativePage('providers'));
                } catch (e) {
                  if (context.mounted)
                    ScaffoldMessenger.of(context)
                        .showSnackBar(SnackBar(content: Text('$e')));
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}
