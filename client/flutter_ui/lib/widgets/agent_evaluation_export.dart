import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';

class AgentEvaluationExport extends ConsumerStatefulWidget {
  const AgentEvaluationExport({super.key, required this.resource, required this.revision, required this.fence, this.compact = false});
  final ProfileResourceRoute resource;
  final int revision, fence;
  final bool compact;
  @override
  ConsumerState<AgentEvaluationExport> createState() => _AgentEvaluationExportState();
}

class _AgentEvaluationExportState extends ConsumerState<AgentEvaluationExport> {
  String? requestId, artifactId, error;
  int? requestRevision, requestFence;
  bool busy = false, writing = false;
  int generation = 0;
  bool get current => mounted && widget.resource.isCurrent(ref);

  static String uuid() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    final s = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}';
  }

  @override
  void didUpdateWidget(covariant AgentEvaluationExport oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.resource.id != widget.resource.id || oldWidget.resource.profileId != widget.resource.profileId ||
        oldWidget.resource.epoch != widget.resource.epoch) {
      generation++;
      busy = false;
      writing = false;
      requestId = artifactId = error = null;
      requestRevision = requestFence = null;
    } else if (oldWidget.revision != widget.revision || oldWidget.fence != widget.fence) {
      generation++; busy = writing = false;
      if (requestId != null && artifactId == null) error = '任务已更新；请核验原导出请求，或明确生成新快照。';
    }
  }

  Future<void> export({bool fresh = false}) async {
    if (!current || busy) return;
    final ticket = ++generation, resource = widget.resource;
    final revision = widget.revision, fence = widget.fence;
    setState(() { busy = true; error = null; });
    try {
      if (!fresh && requestId == null) {
        final last = await FrontendFeatures.channel.invokeMethod<Map<dynamic, dynamic>>('evaluation.last', {
          'profileId': resource.profileId, 'epoch': resource.epoch, 'taskId': resource.id,
        });
        if (!current || ticket != generation) return;
        if (last != null) {
          if (last['owner'] != resource.profileId || last['taskId'] != resource.id || last['requestId'] is! String) throw StateError('原导出意图归属不匹配');
          requestId = last['requestId'] as String;
          requestRevision = int.parse('${last['revision']}');requestFence = int.parse('${last['fence']}');
        }
      }
      if (!mounted || !current || ticket != generation) return;
      final consent = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
        title: Text(requestId != null && !fresh ? '核验原任务评测快照' : '保存任务评测快照'),
        content: SingleChildScrollView(child: Text('${requestId != null && !fresh ? '本次只核验原修订 $requestRevision / fence $requestFence 的导出，不重新捕获回答。原件未写完时可明确生成新快照。\n\n' : ''}保存此任务的完整目标、当前选中分支的公开回答、任务锁定配置、计划、调用摘要和已落盘用量到本机资料库。\n\n不包含密钥、隐藏推理、附件正文或工具原始输出；工具成果需另行按哈希核验。云端尚未同步的执行记录不在此快照内。\n\n本操作不调用模型或联网，不证明数学答案正确，也不自动发送文件。')),
        actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('不保存')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(requestId != null && !fresh ? '核验原快照' : '保存到本机资料库'))],
      ));
      if (consent != true || !current || ticket != generation) return;
      setState(() => writing = true);
      if (fresh) { requestId = null; artifactId = null;requestRevision = requestFence = null; }
      final request = requestId ??= uuid();
      requestRevision ??= revision;requestFence ??= fence;
      final result = await FrontendFeatures.channel.invokeMethod<Map<dynamic, dynamic>>('evaluation.export', {
        'profileId': resource.profileId, 'epoch': resource.epoch, 'taskId': resource.id,
        'revision': requestRevision, 'fence': requestFence, 'requestId': request, 'consent': true,
      });
      if (!current || ticket != generation) return;
      if (result?['owner'] != resource.profileId || result?['taskId'] != resource.id || result?['artifactId'] is! String) {
        throw StateError('导出回执归属不匹配');
      }
      setState(() => artifactId = result!['artifactId'] as String);
    } catch (_) {
      if (current && ticket == generation) setState(() => error = '快照尚未交付。重试会先核验同一请求；若原快照未写完，可明确生成新快照，原件保留。');
    } finally {
      if (mounted && ticket == generation) setState(() { busy = false; writing = false; });
    }
    if (mounted && widget.compact && current && ticket == generation) {
      final message = error ?? (artifactId == null ? null : '任务快照已保存，可从快照菜单预览或另存；数学质量未评估。');
      if (message != null) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    ref.watch(bootstrapProvider);
    if (!current) return const SizedBox.shrink();
    if (widget.compact) {
      return PopupMenuButton<String>(
        tooltip: '任务评测快照', enabled: !busy,
        icon: writing ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.inventory_2_outlined),
        onSelected: (action) {
          if (action == 'open' && artifactId != null && current) {
            context.push('/artifacts/preview', extra: ProfileResourceRoute.capture(ref, artifactId!));
          } else {
            unawaited(export(fresh: action == 'new'));
          }
        },
        itemBuilder: (_) => [
          PopupMenuItem(value: 'export', child: Text(requestId == null && error == null ? '保存任务评测快照' : '核验原导出请求')),
          if (requestId != null || error != null || artifactId != null) const PopupMenuItem(value: 'new', child: Text('生成新快照')),
          if (artifactId != null) const PopupMenuItem(value: 'open', child: Text('预览或另存快照')),
        ],
      );
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Wrap(spacing: 8, children: [
        TextButton(onPressed: busy ? null : () => export(), child: Text(requestId == null && error == null ? '保存任务评测快照' : '核验原导出请求')),
        if (requestId != null || error != null || artifactId != null) TextButton(onPressed: busy ? null : () => export(fresh: true), child: const Text('生成新快照')),
        if (artifactId != null) TextButton(onPressed: () {
          if (current) context.push('/artifacts/preview', extra: ProfileResourceRoute.capture(ref, artifactId!));
        }, child: const Text('预览或另存快照')),
      ]),
      if (writing) const LinearProgressIndicator(),
      if (error != null) Text(error!),
      if (artifactId != null) const Text('快照已独立回读并保存；模型与数学质量待评估。'),
    ]);
  }
}
