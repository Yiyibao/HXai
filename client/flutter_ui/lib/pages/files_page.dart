import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/material_library_providers.dart';
import '../providers/study_providers.dart';
import 'library_detail_page.dart';

class FilesPage extends ConsumerStatefulWidget {
  const FilesPage({super.key});
  @override
  ConsumerState<FilesPage> createState() => _FilesPageState();
}

class _FilesPageState extends ConsumerState<FilesPage> {
  String query = '', folder = '', tag = '', sort = 'newest';
  bool trash = false, busy = false;
  LibraryAccess get access => LibraryAccess(ref.read(activeProfileIdProvider), ref.read(contextEpochProvider));
  @override
  void initState() {
    super.initState();
    Future.microtask(() { if (mounted) refresh(); });
  }
  void refresh() {
    ref.invalidate(materialLibraryProvider);
    ref.read(materialsProvider.notifier).refresh();
    ref.read(artifactsProvider.notifier).refresh();
  }
  Future<void> action(Future<void> Function(LibraryAccess) work) async {
    final accepted = access;
    setState(() => busy = true);
    try {
      await work(accepted);
      if (mounted && accepted.current(ref)) refresh();
    } catch (e) {
      if (mounted && accepted.current(ref)) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      if (mounted && accepted.current(ref)) refresh();
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
  Future<void> addFiles() => action((a) async {
    final ids = await bridgeApi.pickAttachments(a.profile, a.epoch);
    if (mounted && a.current(ref) && ids.isNotEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('已保存 ${ids.length} 个原文件；尚未读取全文')));
    }
  });
  Future<void> addDirectory() => action((a) async {
    final report = await a.call<Map<dynamic, dynamic>>('library.importDirectory');
    if (!mounted || !a.current(ref) || report == null || report['cancelled'] == true) return;
    final ids = report['importedIds'] as List;
    final errors = report['failures'] as List;
    await showDialog<void>(context: context, builder: (c) => AlertDialog(
      title: Text(report['enumerationComplete'] == true && errors.isEmpty ? '目录复制完成' : '目录部分导入'),
      content: SingleChildScrollView(child: Text('已保存 ${ids.length} 个文件，${errors.length} 项失败。\n${report['notice']}\n${errors.map((e) => '${e['name']}: ${e['error']}').join('\n')}')),
      actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('关闭'))],
    ));
  });
  Future<void> searchText() async {
    final accepted = access;
    final search = query;
    if (search.isEmpty) return;
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => LibraryTextSearch(access: accepted, query: search)));
  }
  Future<void> trashAction(LibraryEntry entry, String method) async {
    final accepted = access;
    final yes = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: Text(method == 'library.restore' ? '恢复为新版本？' : '清除回收站原件？'),
      content: Text(method == 'library.restore' ? '独立回读原件，创建新ID；旧删除保护保持，不复活旧引用。' : '仅永久删除本机回收站中的这份原件。旧备份仍受删除保护，删除记录不会清除。'),
      actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('确认'))],
    ));
    if (yes != true || !mounted || !accepted.current(ref)) return;
    await action((a) async { await a.call<Object?>(method, entry.identity); });
  }
  @override
  Widget build(BuildContext context) {
    final state = ref.watch(materialLibraryProvider);
    return Scaffold(
      appBar: AppBar(title: Text(trash ? '资料库回收站' : '文件库'), actions: [
        IconButton(tooltip: trash ? '返回资料库' : '本机回收站', onPressed: busy ? null : () => setState(() => trash = !trash), icon: Icon(trash ? Icons.folder_outlined : Icons.delete_outline)),
        PopupMenuButton<String>(enabled: !busy && !trash, tooltip: '导入资料', onSelected: (value) => value == 'directory' ? addDirectory() : addFiles(), itemBuilder: (_) => [
          const PopupMenuItem(value: 'files', child: Text('选择文件')),
          const PopupMenuItem(value: 'directory', child: Text('选择系统授权目录')),
        ], icon: const Icon(Icons.add)),
      ]),
      body: SafeArea(child: Column(children: [
        if (busy) const LinearProgressIndicator(),
        Padding(padding: const EdgeInsets.all(12), child: TextField(
          decoration: const InputDecoration(labelText: '文件名、文件夹或标签', prefixIcon: Icon(Icons.search)),
          onChanged: (value) => setState(() => query = value.trim()),
        )),
        if (!trash) Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Wrap(spacing: 12, runSpacing: 8, children: [
          TextButton.icon(onPressed: busy || query.isEmpty ? null : searchText, icon: const Icon(Icons.manage_search), label: const Text('检索真实正文')),
          DropdownButton<String>(value: sort, onChanged: (v) => setState(() => sort = v!), items: const [
            DropdownMenuItem(value: 'newest', child: Text('最新在前')),
            DropdownMenuItem(value: 'name', child: Text('按名称')),
            DropdownMenuItem(value: 'size', child: Text('按大小')),
          ]),
        ])),
        Expanded(child: state.when(loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text('资料库加载失败：$e'), TextButton(onPressed: refresh, child: const Text('重试'))])),
          data: (snapshot) {
            if (!snapshot.access.current(ref)) return const SizedBox.shrink();
            final all = trash ? snapshot.trash : snapshot.files;
            final folders = all.expand((entry) {
              final parts = entry.folder.split('/');
              return [for (var i = 1; i <= parts.length; i++) parts.take(i).join('/')];
            }).where((e) => e.isNotEmpty).toSet().toList()..sort();
            final tags = all.expand((e) => e.tags).toSet().toList()..sort();
            final selectedFolder = folders.contains(folder) ? folder : '';
            final selectedTag = tags.contains(tag) ? tag : '';
            final filtered = all.where((entry) => (selectedFolder.isEmpty || entry.folder == selectedFolder || entry.folder.startsWith('$selectedFolder/')) &&
              (selectedTag.isEmpty || entry.tags.contains(selectedTag)) &&
              '${entry.name} ${entry.folder} ${entry.tags.join(' ')}'.toLowerCase().contains(query.toLowerCase())).toList();
            filtered.sort((a, b) => switch (sort) { 'name' => a.name.compareTo(b.name), 'size' => b.size.compareTo(a.size), _ => b.createdAt.compareTo(a.createdAt) });
            return Column(children: [
              Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Wrap(spacing: 8, children: [
                DropdownButton<String>(value: folders.contains(folder) ? folder : '', hint: const Text('文件夹'), items: [
                  const DropdownMenuItem(value: '', child: Text('所有文件夹')),
                  ...folders.map((f) => DropdownMenuItem(value: f, child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 180), child: Text(f, overflow: TextOverflow.ellipsis)))),
                ], onChanged: (v) => setState(() => folder = v!)),
                DropdownButton<String>(value: tags.contains(tag) ? tag : '', items: [
                  const DropdownMenuItem(value: '', child: Text('所有标签')),
                  ...tags.map((t) => DropdownMenuItem(value: t, child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 140), child: Text(t, overflow: TextOverflow.ellipsis)))),
                ], onChanged: (v) => setState(() => tag = v!)),
              ])),
              Expanded(child: RefreshIndicator(onRefresh: () async { refresh(); await ref.read(materialLibraryProvider.future); }, child: ListView.builder(
                physics: const AlwaysScrollableScrollPhysics(), itemCount: filtered.isEmpty ? 1 : filtered.length,
                itemBuilder: (context, index) {
                  if (filtered.isEmpty) return const Padding(padding: EdgeInsets.all(32), child: Text('没有匹配资料。文件长期独立保存，删除聊天不会删除这里的原件。'));
                  final item = filtered[index];
                  return ListTile(
                    leading: Icon(item.data['output'] == true ? Icons.task_outlined : Icons.description_outlined),
                    title: Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text('${item.folder.isEmpty ? '根文件夹' : item.folder} · ${item.tags.join(' / ')}\n${item.extension.toUpperCase()} · ${item.size} bytes · ${item.detail}', maxLines: 4, overflow: TextOverflow.ellipsis),
                    trailing: trash ? PopupMenuButton<String>(enabled: !busy, onSelected: (v) => trashAction(item, v), itemBuilder: (_) => [
                      PopupMenuItem(value: 'library.restore', enabled: item.recoverable, child: Text(item.recoverable ? '恢复为新版本' : '原件已清除，无法恢复')),
                      if (item.recoverable) const PopupMenuItem(value: 'library.purge', child: Text('永久清除本机原件')),
                    ]) : const Icon(Icons.chevron_right),
                    onTap: trash ? null : () async {
                      await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => LibraryDetailPage(access: snapshot.access, entry: item)));
                      if (mounted && snapshot.access.current(ref)) refresh();
                    },
                  );
                },
              ))),
            ]);
          },
        )),
      ])),
    );
  }
}

