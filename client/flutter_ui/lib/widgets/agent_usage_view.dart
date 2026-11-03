import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';

/// Opens only on request. A read never resumes tasks or dispatches a model.
class AgentUsageView extends ConsumerStatefulWidget {
  const AgentUsageView({super.key, required this.owner, required this.taskId, required this.expectedEpoch});
  final String owner;
  final String taskId;
  final int expectedEpoch;
  @override
  ConsumerState<AgentUsageView> createState() => _AgentUsageViewState();
}

class _AgentUsageViewState extends ConsumerState<AgentUsageView> {
  AgentUsagePageDto? _page;
  final _entries = <AgentUsageEntryDto>[];
  String? _error;
  bool _busy = false;
  bool _wholeProfile = false;
  String _period = '全部';
  (int?, int?) _range = (null, null);
  int _generation = 0;
  bool get _current => mounted && ref.read(activeProfileIdProvider) == widget.owner &&
      ref.read(contextEpochProvider) == widget.expectedEpoch;

  @override
  void initState() {
    super.initState();
    unawaited(_read());
  }

  Future<void> _read({bool more = false}) async {
    if (!_current || (more && (_busy || _page?.nextCursor == null))) { return; }
    final generation = ++_generation;
    final taskId = _wholeProfile ? null : widget.taskId;
    final range = _range;
    final cursor = more ? _page?.nextCursor : null;
    setState(() {
      _busy = true; _error = null;
      if (!more) { _page = null; _entries.clear(); }
    });
    try {
      final page = await bridgeApi.getAgentUsage(widget.owner, taskId, range.$1, range.$2,
          cursor, 30, widget.expectedEpoch);
      if (!_current || generation != _generation) { return; }
      if (page.owner != widget.owner || page.taskId != taskId || page.from != range.$1 || page.until != range.$2) {
        throw StateError('请求用量归属或筛选范围不匹配');
      }
      setState(() {
        _page = page;
        final known = _entries.map((entry) => entry.requestId).toSet();
        _entries.addAll(page.entries.where((entry) => known.add(entry.requestId)));
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _error = '用量读取失败；请重试。没有发送模型请求。');
      }
    } finally {
      if (_current && generation == _generation) { setState(() => _busy = false); }
    }
  }

  void _choosePeriod(String period) {
    final now = DateTime.now();
    final day = DateTime(now.year, now.month, now.day);
    final monday = DateTime(now.year, now.month, now.day - (now.weekday - 1));
    final (start, end) = switch (period) {
      '今日' => (day, DateTime(now.year, now.month, now.day + 1)),
      '本周' => (monday, DateTime(monday.year, monday.month, monday.day + 7)),
      '本月' => (DateTime(now.year, now.month), DateTime(now.year, now.month + 1)),
      _ => (null, null),
    };
    setState(() {
      _period = period;
      _range = (start?.millisecondsSinceEpoch, end?.millisecondsSinceEpoch);
    });
    unawaited(_read());
  }

  String _metric(String label, AgentUsageMetricDto value) {
    final parts = <String>[];
    if (value.reported != null) { parts.add('报告 ${value.reported}'); }
    if (value.estimated != null) { parts.add('估算 ${value.estimated}'); }
    if (value.unknownRequests > 0) { parts.add('${value.unknownRequests} 次未知'); }
    return '$label：${parts.isEmpty ? '暂无数据' : parts.join(' · ')}';
  }

  String _role(String role) => switch (role) {
    'main' => '主推理', 'planning' => '任务规划', 'semantic_acceptance' => '目标核验',
    'ocr' => 'OCR', 'title' => '标题', 'compression' => '上下文压缩',
    'credential_validation' => '密钥验证', _ => role,
  };

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    if (!_current) { return const Padding(padding: EdgeInsets.all(16), child: Text('档案或上下文已变化，请关闭后重新打开。')); }
    final page = _page;
    return SingleChildScrollView(padding: const EdgeInsets.all(16), child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        Wrap(spacing: 12, runSpacing: 8, children: [
          SizedBox(width: 250, child: DropdownButton<bool>(isExpanded: true, value: _wholeProfile, items: const [
            DropdownMenuItem(value: false, child: Text('此任务')),
            DropdownMenuItem(value: true, child: Text('当前档案（含辅助任务）', overflow: TextOverflow.ellipsis)),
          ], onChanged: (value) {
            if (value == null) { return; }
            setState(() => _wholeProfile = value);
            unawaited(_read());
          })),
          DropdownButton<String>(value: _period, items: ['全部', '今日', '本周', '本月']
            .map((value) => DropdownMenuItem(value: value, child: Text(value))).toList(),
            onChanged: (value) { if (value != null) { _choosePeriod(value); } }),
          TextButton(onPressed: _busy ? null : () => _read(), child: const Text('刷新用量')),
        ]),
        const Text('周期按设备时区；展示实际请求账本。已知小计不能当完整账单。费用未知：当前账本没有供应商费用来源。'),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null) ...[
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          TextButton(onPressed: _busy ? null : () => _read(more: page != null), child: const Text('重试读取')),
        ],
        if (page != null) ...[
          Text('范围内 ${page.requestCount} 次请求 · 报告 ${page.reportedRequests} · 估算 ${page.estimatedRequests} · 未知 ${page.unknownRequests}'),
          Text(_metric('输入 Token', page.input)), Text(_metric('输出 Token', page.output)),
          Text(_metric('缓存 Token', page.cached)), Text(_metric('总 Token', page.total)),
          if (page.undatedRequests > 0)
            Text(_range.$1 == null ? '${page.undatedRequests} 次请求的时间未知。' : '${page.undatedRequests} 次时间未知的请求未计入本周期，可在“全部”查看。'),
          if (page.requestCount == 0) const Text('此范围没有请求记录。'),
          for (final entry in _entries) Card(child: Padding(padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${_role(entry.role)} · ${entry.state} · ${switch (entry.provenance) { 'reported' => '供应商报告', 'estimated' => '估算', _ => '未知用量' }}'),
              SelectableText('提供商：${entry.providerId ?? '未知'}\n模型：${entry.modelId ?? '未知'} · 推理：${entry.reasoning ?? '未知'}\n'
                  '输入 ${entry.inputTokens ?? '未知'} · 输出 ${entry.outputTokens ?? '未知'} · 缓存 ${entry.cachedTokens ?? '未知'} · 总计 ${entry.totalTokens ?? '未知'}\n'
                  '时间：${entry.createdAt == null ? '未知' : DateTime.fromMillisecondsSinceEpoch(entry.createdAt!).toLocal()}\n'
                  '请求：${entry.requestId}\n任务：${entry.taskId}'),
            ]))),
          if (page.nextCursor != null)
            TextButton(onPressed: _busy ? null : () => _read(more: true), child: const Text('加载更多请求')),
        ],
      ],
    ));
  }
}
