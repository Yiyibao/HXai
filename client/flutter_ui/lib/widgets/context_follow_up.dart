import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';
import '../providers/frontend_feature_providers.dart';
import 'chat_composer.dart';

/// Sends a follow-up through the existing chat pipeline and keeps the draft on failure.
class ContextFollowUp extends ConsumerStatefulWidget {
  final String hint;
  final String contextText;
  final String Function()? contextTextBuilder;
  final Future<bool> Function()? beforeSend;
  final bool Function()? isAvailable;
  final String? materialId;
  final VoidCallback? onSent;
  const ContextFollowUp({
    super.key,
    required this.hint,
    this.contextText = '',
    this.contextTextBuilder,
    this.beforeSend,
    this.materialId,
    this.isAvailable,
    this.onSent,
  });
  @override
  ConsumerState<ContextFollowUp> createState() => _ContextFollowUpState();
}

class _ContextFollowUpState extends ConsumerState<ContextFollowUp> {
  final controller = TextEditingController();
  bool sending = false;
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> send() async {
    final text = controller.text.trim();
    if (text.isEmpty || sending) return;
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final conversation = ref.read(activeConversationIdProvider);
    final revision = ref
        .read(activeConversationIdProvider.notifier)
        .selectionRevision;
    bool current() =>
        mounted &&
        (widget.isAvailable?.call() ?? true) &&
        owner == ref.read(activeProfileIdProvider) &&
        epoch == ref.read(contextEpochProvider) &&
        conversation == ref.read(activeConversationIdProvider) &&
        revision ==
            ref.read(activeConversationIdProvider.notifier).selectionRevision;
    setState(() => sending = true);
    try {
      if (widget.beforeSend != null && !await widget.beforeSend!()) return;
      if (!current()) return;
      final path = widget.materialId == null
          ? null
          : await FrontendFeatures.fromWidget<String>(ref, 'materialPath', {
              'id': widget.materialId,
            });
      if (!current()) return;
      await ref
          .read(activeConversationDetailProvider.notifier)
          .sendMessage(
            '当前内容：${widget.contextTextBuilder?.call() ?? widget.contextText}\n\n$text',
            attachmentPaths: path == null ? null : [path],
          );
      if (mounted &&
          owner == ref.read(activeProfileIdProvider) &&
          epoch == ref.read(contextEpochProvider) &&
          ref.read(activeConversationIdProvider.notifier).selectionRevision ==
              revision + (conversation == null ? 1 : 0)) {
        if (controller.text.trim() == text) controller.clear();
        widget.onSent?.call();
      }
    } catch (e) {
      if (mounted &&
          (widget.isAvailable?.call() ?? true) &&
          owner == ref.read(activeProfileIdProvider) &&
          epoch == ref.read(contextEpochProvider))
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('发送失败，草稿已保留：$e')));
    } finally {
      if (mounted) setState(() => sending = false);
    }
  }

  @override
  Widget build(BuildContext context) => ChatComposer(
    controller: controller,
    hint: widget.hint,
    busy: sending,
    onSend: send,
  );
}
