import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../providers/profile_resource.dart';
import '../providers/material_library_providers.dart';
import '../providers/study_providers.dart';
import '../widgets/context_follow_up.dart';
import 'math_work_page.dart';

class LibraryDetailPage extends ConsumerStatefulWidget {
  final LibraryAccess access;
  final LibraryEntry entry;
  const LibraryDetailPage({super.key, required this.access, required this.entry});
  @override
  ConsumerState<LibraryDetailPage> createState() => _LibraryDetailPageState();
}
class _LibraryDetailPageState extends ConsumerState<LibraryDetailPage> {
  Map<String, dynamic>? page, mathEvidence, chapters;
  String? error;
  bool busy = false, removed = false, mathMode = false;
  int request = 0;
  final transcript = TextEditingController();
  final offsets = <int>[];
  Future<String?>? imagePath;
  bool get current {
    if (!mounted || !widget.access.current(ref) || removed) return false;
    final entry = widget.entry;
    if (entry.data['output'] == true) {
      final state = ref.read(artifactsProvider);
      return !state.isLoading && !state.hasError && (state.value?.any((e) => e.id == entry.id && e.sha256 == entry.hash) ?? false);
    }
    final state = ref.read(materialsProvider);
    return !state.isLoading && !state.hasError && (state.value?.any((e) => e.id == entry.id && e.sha256 == entry.hash) ?? false);
  }
  @override
  void initState() {
    super.initState();
    transcript.text = widget.entry.data['symbolCorrection'] as String? ?? '';
    if (const ['png', 'jpg', 'jpeg', 'webp'].contains(widget.entry.extension)) {
      imagePath = widget.access.call<String>('library.imagePath', widget.entry.identity);
    }
  }
  @override
  void dispose() { transcript.dispose(); super.dispose(); }
  Future<Map<String, dynamic>> jsonCall(String method, [Map<String, Object?> fields = const {}]) async {
    final raw = await widget.access.call<String>(method, {...widget.entry.identity, ...fields});
    if (!current) throw StateError('档案或材料已改变');
    return jsonDecode(raw!) as Map<String, dynamic>;
  }
  Future<void> perform(Future<void> Function() work) async {
    if (!current || busy) return;
    setState(() { busy = true; error = null; });
    try { await work(); } catch (e) {
      if (mounted && widget.access.current(ref)) {
        if (current) { setState(() => error = '$e'); }
        else { ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e'))); }
      }
    }
    finally { if (mounted) setState(() => busy = false); }
  }
  Future<void> load(int offset, {bool previous = false}) => perform(() async {
    final serial = ++request;
    final result = await jsonCall('library.read', {'offset': offset});
    if (!current || serial != request) return;
    setState(() {
      if (previous) { if (offsets.isNotEmpty) offsets.removeLast(); }
      else if (page != null) { offsets.add(page!['offset'] as int); }
      page = result;
    });
  });
  Future<void> organize() async {
    final folder = TextEditingController(text: widget.entry.folder);
    final tags = TextEditingController(text: widget.entry.tags.join(', '));
    final accepted = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('文件夹与标签'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(controller: folder, decoration: const InputDecoration(labelText: '逻辑文件夹，如 数学/高等数学')),
        TextField(controller: tags, decoration: const InputDecoration(labelText: '标签，使用逗号分隔')),
        const Text('原件路径和版本不变。文件夹可含12层，标签最多20个。'),
      ])),
      actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('保存'))],
    ));
    final path = folder.text.trim(); final labels = tags.text.split(RegExp('[,，]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
    folder.dispose(); tags.dispose();
    if (accepted != true || !current) return;
    await perform(() async {
      await widget.access.call<Object?>('library.organize', {...widget.entry.identity, 'folder': path, 'tags': labels});
      if (mounted && current) { ref.invalidate(materialLibraryProvider); Navigator.pop(context); }
    });
  }
  Future<void> delete() async {
    final accepted = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('删除这份资料版本？'),
      content: const Text('立即撤下该ID的检索、读取和引用，清理受控提取索引/缓存。原件仅保留在本机回收站，不进入新备份。恢复会生成新版本，旧备份不能自动复活旧ID。聊天与任务的历史记录独立管理。'),
      actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('移到回收站'))],
    ));
    if (accepted != true || !current) return;
    await perform(() async {
      try {
        await widget.access.call<Object?>('library.delete', widget.entry.identity);
        if (mounted && current) { setState(() => removed = true); Navigator.pop(context); }
      } finally {
        if (mounted && widget.access.current(ref)) {
          ref.invalidate(materialLibraryProvider);
          ref.read(materialsProvider.notifier).refresh();
          ref.read(artifactsProvider.notifier).refresh();
        }
      }
    });
  }
  Future<bool> prepareFollowUp() async {
    if (!current) return false;
    try {
      if (mathMode) {
        final result = await jsonCall('library.mathEvidence');
        if (current) setState(() => mathEvidence = result);
        if (result['status'] != 'SOURCE_READY') throw StateError('关键符号/扫描内容尚未确认，请先核对原件并填写转录。');
      } else {
        final snapshot = await widget.access.call<Map<dynamic, dynamic>>('library.snapshot');
        final entries = (snapshot?['files'] as List?) ?? [];
        if (!entries.any((e) => e['id'] == widget.entry.id && e['sha256'] == widget.entry.hash)) throw StateError('原版本已不可用');
      }
      return current;
    } catch (e) {
      if (current) setState(() => error = '$e');
      return false;
    }
  }
  Future<void> versions() => perform(() async {
    final rows = await widget.access.call<List<dynamic>>('library.versions', widget.entry.identity);
    if (!mounted || !current) return;
    await showDialog<void>(context: context, builder: (c) => AlertDialog(
      title: const Text('真实版本家族'),
      content: SizedBox(width: 420, child: ListView(shrinkWrap: true, children: (rows ?? []).map((row) {
        final version = LibraryEntry(row as Map);
        return ListTile(title: Text(version.name), subtitle: Text('${version.id}\n${version.hash}\n${version.state}'),
          enabled: !version.deleted, onTap: () async {
            Navigator.pop(c);
            await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => LibraryDetailPage(access: widget.access, entry: version)));
          });
      }).toList())),
      actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('关闭'))],
    ));
  });
  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider); ref.watch(contextEpochProvider);
    final entry = widget.entry;
    final loading = entry.data['output'] == true ? ref.watch(artifactsProvider).isLoading : ref.watch(materialsProvider).isLoading;
    return Scaffold(appBar: AppBar(title: const Text('资料与固定版本'), actions: [
      IconButton(tooltip: '文件夹与标签', onPressed: busy || !current ? null : organize, icon: const Icon(Icons.folder_outlined)),
      IconButton(tooltip: '删除版本', onPressed: busy || !current ? null : delete, icon: const Icon(Icons.delete_outline)),
    ]), body: SafeArea(child: !current ? (loading && widget.access.current(ref) && !removed ? const Center(child: CircularProgressIndicator()) : const Center(child: Text('档案或材料已改变，请重新打开。'))) : Column(children: [
      if (busy) const LinearProgressIndicator(),
      Expanded(child: ListView(padding: const EdgeInsets.all(16), children: [
        Text(entry.name, style: Theme.of(context).textTheme.titleLarge),
        SelectableText('版本 ${entry.id}\nSHA-256 ${entry.hash}\n上级版本 ${entry.parentId ?? '无'}\n${entry.extension.toUpperCase()} · ${entry.size} bytes\n${entry.detail}'),
        if (imagePath != null) FutureBuilder<String?>(future: imagePath, builder: (context, snapshot) {
          if (snapshot.hasError) return Text('原图无法核验：${snapshot.error}');
          if (snapshot.data == null) return const LinearProgressIndicator();
          return Image.file(File(snapshot.data!), cacheWidth: 800, fit: BoxFit.contain,
            semanticLabel: '固定版本原图；未作OCR识别', errorBuilder: (_, e, _) => Text('原图无法显示：$e'));
        }),
        Wrap(spacing: 8, runSpacing: 4, children: [
          OutlinedButton(onPressed: busy ? null : () => perform(() async { checkCommand(await bridgeApi.openArtifact(widget.access.profile, entry.id)); }), child: const Text('打开原件')),
          OutlinedButton(onPressed: busy ? null : () => perform(() async { checkCommand(await bridgeApi.saveArtifact(widget.access.profile, entry.id)); if (context.mounted && current) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('目标文件已独立回读核验'))); }), child: const Text('另存/下载')),
          OutlinedButton(onPressed: busy ? null : () => perform(() async { checkCommand(await bridgeApi.shareArtifact(widget.access.profile, entry.id)); if (context.mounted && current) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已打开系统分享面板；不代表已发送'))); }), child: const Text('系统分享')),
          OutlinedButton(onPressed: busy ? null : () => load(0), child: const Text('读取真实正文')),
          OutlinedButton(onPressed: busy ? null : () => perform(() async { final result = await jsonCall('library.chapters'); if (current) setState(() => chapters = result); }), child: const Text('章节候选索引')),
          OutlinedButton(onPressed: busy ? null : versions, child: const Text('版本家族 / 查看旧版')),
          if (entry.data['symbolCheckPending'] == true && entry.data['output'] != true)
            OutlinedButton(onPressed: busy ? null : () => context.push('/files/symbol-check',
              extra: ProfileResourceRoute(id: entry.id, profileId: widget.access.profile, epoch: widget.access.epoch)),
              child: const Text('原页核对')),
        ]),
        if (error != null) Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        if (chapters != null) ...[
          const Text('章节是提取标题候选；不是整章完整或印刷页证明。'),
          SelectableText(const JsonEncoder.withIndent('  ').convert(chapters)),
          if (chapters!['next_index_offset'] != null) TextButton(onPressed: busy ? null : () => perform(() async {
            final result = await jsonCall('library.chapters', {'offset': chapters!['next_index_offset']});
            if (current) setState(() => chapters = result);
          }), child: const Text('下一页章节候选')),
        ],
        if (page != null) ...[
          const Divider(),
          Text(page!['coverage'] as String),
          Text('本页提取范围 ${page!['offset']}–${(page!['offset'] as int) + (page!['untrusted_file_data'] as String).length} UTF-16 / ${page!['total_characters']}；不是全文已读'),
          if (page!['total_pages'] != null) Text('PDF原页 ${page!['total_pages']}；尝试提取 ${page!['extracted_pages']}；无可提取文字页 ${page!['pages_without_extractable_text']}'),
          SelectableText(page!['untrusted_file_data'] as String),
          ExpansionTile(title: const Text('独立真实源定位证据'), children: [SelectableText(const JsonEncoder.withIndent('  ').convert(page!['source_receipt']))]),
          Wrap(spacing: 12, children: [
            TextButton(onPressed: busy || offsets.isEmpty ? null : () => load(offsets.last, previous: true), child: const Text('上一范围')),
            TextButton(onPressed: busy || page!['next_offset'] == null ? null : () => load(page!['next_offset'] as int), child: const Text('下一范围')),
          ]),
        ],
        const Divider(),
        SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('数学题目 / 解答批改'), subtitle: const Text('仍使用同一Chat Agent、已选模型和任务；本页不自报数学已证明。'), value: mathMode, onChanged: busy ? null : (v) => setState(() { mathMode = v; mathEvidence = null; })),
        if (mathMode) ...[
          if (['png', 'jpg', 'jpeg', 'webp', 'pdf'].contains(entry.extension))
            OutlinedButton(onPressed: busy || !current ? null : () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => MathWorkPage(access: widget.access, initial: entry))), child: const Text('多页手写：页序、转录确认与逐步批改')),
          const Text('先打开原件核对关键符号。扫描/图片必须显式确认转录；未核前缀不得把后续错误宣称为首错。'),
          TextField(controller: transcript, maxLines: 5, maxLength: 4000, decoration: const InputDecoration(labelText: '已对照原件的用户转录（不是OCR或教材原文）')),
          Wrap(spacing: 8, children: [
            OutlinedButton(onPressed: busy ? null : () => perform(() async {
              await widget.access.call<Object?>('library.confirmSymbols', {...entry.identity, 'transcription': transcript.text.trim()});
              final result = await jsonCall('library.mathEvidence');
              if (current) setState(() => mathEvidence = result);
            }), child: const Text('确认转录并核对来源')),
            TextButton(onPressed: busy ? null : () => perform(() async { final result = await jsonCall('library.mathEvidence'); if (current) setState(() => mathEvidence = result); }), child: const Text('检查来源状态')),
          ]),
          if (mathEvidence != null) SelectableText(const JsonEncoder.withIndent('  ').convert(mathEvidence)),
        ],
      ])),
      ContextFollowUp(key: ValueKey('${widget.access.profile}:${widget.access.epoch}:${entry.id}:$mathMode'),
        hint: mathMode ? '向同一Agent提交解题/批改要求' : '针对固定资料版本提问', materialId: entry.id,
        isAvailable: () => current && !busy, beforeSend: prepareFollowUp,
        contextTextBuilder: () => mathMode ? '数学来源证据（不是数学证明）：${jsonEncode(mathEvidence)}' : '文件 ${entry.name}，固定版本 ${entry.id}，SHA-256 ${entry.hash}。请按真实覆盖范围读取并引用，不猜印刷页。',
      ),
    ])));
  }
}
