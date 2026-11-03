import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../theme/study_theme.dart';

class ArtifactsPage extends ConsumerStatefulWidget {
  const ArtifactsPage({super.key});

  @override
  ConsumerState<ArtifactsPage> createState() => _ArtifactsPageState();
}

class _ArtifactsPageState extends ConsumerState<ArtifactsPage> {
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final artifactsAsync = ref.watch(artifactsProvider);

    return Scaffold(
      backgroundColor: StudyPalette.of(context).bgWhite,
      appBar: AppBar(
        backgroundColor: StudyPalette.of(context).bgWhite,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back,
            color: StudyPalette.of(context).textBlack,
          ),
          onPressed: () => context.pop(),
        ),
        title: Text(
          '生成成果',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
        actions: [
          IconButton(
            icon: Icon(
              Icons.refresh,
              color: StudyPalette.of(context).textBlack,
              size: 20,
            ),
            tooltip: '刷新成果',
            onPressed: () => ref.read(artifactsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Search Bar
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Container(
                decoration: BoxDecoration(
                  color: StudyPalette.of(context).surfaceBackground,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: StudyPalette.of(context).borderGray,
                    width: 0.8,
                  ),
                ),
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Row(
                  children: [
                    Icon(
                      Icons.search,
                      size: 18,
                      color: StudyPalette.of(context).textGray,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: _searchController,
                        onChanged: (val) =>
                            setState(() => _searchQuery = val.trim()),
                        style: TextStyle(
                          fontSize: 14,
                          color: StudyPalette.of(context).textBlack,
                        ),
                        decoration: InputDecoration(
                          hintText: '搜索生成成果',
                          hintStyle: TextStyle(
                            fontSize: 14,
                            color: StudyPalette.of(context).textGray,
                          ),
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          filled: false,
                          isDense: true,
                          contentPadding: EdgeInsets.symmetric(vertical: 10),
                        ),
                      ),
                    ),
                    if (_searchQuery.isNotEmpty)
                      IconButton(
                        icon: Icon(
                          Icons.clear,
                          size: 16,
                          color: StudyPalette.of(context).textGray,
                        ),
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchQuery = '');
                        },
                      ),
                  ],
                ),
              ),
            ),

            // List of artifacts
            Expanded(
              child: artifactsAsync.when(
                loading: () => Center(
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                error: (err, _) => Center(
                  child: Text(
                    '加载成果失败: $err',
                    style: TextStyle(color: StudyPalette.of(context).textGray),
                  ),
                ),
                data: (list) {
                  final filtered = list.where((item) {
                    return _searchQuery.isEmpty ||
                        item.name.toLowerCase().contains(
                          _searchQuery.toLowerCase(),
                        );
                  }).toList();

                  if (filtered.isEmpty) {
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 32),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Container(
                              width: 56,
                              height: 56,
                              decoration: BoxDecoration(
                                color: StudyPalette.of(context)
                                    .surfaceBackground,
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: StudyPalette.of(context).borderGray,
                                  width: 0.8,
                                ),
                              ),
                              child: Icon(
                                Icons.auto_stories_outlined,
                                size: 28,
                                color: StudyPalette.of(context).textGray,
                              ),
                            ),
                            const SizedBox(height: 16),
                            Text(
                              '暂无生成成果',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                                color: StudyPalette.of(context).textBlack,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '在对话中让助手整理并生成 Word、Excel 或 PPT 成果后，可在这里统一查看。',
                              style: TextStyle(
                                fontSize: 13,
                                color: StudyPalette.of(context).textGray,
                                height: 1.4,
                              ),
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ),
                      ),
                    );
                  }

                  return ListView.separated(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 8,
                    ),
                    itemCount: filtered.length,
                    separatorBuilder: (_, _) => Divider(
                      height: 1,
                      color: StudyPalette.of(context).borderGray,
                    ),
                    itemBuilder: (context, index) {
                      final item = filtered[index];
                      return _buildArtifactTile(context, item);
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildArtifactTile(BuildContext context, ArtifactDto item) {
    final ext = item.extension.toLowerCase();
    IconData icon = Icons.description_outlined;
    Color iconColor = StudyPalette.of(context).textBlack;

    if (ext == 'docx' || ext == 'doc') {
      icon = Icons.article_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (ext == 'xlsx' || ext == 'xls') {
      icon = Icons.table_chart_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (ext == 'pptx' || ext == 'ppt') {
      icon = Icons.slideshow_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (ext == 'pdf') {
      icon = Icons.picture_as_pdf_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    }

    return InkWell(
      onTap: () {
        context.push(
          '/artifacts/preview',
          extra: ProfileResourceRoute.capture(ref, item.id),
        );
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: StudyPalette.of(context).surfaceBackground,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: StudyPalette.of(context).borderGray,
                  width: 0.8,
                ),
              ),
              child: Icon(icon, size: 20, color: iconColor),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.name,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: StudyPalette.of(context).textBlack,
                    ),
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '${item.extension.toUpperCase()} · ${_formatSize(item.size)} · 点击预览',
                    style: TextStyle(
                      fontSize: 11,
                      color: StudyPalette.of(context).textGray,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              icon: Icon(
                Icons.more_horiz,
                size: 18,
                color: StudyPalette.of(context).textGray,
              ),
              onPressed: () => _showMoreActions(context, item),
            ),
          ],
        ),
      ),
    );
  }

  void _showMoreActions(BuildContext context, ArtifactDto item) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) => Container(
        decoration: BoxDecoration(
          color: StudyPalette.of(context).bgWhite,
          borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        ),
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 16,
          bottom: MediaQuery.of(ctx).padding.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              item.name,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: StudyPalette.of(context).textBlack,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            const SizedBox(height: 16),
            ListTile(
              leading: Icon(
                Icons.history_outlined,
                color: StudyPalette.of(context).textBlack,
              ),
              title: const Text('版本历史', style: TextStyle(fontSize: 14)),
              onTap: () {
                Navigator.pop(ctx);
                context.push(
                  '/artifacts/versions',
                  extra: ProfileResourceRoute.capture(ref, item.id),
                );
              },
            ),
            ListTile(
              leading: Icon(
                Icons.download_outlined,
                color: StudyPalette.of(context).textBlack,
              ),
              title: const Text('保存到本地', style: TextStyle(fontSize: 14)),
              onTap: () async {
                Navigator.pop(ctx);
                final profile = ref.read(activeProfileIdProvider);
                try {
                  final res = await bridgeApi.saveArtifact(profile, item.id);
                  checkCommand(res);
                  if (context.mounted)
                    ScaffoldMessenger.of(
                      context,
                    ).showSnackBar(const SnackBar(content: Text('文件已保存到本地')));
                } catch (e) {
                  if (context.mounted)
                    ScaffoldMessenger.of(context)
                        .showSnackBar(SnackBar(content: Text('$e')));
                }
              },
            ),
            ListTile(
              leading: Icon(
                Icons.share_outlined,
                color: StudyPalette.of(context).textBlack,
              ),
              title: const Text('分享文件', style: TextStyle(fontSize: 14)),
              onTap: () async {
                Navigator.pop(ctx);
                final profile = ref.read(activeProfileIdProvider);
                try {
                  await bridgeApi.shareArtifact(profile, item.id);
                } catch (e) {
                  if (context.mounted)
                    ScaffoldMessenger.of(context)
                        .showSnackBar(SnackBar(content: Text('$e')));
                }
              },
            ),
          ],
        ),
      ),
    );
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
