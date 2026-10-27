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
    final currentProfileId = ref.watch(activeProfileIdProvider);
    final epoch = ref.watch(contextEpochProvider);
    final protection = ref.watch(memoryProtectionProvider);
    final status =
        !protection.isLoading &&
            !protection.hasError &&
            protection.value?.profileId == currentProfileId &&
            protection.value?.contextEpoch == epoch
        ? protection.value
        : null;
    final isAutoMemory = status?.configuredEnabled ?? false;
    final canToggle =
        status != null && (!status.recoveryBlocked || status.configuredEnabled);
    final statusText = status == null
        ? protection.hasError
              ? '记忆状态未能核对，请刷新重试。当前是否可用尚不确定。'
              : '正在核对记忆状态'
        : status.recoveryBlocked
        ? '记忆保护中：自动抽取与聊天记忆引用已暂停。旧删除约束尚无法恢复；原记录已保留，可继续查看、修改、删除或手动添加。关闭或开启设置不会解除保护，可使用新档案继续。'
        : status.configuredEnabled
        ? '记忆已开启：可自动抽取并在聊天中引用。'
        : '记忆已关闭：不自动抽取，也不在聊天中引用。已有记录仍可管理。';

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
            onPressed: () {
              ref.read(memoriesProvider.notifier).refresh();
              ref.read(memoryProtectionProvider.notifier).refresh();
            },
          ),
        ],
      ),
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverToBoxAdapter(
              child: Column(
                children: [
                  // Auto Memory Switch Card
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
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
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      '记忆设置',
                                      style: TextStyle(
                                        fontSize: 15,
                                        fontWeight: FontWeight.w600,
                                        color: StudyPalette.of(context)
                                            .textBlack,
                                      ),
                                    ),
                                    SizedBox(height: 2),
                                    Text(
                                      '自动抽取与聊天记忆引用',
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: StudyPalette.of(context)
                                            .textGray,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              Semantics(
                                label: '自动抽取与聊天记忆引用设置',
                                value: status == null ? '状态未知' : null,
                                child: Switch(
                                  value: isAutoMemory,
                                  onChanged: !canToggle
                                      ? null
                                      : (val) async {
                                          final messenger =
                                              ScaffoldMessenger.of(context);
                                          try {
                                            await ref
                                                .read(memoriesProvider.notifier)
                                                .toggleAutoMemory(val);
                                          } catch (error) {
                                            if (!mounted ||
                                                ref.read(
                                                      activeProfileIdProvider,
                                                    ) !=
                                                    currentProfileId ||
                                                ref.read(
                                                      contextEpochProvider,
                                                    ) !=
                                                    epoch) {
                                              return;
                                            }
                                            messenger.showSnackBar(
                                              SnackBar(content: Text('$error')),
                                            );
                                          }
                                        },
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          Semantics(
                            liveRegion: true,
                            child: Text(
                              statusText,
                              key: const ValueKey('memory-protection-status'),
                              style: TextStyle(
                                color: StudyPalette.of(context).textBlack,
                                fontSize: 13,
                                height: 1.4,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),

                  // Search Bar
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 4,
                    ),
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
                                contentPadding: EdgeInsets.symmetric(
                                  vertical: 10,
                                ),
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
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 6,
                    ),
                    child: Wrap(
                      children: ['全部', '偏好', '工作信息'].map((filter) {
                        final isSelected = filter == _selectedFilter;
                        return Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: InkWell(
                            onTap: () =>
                                setState(() => _selectedFilter = filter),
                            borderRadius: BorderRadius.circular(16),
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 6,
                              ),
                              decoration: BoxDecoration(
                                color: isSelected
                                    ? StudyPalette.of(context).bubbleGray
                                    : StudyPalette.of(context)
                                          .surfaceBackground,
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
                ],
              ),
            ),

            // Memories List
            memoriesAsync.when(
              skipLoadingOnReload: false,
              skipLoadingOnRefresh: false,
              loading: () => SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
              ),
              error: (err, _) => SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Text(
                    '加载记忆失败: $err',
                    style: TextStyle(color: StudyPalette.of(context).textGray),
                  ),
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
                  return SliverFillRemaining(
                    hasScrollBody: false,
                    child: Center(
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
                              '可手动添加记忆。自动抽取与聊天引用以本档案的设置和保护状态为准。',
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
                    ),
                  );
                }

                return SliverPadding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 8,
                  ),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (context, index) => index.isOdd
                          ? Divider(
                              height: 1,
                              color: StudyPalette.of(context).borderGray,
                            )
                          : _buildMemoryRow(context, filtered[index ~/ 2]),
                      childCount: filtered.length * 2 - 1,
                    ),
                  ),
                );
              },
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
