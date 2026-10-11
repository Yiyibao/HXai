import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/study_ui.dart';
import '../theme/study_theme.dart';

class MemoryEditPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const MemoryEditPage({super.key, this.resource});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final item = watchResource(ref, resource, memoriesProvider, (m) => m.id);
    if (item == null) {
      final loading = resource?.isCurrent(ref) == true &&
          ref.watch(memoriesProvider).isLoading;
      return Scaffold(
        appBar: const StudyHeader('编辑记忆'),
        body: loading
            ? const Center(child: CircularProgressIndicator())
            : const StudyEmptyState(
                icon: Icons.psychology_outlined,
                title: '记忆不可用',
                description: '旧编辑内容已撤销，请从当前记忆列表重新打开。',
              ),
      );
    }
    return _MemoryEditor(
      key: ValueKey('${resource!.profileId}:${resource!.epoch}:${item.id}:${item.revision}'),
      resource: resource!,
      memory: item,
    );
  }
}

class _MemoryEditor extends ConsumerStatefulWidget {
  final ProfileResourceRoute resource;
  final MemoryItemDto memory;
  const _MemoryEditor({super.key, required this.resource, required this.memory});

  @override
  ConsumerState<_MemoryEditor> createState() => _MemoryEditorState();
}

class _MemoryEditorState extends ConsumerState<_MemoryEditor> {
  late TextEditingController _contentController;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _contentController = TextEditingController(
      text: widget.memory.content,
    );
  }

  @override
  void dispose() {
    _contentController.dispose();
    super.dispose();
  }

  Future<void> _handleSave() async {
    final newText = _contentController.text.trim();
    if (newText.isEmpty) return;

    final m = readResource(ref, widget.resource, memoriesProvider, (m) => m.id);
    if (_saving || m == null || m.revision != widget.memory.revision) return;
    bool current() => mounted && widget.resource.isCurrent(ref) &&
        readResource(ref, widget.resource, memoriesProvider, (m) => m.id)?.revision ==
            m.revision;

    setState(() => _saving = true);
    final profile = widget.resource.profileId;
    final reqId = 'update_mem_${DateTime.now().millisecondsSinceEpoch}';

    try {
      final res = await bridgeApi.updateMemory(
        profile,
        m.id,
        newText,
        m.revision,
        reqId,
      );
      if (!current()) return;
      checkCommand(res);
      ref.read(memoriesProvider.notifier).refresh();
      if (mounted && widget.resource.isCurrent(ref)) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('记忆已更新并保存为新版本')));
        context.pop();
      }
    } catch (e) {
      if (current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('保存失败: $e')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final canSave = _contentController.text.trim().isNotEmpty &&
        readResource(ref, widget.resource, memoriesProvider, (m) => m.id)?.revision ==
            widget.memory.revision;

    return Scaffold(
      backgroundColor: StudyPalette.of(context).bgWhite,
      appBar: AppBar(
        backgroundColor: StudyPalette.of(context).bgWhite,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back,
            color: StudyPalette.of(context).textBlack,
          ),
          onPressed: () => context.pop(),
        ),
        title: Text(
          '编辑记忆',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: TextButton(
              onPressed: canSave && !_saving ? _handleSave : null,
              child: _saving
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(
                      '保存',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: canSave
                            ? StudyPalette.of(context).textBlack
                            : StudyPalette.of(context).borderGray,
                      ),
                    ),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Memory text editing field
              Text(
                '记忆内容',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: StudyPalette.of(context).textBlack,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _contentController,
                onChanged: (_) => setState(() {}),
                maxLines: 6,
                style: TextStyle(
                  fontSize: 15,
                  color: StudyPalette.of(context).textBlack,
                  height: 1.5,
                ),
                decoration: InputDecoration(
                  filled: true,
                  fillColor: StudyPalette.of(context).surfaceBackground,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide(
                      color: StudyPalette.of(context).borderGray,
                    ),
                  ),
                  contentPadding: const EdgeInsets.all(16),
                ),
              ),
              const SizedBox(height: 12),

              // Source footnote
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: StudyPalette.of(context).bgWhite,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: StudyPalette.of(context).borderGray,
                    width: 0.8,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '来源摘要',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                    SizedBox(height: 4),
                    Text(
                      '${widget.memory.sourceTopic.isNotEmpty ? widget.memory.sourceTopic : '来源未提供'}。此次修改将保存为新版本。',
                      style: TextStyle(
                        fontSize: 12,
                        color: StudyPalette.of(context).textGray,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}
