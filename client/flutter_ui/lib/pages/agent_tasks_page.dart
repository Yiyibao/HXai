import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/agent_usage_view.dart';

String agentTaskStatus(String state) => switch (state) {
  'QUEUED' => '排队中',
  'RUNNING' => '执行中',
  'STOP_REQUESTED' => '已请求停止，尚未确认停止',
  'UNCERTAIN_SIDE_EFFECT' => '结果未知，禁止重复执行',
  'SUCCEEDED' => '任务记录：目标验收通过',
  'COMPLETED_UNVERIFIED' => '执行已结束，目标尚未独立验收',
  'PAUSED_BY_USER' => '已暂停',
  'CANCELED' => '已取消',
  'WAITING_AUTHORIZATION' => '等待原范围授权',
  'WAITING_DEVICE' => '等待设备或前台确认',
  'WAITING_INPUT' => '等待输入',
  'FAILED_BLOCKED' => '失败，等待处理',
  'INTERRUPTED' => '已中断，等待恢复',
  _ => state,
};
bool _ready(WidgetRef ref) {
  final bootstrap = ref.watch(bootstrapProvider);
  return bootstrap.hasValue &&
      !bootstrap.isLoading &&
      !bootstrap.hasError &&
      bootstrap.value!.currentProfileId == ref.watch(activeProfileIdProvider) &&
      bootstrap.value!.contextEpoch == ref.watch(contextEpochProvider);
}

class AgentTasksPage extends ConsumerWidget {
  const AgentTasksPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ready = _ready(ref);
    final owner = ref.watch(activeProfileIdProvider),
        epoch = ref.watch(contextEpochProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('任务中心'),
        actions: [
          TextButton(
            onPressed: ready
                ? () => context.push(
                    '/usage',
                    extra: ProfileResourceRoute.capture(ref, 'usage'),
                  )
                : null,
            child: const Text('请求用量'),
          ),
        ],
      ),
      body: ready
          ? AgentTaskCatalogView(
              key: ValueKey('$owner:$epoch'),
              owner: owner,
              epoch: epoch,
            )
          : Center(
              child: TextButton(
                onPressed: () => ref.invalidate(bootstrapProvider),
                child: const Text('正在核对档案，点击重新读取'),
              ),
            ),
    );
  }
}

class AgentTaskCatalogView extends ConsumerStatefulWidget {
  const AgentTaskCatalogView({
    super.key,
    required this.owner,
    required this.epoch,
  });
  final String owner;
  final int epoch;
  @override
  ConsumerState<AgentTaskCatalogView> createState() =>
      _AgentTaskCatalogViewState();
}

class _AgentTaskCatalogViewState extends ConsumerState<AgentTaskCatalogView> {
  final _tasks = <AgentCatalogTaskDto>[];
  String _kind = 'all';
  String? _next, _error;
  int _generation = 0;
  bool _busy = false;
  bool get _current {
    final bootstrap = ref.read(bootstrapProvider);
    return mounted &&
        bootstrap.hasValue &&
        !bootstrap.isLoading &&
        !bootstrap.hasError &&
        bootstrap.value!.currentProfileId == widget.owner &&
        bootstrap.value!.contextEpoch == widget.epoch &&
        ref.read(activeProfileIdProvider) == widget.owner &&
        ref.read(contextEpochProvider) == widget.epoch;
  }

  @override
  void initState() {
    super.initState();
    unawaited(_read());
  }

