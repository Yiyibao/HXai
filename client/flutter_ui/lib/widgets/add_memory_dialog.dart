import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';

Future<void> showAddMemoryDialog(BuildContext context) => showDialog<void>(
  context: context,
  builder: (_) => const _AddMemoryDialog(),
);

class _AddMemoryDialog extends ConsumerStatefulWidget {
  const _AddMemoryDialog();
  @override
  ConsumerState<_AddMemoryDialog> createState() => _AddMemoryDialogState();
}

class _AddMemoryDialogState extends ConsumerState<_AddMemoryDialog> {
  final controller = TextEditingController();
  final requestId = 'manual_${DateTime.now().microsecondsSinceEpoch}';
  late final String profile;
  late final int epoch;
  String type = 'PREFERENCE';
  bool saving = false;
  String? error;
  @override
  void initState() {
    super.initState();
    profile = ref.read(activeProfileIdProvider);
    epoch = ref.read(contextEpochProvider);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (saving || controller.text.trim().isEmpty) return;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      checkCommand(
        await bridgeApi.addMemory(
          profile,
          controller.text.trim(),
          type,
          requestId,
          epoch,
        ),
      );
      if (!mounted) return;
      if (ref.read(activeProfileIdProvider) == profile &&
          ref.read(contextEpochProvider) == epoch) {
        await ref.read(memoriesProvider.notifier).refresh();
      }
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) {
        setState(() {
          saving = false;
          error = '$e';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final current =
        ref.watch(activeProfileIdProvider) == profile &&
        ref.watch(contextEpochProvider) == epoch;
    return PopScope(
      canPop: !saving,
      child: AlertDialog(
        title: const Text('手动添加记忆'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: type,
                decoration: const InputDecoration(labelText: '类型'),
                items: const [
                  DropdownMenuItem(value: 'PREFERENCE', child: Text('偏好')),
                  DropdownMenuItem(value: 'BACKGROUND', child: Text('背景信息')),
                ],
                onChanged: saving
                    ? null
                    : (value) => setState(() => type = value!),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                minLines: 3,
                maxLines: 6,
                maxLength: 2000,
                enabled: !saving,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  labelText: '记忆内容',
                  hintText: '例如：回答时先给结论，再解释原因',
                ),
              ),
              const Text('来源将标记为“手动添加”，保存后可编辑或删除。'),
              if (!current) const Text('档案已切换，请关闭后重新添加。'),
              if (error != null)
                Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: saving ? null : () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: !saving && current && controller.text.trim().isNotEmpty
                ? save
                : null,
            child: Text(saving ? '保存中…' : '保存'),
          ),
        ],
      ),
    );
  }
}
