import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../providers/frontend_feature_providers.dart';
import '../providers/profile_resource.dart';

class CloudTaskPanel extends ConsumerStatefulWidget {
  const CloudTaskPanel({super.key, required this.resource, required this.onDelegation});
  final ProfileResourceRoute resource;
  final ValueChanged<bool> onDelegation;
  @override
  ConsumerState<CloudTaskPanel> createState() => _CloudTaskPanelState();
}
class _CloudTaskPanelState extends ConsumerState<CloudTaskPanel> with WidgetsBindingObserver {
  Map<dynamic, dynamic>? workspace, preview, remote;
  String? error;
  bool busy = false, reading = false, executing = false;
  int generation = 0, sequence = 0;
  Timer? timer;
  final input = TextEditingController(), keyInput = TextEditingController();
  bool get current => mounted && widget.resource.isCurrent(ref);
  String command() => 'cloud_${DateTime.now().microsecondsSinceEpoch}_${sequence++}';
  Future<Map<dynamic, dynamic>> call(String method, [Map<String, Object?> fields = const {}]) async {
    final resource = widget.resource;
    if (!current) throw StateError('档案或上下文已变化');
    final result = await FrontendFeatures.channel.invokeMethod<Map<dynamic, dynamic>>(method, {
      'profileId': resource.profileId, 'epoch': resource.epoch, 'taskId': resource.id, ...fields,
    });
    if (!current || widget.resource.id != resource.id || widget.resource.epoch != resource.epoch) throw StateError('旧上下文回执已丢弃');
    return result ?? {};
  }
  @override
  void initState() {
    super.initState(); WidgetsBinding.instance.addObserver(this);
    unawaited(read());
    timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (current && preview?['delegated'] == true && !reading && !busy && !executing && WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) unawaited(read(clearError: false));
    });
  }
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && current && !busy && !executing) unawaited(read(clearError: false));
  }
  @override
  void dispose() {
    generation++; timer?.cancel(); WidgetsBinding.instance.removeObserver(this); input.dispose(); keyInput.dispose(); super.dispose();
  }
  Future<void> read({bool clearError = true}) async {
    if (!current || reading) return;
    final ticket = ++generation;
    setState(() => reading = true);
    try {
      final ws = await call('cloud.state');
      final mapped = (ws['delegations'] as List? ?? []).contains(widget.resource.id);
      final p = ws['enabled'] == true || mapped
          ? await call('cloud.preview')
          : <dynamic, dynamic>{'delegated': false};
      final r = p['delegated'] == true ? await call('cloud.read') : null;
      if (!current || ticket != generation) return;
      setState(() { workspace = ws; preview = p; remote = r; if (clearError) error = null; });
      widget.onDelegation(p['delegated'] == true);
    } catch (e) { if (current && ticket == generation) setState(() => error = '$e'); }
    finally { if (mounted && ticket == generation) setState(() => reading = false); }
  }
  Future<void> perform(String method, Map<String, Object?> fields) async {
    if (!current || busy || executing) return;
    setState(() { busy = true; error = null; });
    try { await call(method, fields); }
    catch (e) { if (current) setState(() => error = '$e'); }
    finally { if (mounted) setState(() => busy = false); }
    if (current) await read(clearError: false);
  }
  Future<void> handoff() async {
    final p = preview;
    if (!current || p == null) return;
    final resource = widget.resource;
    final sources = <String>{};
    bool evidence = true, consent = false, includeHistory = false;
    String hostingMode = 'UNTIL';
    bool documentConsent = false;
    final accepted = await showDialog<bool>(context: context, builder: (dialogContext) => StatefulBuilder(builder: (context, update) => AlertDialog(
      title: const Text('逐任务托管授权'),
      content: SizedBox(width: 520, child: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('接收端：${workspace?['endpoint']}\n原模型：${p['provider']} / ${p['model']} · ${p['reasoning']}'),
        const Text('任务目标、已核验步骤、下列所选上下文和来源、锁定模型凭据会传到所示服务器；仅原模型可接收任务内容。未选文件和长期记忆不会自动外传。'),
        DropdownButtonFormField<String>(initialValue: hostingMode, decoration: const InputDecoration(labelText: '此任务托管期限'),
          items: const [DropdownMenuItem(value: 'UNTIL', child: Text('临时托管24小时')), DropdownMenuItem(value: 'UNTIL_REVOKED', child: Text('持续托管，直到明确撤销'))],
          onChanged: (v) => update(() => hostingMode = v ?? 'UNTIL')),
        const Text('持续授权仍只覆盖此目标、所选来源与原模型，不授权其他任务；可暂停、变更期限或撤销。'),
        if (p['documentEndpoint'] != null) CheckboxListTile(value: documentConsent, onChanged: (v) => update(() => documentConsent = v ?? false),
          title: const Text('另行授权文档服务托管与外传'), subtitle: Text('接收端 ${p['documentEndpoint']}；该档案独立文档凭据交给云端，仅所选文件可送往此服务做真实渲染、重算或解析。每文件最多30MiB，不静默截断；原件保留。')),
        const Text('原手动暂停、缺输入与设备阻碍会保留；托管不等于恢复。手机退出后云端非设备步骤可继续。'),
        for (final source in (p['sources'] as List? ?? []).cast<Map>()) CheckboxListTile(
          value: sources.contains(source['id']), title: Text('${source['name']}'), subtitle: Text('${source['size']} bytes · ${source['sha256']}'),
          onChanged: (v) => update(() { if (v == true) { sources.add(source['id'] as String); } else { sources.remove(source['id']); } })),
        CheckboxListTile(value: includeHistory, onChanged: (v) => update(() => includeHistory = v ?? false),
          title: const Text('包含当前会话可用文本上下文'), subtitle: const Text('沿用原来源删除过滤与上下文设置；不发送原图或自动检索长期记忆。')),
        CheckboxListTile(value: evidence, onChanged: (v) => update(() => evidence = v ?? true), title: const Text('目标要求真实工具成果'), subtitle: const Text('闲聊可关闭；交付文件、计算与动作任务须保留。')),
        CheckboxListTile(value: consent, onChanged: (v) => update(() => consent = v ?? false), title: const Text('明确同意上述任务范围与凭据传输')),
      ]))),
      actions: [TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('不移交')),
        FilledButton(onPressed: consent ? () => Navigator.pop(dialogContext, true) : null, child: const Text('确认托管'))],
    )));
    if (accepted == true && current && widget.resource.id == resource.id && widget.resource.epoch == resource.epoch) {
      await perform('cloud.delegate', {'consent': true, 'hostingMode': hostingMode, 'documentConsent': documentConsent, 'sources': sources.toList(), 'requireEvidence': evidence, 'includeHistory': includeHistory});
    }
  }
  Future<void> control(String action) => perform('cloud.control', {
    'action': action, 'payload': input.text, 'revision': remote?['revision'], 'commandId': command(),
  });
  Future<void> changeHosting() async {
    final selected = await showDialog<String>(context: context, builder: (c) => AlertDialog(
      title: const Text('变更此任务托管期限'),
      content: const Text('仅调整期限，不增加来源、工具或模型权限，不解除暂停、缺输入或设备确认。请先停止并结算当前执行器。'),
      actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('不变更')),
        TextButton(onPressed: () => Navigator.pop(c, 'UNTIL'), child: const Text('明确授权24小时')),
        TextButton(onPressed: () => Navigator.pop(c, 'UNTIL_REVOKED'), child: const Text('明确授权至撤销'))],
    ));
    if (selected != null && current) await perform('cloud.hosting', {'hostingMode': selected, 'consent': true, 'revision': remote?['revision'], 'commandId': command()});
  }
  Future<void> execute() async {
    final request = remote?['device'];
    if (!current || busy || executing || request is! Map) return;
    final resource = widget.resource;
    final approved = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('前台确认手机动作'),
      content: SingleChildScrollView(child: SelectableText('动作：${request['callId']}\n工具：${request['tool']}\n参数：${jsonEncode(request['arguments'])}\n输入摘要：${request['inputHash']}\n授权代次：${request['grantEpoch']} · 任务修订：${request['taskRevision']}\n\n确认后仅执行此绑定输入与授权版本；发生变化须刷新并重新核对。离开应用、锁屏或切换档案会请求停止；未知结果不会自动重发。跨 App 与截屏未开放。')),
      actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('暂不接管')),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('确认并保持前台'))],
    ));
    if (approved != true || !current || widget.resource.id != resource.id || widget.resource.epoch != resource.epoch) return;
    setState(() { executing = true; error = null; });
    try { await call('cloud.executeDevice', {'callId': request['callId'], 'inputHash': request['inputHash'],
      'grantEpoch': request['grantEpoch'], 'taskRevision': request['taskRevision']}); }
    catch (e) { if (current) setState(() => error = '$e'); }
    finally { if (mounted) setState(() => executing = false); }
    if (current) await read();
  }
  Future<void> stop() async {
    if (!current) return;
    try {
      await call('cloud.stopDevice');
      if (current) setState(() => error = 'STOP_REQUESTED：已请求停止，等待实际作用域退出；这不是已停止证明。');
    } catch (e) { if (current) setState(() => error = '$e'); }
  }
  @override
  Widget build(BuildContext context) {
    final enabled = workspace?['enabled'] == true, delegated = preview?['delegated'] == true;
    final pending = remote?['device'];
    final idle = current && !busy && !executing && !reading;
    final state = remote?['state'];
    final artifacts = ((remote?['result'] as Map?)?['artifacts'] as List? ?? []).cast<Map>();
    if (workspace?['enabled'] == false && !delegated) return const SizedBox.shrink();
    return Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('云端协作', style: Theme.of(context).textTheme.titleMedium),
      if (busy || reading || executing) const LinearProgressIndicator(),
      if (error != null) SelectableText(error!),
      if (!enabled && !delegated) ...[
        const Text('尚未绑定网络权威。已移交任务的映射会永久保留，停用不能授权本机重跑。'),
        TextButton(onPressed: () => context.push('/settings/cloud'), child: const Text('配置唯一网络权威')),
      ] else ...[
        Text('任务原模型：${preview?['provider']} / ${preview?['model']} · ${preview?['reasoning']}'),
        if (!delegated) FilledButton(onPressed: idle ? handoff : null, child: const Text('授权移交此任务')),
        if (delegated && remote == null) OutlinedButton(onPressed: idle ? () => perform('cloud.delegate', {'consent': true}) : null, child: const Text('恢复已授权移交回执（不另建任务）')),
        if (remote != null) ...[
          SelectableText('权威状态：$state\n远端修订 ${remote?['revision']} · fence ${remote?['fence']}\n授权 ${remote?['authorization']} · 用户暂停 ${remote?['manualPaused']}\n缺输入 ${remote?['inputBlocked']} · 设备阻碍 ${remote?['deviceBlocked']} · 前台确认 ${remote?['deviceConfirmation']}'),
          Text(remote?['grantMode'] == 'UNTIL_REVOKED' ? '托管期限：持续至明确撤销（仅此任务）' :
            '托管截止：${remote?['grantExpiresAt'] is num ? DateTime.fromMillisecondsSinceEpoch(((remote!['grantExpiresAt'] as num) * 1000).round()).toLocal() : '未知'}'),
          if (remote?['documentService'] is Map) SelectableText('任务固定文档服务：${(remote!['documentService'] as Map)['endpoint']}\n仅此任务已选来源；当前设置变更不会替换此角色配置。'),
          if (remote?['reason'] != null) SelectableText('持久原因：${remote?['reason']}'),
          for (final operation in (remote?['documentCalls'] as List? ?? []).cast<Map>())
            SelectableText('文档步骤 ${operation['callId']} · ${operation['operation']} · ${operation['state']}\n远端任务 ${operation['remoteTaskId'] ?? '上传回执未确认'}；取消ACK不等于停止证明。文档角色费用未知，单列且不计零。'),
          const Text('下方本机记录为历史，不冒充云端状态；刷新不会恢复任务。'),
          if ((remote?['goal'] as String?) != null) SelectableText(remote!['goal'] as String),
          if (remote?['canDispatch'] != true) const Text('此任务属于已退役权威，只允许读回或安全停止；不能在另一个执行域恢复。'),
          if (remote?['deviceConfirmation'] == true && remote?['goalRevision'] != remote?['plannedRevision'])
            OutlinedButton(onPressed: idle && remote?['canDispatch'] == true ? () => perform('cloud.confirmReplan', {
              'revision': remote?['revision'], 'grantEpoch': remote?['grantEpoch'], 'goal': remote?['goal'], 'commandId': command(),
            }) : null, child: const Text('已核对新目标，前台确认撤销旧设备输入并重新规划')),
          Wrap(spacing: 8, children: [
            for (final action in {'pause': '暂停云端', 'cancel': '取消云端', 'revoke': '撤销托管'}.entries)
              OutlinedButton(onPressed: idle && !const ['SUCCEEDED', 'CANCELED'].contains(state) ? () => control(action.key) : null, child: Text(action.value)),
            OutlinedButton(onPressed: idle && remote?['canDispatch'] == true && !const ['SUCCEEDED', 'CANCELED', 'RUNNING', 'STOP_REQUESTED', 'UNCERTAIN_SIDE_EFFECT'].contains(state) ? () => control('resume') : null, child: const Text('明确恢复（不清除其他阻碍）')),
            OutlinedButton(onPressed: idle && remote?['canDispatch'] == true && const ['ACTIVE', 'EXPIRED'].contains(remote?['authorization']) && remote?['executorActive'] == false && !const ['SUCCEEDED', 'CANCELED', 'UNCERTAIN_SIDE_EFFECT'].contains(state) ? changeHosting : null, child: const Text('明确变更托管期限')),
          ]),
          TextField(controller: input, enabled: idle && remote?['canDispatch'] == true, minLines: 1, maxLines: 5, decoration: const InputDecoration(labelText: '同任务追加或补充缺失输入')),
          Wrap(spacing: 8, children: [
            TextButton(onPressed: idle && remote?['canDispatch'] == true ? () => control('supplement') : null, child: const Text('追加目标')),
            TextButton(onPressed: idle && remote?['canDispatch'] == true && remote?['inputBlocked'] == true ? () => control('provide_input') : null, child: const Text('补充所缺输入')),
          ]),
          if (const ['ACTIVE', 'EXPIRED'].contains(remote?['authorization']) && !const ['SUCCEEDED', 'CANCELED'].contains(state)) ...[
            TextField(controller: keyInput, obscureText: true, enableSuggestions: false, autocorrect: false, enabled: idle,
              decoration: const InputDecoration(labelText: '仅替换原配置密钥，需真实请求验证')),
            TextButton(onPressed: idle && remote?['canDispatch'] == true && remote?['executorActive'] == false ? () async {
              await perform('cloud.replaceKey', {'secret': keyInput.text, 'consent': true, 'revision': remote?['revision'], 'commandId': command()});
              if (mounted) keyInput.clear();
            } : null, child: const Text('授权验证新密钥（不换模型，不解除暂停）')),
          ],
          if (pending is Map) ...[
            SelectableText('待手机动作：${pending['tool']} · ${pending['state']}\n${jsonEncode(pending['arguments'])}'),
            FilledButton(onPressed: idle && remote?['canDispatch'] == true ? execute : null, child: const Text('核对输入并前台接管／回读既有结果')),
          ],
          if (executing) FilledButton.icon(onPressed: stop, icon: const Icon(Icons.stop_circle_outlined), label: const Text('停止当前手机执行器')),
          if (remote?['result'] is Map) SelectableText('${(remote!['result'] as Map)['answer'] ?? ''}'),
          for (final artifact in artifacts) ...[
            if ((artifact['warnings'] as String? ?? '').isNotEmpty)
              SelectableText('文档核验警告：${artifact['warnings']}${artifact['warningsTruncated'] == true ? '\n警告投影已截断；完整警告保留在原服务manifest。' : ''}'),
            OutlinedButton(onPressed: idle ? () => perform('cloud.import', {'artifactId': artifact['id']}) : null,
              child: Text('独立核对并保存成果 ${artifact['id']}')),
          ],
          ExpansionTile(title: const Text('云端实际请求用量（未知不计零）'), children: [
            for (final row in (remote?['usage'] as List? ?? []).cast<Map>()) ListTile(title: Text('${row['provenance']} · ${row['request_id']}'),
              subtitle: Text('输入 ${row['input_tokens'] ?? '未知'} / 输出 ${row['output_tokens'] ?? '未知'} / 缓存 ${row['cached_tokens'] ?? '未知'} · 费用未知')),
          ]),
        ],
      ],
      TextButton(onPressed: idle ? read : null, child: const Text('读取权威状态')),
    ])));
  }
}
