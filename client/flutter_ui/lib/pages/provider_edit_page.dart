import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';

class ProviderEditPage extends ConsumerStatefulWidget {
  final ProviderDto? provider;
  const ProviderEditPage({super.key, this.provider});
  @override
  ConsumerState<ProviderEditPage> createState() => _ProviderEditPageState();
}

class _ProviderEditPageState extends ConsumerState<ProviderEditPage> {
  late final name = TextEditingController(text: widget.provider?.name ?? '');
  late bool enabled = widget.provider?.enabled ?? false;
  bool busy = false;
  String status = '尚未测试';
  @override
  void dispose() {
    name.dispose();
    super.dispose();
  }

  Future<void> action(bool test) async {
    final p = widget.provider;
    if (p == null || busy) return;
    setState(() => busy = true);
    try {
      if (test) {
        final result = await bridgeApi.testProvider(p.id);
        checkCommand(result);
        if (mounted) setState(() => status = '连接测试通过');
      } else {
        if (name.text.trim().isEmpty) throw const FormatException('提供商名称不能为空');
        final updated = ProviderDto(
          id: p.id,
          name: name.text.trim(),
          type: p.type,
          endpoint: p.endpoint,
          apiKeyMasked: p.apiKeyMasked,
          enabled: enabled,
          isPrimary: p.isPrimary,
          models: p.models,
        );
        checkCommand(await bridgeApi.saveProvider(updated));
        ref.invalidate(providersConfigProvider);
        if (mounted) context.pop();
      }
    } catch (e) {
      if (mounted) {
        if (test) setState(() => status = '连接测试失败');
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> native() async {
    try {
      checkCommand(await bridgeApi.openNativePage('providers'));
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.provider;
    return Scaffold(
      appBar: StudyHeader(p == null ? '添加提供商' : '提供商连接'),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
          children: [
            if (p != null) ...[
              const Text('名称'),
              const SizedBox(height: 8),
              TextField(
                controller: name,
                enabled: !busy,
                decoration: const InputDecoration(hintText: '提供商名称'),
              ),
              const SizedBox(height: 24),
              StudySection(
                title: '连接信息',
                children: [
                  StudyRow(title: '服务类型', subtitle: p.type),
                  StudyRow(
                    title: '服务地址',
                    subtitle: p.endpoint.isEmpty ? '未配置' : p.endpoint,
                  ),
                  StudyRow(
                    title: 'API Key',
                    subtitle: p.apiKeyMasked.isEmpty ? '未配置' : '••••••••',
                  ),
                ],
              ),
              StudySection(
                children: [
                  StudyRow(
                    title: '启用提供商',
                    subtitle: '启用不代表连接测试通过',
                    trailing: Switch(
                      value: enabled,
                      onChanged: busy
                          ? null
                          : (v) => setState(() => enabled = v),
                    ),
                  ),
                ],
              ),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  OutlinedButton(
                    onPressed: busy ? null : () => action(true),
                    child: const Text('测试连接'),
                  ),
                  Text(status, style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: busy ? null : () => action(false),
                child: Text(busy ? '处理中…' : '保存'),
              ),
              const SizedBox(height: 12),
            ],
            Text(
              '服务地址、密钥和模型配置使用现有提供商管理工具编辑。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: native,
              icon: const Icon(Icons.open_in_new, size: 18),
              label: Text(p == null ? '打开工具添加提供商' : '编辑连接与模型配置'),
            ),
          ],
        ),
      ),
    );
  }
}
