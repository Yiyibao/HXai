import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'study_providers.dart';

class FrontendFeatures {
  static const channel = MethodChannel('me.rerere.rikkahub/frontend_features');
  static Future<T?> call<T>(
    Ref ref,
    String method, [
    Map<String, Object?> fields = const {},
  ]) => channel.invokeMethod<T>(method, {
    'profileId': ref.read(activeProfileIdProvider),
    'epoch': ref.read(contextEpochProvider),
    ...fields,
  });
  static Future<T?> fromWidget<T>(
    WidgetRef ref,
    String method, [
    Map<String, Object?> fields = const {},
  ]) => channel.invokeMethod<T>(method, {
    'profileId': ref.read(activeProfileIdProvider),
    'epoch': ref.read(contextEpochProvider),
    ...fields,
  });
}

class DocumentTaskInfo {
  final String id, sourceId, fileName, state;
  final int start, end;
  final bool downloaded;
  final String? error;
  DocumentTaskInfo(Map<dynamic, dynamic> m)
    : id = m['id'] as String,
      sourceId = m['sourceId'] as String,
      fileName = m['fileName'] as String,
      state = m['state'] as String,
      start = m['start'] as int,
      end = m['end'] as int,
      downloaded = m['downloaded'] as bool,
      error = m['error'] as String?;
  bool get running => const ['SUBMITTING', 'QUEUED', 'RUNNING'].contains(state);
  String get range => '第 $start–$end 页';
  String get label => switch (state) {
    'SUBMITTING' => '正在提交',
    'CANCELLING' => '取消已保存，等待服务确认',
    'QUEUED' => '等待处理',
    'RUNNING' => '正在处理',
    'SUCCEEDED' => downloaded ? '结果已下载' : '处理完成，待下载',
    'PARTIAL' => '部分完成',
    'FAILED' => '处理失败',
    'CANCELLED' => '已取消',
    'EXPIRED' => '已过期',
    _ => state,
  };
}

final documentConfigProvider = FutureProvider<Map<dynamic, dynamic>>((
  ref,
) async {
  ref.watch(activeProfileIdProvider);
  ref.watch(contextEpochProvider);
  return await FrontendFeatures.call<Map<dynamic, dynamic>>(ref, 'docConfig') ??
      {};
});
final documentTasksProvider = FutureProvider<List<DocumentTaskInfo>>((
  ref,
) async {
  ref.watch(activeProfileIdProvider);
  ref.watch(contextEpochProvider);
  final list =
      await FrontendFeatures.call<List<dynamic>>(ref, 'docTasks') ?? [];
  return list.map((e) => DocumentTaskInfo(e as Map)).toList();
});
