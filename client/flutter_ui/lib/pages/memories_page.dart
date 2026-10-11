import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/profile_resource.dart';
import '../theme/study_theme.dart';
import '../widgets/add_memory_dialog.dart';

class MemoriesPage extends ConsumerStatefulWidget {
  const MemoriesPage({super.key});

  @override
  ConsumerState<MemoriesPage> createState() => _MemoriesPageState();
}

class _MemoriesPageState extends ConsumerState<MemoriesPage> {
  final TextEditingController _searchController = TextEditingController();
  String _selectedFilter = '全部'; // 全部, 偏好, 工作信息
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final memoriesAsync = ref.watch(memoriesProvider);
    final bootstrap = ref.watch(bootstrapProvider).value;
    final currentProfileId = ref.watch(activeProfileIdProvider);
    LearnerProfileDto? currentProfile;
    if (bootstrap != null) {
      for (final p in bootstrap.profiles) {
        if (p.id == currentProfileId) {
          currentProfile = p;
          break;
        }
      }
    }
    final isAutoMemory = currentProfile?.memoryEnabled ?? true;

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
          '记忆',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: '手动添加记忆',
            onPressed: () => showAddMemoryDialog(context),
          ),
          IconButton(
            icon: Icon(
              Icons.refresh,
              color: StudyPalette.of(context).textBlack,
              size: 20,
            ),
            tooltip: '刷新记忆',
            onPressed: () => ref.read(memoriesProvider.notifier).refresh(),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Auto Memory Switch Card
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 12,
                ),
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
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '使用记忆',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: StudyPalette.of(context).textBlack,
                            ),
                          ),
                          SizedBox(height: 2),
                          Text(
                            '帮助保留长期偏好，可随时查看和修改',
                            style: TextStyle(
                              fontSize: 12,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: isAutoMemory,
                      onChanged: (val) async {
                        final messenger = ScaffoldMessenger.of(context);
                        try {
                          await ref
                              .read(memoriesProvider.notifier)
                              .toggleAutoMemory(val);
                        } catch (error) {
                          messenger.showSnackBar(
                            SnackBar(content: Text('$error')),
                          );
                        }
                      },
                    ),
                  ],
                ),
              ),
            ),

            // Search Bar
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
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
                          hintText: '搜索记忆',
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

            // Filter Pills: 全部 / 偏好 / 工作信息
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Row(
                children: ['全部', '偏好', '工作信息'].map((filter) {
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
            const SizedBox(height: 6),

            // Memories List
            Expanded(
              child: memoriesAsync.when(
                loading: () => Center(
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                error: (err, _) => Center(
                  child: Text(
                    '加载记忆失败: $err',
                    style: TextStyle(color: StudyPalette.of(context).textGray),
                  ),
                ),
                data: (items) {
                  final filtered = items.where((item) {
                    final matchQuery =
                        _searchQuery.isEmpty ||
                        item.content.toLowerCase().contains(
                          _searchQuery.toLowerCase(),
                        );
                    if (!matchQuery) return false;
                    if (_selectedFilter == '偏好') {
                      return item.content.contains('喜欢') ||
                          item.content.contains('偏好') ||
                          item.type == 'PREFERENCE';
                    } else if (_selectedFilter == '工作信息') {
                      return item.content.contains('报告') ||
                          item.content.contains('格式') ||
                          item.type == 'WORK';
                    }
                    return true;
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
                                Icons.psychology_outlined,
                                size: 28,
                                color: StudyPalette.of(context).textGray,
                              ),
                            ),
                            const SizedBox(height: 16),
                            Text(
                              '暂无记忆记录',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                                color: StudyPalette.of(context).textBlack,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '在对话中提到长期习惯或偏好后，将由助手整理保存在这里。',
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
                      return _buildMemoryRow(context, item);
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

  Widget _buildMemoryRow(BuildContext context, MemoryItemDto item) {
    return InkWell(
      onTap: () => context.push(
        '/settings/memories/detail',
        extra: ProfileResourceRoute.capture(ref, item.id),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              margin: const EdgeInsets.only(top: 2),
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: StudyPalette.of(context).textBlack,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.content,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: StudyPalette.of(context).textBlack,
                      height: 1.4,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '最后更新：${_formatDate(item.updatedAt)} · 点击查看详情',
                    style: TextStyle(
                      fontSize: 11,
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

  String _formatDate(int timestamp) {
    if (timestamp <= 0) return '近期';
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
    return '${date.month}月${date.day}日';
  }
}
