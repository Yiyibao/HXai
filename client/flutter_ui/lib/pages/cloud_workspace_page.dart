import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';

final cloudWorkspaceProvider = FutureProvider<Map<dynamic, dynamic>>((ref) async {
  ref.watch(activeProfileIdProvider);
  ref.watch(contextEpochProvider);
  return await FrontendFeatures.call<Map<dynamic, dynamic>>(ref, 'cloud.state') ?? {};
});

class CloudWorkspacePage extends ConsumerWidget {
  const CloudWorkspacePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bootstrap = ref.watch(bootstrapProvider);
    if (!bootstrap.hasValue || bootstrap.hasError) {
      return Scaffold(appBar: AppBar(title: const Text('云端协作')), body: const Center(child: Text('正在核对当前档案…')));
    }
    return _CloudWorkspaceForm(key: ValueKey('${ref.watch(activeProfileIdProvider)}:${ref.watch(contextEpochProvider)}'));
  }
}

class _CloudWorkspaceForm extends ConsumerStatefulWidget {
  const _CloudWorkspaceForm({super.key});
  @override
  ConsumerState<_CloudWorkspaceForm> createState() => _CloudWorkspaceFormState();
}
class _CloudWorkspaceFormState extends ConsumerState<_CloudWorkspaceForm> {
  final endpoint = TextEditingController(), token = TextEditingController();
  bool consent = false, busy = false;
  String? error;
  @override
  void dispose() { endpoint.dispose(); token.dispose(); super.dispose(); }
  Future<void> change(String method) async {
    if (busy) return;
    setState(() { busy = true; error = null; });
    try {
      await FrontendFeatures.fromWidget<Object?>(ref, method, {
        'endpoint': endpoint.text.trim(), 'token': token.text.trim(), 'consent': consent,
      });
      token.clear();
      if (mounted) ref.invalidate(cloudWorkspaceProvider);
    } catch (e) { if (mounted) setState(() => error = '$e'); }
    finally { if (mounted) setState(() => busy = false); }
  }
  @override
  Widget build(BuildContext context) {
    final state = ref.watch(cloudWorkspaceProvider);
    final value = state.value;
    final enabled = value?['enabled'] == true;
    return Scaffold(
      appBar: AppBar(title: const Text('云端协作与手机执行')),
      body: ListView(padding: const EdgeInsets.all(20), children: [
        const Text('一个用户工作域只有一个执行权威。启用后所有本机任务也须联网取得执行位；断网不会另开本机执行位。'),
        const SizedBox(height: 12),
        if (busy || state.isLoading) const LinearProgressIndicator(),
        if (state.hasError) Text('读取配置失败：${state.error}'),
        if (error != null) SelectableText(error!),
        if (enabled) ...[
          SelectableText('已绑定：${value?['endpoint']}\n设备：${value?['actor']}\n档案：${value?['owners']}\n未确认本机执行位：${value?['pendingAdmissions']}'),
          const Text('模型凭据仅在逐任务确认后加密移交；此设备注册密钥不会显示或导出。'),
          OutlinedButton(onPressed: busy ? null : () => change('cloud.disconnect'), child: const Text('确认执行器停止后停用')),
        ] else ...[
          TextField(controller: endpoint, enabled: !busy, decoration: const InputDecoration(labelText: '独立权威 HTTPS 地址', hintText: 'https://your-authority.example')),
          TextField(controller: token, enabled: !busy, obscureText: true, enableSuggestions: false, autocorrect: false, decoration: const InputDecoration(labelText: '管理员单次设备配对凭据')),
          CheckboxListTile(value: consent, onChanged: busy ? null : (v) => setState(() => consent = v ?? false),
            title: const Text('将整个用户工作域绑定到此唯一权威'),
            subtitle: const Text('服务端设备范围须涵盖当前所有档案；不能借档案切换增加执行位。')),
          FilledButton(onPressed: busy || !consent ? null : () => change('cloud.connect'), child: const Text('验证设备并绑定')),
        ],
        const Divider(),
        const Text('逐任务移交入口：任务中心 → 任务详情 → 云端协作。原任务锁定的提供商、模型与推理强度不会随当前设置变化。'),
        const Text('手机动作必须前台解锁确认；离开应用、锁屏或切换档案会请求停止。恢复不抢占其他任务，暂停、撤权、缺输入与前台确认独立保留。'),
        const Text('跨 App 接管默认关闭，当前配置不开放屏幕工具；不会自动截屏或将截图加入记忆、备份或云端。'),
        TextButton(onPressed: busy ? null : () => ref.invalidate(cloudWorkspaceProvider), child: const Text('重新读取实际配置')),
      ]),
    );
  }
}
