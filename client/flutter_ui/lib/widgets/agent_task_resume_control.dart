import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';

/// Uses the conversation command's exact-task guard; ACK never sets task status.
class AgentTaskResumeControl extends ConsumerStatefulWidget {
  const AgentTaskResumeControl({
    super.key,
    required this.task,
    required this.conversationAvailable,
    required this.resource,
    required this.refresh,
  });
  final AgentCatalogTaskDto task;
  final bool conversationAvailable;
  final ProfileResourceRoute resource;
  final Future<void> Function() refresh;
  @override
  ConsumerState<AgentTaskResumeControl> createState() =>
      _AgentTaskResumeControlState();
}

class _AgentTaskResumeControlState extends ConsumerState<AgentTaskResumeControl>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  ({String id, String conversationId, int revision, int fence})? _pending;
  bool _busy = false;
  bool _reading = true;
  bool _readFailed = false;
  int _readGeneration = 0;
  TaskResumeReceiptDto? _receipt;
  String? _error;
  @override
  void initState() {
    super.initState();
    unawaited(_readReceipt());
  }

  @override
  void didUpdateWidget(covariant AgentTaskResumeControl oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.task.revision != widget.task.revision ||
        oldWidget.task.fence != widget.task.fence) {
      unawaited(_readReceipt());
    }
  }

  Future<void> _readReceipt({String? requestId}) async {
    final resource = widget.resource;
    if (!_current(resource)) return;
    final generation = ++_readGeneration;
    final query = requestId ?? _pending?.id;
    setState(() { _reading = true; _readFailed = false; });
    try {
      final receipt = await bridgeApi.getTaskResumeReceipt(
        resource.profileId, resource.id, query, resource.epoch);
      if (!_current(resource) || generation != _readGeneration) return;
      if (receipt != null && (receipt.owner != resource.profileId ||
          receipt.taskId != resource.id ||
          receipt.conversationId != widget.task.conversationId ||
          receipt.requestId.isEmpty || receipt.expectedRevision < 0 ||
          receipt.expectedFence < 0 || (query != null && receipt.requestId != query))) {
        throw StateError('Resume receipt identity mismatch');
      }
      setState(() {
        _receipt = receipt;
        if (receipt != null) _pending = null;
        _error = null;
      });
    } catch (_) {
      if (_current(resource) && generation == _readGeneration) {
        setState(() { _readFailed = true; _error = '恢复回执读取失败；请先核对，避免重复恢复。'; });
      }
    } finally {
      if (_current(resource) && generation == _readGeneration) {
        setState(() => _reading = false);
      }
    }
  }
  bool _current(ProfileResourceRoute original) =>
      mounted &&
      original.isCurrent(ref) &&
      widget.resource.id == original.id &&
      widget.resource.profileId == original.profileId &&
      widget.resource.epoch == original.epoch;

  String? get _blocked {
    final task = widget.task;
    if (!widget.conversationAvailable) {
      return '关联会话已不可用，无法恢复原检查点。';
    }
    if (task.authorization != 'ACTIVE') {
      return '需在关联会话处理原范围授权。';
    }
    if (task.inputBlocked ||
        task.deviceBlocked ||
        task.deviceConfirmation ||
        task.approvalBlocked) {
      return '仍有输入、设备或工具审批等待，请在关联会话处理。';
    }
    if (!const {
      'QUEUED',
      'INTERRUPTED',
      'WAITING_INPUT',
      'WAITING_DEVICE',
      'WAITING_AUTHORIZATION',
      'PAUSED_BY_USER',
      'FAILED_BLOCKED',
      'COMPLETED_UNVERIFIED',
    }.contains(task.state)) {
      return '当前状态不能恢复；结果未知的调用需先核验。';
    }
    return null;
  }

  Future<void> _resume({bool coordinate = false}) async {
    final resource = widget.resource, task = widget.task;
    if (!_current(resource) ||
        _busy || _reading || _readFailed ||
        task.owner != resource.profileId ||
        task.id != resource.id ||
        (_pending == null && _blocked != null)) {
      return;
    }
    final receipt = _receipt;
    if (coordinate && (receipt == null || !receipt.canCoordinate || _blocked != null)) return;
    final command = coordinate
        ? (id: receipt!.requestId, conversationId: receipt.conversationId,
            revision: receipt.expectedRevision, fence: receipt.expectedFence)
        : _pending ??
        (
          id: 'catalog_resume_${DateTime.now().microsecondsSinceEpoch}',
          conversationId: task.conversationId,
          revision: task.revision,
          fence: task.fence,
        );
    setState(() {
      _pending = command;
      _busy = true;
      _error = null;
    });
    var refresh = false;
    try {
      final ack = await bridgeApi.taskCommand(
        resource.profileId,
        command.conversationId,
        resource.id,
        command.id,
        resource.epoch,
        command.revision,
        command.fence,
        'resume',
        '',
      );
      if (!_current(resource)) {
        return;
      }
      if (!ack.success && ack.error?.code == 'TASK_PRECONDITION_REJECTED') {
        setState(() {
          _pending = null;
          _error = '原任务、分支或等待状态已变化，请核对最新详情及关联会话。';
        });
        refresh = true;
      } else {
        checkCommand(ack);
        if (ack.idResult != resource.id) {
          throw StateError('Resume ACK belongs to another task');
        }
        setState(() {
          _pending = null;
          _error = null;
        });
        await _readReceipt(requestId: command.id);
        refresh = true;
      }
    } catch (_) {
      if (_current(resource)) {
        setState(() => _error = '恢复回执未确认；先查询原命令，查询不会继续执行。');
      }
    } finally {
      if (_current(resource)) {
        setState(() => _busy = false);
      }
    }
    if (refresh && _current(resource)) {
      await widget.refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final blocked = _blocked;
    final ready = !_busy && !_reading && !_readFailed;
    final receipt = _receipt;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('恢复将继续原任务及其锁定模型。宿主会核对当前分支与原检查点。'),
        if (receipt != null) const Text('原恢复命令已记录；这不代表任务已经启动或完成。'),
        TextButton(
          onPressed: _pending != null
              ? (!_busy && !_reading ? _readReceipt : null)
              : ready && blocked == null
              ? () => _resume(coordinate: receipt?.canCoordinate == true)
              : null,
          child: Text(_pending != null ? '查询恢复回执' :
            receipt?.canCoordinate == true ? '继续协调原恢复' : '恢复此任务'),
        ),
        if (_pending != null && ready && _receipt == null)
          TextButton(onPressed: () => _resume(), child: const Text('重试原恢复命令')),
        if (_readFailed || receipt != null)
          TextButton(onPressed: !_busy && !_reading ? _readReceipt : null,
            child: const Text('重新核对恢复回执')),
        if (blocked != null) Text(blocked),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_busy || _reading) const LinearProgressIndicator(),
      ],
    );
  }
}