class LibraryTextSearch extends ConsumerStatefulWidget {
  final LibraryAccess access;
  final String query;
  const LibraryTextSearch({super.key, required this.access, required this.query});
  @override
  ConsumerState<LibraryTextSearch> createState() => _LibraryTextSearchState();
}
class _LibraryTextSearchState extends ConsumerState<LibraryTextSearch> {
  Map<String, dynamic>? page;
  String? error;
  bool busy = true;
  @override
  void initState() { super.initState(); load(''); }
  Future<void> load(String cursor) async {
    setState(() { busy = true; error = null; });
    try {
      final raw = await widget.access.call<String>('library.search', {'query': widget.query, 'cursor': cursor});
      if (mounted && widget.access.current(ref)) setState(() => page = jsonDecode(raw!) as Map<String, dynamic>);
    } catch (e) { if (mounted && widget.access.current(ref)) setState(() => error = '$e'); }
    finally { if (mounted) setState(() => busy = false); }
  }
  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider); ref.watch(contextEpochProvider);
    return Scaffold(appBar: AppBar(title: const Text('真实正文检索')), body: !widget.access.current(ref) ? const Center(child: Text('档案已改变，请重新打开。')) :
      ListView(padding: const EdgeInsets.all(16), children: [
        Text('字面查询：${widget.query}'),
        if (busy) const LinearProgressIndicator(),
        if (error != null) Text(error!),
        if (page != null) ...[
          Text(page!['error'] as String? ?? page!['notice'] as String),
          ...((page!['hits'] as List).map((hit) => Card(child: Padding(padding: const EdgeInsets.all(12), child: SelectableText(const JsonEncoder.withIndent('  ').convert(hit)))))),
          Text('跳过/失败：${page!['skipped_sources']} / ${page!['source_errors']}'),
        ],
        if (page?['next_cursor'] is String) TextButton(onPressed: busy ? null : () => load(page!['next_cursor'] as String), child: const Text('继续检索下一范围')),
        TextButton(onPressed: busy ? null : () => load(''), child: const Text('从头检索')),
      ]));
  }
}
