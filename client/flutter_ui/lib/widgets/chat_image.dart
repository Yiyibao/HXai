import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';

final _chatImagePathProvider = FutureProvider.autoDispose
    .family<String, (String, int)>((ref, request) async {
      final owner = ref.watch(activeProfileIdProvider);
      final epoch = ref.watch(contextEpochProvider);
      final conversation = ref.watch(activeConversationIdProvider);
      final path = await FrontendFeatures.call<String>(ref, 'resolveChatImage', {
        'source': request.$1,
        'conversationId': conversation,
      });
      if (ref.read(activeProfileIdProvider) != owner ||
          ref.read(contextEpochProvider) != epoch || path == null) {
        throw const FormatException('图片上下文已改变');
      }
      return path;
    });

/// Kotlin validates owner/hash and returns the stored bytes' path. No Dart HTTP.
class ChatImage extends ConsumerStatefulWidget {
  final String source;
  final BoxFit fit;
  final ImageErrorWidgetBuilder? errorBuilder;

  const ChatImage({
    super.key,
    required this.source,
    this.fit = BoxFit.contain,
    this.errorBuilder,
  });

  @override
  ConsumerState<ChatImage> createState() => _ChatImageState();
}

class _ChatImageState extends ConsumerState<ChatImage> {
  int attempt = 0;

  Widget _error(BuildContext context, Object error, StackTrace? stack) {
    if (widget.errorBuilder != null) {
      return widget.errorBuilder!(context, error, stack);
    }
    return SizedBox(
      height: 150,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.broken_image_outlined),
            const Text('图片加载失败'),
            TextButton(
              onPressed: () => setState(() => attempt++),
              child: const Text('重新加载'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final path = ref.watch(_chatImagePathProvider((widget.source, attempt)));
    return path.when(
      skipLoadingOnRefresh: false,
      skipLoadingOnReload: false,
      loading: () => const SizedBox(
        height: 150,
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (error, stack) => _error(context, error, stack),
      data: (path) => Image(
        key: ValueKey('${widget.source}:$attempt:$path'),
        image: FileImage(File(path)),
        fit: widget.fit,
        errorBuilder: _error,
      ),
    );
  }
}
