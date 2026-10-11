import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/study_ui.dart';

class SymbolCheckPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const SymbolCheckPage({super.key, this.resource});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final material = watchResource(ref, resource, materialsProvider, (m) => m.id);
    if (material == null) {
      final loading = resource?.isCurrent(ref) == true &&
          ref.watch(materialsProvider).isLoading;
      return Scaffold(
        appBar: const StudyHeader('原页核对'),
        body: loading
            ? const Center(child: CircularProgressIndicator())
            : const StudyEmptyState(
                icon: Icons.fact_check_outlined,
                title: '文件不可用',
                description: '旧核对内容已撤销，请从当前文件库重新打开。',
              ),
      );
    }
    return _SymbolEditor(
      key: ValueKey('${resource!.profileId}:${resource!.epoch}:${material.id}:${material.symbolCorrection}'),
      resource: resource!,
      material: material,
    );
  }
}

class _SymbolEditor extends ConsumerStatefulWidget {
  final ProfileResourceRoute resource;
  final MaterialFileDto material;
  const _SymbolEditor({super.key, required this.resource, required this.material});
  @override
  ConsumerState<_SymbolEditor> createState() => _SymbolEditorState();
}

class _SymbolEditorState extends ConsumerState<_SymbolEditor> {
  late final controller = TextEditingController(
    text: widget.material.symbolCorrection ?? '',
  );
  bool busy = false;
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> save() async {
    final material = readResource(ref, widget.resource, materialsProvider, (m) => m.id);
    if (material == null || busy || controller.text.trim().isEmpty) return;
    bool current() => mounted && widget.resource.isCurrent(ref) &&
        readResource(ref, widget.resource, materialsProvider, (m) => m.id) != null;
    setState(() => busy = true);
    try {
      final result = await bridgeApi.checkSymbol(
        widget.resource.profileId,
        material.id,
        controller.text.trim(),
        'sym_${DateTime.now().microsecondsSinceEpoch}',
      );
      if (!current()) return;
      checkCommand(result);
      ref.read(materialsProvider.notifier).refresh();
      if (mounted && widget.resource.isCurrent(ref)) context.pop();
    } catch (e) {
      if (current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.material;
    return Scaffold(
      appBar: const StudyHeader('原页核对'),
      body: SafeArea(
        child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                children: [
                  StudySection(
                    title: '原文件',
                    children: [
                      StudyRow(
                        icon: Icons.description_outlined,
                        title: m.name,
                        subtitle: m.pageNumber == null
                            ? '原页页码未提供'
                            : '原文件第 ${m.pageNumber} 页',
                      ),
                    ],
                  ),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.open_in_new, size: 18),
                    label: const Text('打开原文件核对'),
                    onPressed: () async {
                      if (readResource(ref, widget.resource, materialsProvider,
                          (m) => m.id) == null) return;
                      try {
                        checkCommand(
                          await bridgeApi.openArtifact(
                            widget.resource.profileId,
                            m.id,
                          ),
                        );
                      } catch (e) {
                        if (context.mounted &&
                            readResource(ref, widget.resource, materialsProvider,
                                (m) => m.id) != null)
                          ScaffoldMessenger.of(context)
                              .showSnackBar(SnackBar(content: Text('$e')));
                      }
                    },
                  ),
                  const SizedBox(height: 24),
                  const Text('核对后的内容'),
                  const SizedBox(height: 10),
                  TextField(
                    controller: controller,
                    minLines: 4,
                    maxLines: 12,
                    onChanged: (_) => setState(() {}),
                    decoration: const InputDecoration(
                      hintText: '对照原文件，输入准确的符号或文字',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '原页内嵌预览尚未接入；请先使用系统应用查看真实原文件。',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton(
                    onPressed: busy || controller.text.trim().isEmpty
                        ? null
                        : save,
                    child: Text(busy ? '保存中…' : '确认并保存'),
                  ),
                ],
              ),
      ),
    );
  }
}
