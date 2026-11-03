import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';

/// Only narrows execution. Task status always comes from the parent's new DTO.
class AgentTaskSafetyControls extends ConsumerStatefulWidget {
  const AgentTaskSafetyControls({
    super.key,
    required this.task,
    required this.resource,
    required this.refresh,
  });
  final AgentCatalogTaskDto task;
  final ProfileResourceRoute resource;
  final Future<void> Function() refresh;
  @override
  ConsumerState<AgentTaskSafetyControls> createState() =>
      _AgentTaskSafetyControlsState();
}

class _AgentTaskSafetyControlsState
    extends ConsumerState<AgentTaskSafetyControls>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _pending = <String, ({String id, int revision, int fence})>{};
  final _busy = <String>{};
  final _errors = <String, String>{};
  static const _labels = {'pause': '暂停', 'cancel': '取消', 'revoke': '撤权'};

  bool _current(ProfileResourceRoute original) =>
      mounted &&
      original.isCurrent(ref) &&
      widget.resource.id == original.id &&
      widget.resource.profileId == original.profileId &&
      widget.resource.epoch == original.epoch;

  Future<void> _command(String action) async {
    final resource = widget.resource;
    final task = widget.task;
    if (!_current(resource) ||
        _busy.contains(action) ||
        task.id != resource.id ||
        task.owner != resource.profileId) {
      return;
    }
    final command =
        _pending[action] ??
        (
          id: 'catalog_${action}_${DateTime.now().microsecondsSinceEpoch}',
          revision: task.revision,
          fence: task.fence,
        );
    setState(() {
      _pending[action] = command;
      _busy.add(action);
      _errors.remove(action);
    });
    var refresh = false;
    try {
      final ack = await bridgeApi.taskSafetyCommand(
        resource.profileId,
        resource.id,
        command.id,
        resource.epoch,
        command.revision,
        command.fence,
        action,
      );
      if (!_current(resource)) return;
      if (!ack.success && ack.error?.code == 'TASK_PRECONDITION_REJECTED') {
        setState(() {
          _pending.remove(action);
          _errors[action] = '任务状态已变化，请核对刷新后的状态。';
        });
        refresh = true;
      } else {
        checkCommand(ack);
        if (ack.idResult != resource.id) {
          throw StateError('Safety ACK belongs to another task');
        }
        setState(() {
          _pending.remove(action);
          _errors.remove(action);
        });
        refresh = true;
      }
    } catch (_) {
      if (_current(resource)) {
        setState(
          () => _errors[action] = '${_labels[action]}回执未确认；只查询原命令，不恢复或重复执行任务。',
        );
      }
    } finally {
      if (_current(resource)) setState(() => _busy.remove(action));
    }
    if (refresh && _current(resource)) await widget.refresh();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final task = widget.task;
    final terminal = const {'SUCCEEDED', 'CANCELED'}.contains(task.state);
    final enabled = {
      'pause': !terminal && !task.manualPaused,
      'cancel': !terminal,
      'revoke': task.authorization != 'REVOKED',
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('安全控制作用于此任务；停止中的调用或未知结果仍需核对。恢复、补充要求及审批请在关联会话处理。'),
        Wrap(
          spacing: 8,
          children: [
            for (final action in _labels.keys)
              TextButton(
                onPressed:
                    !_busy.contains(action) &&
                        (enabled[action]! || _pending.containsKey(action))
                    ? () => _command(action)
                    : null,
                child: Text(
                  _pending.containsKey(action)
                      ? '查询${_labels[action]}回执'
                      : action == 'revoke'
                      ? '主动撤权'
                      : '${_labels[action]}任务',
                ),
              ),
          ],
        ),
        for (final error in _errors.values)
          Text(
            error,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_busy.isNotEmpty) const LinearProgressIndicator(),
      ],
    );
  }
}
