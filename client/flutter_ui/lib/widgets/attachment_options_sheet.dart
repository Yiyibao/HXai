import 'package:flutter/material.dart';

import '../theme/study_theme.dart';

enum AttachmentSourceType { camera, image, file, createImage }

class AttachmentOptionsSheet extends StatelessWidget {
  const AttachmentOptionsSheet({super.key, required this.onSelect});

  final ValueChanged<AttachmentSourceType> onSelect;

  static Future<AttachmentSourceType?> show(BuildContext context) {
    return showModalBottomSheet<AttachmentSourceType>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => AttachmentOptionsSheet(
        onSelect: (type) => Navigator.of(ctx).pop(type),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Drag handle
          Center(
            child: Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                color: StudyPalette.of(context).borderGray,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),

          // Header
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '添加到对话',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: StudyPalette.of(context).textBlack,
                ),
              ),
              IconButton(
                icon: Icon(
                  Icons.close,
                  size: 20,
                  color: StudyPalette.of(context).textGray,
                ),
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Option: Camera
          _buildOptionTile(
            context,
            icon: Icons.camera_alt_outlined,
            title: '拍照',
            subtitle: '当前版本尚未接入相机',
            enabled: false,
            onTap: () => onSelect(AttachmentSourceType.camera),
          ),
          const SizedBox(height: 8),

          // Option: Pick Image
          _buildOptionTile(
            context,
            icon: Icons.image_outlined,
            title: '选择图片',
            subtitle: '从相册选择截图或照片',
            onTap: () => onSelect(AttachmentSourceType.image),
          ),
          const SizedBox(height: 8),

          // Option: Pick Document
          _buildOptionTile(
            context,
            icon: Icons.insert_drive_file_outlined,
            title: '选择文件',
            subtitle: '支持 PDF、Word、Excel 等文档',
            onTap: () => onSelect(AttachmentSourceType.file),
          ),
          const SizedBox(height: 18),

          _buildOptionTile(
            context,
            icon: Icons.auto_awesome_outlined,
            title: '创建图片',
            subtitle: '用文字或参考图创作',
            onTap: () => onSelect(AttachmentSourceType.createImage),
          ),
          // Hint footer
          Center(
            child: Text(
              '上传后可在对话中提问',
              style: TextStyle(
                fontSize: 12,
                color: StudyPalette.of(context).textGray,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildOptionTile(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    bool enabled = true,
  }) {
    return InkWell(
      onTap: enabled ? onTap : null,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: StudyPalette.of(context).surfaceBackground,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: StudyPalette.of(context).borderGray,
            width: 0.8,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: StudyPalette.of(context).bgWhite,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: StudyPalette.of(context).borderGray,
                  width: 0.8,
                ),
              ),
              child: Icon(
                icon,
                size: 22,
                color: StudyPalette.of(context).textBlack,
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: StudyPalette.of(context).textBlack,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: StudyPalette.of(context).textGray,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right,
              size: 18,
              color: StudyPalette.of(context).textGray,
            ),
          ],
        ),
      ),
    );
  }
}