  @override
  void didUpdateWidget(covariant AgentTaskCatalogView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.owner != widget.owner || oldWidget.epoch != widget.epoch) {
      _generation++;
      _tasks.clear();
      _next = null;
      _error = null;
      _busy = false;
      unawaited(_read());
    }
  }

  Future<void> _read({bool more = false}) async {
    if (!_current || (more && (_busy || _next == null))) {
      return;
    }
    final generation = ++_generation, kind = _kind;
    final cursor = more ? _next : null;
    setState(() {
      _busy = true;
      _error = null;
      if (!more) {
        _tasks.clear();
        _next = null;
      }
    });
    try {
      final page = await bridgeApi.getAgentTaskCatalog(
        widget.owner,
        kind,
        cursor,
        20,
        widget.epoch,
      );
      if (!_current || generation != _generation) {
        return;
      }
      if (page.owner != widget.owner ||
          page.kind != kind ||
          page.tasks.any((task) => task.owner != widget.owner)) {
        throw StateError('任务目录归属不匹配');
      }
      setState(() {
        final ids = _tasks.map((task) => task.id).toSet();
        _tasks.addAll(page.tasks.where((task) => ids.add(task.id)));
        _next = page.nextCursor;
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _error = '任务目录读取失败，请重试。');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    ref.watch(bootstrapProvider);
    if (!_current) {
      return const Center(child: Text('档案或上下文已变化，请重新打开任务中心。'));
    }
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text('当前档案跨会话任务 · 全工作域共用一个执行位。列表来自持久任务记录，点击查看实际计划与回执。'),
        Wrap(
          spacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            DropdownButton<String>(
              value: _kind,
              items: const [
                DropdownMenuItem(value: 'all', child: Text('全部任务')),
                DropdownMenuItem(value: 'main', child: Text('主任务')),
                DropdownMenuItem(value: 'auxiliary', child: Text('辅助请求')),
              ],
              onChanged: (value) {
                if (value != null) {
                  setState(() => _kind = value);
                  unawaited(_read());
                }
              },
            ),
            TextButton(
              onPressed: _busy ? null : () => _read(),
              child: const Text('刷新任务'),
            ),
          ],
        ),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null) ...[
          Text(_error!),
          TextButton(
            onPressed: _busy ? null : () => _read(more: _tasks.isNotEmpty),
            child: const Text('重试读取任务'),
          ),
        ],
        if (!_busy && _error == null && _tasks.isEmpty)
          const Padding(
            padding: EdgeInsets.all(20),
            child: Text('此范围暂无任务。请求用量仍可从上方打开。'),
          ),
        for (final task in _tasks)
          Card(
            child: InkWell(
              onTap: () => context.push(
                '/tasks/detail',
                extra: ProfileResourceRoute.capture(ref, task.id),
              ),
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      agentTaskStatus(task.state),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    Text(
                      task.goal,
                      maxLines: 4,
                      overflow: TextOverflow.ellipsis,
                    ),
                    Text(
                      '${task.role == 'main' ? '主任务' : task.role} · 已接受模型 ${task.modelId} · ${task.reasoning}',
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (task.manualPaused ||
                        task.authorization != 'ACTIVE' ||
                        task.inputBlocked ||
                        task.deviceBlocked ||
                        task.deviceConfirmation ||
                        task.approvalBlocked)
                      Text(
                        '等待条件：${[if (task.manualPaused) '用户暂停', if (task.authorization != 'ACTIVE') task.authorization, if (task.inputBlocked) '缺少输入', if (task.deviceBlocked) '设备不可用', if (task.deviceConfirmation) '前台确认', if (task.approvalBlocked) '工具审批'].join(' · ')}',
                      ),
                    Text(
                      '目标/计划修订 ${task.goalRevision}/${task.plannedRevision} · 点击查看回执',
                    ),
                  ],
                ),
              ),
            ),
          ),
        if (_next != null)
          TextButton(
            onPressed: _busy ? null : () => _read(more: true),
            child: const Text('加载更多任务'),
          ),
      ],
    );
  }
}

class AgentUsagePage extends ConsumerWidget {
  const AgentUsagePage({super.key, required this.resource});
  final ProfileResourceRoute? resource;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    ref.watch(bootstrapProvider);
    final current = _ready(ref) && resource?.isCurrent(ref) == true;
    return Scaffold(
      appBar: AppBar(title: const Text('请求用量')),
      body: current
          ? AgentUsageView(
              owner: resource!.profileId,
              taskId: null,
              expectedEpoch: resource!.epoch,
            )
          : const Center(child: Text('档案或上下文已变化，请从侧栏重新打开请求用量。')),
    );
  }
}
