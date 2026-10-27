import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';

/// Advisory counts from the full native scope; never initiates a model request.
class AgentUsageAlertsView extends ConsumerStatefulWidget {
  const AgentUsageAlertsView({
    super.key,
    required this.owner,
    required this.taskId,
    required this.expectedEpoch,
    this.from,
    this.until,
    this.autoRefresh = false,
    this.compact = false,
  });
  final String owner;
  final String? taskId;
  final int expectedEpoch;
  final int? from, until;
  final bool autoRefresh, compact;
  @override
  ConsumerState<AgentUsageAlertsView> createState() =>
      _AgentUsageAlertsViewState();
}

class _AgentUsageAlertsViewState extends ConsumerState<AgentUsageAlertsView>
    with WidgetsBindingObserver {
  AgentUsageAlertsDto? _alerts;
  String? _error;
  Timer? _timer;
  int _generation = 0;
  bool _busy = false;
  bool _foreground = true;
  bool _routeActive = false;
  bool get _current =>
      mounted &&
      _foreground &&
      _routeActive &&
      ref.read(activeProfileIdProvider) == widget.owner &&
      ref.read(contextEpochProvider) == widget.expectedEpoch;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final state = WidgetsBinding.instance.lifecycleState;
    _foreground = state == null || state == AppLifecycleState.resumed;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final visible = ModalRoute.isCurrentOf(context) ?? true;
    if (visible != _routeActive) {
      _routeActive = visible;
      _generation++;
      _busy = false;
      _alerts = null;
      _error = null;
      _start();
    }
  }

  void _start() {
    _timer?.cancel();
    if (!_foreground || !_routeActive) return;
    unawaited(_read());
    if (widget.autoRefresh) {
      _timer = Timer.periodic(const Duration(seconds: 15), (_) {
        if (!_busy) unawaited(_read());
      });
    }
  }

  @override
  void didUpdateWidget(covariant AgentUsageAlertsView old) {
    super.didUpdateWidget(old);
    if (old.owner != widget.owner ||
        old.taskId != widget.taskId ||
        old.expectedEpoch != widget.expectedEpoch ||
        old.from != widget.from ||
        old.until != widget.until ||
        old.autoRefresh != widget.autoRefresh) {
      _generation++;
      _busy = false;
      _alerts = null;
      _error = null;
      _start();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _timer?.cancel();
    _generation++;
    _busy = false;
    if (mounted) {
      setState(() {
        _alerts = null;
        _error = null;
      });
    }
    if (_foreground) _start();
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _read() async {
    if (!_current || _busy) return;
    final generation = ++_generation;
    _busy = true;
    if (mounted) {
      setState(() {
        _error = null;
      });
    }
    try {
      final value = await bridgeApi.getAgentUsageAlerts(
        widget.owner,
        widget.taskId,
        widget.from,
        widget.until,
        widget.expectedEpoch,
      );
      if (!_current || generation != _generation) return;
      if (value.owner != widget.owner ||
          value.taskId != widget.taskId ||
          value.from != widget.from ||
          value.until != widget.until ||
          value.evaluatedAt < 0 ||
          value.evaluatedAt > 8640000000000000 ||
          [
            value.uncertainRequests,
            value.unreportedCompletedRequests,
            value.denseTaskCount,
            value.denseRequests,
            value.undatedRequests,
          ].any((n) => n < 0) ||
          (value.denseTaskCount == 0
              ? value.denseRequests != 0
              : value.denseRequests ~/ value.denseTaskCount < 20)) {
        throw StateError('提醒范围或计数不匹配');
      }
      setState(() {
        _alerts = value;
        _error = null;
      });
    } catch (_) {
      if (_current && generation == _generation) {
        setState(() {
          _alerts = null;
          _error = '提醒读取失败；尚未取得当前范围提醒。';
        });
      }
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    if (!_current) return const SizedBox.shrink();
    final value = _alerts;
    final issues =
        value != null &&
        (value.uncertainRequests > 0 ||
            value.unreportedCompletedRequests > 0 ||
            value.denseTaskCount > 0);
    if (widget.compact && !issues && _error == null) {
      return const SizedBox.shrink();
    }
    return Card(
      semanticContainer: false,
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('请求用量提醒'),
            if (_busy && value == null) const Text('正在读取实际请求账本…'),
            if (_error != null) ...[
              Text(_error!),
              TextButton(
                onPressed: _busy ? null : () => _read(),
                child: const Text('重试提醒读取'),
              ),
            ],
            if (value != null) ...[
              if (value.uncertainRequests > 0)
                Text(
                  '${value.uncertainRequests} 次请求结果未确认，可能已产生费用。请核对提供商记录，勿盲目重试。',
                ),
              if (value.unreportedCompletedRequests > 0)
                Text(
                  '${value.unreportedCompletedRequests} 次已完成请求未报告用量；费用未知，不能按零计费。',
                ),
              if (value.denseTaskCount > 0)
                Text(
                  '最近5分钟内 ${value.denseTaskCount} 项任务各发出至少20次请求，共 ${value.denseRequests} 次。请核对活动是否符合预期。',
                ),
              if (!issues) const Text('本次范围未触发上述提醒；费用仍未知。'),
              if (!widget.compact &&
                  widget.from != null &&
                  value.undatedRequests > 0)
                Text('${value.undatedRequests} 次请求缺少日期，未计入本周期提醒。'),
              const Text('提醒只供核对，不自动停止或重发，不证明重复收费。'),
              if (!widget.compact)
                Text(
                  '账本读取时间：${DateTime.fromMillisecondsSinceEpoch(value.evaluatedAt).toLocal()}',
                ),
            ],
          ],
        ),
      ),
    );
  }
}
