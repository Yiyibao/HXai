import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';
import '../widgets/agent_usage_view.dart';
import '../widgets/agent_task_safety_controls.dart';
import '../widgets/agent_task_resume_control.dart';
import 'agent_tasks_page.dart';

class AgentTaskDetailPage extends ConsumerStatefulWidget {
  const AgentTaskDetailPage({super.key, required this.resource});
  final ProfileResourceRoute? resource;
  @override
  ConsumerState<AgentTaskDetailPage> createState() =>
      _AgentTaskDetailPageState();
}

class _AgentTaskDetailPageState extends ConsumerState<AgentTaskDetailPage> {
  AgentCatalogDetailDto? _detail;
  final _calls = <AgentCatalogCallDto>[];
  String? _error;
  int _generation = 0;
  bool _busy = false, _opening = false;
  bool _retryMore = false;
  bool get _current {
    final bootstrap = ref.read(bootstrapProvider).value;
    return mounted &&
        widget.resource?.isCurrent(ref) == true &&
        bootstrap?.currentProfileId == widget.resource?.profileId &&
        bootstrap?.contextEpoch == widget.resource?.epoch;
  }

  bool _for(ProfileResourceRoute resource) =>
      _current &&
      widget.resource?.id == resource.id &&
      widget.resource?.profileId == resource.profileId &&
      widget.resource?.epoch == resource.epoch;
  @override
  void initState() {
    super.initState();
    unawaited(_read());
  }

