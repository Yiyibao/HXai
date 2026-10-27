import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import 'agent_usage_export_history.dart';
import 'agent_usage_alerts.dart';

/// Opens only on request. A read never resumes tasks or dispatches a model.
class AgentUsageView extends ConsumerStatefulWidget {
  const AgentUsageView({
    super.key,
    required this.owner,
    required this.taskId,
    required this.expectedEpoch,
  });
  final String owner;
  final String? taskId;
  final int expectedEpoch;
  @override
  ConsumerState<AgentUsageView> createState() => _AgentUsageViewState();
}

class _AgentUsageViewState extends ConsumerState<AgentUsageView> {
  AgentUsagePageDto? _page;
  AgentUsageGroupsPageDto? _groupsPage;
  final _entries = <AgentUsageEntryDto>[];
  final _groups = <AgentUsageGroupDto>[];
  String? _error;
  bool _busy = false;
  bool _wholeProfile = false;
  String _period = '全部';
  String _groupBy = 'requests';
  (int?, int?) _range = (null, null);
  int _generation = 0;
  bool _exportBusy = false;
  String? _exportRequest, _exportError;
  AgentUsageExportDto? _exported;
  AgentUsageExportIntentDto? _lastExport;
  String? _lastExportError;
  bool _lastExportBusy = false;
  bool _notificationSettingsBusy = false;
  String? _notificationSettingsError;
  int _notificationSettingsGeneration = 0;
  int _historyGeneration = 0;
  bool get _current =>
      mounted &&
      ref.read(activeProfileIdProvider) == widget.owner &&
      ref.read(contextEpochProvider) == widget.expectedEpoch;

  @override
  void initState() {
    super.initState();
    _wholeProfile = widget.taskId == null;
    unawaited(_read());
  }

