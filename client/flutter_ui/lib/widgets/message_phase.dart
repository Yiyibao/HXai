import 'package:flutter/material.dart';

import '../bridge/study_host_api.g.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'chat_context_settings.dart';
import 'chat_activity.dart';
import 'reasoning_view.dart';
import '../providers/app_settings_providers.dart';

bool hasUncertainTools(MessageDto message) => message.parts.any(
  (p) =>
      p.type == 'tool' &&
      const [
        'INTERRUPTED',
        'SKIPPED_UNKNOWN',
        'RUNNING',
      ].contains(p.executionState),
);

class MessagePhase extends ConsumerStatefulWidget {
  const MessagePhase({
    super.key,
    required this.message,
    this.onResume,
    this.onRetry,
    this.conversationId,
  });
  final MessageDto message;
  final String? conversationId;
  final Future<void> Function()? onResume, onRetry;
  @override
  ConsumerState<MessagePhase> createState() => _MessagePhaseState();
}

class _MessagePhaseState extends ConsumerState<MessagePhase> {
  bool busy = false;
  Future<void> run(Future<void> Function() action) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await action();
    } catch (error) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('操作未完成：$error')));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.message;
    final status = widget.conversationId == null
        ? const <dynamic, dynamic>{}
        : ref.watch(chatRuntimeStatusProvider(widget.conversationId!)).value ??
              {};
    final waitingApproval = m.parts.any(
      (p) => p.approvalState?.toLowerCase() == 'pending',
    );
    final interrupted = const [
      'FAILED',
      'INTERRUPTED',
      'CANCELLED',
    ].contains(m.generationState);
    if (!interrupted && m.generationState != 'RUNNING' && !waitingApproval) {
      return const SizedBox.shrink();
    }
    final processing = status['processing'];
    final activeTool = m.parts.any(
      (p) => p.type == 'tool' && p.executionState == 'RUNNING',
    );
    final lastReasoning = m.parts.lastIndexWhere((p) => p.type == 'reasoning');
    // The expandable reasoning header already shows the same active indicator.
    if (!waitingApproval &&
        !interrupted &&
        !activeTool &&
        !(processing is String && processing.isNotEmpty) &&
        lastReasoning >= 0 &&
        reasoningStatus(m, lastReasoning) == ReasoningStatus.thinking) {
      return const SizedBox.shrink();
    }
    final uncertain = hasUncertainTools(m);
    final label = waitingApproval
        ? '等待你确认操作'
        : interrupted
        ? uncertain
              ? '工具执行结果待核对，请先检查已有成果'
              : m.generationState == 'FAILED'
              ? '回复未完成，已保留生成内容'
              : '回复已中断，已保留生成内容'
        : activeTool
        ? '正在执行'
        : processing is String && processing.isNotEmpty
        ? processing
        : m.parts.any(
            (p) => p.type == 'text' && (p.text ?? '').trim().isNotEmpty,
          )
        ? '正在生成回复'
        : '正在思考';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!interrupted && !waitingApproval)
            ChatActivity(
              label: label,
              reduceMotion: ref.watch(reduceMotionSettingProvider),
            )
          else
            Semantics(
              liveRegion: true,
              child: Row(
                children: [
                  Icon(
                    interrupted ? Icons.info_outline : Icons.hourglass_empty,
                    size: 16,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      label,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                ],
              ),
            ),
          if (interrupted && status['error'] is String)
            Text(
              status['error'] as String,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          if (interrupted && !uncertain && !waitingApproval)
            Wrap(
              spacing: 8,
              children: [
                if (widget.onResume != null)
                  TextButton(
                    onPressed: busy ? null : () => run(widget.onResume!),
                    child: const Text('继续处理'),
                  ),
                if (widget.onRetry != null)
                  TextButton(
                    onPressed: busy ? null : () => run(widget.onRetry!),
                    child: const Text('重新生成'),
                  ),
              ],
            ),
        ],
      ),
    );
  }
}

class MessageArrival extends StatefulWidget {
  const MessageArrival({super.key, required this.animate, required this.child});
  final bool animate;
  final Widget child;
  @override
  State<MessageArrival> createState() => _MessageArrivalState();
}

class _MessageArrivalState extends State<MessageArrival>
    with SingleTickerProviderStateMixin {
  late final AnimationController controller;
  @override
  void initState() {
    super.initState();
    controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 180),
      value: widget.animate ? 0 : 1,
    );
    if (widget.animate) controller.forward();
  }

  @override
  void didUpdateWidget(covariant MessageArrival old) {
    super.didUpdateWidget(old);
    if (!widget.animate) controller.value = 1;
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: controller,
    child: SlideTransition(
      position: Tween(
        begin: const Offset(0, 0.025),
        end: Offset.zero,
      ).animate(controller),
      child: widget.child,
    ),
  );
}
