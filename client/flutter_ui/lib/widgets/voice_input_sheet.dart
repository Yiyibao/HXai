import 'package:flutter/material.dart';

import 'study_ui.dart';

/// Shown only when an entry point explicitly requests an unsupported voice feature.
class VoiceInputSheet extends StatelessWidget {
  final ValueChanged<String>? onComplete;
  const VoiceInputSheet({super.key, this.onComplete});
  static Future<String?> show(BuildContext context) =>
      showModalBottomSheet<String>(
        context: context,
        builder: (_) => const VoiceInputSheet(),
      );
  @override
  Widget build(BuildContext context) => SafeArea(
    child: SizedBox(
      height: 300,
      child: StudyEmptyState(
        icon: Icons.mic_none_outlined,
        title: '语音输入暂不可用',
        description: '当前 Flutter 界面尚未接通录音与转写。',
        action: OutlinedButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ),
    ),
  );
}
