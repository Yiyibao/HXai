import 'package:flutter/material.dart';

import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import 'math_markdown.dart';
import 'enhanced_image_view.dart';

/// Message bubble following ChatGPT-inspired design
/// User messages: right-aligned gray bubble
/// Assistant messages: left-aligned, white background, natural layout
class MessageBubble extends StatelessWidget {
  final MessageDto message;
  final bool isUser;
  final VoidCallback? onRetry;
  final VoidCallback? onEdit;
  final Widget? branchSelector;

  const MessageBubble({
    super.key,
    required this.message,
    required this.isUser,
    this.onRetry,
    this.onEdit,
    this.branchSelector,
  });

  @override
  Widget build(BuildContext context) {
    if (isUser) {
      return _buildUserMessage(context);
    } else {
      return _buildAssistantMessage(context);
    }
  }

  Widget _buildUserMessage(BuildContext context) {
    return Align(
      alignment: Alignment.centerRight,
      child: Container(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.88,
        ),
        margin: const EdgeInsets.only(left: 48, bottom: 22),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        decoration: BoxDecoration(
          color: StudyPalette.of(context).userBubble,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ...message.parts.map((part) => _buildMessagePart(context, part)),
            if (onEdit != null || branchSelector != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ?branchSelector,
                    if (onEdit != null)
                      IconButton(
                        icon: const Icon(Icons.edit_outlined, size: 16),
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(
                          minWidth: 32,
                          minHeight: 32,
                        ),
                        color: StudyPalette.of(context).textGray,
                        onPressed: onEdit,
                        tooltip: '编辑消息',
                      ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildAssistantMessage(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...message.parts.map((part) => _buildMessagePart(context, part)),
            ],
          ),
        ),
        // Action bar for assistant messages
        Padding(
          padding: const EdgeInsets.only(
            left: 20,
            right: 20,
            top: 12,
            bottom: 20,
          ),
          child: Row(
            children: [
              _buildActionButton(
                context,
                icon: Icons.content_copy_outlined,
                tooltip: '复制',
                onPressed: () {
                  // TODO: Implement copy functionality
                },
              ),
              const SizedBox(width: 4),
              _buildActionButton(
                context,
                icon: Icons.thumb_up_outlined,
                tooltip: '好评',
                onPressed: () {
                  // TODO: Implement feedback
                },
              ),
              const SizedBox(width: 4),
              _buildActionButton(
                context,
                icon: Icons.thumb_down_outlined,
                tooltip: '差评',
                onPressed: () {
                  // TODO: Implement feedback
                },
              ),
              const SizedBox(width: 4),
              _buildActionButton(
                context,
                icon: Icons.volume_up_outlined,
                tooltip: '朗读',
                onPressed: () {
                  // TODO: Implement text-to-speech
                },
              ),
              if (branchSelector != null) ...[const Spacer(), branchSelector!],
              const Spacer(),
              if (onRetry != null)
                InkWell(
                  borderRadius: BorderRadius.circular(6),
                  onTap: onRetry,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 6,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.refresh_rounded,
                          size: 14,
                          color: StudyPalette.of(context).textGray,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          '重新生成',
                          style: TextStyle(
                            fontSize: 12,
                            color: StudyPalette.of(context).textGray,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildActionButton(
    BuildContext context, {
    required IconData icon,
    required String tooltip,
    required VoidCallback onPressed,
  }) {
    return IconButton(
      icon: Icon(icon, size: 17),
      padding: const EdgeInsets.all(8),
      constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
      color: StudyPalette.of(context).textGray,
      onPressed: onPressed,
      tooltip: tooltip,
    );
  }

  Widget _buildMessagePart(BuildContext context, MessagePartDto part) {
    switch (part.type) {
      case 'text':
        return MathMarkdownView(
          content: part.text ?? '',
          style: Theme.of(context).textTheme.bodyLarge,
        );

      case 'image':
        return _buildImagePart(context, part);

      case 'file':
        return _buildFilePart(context, part);

      default:
        return const SizedBox.shrink();
    }
  }

  Widget _buildImagePart(BuildContext context, MessagePartDto part) =>
      EnhancedImageView(part: part, messageId: message.id);

  Widget _buildFilePart(BuildContext context, MessagePartDto part) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: StudyPalette.of(context).borderGray),
      ),
      child: Row(
        children: [
          Icon(
            Icons.description_outlined,
            size: 20,
            color: StudyPalette.of(context).textGray,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  part.fileName ?? '附件',
                  style: TextStyle(
                    fontSize: 13,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                Text(
                  _getFileType(part.fileName),
                  style: TextStyle(
                    fontSize: 11,
                    color: StudyPalette.of(context).textGray,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _getFileType(String? fileName) {
    if (fileName == null) return '文件';
    final ext = fileName.split('.').last.toLowerCase();
    return switch (ext) {
      'pdf' => 'PDF 文档',
      'docx' || 'doc' => 'Word 文档',
      'xlsx' || 'xls' => 'Excel 表格',
      'pptx' || 'ppt' => 'PowerPoint',
      'txt' => '文本文件',
      'md' => 'Markdown',
      _ => '文件',
    };
  }
}

/// Custom button that wraps ClipRRect for image expansion
class ClipRRectButton extends StatelessWidget {
  final BorderRadius borderRadius;
  final VoidCallback onPressed;
  final Widget child;

  const ClipRRectButton({
    super.key,
    required this.borderRadius,
    required this.onPressed,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: borderRadius,
      child: Material(
        color: Colors.transparent,
        child: InkWell(onTap: onPressed, child: child),
      ),
    );
  }
}
