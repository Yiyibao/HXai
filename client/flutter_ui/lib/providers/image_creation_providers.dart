import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'frontend_feature_providers.dart';
import 'study_providers.dart';

class ImageCreationTask {
  final String id,
      conversationId,
      messageId,
      prompt,
      rootId,
      model,
      state,
      size,
      quality;
  final String? parentId, artifactId, path, error;
  final int revision, createdAt;
  final List<String> references;
  ImageCreationTask(Map m)
    : id = m['id'],
      conversationId = m['conversationId'],
      messageId = m['messageId'],
      prompt = m['prompt'],
      rootId = m['rootId'],
      model = m['model'],
      state = m['state'],
      size = m['size'],
      quality = m['quality'],
      parentId = m['parentId'],
      artifactId = m['artifactId'],
      path = m['path'],
      error = m['error'],
      revision = m['revision'],
      createdAt = m['createdAt'],
      references = List<String>.from(m['references'] ?? []);
  bool get running => state == 'RUNNING';
  bool get completed => state == 'SUCCEEDED' && path != null;
}

final imageTasksProvider = FutureProvider<List<ImageCreationTask>>(
  retry: (_, _) => null,
  (ref) async {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    final rows =
        await FrontendFeatures.call<List<dynamic>>(ref, 'imageTasks') ?? [];
    final tasks = rows.map((m) => ImageCreationTask(m as Map)).toList();
    if (tasks.any((t) => t.running)) {
      final timer = Timer(const Duration(seconds: 2), () {
        if (ref.mounted) ref.invalidateSelf();
      });
      ref.onDispose(timer.cancel);
    }
    return tasks;
  },
);
final imageConfigProvider = FutureProvider<Map<dynamic, dynamic>>(
  retry: (_, _) => null,
  (ref) async {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    return await FrontendFeatures.call<Map<dynamic, dynamic>>(
          ref,
          'imageConfig',
        ) ??
        {};
  },
);
final imageTemplatesProvider = FutureProvider<List<Map<dynamic, dynamic>>>(
  retry: (_, _) => null,
  (ref) async {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    return (await FrontendFeatures.call<List<dynamic>>(ref, 'imageTemplates') ??
            [])
        .map((e) => e as Map<dynamic, dynamic>)
        .toList();
  },
);
final imageModeProvider = NotifierProvider<ImageModeNotifier, bool>(
  ImageModeNotifier.new,
);

class ImageModeNotifier extends Notifier<bool> {
  @override
  bool build() {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    return false;
  }

  void set(bool enabled) => state = enabled;
}
