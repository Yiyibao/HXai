import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import 'agent_usage_view.dart';
import 'agent_usage_alerts.dart';

class AgentTaskPanel extends ConsumerStatefulWidget {
  const AgentTaskPanel({super.key, required this.conversationId});
  final String conversationId;
  @override
  ConsumerState<AgentTaskPanel> createState() => _AgentTaskPanelState();
}

class _AgentTaskPanelState extends ConsumerState<AgentTaskPanel> {
  Timer? _timer;
  AgentTaskDto? _task;
  String? _error;
  bool _reading = false;
  bool _busy = false;
  bool _credentialBusy = false;
  ({String id, String action, String payload, AgentTaskDto task})? _pending;
  final _pendingSafety = <String, ({String id, String action, String payload, AgentTaskDto task})>{};
  final _safetyBusy = <String>{};
  ({String id, String key, AgentTaskDto task, int epoch})? _pendingCredential;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
    unawaited(_refresh());
  }
  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
  bool _current(String owner, int epoch) => mounted &&
      ref.read(activeProfileIdProvider) == owner &&
      ref.read(contextEpochProvider) == epoch &&
      ref.read(activeConversationIdProvider) == widget.conversationId;
  Future<void> _refresh() async {
    if (_reading || !mounted) { return; }
    _reading = true;
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    try {
      final task = await bridgeApi.getConversationTask(owner, widget.conversationId, epoch);
      if (!_current(owner, epoch) || (task != null && task.owner != owner)) { return; }
      if (task != null && _task?.id == task.id &&
          (task.revision < _task!.revision || task.fence < _task!.fence)) { return; }
      setState(() { _task = task; if (_pending == null) { _error = null; } });
    } catch (error) {
      if (_current(owner, epoch)) { setState(() => _error = '$error'); }
    } finally { _reading = false; }
  }
  Future<void> _command(String action, [String payload = '']) async {
    final task = _task;
    final safety = const {'pause', 'cancel', 'revoke'}.contains(action);
    if (task == null || (safety ? _safetyBusy.contains(action) : _busy)) { return; }
    final previous = safety ? _pendingSafety[action] : _pending;
    if (previous != null && (previous.action != action || previous.payload != payload)) {
      setState(() => _error = '上次命令回执未知，请先重试原命令'); return;
    }
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final command = previous ?? (id: 'task_${DateTime.now().microsecondsSinceEpoch}', action: action, payload: payload, task: task);
    setState(() {
      if (safety) { _pendingSafety[action] = command; _safetyBusy.add(action); }
      else { _pending = command; _busy = true; }
    });
    try {
      final result = await bridgeApi.taskCommand(owner, widget.conversationId,
          command.task.id, command.id, epoch, command.task.revision,
          command.task.fence, command.action, command.payload);
      if (!_current(owner, epoch)) { return; }
      if (!result.success && result.error?.code == 'TASK_PRECONDITION_REJECTED') {
        setState(() {
          if (safety) { _pendingSafety.remove(action); } else { _pending = null; }
          _error = result.error?.message;
        });
        await _refresh();
        return;
      }
      checkCommand(result);
      setState(() {
        if (safety) { _pendingSafety.remove(action); } else { _pending = null; }
        _error = null;
      });
      await _refresh();
      if (_current(owner, epoch)) {
        unawaited(ref.read(activeConversationDetailProvider.notifier).refresh());
      }
    } catch (error) {
      if (_current(owner, epoch)) { setState(() => _error = '命令未确认：$error'); }
    } finally {
      if (_current(owner, epoch)) {
        setState(() { if (safety) { _safetyBusy.remove(action); } else { _busy = false; } });
      }
    }
  }
  Future<void> _input(String action) async {
    var value = '';
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final input = await showDialog<String>(context: context, builder: (dialogContext) => AlertDialog(
      title: Text(action == 'supplement' ? '补充同一任务要求' : '提供缺少的输入'),
      content: TextField(onChanged: (text) => value = text, maxLines: 4, autofocus: true),
      actions: [TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(dialogContext, value.trim()), child: const Text('提交'))],
    ));
    if (_current(owner, epoch) && input != null && input.isNotEmpty) { await _command(action, input); }
  }
  Future<void> _credential() async {
    final task = _task;
    if (task == null || _busy || _credentialBusy || _pending != null) { return; }
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    if (_pendingCredential == null && task.credentialRequestId != null &&
        task.credentialState != 'APPLIED' && task.credentialState != 'WAITING_AUTHORIZATION' &&
        task.credentialState != 'CANCELED') {
      await _continueCredential(task, owner, epoch);
      return;
    }
    if (_pendingCredential == null) {
      var value = '';
      final key = await showDialog<String>(context: context, builder: (dialogContext) => AlertDialog(
        title: const Text('验证原任务的新密钥'),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${task.providerName}\n${task.providerOrigin}\n${task.modelName} · ${task.reasoning}'),
            const Text('将向上述原提供商发送合成验证请求及必要的服务端续请求，可能产生费用；不发送任务材料。'
                '只恢复过期的原授权，不解除暂停、主动撤权、缺输入或设备等待，不自动继续任务。'),
            TextField(onChanged: (text) => value = text, obscureText: true,
                autocorrect: false, enableSuggestions: false,
                decoration: const InputDecoration(labelText: '新 API 密钥')),
          ])),
        actions: [TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(dialogContext, value.trim()), child: const Text('明确同意并验证'))],
      ));
      if (!_current(owner, epoch) || key == null || key.isEmpty) { return; }
      _pendingCredential = (id: 'credential_${DateTime.now().microsecondsSinceEpoch}', key: key, task: task, epoch: epoch);
    }
    final pending = _pendingCredential!;
    if (pending.task.owner != owner || pending.epoch != epoch) {
      setState(() => _pendingCredential = null);
      return;
    }
    setState(() => _credentialBusy = true);
    try {
      final result = await bridgeApi.updateTaskCredential(owner, widget.conversationId,
          pending.task.id, pending.id, pending.epoch, pending.task.revision, pending.task.fence, pending.key);
      if (!_current(owner, epoch)) { return; }
      if (result.success || result.error?.code == 'TASK_PRECONDITION_REJECTED' ||
          result.error?.code == 'CREDENTIAL_REJECTED') {
        setState(() { _pendingCredential = null; _error = result.error?.message; });
      } else {
        setState(() => _error = '验证回执未确认；不得提交新的验证请求。${result.error?.message ?? ''}');
      }
      await _refresh();
    } catch (_) {
      if (_current(owner, epoch)) {
        setState(() => _error = '验证回执未知；只允许查询原请求，不重复发送模型请求。');
      }
    } finally {
      if (_current(owner, epoch)) { setState(() => _credentialBusy = false); }
    }
  }
  Future<void> _continueCredential(AgentTaskDto task, String owner, int epoch) async {
    final confirmed = await showDialog<bool>(context: context, builder: (dialogContext) => AlertDialog(
      title: const Text('继续原验证请求'),
      content: Text('${task.providerName}\n${task.providerOrigin}\n${task.modelName} · ${task.reasoning}\n'
          '当前持久状态：${task.credentialState}\n'
          '已收到的验证回执只会重新提交，不会再发模型请求；结果未知的请求不会重放。'
          '若原请求尚未发送，继续可能首次发送合成验证请求及必要续请求并产生费用。'
          '原授权范围和所有暂停、输入、设备等待条件保持不变。'),
      actions: [TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(dialogContext, true), child: const Text('明确继续原请求'))],
    ));
    if (!_current(owner, epoch) || confirmed != true) { return; }
    setState(() => _credentialBusy = true);
    try {
      final result = await bridgeApi.continueTaskCredential(owner, widget.conversationId,
          task.id, task.credentialRequestId!, epoch);
      if (!_current(owner, epoch)) { return; }
      setState(() => _error = result.error?.message);
      await _refresh();
    } catch (_) {
      if (_current(owner, epoch)) { setState(() => _error = '原验证回执未确认；不会创建或重放新的模型请求。'); }
    } finally {
      if (_current(owner, epoch)) { setState(() => _credentialBusy = false); }
    }
  }
  Future<void> _usage(AgentTaskDto task) => showDialog<void>(context: context, builder: (dialogContext) => Dialog(
    child: ConstrainedBox(constraints: BoxConstraints(maxWidth: 620,
      maxHeight: MediaQuery.sizeOf(dialogContext).height * .85), child: Column(children: [
        Row(children: [const Expanded(child: Padding(padding: EdgeInsets.all(12), child: Text('请求用量'))),
          IconButton(tooltip: '关闭用量', onPressed: () => Navigator.pop(dialogContext), icon: const Icon(Icons.close))]),
        Expanded(child: AgentUsageView(owner: task.owner, taskId: task.id,
          expectedEpoch: ref.read(contextEpochProvider))),
      ])),
  ));
  @override
  Widget build(BuildContext context) {
    ref.listen(contextEpochProvider, (_, _) {
      setState(() {
        _task = null; _pending = null; _pendingSafety.clear(); _safetyBusy.clear();
        _pendingCredential = null; _busy = false; _credentialBusy = false; _error = null;
      });
      unawaited(_refresh());
    });
    final task = _task;
    if (task == null || task.owner != ref.watch(activeProfileIdProvider)) {
      return _error == null ? const SizedBox.shrink() : Text('任务状态读取失败：$_error');
    }
    final status = switch (task.state) {
      'QUEUED' => '排队中（全工作域共用执行位）',
      'RUNNING' => '执行中',
      'STOP_REQUESTED' => '已请求停止，尚未确认停止',
      'UNCERTAIN_SIDE_EFFECT' => '结果未知，禁止重复执行',
      'COMPLETED_UNVERIFIED' => '执行已结束，目标尚未独立验收',
      'SUCCEEDED' => '目标验收通过',
      'PAUSED_BY_USER' => '已暂停',
      'CANCELED' => '已取消',
      'WAITING_AUTHORIZATION' => '等待原范围授权',
      'WAITING_DEVICE' => '等待设备或前台确认',
      'WAITING_INPUT' => '等待输入',
      _ => task.state,
    };
    final blocked = task.authorization != 'ACTIVE' || task.inputBlocked ||
        task.approvalCallIds.isNotEmpty || task.deviceBlocked || task.deviceConfirmation ||
        task.state == 'UNCERTAIN_SIDE_EFFECT' || task.state == 'CANCELED';
    final running = task.state == 'RUNNING' || task.state == 'QUEUED' || task.state == 'STOP_REQUESTED';
    Widget button(String label, String action, bool enabled) {
      final safety = const {'pause', 'cancel', 'revoke'}.contains(action);
      final available = safety ? !_safetyBusy.contains(action) :
          !_busy && _pending == null && !_credentialBusy && _pendingCredential == null;
      return TextButton(onPressed: available && (enabled || _pendingSafety.containsKey(action))
          ? () => _command(action) : null, child: Text(label));
    }
    return ExpansionTile(
      title: Text(status, style: Theme.of(context).textTheme.bodySmall),
      subtitle: AgentUsageAlertsView(key: ValueKey(task.id), owner: task.owner, taskId: task.id,
          expectedEpoch: ref.watch(contextEpochProvider), autoRefresh: true, compact: true),
      children: [Align(alignment: Alignment.centerLeft,
        child: TextButton(onPressed: () => _usage(task), child: const Text('查看请求用量'))),
        ConstrainedBox(constraints: const BoxConstraints(maxHeight: 210), child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SelectableText('任务已锁定模型：${task.modelName}\n提供商：${task.providerName} · ${task.providerOrigin} · 推理：${task.reasoning}\n'
              '授权：${task.authorization}（epoch ${task.grantEpoch}）\n'
              '目标/计划修订：${task.goalRevision}/${task.plannedRevision} · fence ${task.fence}\n'
              '已核验步骤：${task.verifiedSteps.join(', ')}\n'
              '手动暂停：${task.manualPaused} · 缺输入：${task.inputBlocked} · 等设备：${task.deviceBlocked} · 前台确认：${task.deviceConfirmation}'),
          if (task.approvalCallIds.isNotEmpty)
            Text('等待工具审批/回答：${task.approvalCallIds.join(', ')}；请在对应消息处理，不会解除其他等待。'),
          if (task.credentialState != null) Text('最近密钥验证：${task.credentialState}；以当前授权和等待条件为准。'),
          if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          Wrap(children: [
            button('暂停', 'pause', !task.manualPaused && task.state != 'CANCELED' && task.state != 'SUCCEEDED'),
            button('取消', 'cancel', task.state != 'CANCELED'),
            button('主动撤权', 'revoke', task.authorization != 'REVOKED' && task.state != 'CANCELED'),
            button('明确恢复', 'resume', !blocked && !running && task.state != 'SUCCEEDED'),
            if (task.deviceConfirmation) button('前台设备确认', 'confirm_device', true),
            TextButton(onPressed: !_busy && !_credentialBusy && _pending == null && _pendingCredential == null &&
                task.state != 'CANCELED' ? () => _input('supplement') : null, child: const Text('补充要求')),
            if (task.inputBlocked) TextButton(onPressed: !_busy && !_credentialBusy && _pending == null && _pendingCredential == null
                ? () => _input('provide_input') : null, child: const Text('提供输入')),
            if (_pending != null) TextButton(onPressed: !_busy ? () => _command(_pending!.action, _pending!.payload) : null, child: const Text('重试原命令回执')),
            for (final action in _pendingSafety.keys)
              TextButton(onPressed: !_safetyBusy.contains(action) ? () => _command(action) : null,
                  child: Text('查询安全命令回执：$action')),
            if (task.authorization == 'EXPIRED' && _pending == null)
              TextButton(onPressed: !_busy && !_credentialBusy ? _credential : null,
                child: Text(_pendingCredential != null ? '查询原验证请求' :
                    task.credentialRequestId != null && task.credentialState != 'APPLIED' &&
                    task.credentialState != 'WAITING_AUTHORIZATION' && task.credentialState != 'CANCELED'
                        ? '继续原验证请求' : '验证原任务的新密钥')),
          ]),
        ]),
      ))],
    );
  }
}
