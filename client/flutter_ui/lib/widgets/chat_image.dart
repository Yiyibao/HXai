import 'dart:io';

import 'package:flutter/material.dart';

/// One loader for message images, Markdown images and interactive surfaces.
class ChatImage extends StatefulWidget {
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
  State<ChatImage> createState() => _ChatImageState();
}

class _ChatImageState extends State<ChatImage> {
  int attempt = 0;
  String? cachedSource;
  ImageProvider? cachedProvider;

  ImageProvider _provider() {
    if (cachedSource == widget.source && cachedProvider != null) {
      return cachedProvider!;
    }
    final provider = _createProvider();
    cachedSource = widget.source;
    return cachedProvider = provider;
  }

  ImageProvider _createProvider() {
    final source = widget.source.trim();
    final uri = Uri.parse(source);
    if (uri.scheme == 'data') {
      final data = uri.data!;
      if (!data.mimeType.startsWith('image/')) {
        throw const FormatException('不是图片数据');
      }
      return MemoryImage(data.contentAsBytes());
    }
    if (uri.scheme == 'file') return FileImage(File.fromUri(uri));
    if (source.startsWith('/') || RegExp(r'^[A-Za-z]:[\\/]').hasMatch(source)) {
      return FileImage(File(source));
    }
    if ((uri.scheme == 'https' || uri.scheme == 'http') &&
        uri.host.isNotEmpty) {
      return NetworkImage(source);
    }
    throw const FormatException('图片地址无效或不支持');
  }

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
              onPressed: () async {
                try {
                  await _provider().evict();
                } catch (_) {}
                if (mounted) setState(() => attempt++);
              },
              child: const Text('重新加载'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    try {
      return Image(
        key: ValueKey('${widget.source}:$attempt'),
        image: _provider(),
        fit: widget.fit,
        errorBuilder: _error,
        loadingBuilder: (context, child, progress) => progress == null
            ? child
            : const SizedBox(
                height: 150,
                child: Center(child: CircularProgressIndicator()),
              ),
      );
    } catch (error, stack) {
      return _error(context, error, stack);
    }
  }
}
