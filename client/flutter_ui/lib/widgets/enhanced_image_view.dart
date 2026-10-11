import 'dart:convert';

import 'package:flutter/material.dart';

import '../theme/study_theme.dart';
import 'chat_image.dart';
import 'source_links.dart';
import 'context_follow_up.dart';
import '../bridge/study_host_api.g.dart';

/// Enhanced image widget with metadata and fullscreen view
/// Supports source attribution, captions, and "ask about this image" functionality
class EnhancedImageView extends StatelessWidget {
  final MessagePartDto part;
  final String messageId;
  final VoidCallback? onAskAboutImage;

  const EnhancedImageView({
    super.key,
    required this.part,
    required this.messageId,
    this.onAskAboutImage,
  });

  Map<String, dynamic> get metadata {
    final text = part.text;
    if (text != null && text.trim().startsWith('{')) {
      try {
        return Map<String, dynamic>.from(jsonDecode(text) as Map);
      } catch (_) {}
    }
    return text != null && text.isNotEmpty ? {'caption': text} : {};
  }

  String get caption => metadata['caption']?.toString() ?? '';
  String get sourceUrl => metadata['sourceUrl']?.toString() ?? '';
  String get author => metadata['author']?.toString() ?? '';
  String get sourceType => metadata['sourceType']?.toString() ?? 'unknown';
  int get imageOrder => metadata['order'] as int? ?? 0;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Image with expansion button
          _buildImageCard(context),

          // Caption and source
          if (caption.isNotEmpty || sourceUrl.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 7),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (caption.isNotEmpty)
                    Text(
                      caption,
                      style: TextStyle(
                        fontSize: 11,
                        height: 1.6,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                  if (sourceUrl.isNotEmpty)
                    InkWell(
                      onTap: isWebSource(sourceUrl)
                          ? () => _openSource(context)
                          : null,
                      child: Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              _getSourceLabel(),
                              style: TextStyle(
                                fontSize: 12,
                                color: StudyPalette.of(context).textGray,
                                decoration: TextDecoration.underline,
                              ),
                            ),
                            const SizedBox(width: 4),
                            Icon(
                              Icons.open_in_new,
                              size: 12,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildImageCard(BuildContext context) {
    return GestureDetector(
      onTap: () => _showFullscreen(context),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(13),
        child: Stack(
          children: [
            _buildImage(context),
            // Expand button overlay
            Positioned(
              right: 9,
              bottom: 9,
              child: InkWell(
                onTap: () => _showFullscreen(context),
                child: Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: StudyPalette.of(context).card
                        .withValues(alpha: 0.95),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    Icons.open_in_full,
                    size: 16,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildImage(BuildContext context) => ChatImage(source: part.url ?? '');

  void _showFullscreen(BuildContext context) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (context) => _FullscreenImageView(
          part: part,
          messageId: messageId,
          onAskAboutImage: onAskAboutImage,
        ),
      ),
    );
  }

  void _openSource(BuildContext context) => openSourceLink(context, sourceUrl);

  String _getSourceLabel() {
    if (author.isNotEmpty) return '$author · 查看原出处 ↗';
    return switch (sourceType) {
      'web_search' => '来自网络检索 · 查看原出处 ↗',
      'user_upload' => '用户上传',
      'ai_generated' => 'AI 生成示意图',
      _ => '查看原出处 ↗',
    };
  }
}

/// Fullscreen image viewer with metadata and ask functionality
class _FullscreenImageView extends StatelessWidget {
  final MessagePartDto part;
  final String messageId;
  final VoidCallback? onAskAboutImage;

  const _FullscreenImageView({
    required this.part,
    required this.messageId,
    this.onAskAboutImage,
  });

  Map<String, dynamic> get metadata {
    final text = part.text;
    if (text != null && text.trim().startsWith('{')) {
      try {
        return Map<String, dynamic>.from(jsonDecode(text) as Map);
      } catch (_) {}
    }
    return text != null && text.isNotEmpty ? {'caption': text} : {};
  }

  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    final caption = metadata['caption']?.toString() ?? '';
    final sourceUrl = metadata['sourceUrl']?.toString() ?? '';
    final author = metadata['author']?.toString() ?? '';
    return Scaffold(
      appBar: AppBar(
        title: const Text('查看图片'),
        leading: IconButton(
          tooltip: '关闭',
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: InteractiveViewer(
                minScale: 0.5,
                maxScale: 4,
                child: Center(child: ChatImage(source: part.url ?? '')),
              ),
            ),
            if (caption.isNotEmpty ||
                author.isNotEmpty ||
                isWebSource(sourceUrl))
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (caption.isNotEmpty)
                      Text(
                        caption,
                        style: TextStyle(
                          fontSize: 13,
                          height: 1.6,
                          color: c.textBlack,
                        ),
                      ),
                    if (author.isNotEmpty)
                      Text(
                        author,
                        style: TextStyle(fontSize: 12, color: c.textGray),
                      ),
                    if (isWebSource(sourceUrl))
                      TextButton.icon(
                        onPressed: () => openSourceLink(context, sourceUrl),
                        icon: const Icon(Icons.open_in_new, size: 16),
                        label: const Text('原出处'),
                      ),
                  ],
                ),
              ),
            if (onAskAboutImage != null)
              ContextFollowUp(
                hint: '针对这张图继续追问',
                contextText:
                    '消息 $messageId 中的图片：${part.url}；$caption${isWebSource(sourceUrl) ? '；出处：$sourceUrl' : ''}',
                onSent: () => Navigator.of(context).pop(),
              ),
          ],
        ),
      ),
    );
  }
}
