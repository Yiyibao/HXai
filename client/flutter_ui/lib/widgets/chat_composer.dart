import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';
import '../pages/model_picker_sheet.dart';
import '../theme/study_theme.dart';

/// The same two-row composer in Chat, image, document and analysis views.
class ChatComposer extends ConsumerWidget {
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String hint;
  final VoidCallback? onSend;
  final VoidCallback? onStop;
  final VoidCallback? onAttach;
  final bool busy;
  final bool hasAttachments;
  final Widget? attachments;
  final bool showDisclaimer;
  const ChatComposer({
    super.key,
    required this.controller,
    this.focusNode,
    this.hint = '继续追问，或添加文件',
    this.onSend,
    this.onStop,
    this.onAttach,
    this.busy = false,
    this.hasAttachments = false,
    this.attachments,
    this.showDisclaimer = false,
  });
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = StudyPalette.of(context);
    final model = ref.watch(bootstrapProvider).value?.activeModelName ?? '当前模型';
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: ValueListenableBuilder<TextEditingValue>(
          valueListenable: controller,
          builder: (context, value, _) {
            final sendEnabled =
                !busy &&
                onSend != null &&
                (value.text.trim().isNotEmpty || hasAttachments);
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  padding: const EdgeInsets.fromLTRB(14, 10, 8, 6),
                  decoration: BoxDecoration(
                    color: c.bubbleGray,
                    borderRadius: BorderRadius.circular(26),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (attachments != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: attachments!,
                        ),
                      TextField(
                        controller: controller,
                        focusNode: focusNode,
                        minLines: 1,
                        maxLines: 5,
                        style: TextStyle(
                          fontSize: 16,
                          height: 1.5,
                          color: c.textBlack,
                        ),
                        decoration: InputDecoration(
                          hintText: hint,
                          hintStyle: TextStyle(fontSize: 15, color: c.textGray),
                          filled: false,
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          isDense: true,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 4,
                          ),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          if (onAttach != null)
                            IconButton(
                              tooltip: '添加文件',
                              onPressed: busy ? null : onAttach,
                              icon: Icon(
                                Icons.add,
                                size: 26,
                                color: c.textBlack,
                              ),
                            ),
                          const Spacer(),
                          ConstrainedBox(
                            constraints: BoxConstraints(
                              maxWidth: MediaQuery.sizeOf(context).width * 0.48,
                            ),
                            child: InkWell(
                              onTap: () => showModelPickerSheet(context),
                              borderRadius: BorderRadius.circular(24),
                              child: Container(
                                constraints: const BoxConstraints(
                                  minHeight: 48,
                                ),
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                ),
                                decoration: BoxDecoration(
                                  border: Border.all(color: c.borderGray),
                                  borderRadius: BorderRadius.circular(24),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Flexible(
                                      child: Text(
                                        model,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: c.textBlack,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 4),
                                    Icon(
                                      Icons.keyboard_arrow_down,
                                      size: 16,
                                      color: c.textGray,
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                          IconButton(
                            tooltip: onStop != null ? '停止生成' : '发送消息',
                            onPressed: onStop ?? (sendEnabled ? onSend : null),
                            style: IconButton.styleFrom(
                              backgroundColor: onStop != null || sendEnabled
                                  ? c.accent
                                  : c.borderGray,
                              foregroundColor: c.onAccent,
                              disabledForegroundColor: c.textGray,
                              disabledBackgroundColor: c.borderGray,
                              minimumSize: const Size(48, 48),
                            ),
                            icon: Icon(
                              onStop != null
                                  ? Icons.stop_rounded
                                  : Icons.arrow_upward_rounded,
                              size: 22,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                if (showDisclaimer)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'AI 的回答可能有误，请核对重要信息',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 10, color: c.textGray),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}
