import 'package:flutter/material.dart';

import '../bridge/study_host_api.g.dart';
import 'math_markdown.dart';
import 'chat_activity.dart';

enum ReasoningStatus { thinking, completed, interrupted }

String messageReasoning(MessageDto message) => message.parts
    .where((part) => part.type == 'reasoning')
    .map((part) => part.reasoning ?? '')
    .where((text) => text.trim().isNotEmpty)
    .join('\n\n---\n\n');

ReasoningStatus reasoningStatus(MessageDto message, int index) {
  // Once an answer or a tool follows this part, this reasoning segment has ended.
  final hasFollowingContent = message.parts
      .skip(index + 1)
      .any(
        (part) =>
            part.type == 'tool' ||
            part.type == 'reasoning' ||
            (part.type == 'text' && (part.text ?? '').trim().isNotEmpty),
      );
  if (hasFollowingContent) return ReasoningStatus.completed;
  if (message.generationState == 'RUNNING') return ReasoningStatus.thinking;
  if (const {
    'INTERRUPTED',
    'CANCELLED',
    'FAILED',
  }.contains(message.generationState)) {
    return ReasoningStatus.interrupted;
  }
  return ReasoningStatus.completed;
}

/// A quiet, collapsible view of reasoning returned by the provider.
class ReasoningView extends StatefulWidget {
  const ReasoningView({
    super.key,
    required this.content,
    required this.status,
    this.reduceMotion = false,
  });

  final String content;
  final ReasoningStatus status;
  final bool reduceMotion;

  @override
  State<ReasoningView> createState() => _ReasoningViewState();
}

class _ReasoningViewState extends State<ReasoningView> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final hasContent = widget.content.trim().isNotEmpty;
    if (!hasContent && widget.status != ReasoningStatus.thinking) {
      return const SizedBox.shrink();
    }
    final colors = Theme.of(context).colorScheme;
    final muted = colors.onSurface.withValues(alpha: 0.6);
    final label = switch (widget.status) {
      ReasoningStatus.thinking => '正在思考',
      ReasoningStatus.completed => '已完成思考',
      ReasoningStatus.interrupted => '思考已中断',
    };
    final expanded = _expanded && hasContent;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          liveRegion: widget.status == ReasoningStatus.thinking,
          button: hasContent,
          expanded: hasContent ? expanded : null,
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: hasContent
                ? () => setState(() => _expanded = !_expanded)
                : null,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 2),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (widget.status == ReasoningStatus.thinking) ...[
                    ChatActivityDots(
                      color: muted,
                      reduceMotion: widget.reduceMotion,
                    ),
                    const SizedBox(width: 8),
                  ],
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: muted,
                    ),
                  ),
                  if (hasContent) ...[
                    const SizedBox(width: 4),
                    Icon(
                      expanded
                          ? Icons.keyboard_arrow_down
                          : Icons.keyboard_arrow_right,
                      size: 18,
                      color: muted,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
        if (expanded)
          Container(
            margin: const EdgeInsets.only(left: 5, bottom: 12),
            padding: const EdgeInsets.only(left: 14, top: 2, bottom: 4),
            decoration: BoxDecoration(
              border: Border(
                left: BorderSide(
                  color: colors.onSurface.withValues(alpha: 0.12),
                  width: 2,
                ),
              ),
            ),
            constraints: const BoxConstraints(maxHeight: 280),
            child: SingleChildScrollView(
              child: MathMarkdownView(
                content: widget.content,
                style: TextStyle(fontSize: 13, height: 1.65, color: muted),
              ),
            ),
          ),
      ],
    );
  }
}
