import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';

/// Historical scope is displayed and verified as saved, independently of the ledger filter.
class AgentUsageExportHistoryView extends ConsumerStatefulWidget {
  const AgentUsageExportHistoryView({
    super.key,
    required this.owner,
    required this.expectedEpoch,
  });
  final String owner;
  final int expectedEpoch;
  @override
  ConsumerState<AgentUsageExportHistoryView> createState() => _HistoryState();
}

class _HistoryState extends ConsumerState<AgentUsageExportHistoryView> {
  final _entries = <AgentUsageExportIntentDto>[];
  String? _next, _error;
  bool _busy = false, _loaded = false;
  String? _verifying;
  int _generation = 0;
  final _receipts = <String, AgentUsageExportDto>{};
  final _errors = <String, String>{};
  bool get _current =>
      mounted &&
      ref.read(activeProfileIdProvider) == widget.owner &&
      ref.read(contextEpochProvider) == widget.expectedEpoch;
  static final _uuid = RegExp(
    r'^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$',
  );
  static final _hash = RegExp(r'^[a-f0-9]{64}$');
  static const _maxDate = 8640000000000000;

  @override
  void initState() {
    super.initState();
    unawaited(_read());
  }

  @override
  void didUpdateWidget(covariant AgentUsageExportHistoryView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.owner != widget.owner ||
        oldWidget.expectedEpoch != widget.expectedEpoch) {
      _generation++;
      _busy = false;
      _verifying = null;
      unawaited(_read());
    }
  }

  bool _valid(AgentUsageExportIntentDto i) =>
      i.owner == widget.owner &&
      (i.taskId == null || _uuid.hasMatch(i.taskId!)) &&
      ((i.from == null && i.until == null) ||
          (i.from != null &&
              i.until != null &&
              i.from! >= 0 &&
              i.until! > i.from! &&
              i.until! <= _maxDate)) &&
      ['requests', 'provider', 'model', 'role'].contains(i.groupBy) &&
      _uuid.hasMatch(i.requestId) &&
      _uuid.hasMatch(i.artifactId) &&
      _hash.hasMatch(i.sha256) &&
      i.size > 0 &&
      i.size <= 8 * 1024 * 1024 &&
      i.createdAt >= 0 &&
      i.createdAt <= _maxDate;
  bool _after(AgentUsageExportIntentDto a, AgentUsageExportIntentDto b) =>
      a.createdAt > b.createdAt ||
      (a.createdAt == b.createdAt && a.requestId.compareTo(b.requestId) > 0);

  Future<void> _read({bool more = false}) async {
    if (!_current || _busy || _verifying != null || (more && _next == null)) {
      return;
    }
    final generation = ++_generation, cursor = more ? _next : null;
    setState(() {
      _busy = true;
      _error = null;
      if (!more) {
        _entries.clear();
        _receipts.clear();
        _errors.clear();
        _next = null;
        _loaded = false;
      }
    });
    try {
      final page = await bridgeApi.getAgentUsageExportHistory(
        widget.owner,
        cursor,
        20,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) return;
      final all = [..._entries, ...page.entries];
      if (page.owner != widget.owner ||
          page.entries.length > 20 ||
          page.entries.any((i) => !_valid(i)) ||
          all.map((i) => i.requestId).toSet().length != all.length ||
          (page.nextCursor != null &&
              (page.nextCursor!.isEmpty ||
                  page.nextCursor!.length > 1024 ||
                  page.nextCursor == cursor ||
                  page.entries.isEmpty)) ||
          [for (var n = 1; n < all.length; n++) _after(all[n - 1], all[n])]
              .contains(false)) {
        throw StateError('历史导出归属、顺序或分页不匹配');
      }
      setState(() {
        _entries.addAll(page.entries);
        _next = page.nextCursor;
        _loaded = true;
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _error = '历史导出读取失败或目录已变化。请刷新历史；原记录和文件保留。');
      }
    } finally {
      if (_current && generation == _generation) setState(() => _busy = false);
    }
  }

  Future<void> _verify(AgentUsageExportIntentDto i) async {
    if (!_current || _busy || _verifying != null) return;
    final generation = _generation;
    setState(() {
      _verifying = i.requestId;
      _receipts.remove(i.requestId);
      _errors.remove(i.requestId);
    });
    try {
      final r = await bridgeApi.verifyAgentUsageExport(
        widget.owner,
        i.taskId,
        i.from,
        i.until,
        i.groupBy,
        i.requestId,
        i.sha256,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) return;
      if (r.owner != i.owner ||
          r.taskId != i.taskId ||
          r.from != i.from ||
          r.until != i.until ||
          r.groupBy != i.groupBy ||
          r.requestId != i.requestId ||
          r.artifactId != i.artifactId ||
          r.sha256 != i.sha256 ||
          r.size != i.size ||
          !r.reused ||
          !_hash.hasMatch(r.sourceSha256) ||
          r.capturedAt < 0 ||
          r.capturedAt > _maxDate ||
          r.requestCount < 0 ||
          r.recordCount < 0 ||
          r.recordCount > 10000 ||
          r.recordCount > r.requestCount ||
          (r.groupBy == 'requests' && r.recordCount != r.requestCount)) {
        throw StateError('原范围核验回执不匹配');
      }
      setState(() => _receipts[i.requestId] = r);
      ref.invalidate(artifactsProvider);
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _errors[i.requestId] = '原文件未通过核验。没有重新写出，原记录保留。');
      }
    } finally {
      if (_current && generation == _generation) {
        setState(() => _verifying = null);
      }
    }
  }

  String _date(int ms) =>
      DateTime.fromMillisecondsSinceEpoch(ms).toLocal().toString();
  String _group(String s) => switch (s) {
    'requests' => '逐请求',
    'provider' => '按提供商',
    'model' => '按模型',
    _ => '按角色',
  };

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    if (!_current) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Text('档案或上下文已变化，请关闭后重新打开。'),
      );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('历史导出', style: Theme.of(context).textTheme.titleLarge),
          const Text('当前档案的本机导出意图。按每条记录的原始范围核验，不改变用量页筛选。文件尚待核验。'),
          TextButton(
            onPressed: _busy || _verifying != null ? null : () => _read(),
            child: const Text('刷新历史'),
          ),
          if (_busy) const LinearProgressIndicator(),
          if (_error != null)
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          if (_loaded && _entries.isEmpty)
            const Text('没有已保存的历史导出意图。旧版无意图的文件可从成果库查看。'),
          for (final i in _entries)
            Card(
              key: ValueKey(i.requestId),
              semanticContainer: false,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('保存：${_date(i.createdAt)}'),
                    Text(
                      '原范围：${i.taskId == null ? '整个档案' : '任务 ${i.taskId}'} · ${_group(i.groupBy)}',
                    ),
                    Text(
                      i.from == null
                          ? '原周期：全部'
                          : '原周期（起含止不含）：${_date(i.from!)} 至 ${_date(i.until!)}',
                    ),
                    SelectableText('请求：${i.requestId}'),
                    if (_errors[i.requestId] case final error?) Text(error),
                    if (_receipts[i.requestId] case final r?) ...[
                      Text('已核验原快照 · ${r.recordCount} 条记录 · 费用保持未知'),
                      SelectableText('文件 SHA256：${r.sha256}'),
                      TextButton(
                        onPressed: _verifying != null
                            ? null
                            : () {
                                if (_current) {
                                  context.push(
                                    '/artifacts/preview',
                                    extra: ProfileResourceRoute(
                                      id: r.artifactId,
                                      profileId: widget.owner,
                                      epoch: widget.expectedEpoch,
                                    ),
                                  );
                                }
                              },
                        child: const Text('查看历史快照'),
                      ),
                    ] else
                      TextButton(
                        onPressed: _busy || _verifying != null
                            ? null
                            : () => _verify(i),
                        child: Text(
                          _verifying == i.requestId ? '正在核验原文件…' : '按原范围核验',
                        ),
                      ),
                  ],
                ),
              ),
            ),
          if (_next != null)
            TextButton(
              onPressed: _busy || _verifying != null || _error != null
                  ? null
                  : () => _read(more: true),
              child: const Text('加载更多历史'),
            ),
        ],
      ),
    );
  }
}
