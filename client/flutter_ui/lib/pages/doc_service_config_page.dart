import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../providers/frontend_feature_providers.dart';
import '../widgets/study_ui.dart';

class DocServiceConfigPage extends ConsumerStatefulWidget {
  const DocServiceConfigPage({super.key});
  @override
  ConsumerState<DocServiceConfigPage> createState() =>
      _DocServiceConfigPageState();
}

class _DocServiceConfigPageState extends ConsumerState<DocServiceConfigPage> {
  final url = TextEditingController();
  final token = TextEditingController();
  bool enabled = false, busy = false, dirty = false;
  String? loadedFor;
  String status = '尚未测试';
  @override
  void dispose() {
    url.dispose();
    token.dispose();
    super.dispose();
  }

  Future<void> run(bool test) async {
    if (busy) return;
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    if (!test && !(Uri.tryParse(url.text.trim())?.scheme == 'https')) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请输入完整的 HTTPS 服务地址')));
      return;
    }
    setState(() {
      busy = true;
      if (test) status = '正在测试';
    });
    try {
      if (test) {
        final response =
            await FrontendFeatures.fromWidget<Map<dynamic, dynamic>>(
              ref,
              'testDocService',
            );
        if (mounted &&
            owner == ref.read(activeProfileIdProvider) &&
            epoch == ref.read(contextEpochProvider)) {
          setState(
            () => status = response?['ready'] == true
                ? '连接成功，服务已就绪'
                : '连接成功，解析尚未就绪',
          );
        }
      } else {
        await FrontendFeatures.fromWidget(ref, 'saveDocConfig', {
          'endpoint': url.text.trim(),
          'token': token.text.trim(),
          'enabled': enabled,
        });
        if (mounted &&
            owner == ref.read(activeProfileIdProvider) &&
            epoch == ref.read(contextEpochProvider)) {
          token.clear();
          ref.invalidate(documentConfigProvider);
          setState(() {
            dirty = false;
            status = '尚未测试';
          });
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('配置已保存')));
        }
      }
    } catch (e) {
      if (mounted && owner == ref.read(activeProfileIdProvider)) {
        setState(() {
          if (test) status = '连接失败';
        });
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final config = ref.watch(documentConfigProvider);
    final identity =
        '${ref.watch(activeProfileIdProvider)}:${ref.watch(contextEpochProvider)}';
    if (config.value != null && loadedFor != identity) {
      url.text = config.value!['endpoint'] as String? ?? '';
      token.clear();
      enabled = config.value!['enabled'] == true;
      loadedFor = identity;
      dirty = false;
      status = '尚未测试';
    }
    return Scaffold(
      appBar: StudyHeader(
        '文档服务',
        actions: [
          IconButton(
            tooltip: '查看任务',
            icon: const Icon(Icons.list_alt_outlined),
            onPressed: () => context.push('/settings/doc-service/tasks'),
          ),
        ],
      ),
      body: SafeArea(
        child: config.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => StudyEmptyState(
            icon: Icons.cloud_off_outlined,
            title: '无法读取配置',
            description: '$e',
            action: OutlinedButton(
              onPressed: () => ref.invalidate(documentConfigProvider),
              child: const Text('重试'),
            ),
          ),
          data: (value) => ListView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
            children: [
              const Text('服务地址'),
              const SizedBox(height: 8),
              TextField(
                controller: url,
                enabled: !busy,
                keyboardType: TextInputType.url,
                autocorrect: false,
                onChanged: (_) => setState(() {
                  dirty = true;
                  status = '尚未测试';
                }),
                decoration: const InputDecoration(hintText: 'https://你的文档服务地址'),
              ),
              const SizedBox(height: 20),
              const Text('访问凭据'),
              const SizedBox(height: 8),
              TextField(
                controller: token,
                enabled: !busy,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                onChanged: (_) => setState(() {
                  dirty = true;
                  status = '尚未测试';
                }),
                decoration: InputDecoration(
                  hintText: value['hasCredential'] == true
                      ? '已配置凭据；留空保持原凭据'
                      : '输入服务凭据',
                ),
              ),
              const SizedBox(height: 24),
              StudySection(
                children: [
                  StudyRow(
                    title: '启用服务',
                    subtitle: '处理文件时，助手按需要选择可用工具',
                    trailing: Switch(
                      value: enabled,
                      onChanged: busy
                          ? null
                          : (v) => setState(() {
                              enabled = v;
                              dirty = true;
                              status = '尚未测试';
                            }),
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
                    onPressed: busy || dirty || !enabled
                        ? null
                        : () => run(true),
                    child: const Text('测试连接'),
                  ),
                  Text(
                    dirty ? '请保存后测试连接' : status,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
              const SizedBox(height: 24),
              Text(
                '仅上传文件不会自动解析全文。启用后，按任务需要将原文件上传到已配置服务；处理范围以服务实际能力为准。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: busy ? null : () => run(false),
                child: Text(busy ? '处理中…' : '保存'),
              ),
              TextButton(
                onPressed: busy || value['hasCredential'] != true
                    ? null
                    : () async {
                        final owner = ref.read(activeProfileIdProvider);
                        final epoch = ref.read(contextEpochProvider);
                        final confirmed = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('撤销服务身份'),
                            content: const Text('将停用服务端当前凭据，并取消该身份排队或运行中的任务。服务端确认前不会删除本地凭据。'),
                            actions: [
                              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
                              TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('撤销')),
                            ],
                          ),
                        );
                        if (confirmed != true || !mounted ||
                            owner != ref.read(activeProfileIdProvider) ||
                            epoch != ref.read(contextEpochProvider)) {
                          return;
                        }
                        setState(() => busy = true);
                        try {
                          await FrontendFeatures.fromWidget(ref, 'revokeDocService');
                          if (mounted &&
                              owner == ref.read(activeProfileIdProvider) &&
                              epoch == ref.read(contextEpochProvider)) {
                            token.clear();
                            enabled = false;
                            dirty = false;
                            status = '服务端撤权已确认，本地凭据已删除';
                            ref.invalidate(documentConfigProvider);
                          }
                        } catch (e) {
                          if (mounted && context.mounted && owner == ref.read(activeProfileIdProvider)) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text('撤权未确认，本地凭据保留：$e')),
                            );
                          }
                        } finally {
                          if (mounted) setState(() => busy = false);
                        }
                      },
                child: const Text('撤销服务身份'),
              ),
              TextButton(
                onPressed: () => context.push('/settings/doc-service/tasks'),
                child: const Text('查看文档任务'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