  @override
  void didUpdateWidget(covariant AgentUsageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.owner != widget.owner ||
        oldWidget.expectedEpoch != widget.expectedEpoch ||
        oldWidget.taskId != widget.taskId) {
      _generation++;
      _page = null;
      _groupsPage = null;
      _entries.clear();
      _groups.clear();
      _error = null;
      _busy = false;
      _exportBusy = false;
      _exportRequest = null;
      _exportError = null;
      _exported = null;
      _lastExport = null;
      _lastExportError = null;
      _lastExportBusy = false;
      _notificationSettingsGeneration++;
      _notificationSettingsBusy = false;
      _notificationSettingsError = null;
      _historyGeneration++;
      _wholeProfile = widget.taskId == null;
      unawaited(_read());
    }
  }

  Future<void> _read({bool more = false}) async {
    final next = _groupBy == 'requests'
        ? _page?.nextCursor
        : _groupsPage?.nextCursor;
    if (!_current || _exportBusy || (more && (_busy || next == null))) {
      return;
    }
    // Paging retains the exact scope; it must not orphan its pending history lookup.
    final generation = more ? _generation : ++_generation;
    final taskId = _wholeProfile ? null : widget.taskId;
    final range = _range;
    final groupBy = _groupBy;
    final cursor = more ? next : null;
    setState(() {
      _busy = true;
      _error = null;
      if (!more) {
        _page = null;
        _groupsPage = null;
        _entries.clear();
        _groups.clear();
        _exportRequest = null;
        _exportError = null;
        _exported = null;
        _lastExport = null;
        _lastExportError = null;
        _lastExportBusy = false;
      }
    });
    if (!more) unawaited(_lookupLastExport());
    try {
      if (groupBy != 'requests') {
        final page = await bridgeApi.getAgentUsageGroups(
          widget.owner,
          taskId,
          range.$1,
          range.$2,
          groupBy,
          cursor,
          30,
          widget.expectedEpoch,
        );
        if (!_current || generation != _generation) {
          return;
        }
        if (page.owner != widget.owner ||
            page.taskId != taskId ||
            page.from != range.$1 ||
            page.until != range.$2 ||
            page.groupBy != groupBy ||
            page.groupCount < 0 ||
            page.requestCount < 0 ||
            page.groups.any((item) => !_validGroup(item, groupBy))) {
          throw StateError('分组用量归属、身份或范围不匹配');
        }
        setState(() {
          _groupsPage = page;
          final known = _groups
              .map((item) => (item.providerId, item.modelId, item.role))
              .toSet();
          _groups.addAll(
            page.groups.where(
              (item) => known.add((item.providerId, item.modelId, item.role)),
            ),
          );
        });
        return;
      }
      final page = await bridgeApi.getAgentUsage(
        widget.owner,
        taskId,
        range.$1,
        range.$2,
        cursor,
        30,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) {
        return;
      }
      if (page.owner != widget.owner ||
          page.taskId != taskId ||
          page.from != range.$1 ||
          page.until != range.$2) {
        throw StateError('请求用量归属或筛选范围不匹配');
      }
      setState(() {
        _page = page;
        final known = _entries.map((entry) => entry.requestId).toSet();
        _entries.addAll(
          page.entries.where((entry) => known.add(entry.requestId)),
        );
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _error = '用量读取失败；请重试。没有发送模型请求。');
      }
    } finally {
      if (_current && generation == _generation) {
        setState(() => _busy = false);
      }
    }
  }

  String _cost(String? encoded) {
    if (encoded == null) return '费用未知';
    try {
      final data = jsonDecode(encoded) as Map;
      if (data['amount'] is String && data['currency'] is String) {
        return "费用：${data['currency']} ${data['amount']} · ${data['status'] == 'reported' ? '供应商报告' : '价格估算'}";
      }
      final totals = (data['totals'] as List? ?? []).cast<Map>();
      final parts = totals
          .map(
            (v) =>
                "${v['currency']} ${v['amount']}（${v['provenance'] == 'reported' ? '报告' : '估算'}）",
          )
          .toList();
      if (data['unknownRequests'] != '0') {
        parts.add("${data['unknownRequests'] ?? '?'} 次费用未知");
      }
      return parts.isEmpty ? '暂无可计金额' : '费用：${parts.join(' · ')}';
    } catch (_) {
      return '费用读取失败';
    }
  }

  String _newExportId() {
    final random = Random.secure();
    final bytes = List.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }

  Future<void> _lookupLastExport() async {
    if (!_current || _exportBusy) return;
    final generation = _generation, history = ++_historyGeneration;
    final taskId = _wholeProfile ? null : widget.taskId;
    final range = _range, mode = _groupBy;
    setState(() {
      _lastExportBusy = true;
      _lastExportError = null;
      _lastExport = null;
    });
    try {
      final item = await bridgeApi.getAgentUsageLastExport(
        widget.owner,
        taskId,
        range.$1,
        range.$2,
        mode,
        widget.expectedEpoch,
      );
      if (!_current ||
          generation != _generation ||
          history != _historyGeneration) {
        return;
      }
      if (item != null &&
          (item.owner != widget.owner ||
              item.taskId != taskId ||
              item.from != range.$1 ||
              item.until != range.$2 ||
              item.groupBy != mode ||
              !_uuid.hasMatch(item.requestId) ||
              !_uuid.hasMatch(item.artifactId) ||
              !_hash.hasMatch(item.sha256) ||
              item.size <= 0 ||
              item.size > 8 * 1024 * 1024 ||
              item.createdAt < 0 ||
              item.createdAt > 8640000000000000)) {
        throw StateError('导出意图归属或范围不匹配');
      }
      setState(() => _lastExport = item);
    } catch (_) {
      if (_current &&
          generation == _generation &&
          history == _historyGeneration) {
        setState(() => _lastExportError = '上次导出记录读取失败；请重新查询。原文件保留。');
      }
    } finally {
      if (_current &&
          generation == _generation &&
          history == _historyGeneration) {
        setState(() => _lastExportBusy = false);
      }
    }
  }

  static final _uuid = RegExp(
    r'^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$',
  );
  static final _hash = RegExp(r'^[a-f0-9]{64}$');

  Future<void> _verifyLastExport() async {
    final item = _lastExport;
    if (!_current || item == null || _exportBusy || _busy || _lastExportBusy) {
      return;
    }
    final generation = _generation;
    _historyGeneration++;
    setState(() {
      _exportBusy = true;
      _exportError = null;
      _exported = null;
    });
    try {
      final result = await bridgeApi.verifyAgentUsageExport(
        widget.owner,
        item.taskId,
        item.from,
        item.until,
        item.groupBy,
        item.requestId,
        item.sha256,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) return;
      if (!_validReceipt(
            result,
            item.taskId,
            (item.from, item.until),
            item.groupBy,
            item.requestId,
          ) ||
          !result.reused ||
          result.artifactId != item.artifactId ||
          result.sha256 != item.sha256 ||
          result.size != item.size) {
        throw StateError('原导出核验回执不匹配');
      }
      setState(() => _exported = result);
      ref.invalidate(artifactsProvider);
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _exportError = '原导出未通过核验；文件可能缺失、中断或摘要不符。没有重新写出，原记录保留。');
      }
    } finally {
      if (_current && generation == _generation) {
        setState(() => _exportBusy = false);
      }
    }
  }

  bool _validReceipt(
    AgentUsageExportDto result,
    String? taskId,
    (int?, int?) range,
    String mode,
    String request,
  ) =>
      result.owner == widget.owner &&
      result.taskId == taskId &&
      result.from == range.$1 &&
      result.until == range.$2 &&
      result.groupBy == mode &&
      result.requestId == request &&
      _hash.hasMatch(result.sha256) &&
      _hash.hasMatch(result.sourceSha256) &&
      _uuid.hasMatch(result.artifactId) &&
      result.size > 0 &&
      result.size <= 8 * 1024 * 1024 &&
      result.capturedAt >= 0 &&
      result.capturedAt <= 8640000000000000 &&
      result.requestCount >= 0 &&
      result.recordCount >= 0 &&
      result.recordCount <= 10000 &&
      result.recordCount <= result.requestCount &&
      (mode != 'requests' || result.recordCount == result.requestCount);

  Future<void> _export({bool fresh = false}) async {
    if (!_current ||
        _busy ||
        _exportBusy ||
        (_page == null && _groupsPage == null)) {
      return;
    }
    final generation = _generation;
    final taskId = _wholeProfile ? null : widget.taskId;
    final range = _range, mode = _groupBy;
    if (fresh || _exported != null) _exportRequest = null;
    final request = _exportRequest ??= _newExportId();
    _historyGeneration++;
    setState(() {
      _lastExportBusy = false;
      _exportBusy = true;
      _exportError = null;
      _exported = null;
    });
    try {
      final result = await bridgeApi.exportAgentUsage(
        widget.owner,
        taskId,
        range.$1,
        range.$2,
        mode,
        request,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) return;
      final hash = RegExp(r'^[a-f0-9]{64}$');
      if (result.owner != widget.owner ||
          result.taskId != taskId ||
          result.from != range.$1 ||
          result.until != range.$2 ||
          result.groupBy != mode ||
          result.requestId != request ||
          !hash.hasMatch(result.sha256) ||
          !hash.hasMatch(result.sourceSha256) ||
          !RegExp(
            r'^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$',
          ).hasMatch(result.artifactId) ||
          result.size <= 0 ||
          result.size > 8 * 1024 * 1024 ||
          result.capturedAt < 0 ||
          result.capturedAt > 8640000000000000 ||
          result.requestCount < 0 ||
          result.recordCount < 0 ||
          result.recordCount > 10000 ||
          result.recordCount > result.requestCount ||
          (mode == 'requests' && result.requestCount != result.recordCount)) {
        throw StateError('导出回执不匹配');
      }
      setState(() => _exported = result);
      ref.invalidate(artifactsProvider);
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() => _exportError = '导出未交付。重试会先核验原操作；重新生成会创建新快照。原文件保留。');
      }
    } finally {
      if (_current && generation == _generation) {
        setState(() => _exportBusy = false);
        unawaited(_lookupLastExport());
      }
    }
  }

  bool _validGroup(AgentUsageGroupDto item, String mode) {
    final identity = switch (mode) {
      'provider' => item.modelId == null && item.role == null,
      'model' => item.role == null,
      'role' => item.providerId == null && item.modelId == null,
      _ => false,
    };
    return identity &&
        item.requestCount >= 0 &&
        item.reportedRequests >= 0 &&
        item.estimatedRequests >= 0 &&
        item.unknownRequests >= 0 &&
        item.reportedRequests + item.estimatedRequests + item.unknownRequests ==
            item.requestCount &&
        [item.input, item.output, item.cached, item.total].every(
          (value) =>
              value.unknownRequests >= 0 &&
              value.unknownRequests <= item.requestCount &&
              (value.reported == null || value.reported! >= 0) &&
              (value.estimated == null || value.estimated! >= 0),
        );
  }

  void _choosePeriod(String period) {
    final now = DateTime.now();
    final day = DateTime(now.year, now.month, now.day);
    final monday = DateTime(now.year, now.month, now.day - (now.weekday - 1));
    final (start, end) = switch (period) {
      '今日' => (day, DateTime(now.year, now.month, now.day + 1)),
      '本周' => (monday, DateTime(monday.year, monday.month, monday.day + 7)),
      '本月' => (
        DateTime(now.year, now.month),
        DateTime(now.year, now.month + 1),
      ),
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
    if (value.reported != null) {
      parts.add('报告 ${value.reported}');
    }
    if (value.estimated != null) {
      parts.add('估算 ${value.estimated}');
    }
    if (value.unknownRequests > 0) {
      parts.add('${value.unknownRequests} 次未知');
    }
    return '$label：${parts.isEmpty ? '暂无数据' : parts.join(' · ')}';
  }

  String _role(String role) => switch (role) {
    'main' => '主推理',
    'planning' => '任务规划',
    'semantic_acceptance' => '目标核验',
    'ocr' => 'OCR',
    'title' => '标题',
    'compression' => '上下文压缩',
    'credential_validation' => '密钥验证',
    _ => role,
  };

  Future<void> _openNotificationSettings() async {
    if (!_current || _notificationSettingsBusy) return;
    final generation = ++_notificationSettingsGeneration;
    setState(() {
      _notificationSettingsBusy = true;
      _notificationSettingsError = null;
    });
    try {
      checkCommand(await bridgeApi.openNativePage('notifications'));
    } catch (_) {
      if (_current && generation == _notificationSettingsGeneration) {
        setState(() => _notificationSettingsError = '提醒设置打开失败，请重试。');
      }
    } finally {
      if (_current && generation == _notificationSettingsGeneration) {
        setState(() => _notificationSettingsBusy = false);
      }
    }
  }

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
    final page = _page;
    final grouped = _groupsPage;
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              DropdownButton<String>(
                value: _groupBy,
                items: const [
                  DropdownMenuItem(value: 'requests', child: Text('逐请求')),
                  DropdownMenuItem(value: 'provider', child: Text('按提供商')),
                  DropdownMenuItem(value: 'model', child: Text('按模型')),
                  DropdownMenuItem(value: 'role', child: Text('按角色')),
                ],
                onChanged: _exportBusy
                    ? null
                    : (value) {
                        if (value == null) {
                          return;
                        }
                        setState(() => _groupBy = value);
                        unawaited(_read());
                      },
              ),
              if (widget.taskId != null)
                SizedBox(
                  width: 250,
                  child: DropdownButton<bool>(
                    isExpanded: true,
                    value: _wholeProfile,
                    items: const [
                      DropdownMenuItem(value: false, child: Text('此任务')),
                      DropdownMenuItem(
                        value: true,
                        child: Text(
                          '当前档案（含辅助任务）',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                    onChanged: _exportBusy
                        ? null
                        : (value) {
                            if (value == null) {
                              return;
                            }
                            setState(() => _wholeProfile = value);
                            unawaited(_read());
                          },
                  ),
                )
              else
                const Text('当前档案（含辅助任务）'),
              DropdownButton<String>(
                value: _period,
                items: ['全部', '今日', '本周', '本月']
                    .map(
                      (value) =>
                          DropdownMenuItem(value: value, child: Text(value)),
                    )
                    .toList(),
                onChanged: _exportBusy
                    ? null
                    : (value) {
                        if (value != null) {
                          _choosePeriod(value);
                        }
                      },
              ),
              TextButton(
                onPressed: _busy || _exportBusy ? null : () => _read(),
                child: const Text('刷新用量'),
              ),
              TextButton(
                onPressed: _notificationSettingsBusy
                    ? null
                    : _openNotificationSettings,
                child: const Text('后台用量提醒设置'),
              ),
              TextButton(
                onPressed: _exportBusy
                    ? null
                    : () => showModalBottomSheet<void>(
                        context: context,
                        isScrollControlled: true,
                        useSafeArea: true,
                        builder: (_) => SizedBox(
                          height: MediaQuery.sizeOf(context).height * .9,
                          child: AgentUsageExportHistoryView(
                            owner: widget.owner,
                            expectedEpoch: widget.expectedEpoch,
                          ),
                        ),
                      ),
                child: const Text('浏览历史导出'),
              ),
              TextButton(
                onPressed: _exportBusy || _lastExportBusy
                    ? null
                    : () => _lookupLastExport(),
                child: const Text('查询上次导出'),
              ),
              TextButton(
                onPressed:
                    _busy || _exportBusy || (page == null && grouped == null)
                    ? null
                    : () => _export(),
                child: const Text('导出 JSON 快照'),
              ),
            ],
          ),
          const Text(
            '周期按设备时区；展示实际请求账本。费用区分供应商报告、价格估算与未知；无适用来源时费用未知。已知小计不能当完整账单。',
          ),
          if (_notificationSettingsError != null)
            Text(_notificationSettingsError!),
          AgentUsageAlertsView(
            key: ValueKey(_generation),
            owner: widget.owner,
            taskId: _wholeProfile ? null : widget.taskId,
            expectedEpoch: widget.expectedEpoch,
            from: _range.$1,
            until: _range.$2,
          ),
          if (_lastExportBusy) const Text('正在读取当前筛选范围的上次导出记录…'),
          if (_lastExportError != null) Text(_lastExportError!),
          if (_lastExport != null && _exported == null) ...[
            const Text('当前范围有已保存的导出意图，文件尚待核验。核验只回读原文件。'),
            Text('请求：${_lastExport!.requestId}'),
            TextButton(
              onPressed: _exportBusy || _busy || _lastExportBusy
                  ? null
                  : () => _verifyLastExport(),
              child: const Text('核验上次导出'),
            ),
          ],
          if (_exportBusy) const Text('正在处理并独立核验导出快照…'),
          if (_exportError != null) ...[
            Text(
              _exportError!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            Wrap(
              spacing: 8,
              children: [
                TextButton(
                  onPressed: _exportBusy
                      ? null
                      : () => _lastExport == null
                            ? _export()
                            : _verifyLastExport(),
                  child: const Text('重试核验导出'),
                ),
                TextButton(
                  onPressed: _exportBusy ? null : () => _export(fresh: true),
                  child: const Text('生成新快照'),
                ),
              ],
            ),
          ],
          if (_exported case final result?)
            Card(
              semanticContainer: false,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '已生成并核验 ${result.recordCount} 条${result.groupBy == 'requests' ? '请求记录' : '分组记录'}的 JSON 快照',
                    ),
                    Text(
                      '时间：${DateTime.fromMillisecondsSinceEpoch(result.capturedAt).toLocal()} · ${result.reused ? '复用原快照' : '新快照'}',
                    ),
                    const Text(
                      '已保存到当前档案成果库。未知字段为 null，整数使用十进制字符串。可在预览页另存，包含当时的费用来源与未知覆盖。',
                    ),
                    SelectableText('文件 SHA256：${result.sha256}'),
                    TextButton(
                      onPressed: !_current
                          ? null
                          : () => context.push(
                              '/artifacts/preview',
                              extra: ProfileResourceRoute(
                                id: result.artifactId,
                                profileId: widget.owner,
                                epoch: widget.expectedEpoch,
                              ),
                            ),
                      child: const Text('查看导出快照'),
                    ),
                  ],
                ),
              ),
            ),
          if (_busy) const LinearProgressIndicator(),
          if (_error != null) ...[
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            TextButton(
              onPressed: _busy || _exportBusy
                  ? null
                  : () => _read(more: page != null || grouped != null),
              child: const Text('重试读取'),
            ),
          ],
          if (page != null) ...[
            Text(
              '范围内 ${page.requestCount} 次请求 · 报告 ${page.reportedRequests} · 估算 ${page.estimatedRequests} · 未知 ${page.unknownRequests}',
            ),
            Text(_metric('输入 Token', page.input)),
            Text(_metric('输出 Token', page.output)),
            Text(_metric('缓存 Token', page.cached)),
            Text(_metric('总 Token', page.total)),
            if (page.undatedRequests > 0)
              Text(
                _range.$1 == null
                    ? '${page.undatedRequests} 次请求的时间未知。'
                    : '${page.undatedRequests} 次时间未知的请求未计入本周期，可在“全部”查看。',
              ),
            if (page.costJson != null) Text(_cost(page.costJson)),
            if (page.requestCount == 0) const Text('此范围没有请求记录。'),
            for (final entry in _entries)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${_role(entry.role)} · ${entry.state} · ${switch (entry.provenance) {
                          'reported' => '供应商报告',
                          'estimated' => '估算',
                          _ => '未知用量',
                        }}',
                      ),
                      SelectableText(
                        '提供商：${entry.providerId ?? '未知'}\n模型：${entry.modelId ?? '未知'} · 推理：${entry.reasoning ?? '未知'}\n'
                        '输入 ${entry.inputTokens ?? '未知'} · 输出 ${entry.outputTokens ?? '未知'} · 缓存 ${entry.cachedTokens ?? '未知'} · 总计 ${entry.totalTokens ?? '未知'}\n'
                        '时间：${entry.createdAt == null ? '未知' : DateTime.fromMillisecondsSinceEpoch(entry.createdAt!).toLocal()}\n'
                        '请求：${entry.requestId}\n任务：${entry.taskId}',
                      ),
                      if (entry.costJson != null) Text(_cost(entry.costJson)),
                      if (entry.costJson != null)
                        ExpansionTile(
                          title: const Text('费用来源'),
                          children: [
                            SelectableText(
                              const JsonEncoder.withIndent('  ')
                                  .convert(jsonDecode(entry.costJson!)),
                            ),
                          ],
                        ),
                    ],
                  ),
                ),
              ),
            if (page.nextCursor != null)
              TextButton(
                onPressed: _busy || _exportBusy
                    ? null
                    : () => _read(more: true),
                child: const Text('加载更多请求'),
              ),
          ],
          if (grouped != null) ...[
            Text(
              '范围内 ${grouped.requestCount} 次请求 · 共 ${grouped.groupCount} 个分组 · 已显示 ${_groups.length} 个',
            ),
            const Text('分组汇总覆盖完整查询范围，按实际请求身份统计；缺失身份保持未知。模型分组同时区分提供商。'),
            if (grouped.undatedRequests > 0)
              Text(
                _range.$1 == null
                    ? '${grouped.undatedRequests} 次请求的时间未知。'
                    : '${grouped.undatedRequests} 次时间未知的请求未计入本周期，可在“全部”查看。',
              ),
            if (grouped.costJson != null) Text(_cost(grouped.costJson)),
            if (grouped.groupCount == 0) const Text('此范围没有分组记录。'),
            for (final item in _groups)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SelectableText(switch (_groupBy) {
                        'provider' => '提供商：${item.providerId ?? '未知提供商'}',
                        'model' =>
                          '提供商：${item.providerId ?? '未知提供商'}\n模型：${item.modelId ?? '未知模型'}',
                        _ =>
                          '角色：${item.role == null ? '未知角色' : _role(item.role!)}',
                      }),
                      Text(
                        '${item.requestCount} 次请求 · 报告 ${item.reportedRequests} · 估算 ${item.estimatedRequests} · 未知 ${item.unknownRequests}',
                      ),
                      Text(_metric('输入 Token', item.input)),
                      Text(_metric('输出 Token', item.output)),
                      Text(_metric('缓存 Token', item.cached)),
                      Text(_metric('总 Token', item.total)),
                      if (item.costJson != null) Text(_cost(item.costJson)),
                    ],
                  ),
                ),
              ),
            if (grouped.nextCursor != null)
              TextButton(
                onPressed: _busy || _exportBusy
                    ? null
                    : () => _read(more: true),
                child: const Text('加载更多分组'),
              ),
          ],
        ],
      ),
    );
  }
}