  @override
  void didUpdateWidget(covariant AgentTaskDetailPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.resource?.id != widget.resource?.id ||
        oldWidget.resource?.profileId != widget.resource?.profileId ||
        oldWidget.resource?.epoch != widget.resource?.epoch) {
      _generation++;
      _detail = null;
      _calls.clear();
      _error = null;
      _busy = false;
      _opening = false;
      _retryMore = false;
      unawaited(_read());
    }
  }

  Future<void> _read({bool more = false, bool keepDetail = true}) async {
    if (!_current || (more && (_busy || _detail?.nextCursor == null))) {
      return;
    }
    final resource = widget.resource!, generation = ++_generation;
    final cursor = more ? _detail?.nextCursor : null;
    setState(() {
      _busy = true;
      _error = null;
      if (!more && !keepDetail) {
        _detail = null;
        _calls.clear();
      }
    });
    try {
      final detail = await bridgeApi.getAgentTaskDetail(
        resource.profileId,
        resource.id,
        cursor,
        20,
        resource.epoch,
      );
      if (!_for(resource) || generation != _generation) {
        return;
      }
      if (detail.task.owner != resource.profileId ||
          detail.task.id != resource.id) {
        throw StateError('任务详情归属不匹配');
      }
      // A page from a changed goal must not be appended to an earlier plan's calls.
      if (more && _detail?.task.revision != detail.task.revision) {
        unawaited(_read());
        return;
      }
      setState(() {
        _detail = detail;
        if (!more) _calls.clear();
        final ids = _calls.map((call) => call.id).toSet();
        _calls.addAll(detail.calls.where((call) => ids.add(call.id)));
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() {
          _error = '任务详情读取失败，请重试。';
          _retryMore = more;
        });
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _openConversation() async {
    final task = _detail?.task;
    if (!_current ||
        _opening ||
        task == null ||
        _detail?.conversationAvailable != true) {
      return;
    }
    setState(() => _opening = true);
    final resource = widget.resource!;
    try {
      final conversation = await bridgeApi.getConversationDetails(
        widget.resource!.profileId,
        task.conversationId,
      );
      if (!_for(resource)) {
        return;
      }
      if (conversation.profileId != task.owner ||
          conversation.id != task.conversationId) {
        throw StateError('会话归属不匹配');
      }
      ref.read(activeConversationIdProvider.notifier).state = conversation.id;
      ref.read(activeStudyModeProvider.notifier).state = conversation.studyMode;
      if (mounted) {
        context.go('/chat');
      }
    } catch (_) {
      if (_for(resource)) {
        setState(() => _error = '关联会话已不可访问；任务回执仍保留。');
      }
    } finally {
      if (_for(resource)) {
        setState(() => _opening = false);
      }
    }
  }

  Future<void> _usage() {
    final resource = widget.resource!;
    return showDialog<void>(
      context: context,
      builder: (dialogContext) => Dialog(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: 620,
            maxHeight: MediaQuery.sizeOf(dialogContext).height * .85,
          ),
          child: Column(
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Padding(
                      padding: EdgeInsets.all(12),
                      child: Text('此任务请求用量'),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭用量',
                    onPressed: () => Navigator.pop(dialogContext),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              Expanded(
                child: Consumer(
                  builder: (context, ref, _) {
                    ref.watch(bootstrapProvider);
                    ref.watch(activeProfileIdProvider);
                    ref.watch(contextEpochProvider);
                    return resource.isCurrent(ref)
                        ? AgentUsageView(
                            owner: resource.profileId,
                            taskId: resource.id,
                            expectedEpoch: resource.epoch,
                          )
                        : const Center(child: Text('档案或上下文已变化，请重新打开请求用量。'));
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    ref.watch(bootstrapProvider);
    final current = _current, detail = current ? _detail : null;
    if (current && detail == null && !_busy && _error == null) {
      Future.microtask(_read);
    }
    return Scaffold(
      appBar: AppBar(title: const Text('任务详情')),
      body: !current
          ? const Center(child: Text('档案或上下文已变化，请重新打开任务详情。'))
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Wrap(
                  spacing: 10,
                  children: [
                    TextButton(
                      onPressed: _busy ? null : () => _read(),
                      child: const Text('刷新详情'),
                    ),
                    TextButton(
                      onPressed: detail == null ? null : _usage,
                      child: const Text('此任务请求用量'),
                    ),
                    TextButton(
                      onPressed:
                          detail?.conversationAvailable == true && !_opening
                          ? _openConversation
                          : null,
                      child: const Text('打开关联会话'),
                    ),
                  ],
                ),
                const Text('历史任务、成果与用量独立保留；查看不会继续执行。'),
                if (detail != null)
                  AgentTaskSafetyControls(
                    key: ValueKey(
                      '${widget.resource!.profileId}:${widget.resource!.epoch}:${detail.task.id}',
                    ),
                    task: detail.task,
                    resource: widget.resource!,
                    refresh: () => _read(keepDetail: true),
                  ),
                if (detail != null)
                  AgentTaskResumeControl(
                    key: ValueKey(
                      'resume:${widget.resource!.profileId}:${widget.resource!.epoch}:${detail.task.id}',
                    ),
                    task: detail.task,
                    conversationAvailable: detail.conversationAvailable,
                    resource: widget.resource!,
                    refresh: () => _read(keepDetail: true),
                  ),
                if (_busy || _opening) const LinearProgressIndicator(),
                if (_error != null) ...[
                  Text(_error!),
                  TextButton(
                    onPressed: _busy ? null : () => _read(more: _retryMore),
                    child: const Text('重试详情读取'),
                  ),
                ],
                if (detail != null) ...[
                  Text(
                    agentTaskStatus(detail.task.state),
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  SelectableText(detail.task.goal),
                  Text(
                    '任务锁定模型：${detail.providerName ?? detail.task.providerId} / ${detail.modelName ?? detail.task.modelId} · ${detail.task.reasoning}',
                  ),
                  Text(
                    '任务修订 ${detail.task.revision} · 目标/计划 ${detail.task.goalRevision}/${detail.task.plannedRevision} · fence ${detail.task.fence}',
                  ),
                  Text(
                    '授权 ${detail.task.authorization} · ${[if (detail.task.manualPaused) '用户暂停', if (detail.task.inputBlocked) '缺少输入', if (detail.task.deviceBlocked) '设备不可用', if (detail.task.deviceConfirmation) '前台确认', if (detail.task.approvalBlocked) '工具审批'].join(' · ')}',
                  ),
                  if (!detail.conversationAvailable)
                    const Text('关联会话已不可用。独立任务记录仍可查看。'),
                  const SizedBox(height: 16),
                  Text('实际计划', style: Theme.of(context).textTheme.titleMedium),
                  if (detail.steps.isEmpty)
                    const Text('没有已发布的工具计划；闲聊或辅助请求可以没有工具。'),
                  if (detail.steps.isEmpty && detail.task.acceptanceVerified)
                    const Text('已核对回复交付；数学结论、引用来源和外部动作须另有验证证据。'),
                  for (final step in detail.steps)
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${step.id} · ${step.tool} · ${step.state}'),
                            Text(step.purpose),
                            if (step.callId != null)
                              SelectableText('调用 ${step.callId}'),
                          ],
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  Text(
                    '回执引用的成果',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const Text('SHA 来自保存的文件元数据，打开预览时独立回读核验当前文件。'),
                  if (detail.resultsPartial)
                    const Text('部分回执过大、尚未核验或成果缺失；本页只列可解析的部分，请在关联会话核对。'),
                  for (final artifact in detail.artifacts)
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(artifact.name),
                            SelectableText('保存的 SHA256：${artifact.sha256}'),
                            TextButton(
                              onPressed: () {
                                if (_current) {
                                  context.push(
                                    '/artifacts/preview',
                                    extra: ProfileResourceRoute.capture(
                                      ref,
                                      artifact.id,
                                    ),
                                  );
                                }
                              },
                              child: const Text('打开成果预览'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  Text(
                    '实际调用回执',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (_calls.isEmpty) const Text('暂无调用记录。'),
                  for (final call in _calls)
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${call.tool} · ${call.state}'),
                            SelectableText(
                              '调用 ${call.id}\n输入指纹 ${call.inputHash}\n结果指纹 ${call.resultHash ?? '未知'}',
                            ),
                            Text(
                              '能力 ${call.capability ?? '未知'} · 授权 epoch ${call.grantEpoch ?? '未知'}',
                            ),
                          ],
                        ),
                      ),
                    ),
                  if (detail.nextCursor != null)
                    TextButton(
                      onPressed: _busy ? null : () => _read(more: true),
                      child: const Text('加载更多调用'),
                    ),
                ],
              ],
            ),
    );
  }
}
