import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../widgets/attachment_options_sheet.dart';

class FilesPage extends ConsumerStatefulWidget {
  const FilesPage({super.key});

  @override
  ConsumerState<FilesPage> createState() => _FilesPageState();
}

class _FilesPageState extends ConsumerState<FilesPage> {
  final TextEditingController _searchController = TextEditingController();
  String _selectedFilter = '全部'; // 全部, 文档, 图片
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _pickNewFile() async {
    final option = await AttachmentOptionsSheet.show(context);
    if (option == null || !mounted) return;
    final profile = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    try {
      final paths = await bridgeApi.pickAttachments(profile, epoch);
      if (paths.isNotEmpty && mounted) {
        ref.read(materialsProvider.notifier).refresh();
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('已添加 ${paths.length} 个文件到文件库')));
      }
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final materialsAsync = ref.watch(materialsProvider);

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
          '文件库',
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
              Icons.add,
              color: StudyPalette.of(context).textBlack,
              size: 22,
            ),
            tooltip: '添加文件',
            onPressed: _pickNewFile,
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Search Input
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
                          hintText: '搜索文件',
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

            // Filter Tabs: 全部 / 文档 / 图片
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Wrap(
                runSpacing: 8,
                children: ['全部', '文档', '图片'].map((filter) {
                  final isSelected = filter == _selectedFilter;
                  return Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: InkWell(
                      onTap: () => setState(() => _selectedFilter = filter),
                      borderRadius: BorderRadius.circular(16),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: isSelected
                              ? StudyPalette.of(context).bubbleGray
                              : StudyPalette.of(context).surfaceBackground,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Text(
                          filter,
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: isSelected
                                ? FontWeight.w600
                                : FontWeight.normal,
                            color: isSelected
                                ? StudyPalette.of(context).textBlack
                                : StudyPalette.of(context).textBlack,
                          ),
                        ),
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
            const SizedBox(height: 8),

            // Content
            Expanded(
              child: materialsAsync.when(
                loading: () => Center(
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                error: (e, _) => Center(
                  child: Text(
                    '加载失败: $e',
                    style: TextStyle(color: StudyPalette.of(context).textGray),
                  ),
                ),
                data: (materials) {
                  // Filter by category and search query
                  final filtered = materials.where((m) {
                    final matchesQuery =
                        _searchQuery.isEmpty ||
                        m.name.toLowerCase().contains(
                          _searchQuery.toLowerCase(),
                        );
                    if (!matchesQuery) return false;

                    if (_selectedFilter == '文档') {
                      final ext = m.name.split('.').last.toLowerCase();
                      return [
                        'pdf',
                        'doc',
                        'docx',
                        'xls',
                        'xlsx',
                        'ppt',
                        'pptx',
                        'txt',
                      ].contains(ext);
                    } else if (_selectedFilter == '图片') {
                      final ext = m.name.split('.').last.toLowerCase();
                      return [
                        'jpg',
                        'jpeg',
                        'png',
                        'webp',
                        'gif',
                      ].contains(ext);
                    }
                    return true;
                  }).toList();

                  // P41: Empty State
                  if (filtered.isEmpty) {
                    return _buildEmptyState(context);
                  }

                  // P18: Files List
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
                      return _buildFileItem(context, item);
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

  // P41: File Library Empty State
  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: StudyPalette.of(context).surfaceBackground,
                shape: BoxShape.circle,
                border: Border.all(
                  color: StudyPalette.of(context).borderGray,
                  width: 0.8,
                ),
              ),
              child: Icon(
                Icons.folder_open_outlined,
                size: 28,
                color: StudyPalette.of(context).textGray,
              ),
            ),
            const SizedBox(height: 16),
            Text(
              '还没有文件',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: StudyPalette.of(context).textBlack,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '在聊天中添加图片或文档后，可在这里查看。',
              style: TextStyle(
                fontSize: 13,
                color: StudyPalette.of(context).textGray,
                height: 1.4,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: StudyPalette.of(context).textBlack,
                foregroundColor: StudyPalette.of(context).onAccent,
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 12,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              onPressed: _pickNewFile,
              child: const Text('添加文件', style: TextStyle(fontSize: 14)),
            ),
            const SizedBox(height: 12),
            TextButton(
              onPressed: () => context.pop(),
              child: Text(
                '返回聊天',
                style: TextStyle(
                  fontSize: 13,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // P18: Single File Row
  Widget _buildFileItem(BuildContext context, MaterialFileDto item) {
    final ext = item.name.split('.').last.toLowerCase();
    final isPdf = ext == 'pdf';
    final isImage = ['jpg', 'jpeg', 'png', 'webp'].contains(ext);
    final isWord = ['doc', 'docx'].contains(ext);
    final isExcel = ['xls', 'xlsx'].contains(ext);

    IconData iconData = Icons.description_outlined;
    Color iconColor = StudyPalette.of(context).textBlack;
    if (isPdf) {
      iconData = Icons.picture_as_pdf_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (isImage) {
      iconData = Icons.image_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (isWord) {
      iconData = Icons.article_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    } else if (isExcel) {
      iconData = Icons.table_chart_outlined;
      iconColor = StudyPalette.of(context).textBlack;
    }

    final typeTag = switch (ext) {
      'pdf' => 'PDF',
      'docx' || 'doc' => 'Word',
      'xlsx' || 'xls' => 'Excel',
      'jpg' || 'jpeg' || 'png' => '图片',
      _ => ext.toUpperCase(),
    };

    return InkWell(
      onTap: () => context.push('/files/detail',
          extra: ProfileResourceRoute.capture(ref, item.id)),
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
              child: Icon(iconData, size: 20, color: iconColor),
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
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      Text(
                        '$typeTag · ${_fileSize(item.size)}',
                        style: TextStyle(
                          fontSize: 11,
                          color: StudyPalette.of(context).textGray,
                        ),
                      ),
                      if (item.symbolCheckPending) ...[
                        const SizedBox(width: 8),
                        Text(
                          '待核对符号',
                          style: TextStyle(
                            fontSize: 11,
                            color: StudyPalette.of(context).warningOrange,
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right,
              size: 20,
              color: StudyPalette.of(context).textGray,
            ),
          ],
        ),
      ),
    );
  }
}

String _fileSize(int bytes) => bytes >= 1048576
    ? '${(bytes / 1048576).toStringAsFixed(1)} MB'
    : '${(bytes / 1024).toStringAsFixed(1)} KB';
