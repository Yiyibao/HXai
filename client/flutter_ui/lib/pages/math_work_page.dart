import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/material_library_providers.dart';
import '../providers/study_providers.dart';
import '../widgets/context_follow_up.dart';

class MathWorkPage extends ConsumerStatefulWidget {
  final LibraryAccess access;
  final LibraryEntry initial;
  const MathWorkPage({super.key, required this.access, required this.initial});
  @override
  ConsumerState<MathWorkPage> createState() => _MathWorkPageState();
}

class _PageInput {
  LibraryEntry source;
  final number = TextEditingController(text: '1');
  final transcription = TextEditingController();
  final ambiguities = TextEditingController();
  Map<String, dynamic>? candidate, candidateEvidence;
  _PageInput(this.source);
  void dispose() { number.dispose(); transcription.dispose(); ambiguities.dispose(); }
  Map<String, Object?> value() => {
    ...source.identity, 'number': int.tryParse(number.text) ?? 0,
    'transcription': transcription.text,
    'ambiguities': ambiguities.text.split('\n').map((e) => e.trim()).where((e) => e.isNotEmpty).toList(),
    if (candidate != null) 'candidate': candidate,
  };
}

class _MathWorkPageState extends ConsumerState<MathWorkPage> {
  final title = TextEditingController(text: '数学作业逐页批改');
  final pages = <_PageInput>[];
  Map<String, dynamic>? receipt;
  bool busy = false;
  String? error;
  List<Map<String,dynamic>> saved = [];
  int nextOffset = 0;
  bool hasMore = false, historyLoading = false;
  bool get current => mounted && widget.access.current(ref);
  @override
  void initState() { super.initState(); pages.add(_PageInput(widget.initial)); Future.microtask(() => loadSaved()); }
  @override
  void dispose() { title.dispose(); for (final page in pages) { page.dispose(); } super.dispose(); }
  void changed([_PageInput? detached]) {
    setState(() {
      receipt = null;
      if (detached != null) { detached.candidate = null; detached.candidateEvidence = null; }
    });
  }
  Future<void> loadSaved({bool more = false}) async {
    if (!current || historyLoading) return;
    setState(() => historyLoading = true);
    try {
      final raw = await widget.access.call<String>('library.mathWork.list', {'offset': more ? nextOffset : 0});
      if (!current) return;
      final result = jsonDecode(raw!) as Map<String,dynamic>;
      setState(() {
        final items = (result['items'] as List).map((e) => Map<String,dynamic>.from(e as Map)).toList();
        saved = more ? [...saved, ...items.where((item) => !saved.any((old) => old['work_id'] == item['work_id']))] : items;
        nextOffset = result['next_offset'] as int; hasMore = result['has_more'] == true;
      });
    } catch (e) { if (current) setState(() => error = '$e'); }
    finally { if (current) setState(() => historyLoading = false); }
  }
  Future<void> restore(String id) async {
    if (!current || busy) return;
    setState(() {busy = true;error = null;});
    try {
      final raw = await widget.access.call<String>('library.mathWork.read', {'work_id': id});
      if (!current) return;
      final record = jsonDecode(raw!) as Map<String,dynamic>;
      final entries = ref.read(materialLibraryProvider).value?.files ?? <LibraryEntry>[];
      final originals = (record['pages'] as List).map((e) => Map<String,dynamic>.from(e as Map)).toList();
      final sources = originals.map((row) => entries.singleWhere((e) => e.id == row['id'] && e.hash == row['sha256'])).toList();
      final restored = [for (var i=0;i<originals.length;i++) _PageInput(sources[i])
        ..number.text = '${originals[i]['number']}'
        ..transcription.text = originals[i]['transcription'] as String
        ..ambiguities.text = (originals[i]['ambiguities'] as List).join('\n')
        ..candidate = originals[i]['candidate'] == null ? null : Map<String, dynamic>.from(originals[i]['candidate'] as Map)
        ..candidateEvidence = record['candidate_evidence'] == null || (record['candidate_evidence'] as List)[i] == null
          ? null : Map<String, dynamic>.from((record['candidate_evidence'] as List)[i] as Map)];
      setState(() { for(final page in pages) {page.dispose();} pages..clear()..addAll(restored);title.text = record['title'] as String;receipt = record;});
    } catch (e) { if (current) setState(() => error = '无法恢复旧输入：$e'); }
    finally { if (current) setState(() => busy = false); }
  }
  Future<void> save(bool confirmed) async {
    if (!current || busy) return;
    setState(() { busy = true; error = null; });
    try {
      final request = jsonEncode({'title': title.text.trim(), 'pages': pages.map((e) => e.value()).toList()});
      final raw = await widget.access.call<String>('library.mathWork.create', {'request': request, 'confirmed': confirmed});
      if (!current) return;
      setState(() => receipt = jsonDecode(raw!) as Map<String, dynamic>);
      await loadSaved();
    } catch (e) { if (current) setState(() => error = '$e'); }
    finally { if (current) setState(() => busy = false); }
  }
  Future<bool> beforeSend() async {
    if (!current || receipt?['status'] != 'SOURCE_READY') return false;
    try {
      final raw = await widget.access.call<String>('library.mathWork.read', {'work_id': receipt!['work_id']});
      if (!current) return false;
      final fresh = jsonDecode(raw!) as Map<String, dynamic>;
      if (fresh['status'] != 'SOURCE_READY') throw StateError('输入仍需确认');
      setState(() => receipt = fresh);
      return true;
    } catch (e) { if (current) setState(() { error = '$e'; receipt = null; }); return false; }
  }
  Future<void> open(_PageInput page) async {
    if (!current) return;
    try {
      checkCommand(await bridgeApi.openArtifact(widget.access.profile, page.source.id));
    } catch (e) { if (current) setState(() => error = '$e'); }
  }
  Widget guardedDialog(BuildContext dialog, Widget child) => Consumer(builder: (_, ref, _) {
    ref.watch(activeProfileIdProvider); ref.watch(contextEpochProvider);
    if (widget.access.current(ref)) return child;
    return AlertDialog(title: const Text('档案已改变；旧候选不再显示'),
      actions: [TextButton(onPressed: () => Navigator.pop(dialog), child: const Text('关闭'))]);
  });
  Future<void> loadCandidate(_PageInput page) async {
    if (!current || busy) return;
    setState(() { busy = true; error = null; });
    try {
      var offset = 0;
      String? task;
      while (current) {
        final raw = await widget.access.call<String>('library.mathWork.parseResults', {...page.source.identity, 'offset': offset});
        if (!current) return;
        final result = jsonDecode(raw!) as Map<String, dynamic>;
        final rows = (result['results'] as List).cast<Map>();
        if (!mounted) return;
        task = await showDialog<String>(context: context, builder: (dialog) => guardedDialog(dialog, AlertDialog(
          title: const Text('复用已下载解析（不上传）'),
          content: SizedBox(width: 420, child: ListView(shrinkWrap: true, children: [
            const Text('候选不是识别真值。只读同档案、同版本已有缓存；页号必须属于实际成功解析范围。'),
            if (rows.isEmpty) const Text('当前范围没有可复用结果；可使用已授权识别能力或手工核对，不会自动新建任务。'),
            for (final row in rows) ListTile(
              title: Text('物理页 ${row['start_page']}–${row['end_page']} · ${row['downloaded'] == true ? '已下载' : row['state']}'),
              subtitle: Text('已处理页 ${row['processed_pages'] ?? '尚未下载核验'}\n解析ID ${row['task_id']}'),
              enabled: row['downloaded'] == true,
              onTap: row['downloaded'] == true ? () => Navigator.pop(dialog, row['task_id'] as String) : null,
            ),
          ])),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialog), child: const Text('取消')),
            if (result['next_offset'] != null) TextButton(onPressed: () => Navigator.pop(dialog, '__next__'), child: const Text('下一批结果')),
          ],
        )));
        if (!current || task == null) return;
        if (task != '__next__') break;
        offset = result['next_offset'] as int;
      }
      if (!current || task == null) return;
      final raw = await widget.access.call<String>('library.mathWork.parseCandidate', {
        ...page.source.identity, 'number': int.tryParse(page.number.text) ?? 0, 'task_id': task,
      });
      if (!current) return;
      final result = jsonDecode(raw!) as Map<String, dynamic>;
      if (page.transcription.text.isNotEmpty) {
        if (!mounted) return;
        final overwrite = await showDialog<bool>(context: context, builder: (dialog) => guardedDialog(dialog, AlertDialog(
          title: const Text('用候选替换本页未保存的转录？'),
          content: const Text('已保存草稿不变；当前编辑区将载入完整候选，并重新等待关键符号确认。'),
          actions: [TextButton(onPressed: () => Navigator.pop(dialog, false), child: const Text('保留当前转录')),
            TextButton(onPressed: () => Navigator.pop(dialog, true), child: const Text('载入候选'))],
        )));
        if (!current || overwrite != true) return;
      }
      setState(() {
        final evidence = Map<String, dynamic>.from(result['untrusted_candidate'] as Map);
        page.transcription.text = evidence['transcription'] as String;
        page.ambiguities.text = (result['ambiguities'] as List).join('\n');
        page.candidate = Map<String, dynamic>.from(result['reference'] as Map);
        page.candidateEvidence = evidence; receipt = null;
      });
    } catch (e) { if (current) setState(() => error = '候选未载入，当前转录保留：$e'); }
    finally { if (current) setState(() => busy = false); }
  }
  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider); ref.watch(contextEpochProvider);
    final snapshot = ref.watch(materialLibraryProvider);
    final candidates = snapshot.value?.files.where((e) => ['png', 'jpg', 'jpeg', 'webp', 'pdf'].contains(e.extension) && e.state == 'SUCCEEDED').toList() ?? <LibraryEntry>[];
    final valid = current && snapshot.value?.access.profile == widget.access.profile && snapshot.value?.access.epoch == widget.access.epoch;
    return Scaffold(appBar: AppBar(title: const Text('多页数学批改')), body: SafeArea(child: !current
      ? const Center(child: Text('档案已改变，请重新打开；旧确认不会提交到新档案。'))
      : Column(children: [
        if (busy) const LinearProgressIndicator(),
        Expanded(child: ListView(padding: const EdgeInsets.all(16), children: [
          const Text('仍使用同一Chat Agent和已选模型。先核页序与关键符号，再批改；手工转录或解析候选都不是识别真值或数学证明。'),
          if (saved.isNotEmpty) DropdownButtonFormField<String>(
            key: ValueKey('saved-${receipt?['work_id']}'),
            isExpanded: true, decoration: const InputDecoration(labelText: '继续已保存的草稿 / 确认输入'),
            items: saved.map((e) => DropdownMenuItem(value:e['work_id'] as String,child:Text('${e['title']} · ${e['page_count']}页 · ${e['status'] == 'SOURCE_READY' ? '已确认' : '待确认'}',overflow:TextOverflow.ellipsis))).toList(),
            onChanged:busy ? null : (id) {if(id != null) restore(id);},
          ),
          if (historyLoading) const LinearProgressIndicator(),
          if (hasMore) TextButton(onPressed:historyLoading ? null : () => loadSaved(more:true),child:const Text('载入更多已保存输入')),
          const SizedBox(height: 8),
          TextField(controller: title, enabled: !busy, maxLength: 100, onChanged: (_) => changed(), decoration: const InputDecoration(labelText: '作业标题')),
          const Text('按原稿顺序添加最多12页。每行保留一个原步骤（题设、假设和解释也保留）；未知行不能省略。PDF填真实物理页，不猜教材印刷页。'),
          for (var index = 0; index < pages.length; index++) Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('原稿顺序 ${index + 1}', style: Theme.of(context).textTheme.titleMedium),
            DropdownButtonFormField<String>(
              key: ValueKey('source-$index-${pages[index].source.id}'),
              initialValue: candidates.any((e) => e.id == pages[index].source.id) ? pages[index].source.id : null,
              isExpanded: true,
              decoration: const InputDecoration(labelText: '固定来源版本'),
              items: candidates.map((e) => DropdownMenuItem(value: e.id, child: Text(e.name, maxLines: 2, overflow: TextOverflow.ellipsis))).toList(),
              onChanged: busy || !valid ? null : (id) { if (id == null) return; setState(() { pages[index].source = candidates.singleWhere((e) => e.id == id); pages[index].number.text = '1'; pages[index].candidate = null; pages[index].candidateEvidence = null; receipt = null; }); },
            ),
            SelectableText('SHA-256 ${pages[index].source.hash}', style: Theme.of(context).textTheme.bodySmall),
            Wrap(spacing: 8, children: [
              TextButton(onPressed: busy ? null : () => open(pages[index]), child: const Text('打开原件核对')),
              TextButton(onPressed: busy || !valid ? null : () => loadCandidate(pages[index]), child: const Text('载入已有解析/OCR候选（不上传）')),
              IconButton(tooltip: '向前移一页', onPressed: busy || index == 0 ? null : () => setState(() { final p = pages.removeAt(index); pages.insert(index - 1, p); receipt = null; }), icon: const Icon(Icons.arrow_upward)),
              IconButton(tooltip: '向后移一页', onPressed: busy || index == pages.length - 1 ? null : () => setState(() { final p = pages.removeAt(index); pages.insert(index + 1, p); receipt = null; }), icon: const Icon(Icons.arrow_downward)),
              IconButton(tooltip: '移除这页，不删除原件', onPressed: busy || pages.length == 1 ? null : () => setState(() { pages.removeAt(index).dispose(); receipt = null; }), icon: const Icon(Icons.remove_circle_outline)),
            ]),
            TextField(controller: pages[index].number, enabled: !busy, keyboardType: TextInputType.number, onChanged: (_) => changed(pages[index]), decoration: InputDecoration(labelText: pages[index].source.extension == 'pdf' ? 'PDF物理页（从1开始）' : '图片页号固定为1')),
            TextField(controller: pages[index].transcription, enabled: !busy, minLines: 4, maxLines: 12, maxLength: 4000, onChanged: (_) => changed(), decoration: const InputDecoration(labelText: '对照原页的完整转录，一步一行；保留原方法')),
            TextField(controller: pages[index].ambiguities, enabled: !busy, minLines: 1, maxLines: 4, onChanged: (_) => changed(), decoration: const InputDecoration(labelText: '尚不确定的关键符号/条件，一项一行；有内容时只能存草稿')),
            if (pages[index].candidateEvidence != null) ...[
              const Text('本页由已下载解析候选起草；可修订转录。请对照原页核对，再清除已解决的歧义。'),
              ExpansionTile(title: const Text('原候选、公式与真实覆盖证据（不可信内容）'), children: [
                SelectableText(const JsonEncoder.withIndent('  ').convert(pages[index].candidateEvidence)),
              ]),
              TextButton(onPressed: busy ? null : () => changed(pages[index]), child: const Text('改为手工转录（保留文字，解除候选绑定）')),
            ],
          ]))),
          OutlinedButton.icon(onPressed: busy || pages.length >= 12 || candidates.isEmpty ? null : () => setState(() { pages.add(_PageInput(candidates.first)); receipt = null; }), icon: const Icon(Icons.add), label: const Text('添加下一原稿页')),
          Wrap(spacing: 12, runSpacing: 8, children: [
            OutlinedButton(onPressed: busy || !valid ? null : () => save(false), child: const Text('保存待确认草稿')),
            FilledButton(onPressed: busy || !valid ? null : () => save(true), child: const Text('我已核对全部页序、转录与关键符号')),
          ]),
          if (error != null) Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
          if (receipt != null) Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: SelectableText('${receipt!['status'] == 'SOURCE_READY' ? '转录与页序已确认；数学尚未核验' : '仍有待确认输入，不能按猜测批改'}\n确认记录 ${receipt!['work_id']}\n数学状态 ${receipt!['mathematics_status']}')),
          const Text('批改应给最早可证实错误、原因与最小修复；前缀未知时先报告最早未核步。参考答案不是唯一方法，不默认打分。需要识别原页时只能使用当前已授权的视觉/OCR能力；本页手工转录不构成识别率验收。'),
        ])),
        ContextFollowUp(
          key: ValueKey('math-work-${widget.access.profile}-${widget.access.epoch}-${receipt?['work_id']}'),
          hint: '向同一Agent提出解题、批改或生成讲义要求',
          materialId: pages.first.source.id,
          isAvailable: () => current && !busy && receipt?['status'] == 'SOURCE_READY',
          beforeSend: beforeSend,
          contextTextBuilder: () => '多页数学作业：可信确认记录 work_id=${receipt?['work_id']}。页序和转录由用户确认，数学未核验。先read_math_work读回全部固定原页和转录，再用review_math_work逐行覆盖全部非空原文，locator为用户页序号:原转录行号；未知行check:null，不能漏前缀或默认评分。继续同一锁定模型，若生成Word须绑定实际来源与新版本。',
        ),
      ])));
  }
}
