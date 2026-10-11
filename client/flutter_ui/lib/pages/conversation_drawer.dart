import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart' show CupertinoIcons;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import '../widgets/chat_more_sheet.dart';
import '../widgets/profile_picker_sheet.dart';
import '../widgets/project_groups.dart';

/// P01 · 聊天历史侧栏
class ConversationDrawer extends ConsumerStatefulWidget {
  const ConversationDrawer({super.key});

  @override
  ConsumerState<ConversationDrawer> createState() => _ConversationDrawerState();
}

class _ConversationDrawerState extends ConsumerState<ConversationDrawer> {
  bool _projectsExpanded = false;

  @override
  Widget build(BuildContext context) {
    final conversations = ref.watch(conversationsProvider);
    final activeId = ref.watch(activeConversationIdProvider);
    final bootstrap = ref.watch(bootstrapProvider).value;
    final profileId = ref.watch(activeProfileIdProvider);
    final archived = ref.watch(historyArchivedProvider);
    final groupedIds =
        ref
            .watch(projectsProvider)
            .value
            ?.expand((p) => p.conversations)
            .map((c) => c.id)
            .toSet() ??
        <String>{};
    final profileName =
        bootstrap?.profiles.where((p) => p.id == profileId).firstOrNull?.name ??
        '当前档案';
    final c = StudyPalette.of(context);
    final width = MediaQuery.sizeOf(context).width;

    return Drawer(
      width: (width * 0.86).clamp(0.0, 360.0),
      backgroundColor: c.bgWhite,
      elevation: 0,
      shape: const RoundedRectangleBorder(),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 8, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      'MathStudyAgent',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 19,
                        fontWeight: FontWeight.w600,
                        color: c.textBlack,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '收起侧栏',
                    icon: const Icon(CupertinoIcons.sidebar_left),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(10, 4, 10, 16),
                    child: Material(
                      color: c.bubbleGray,
                      borderRadius: BorderRadius.circular(14),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () {
                          Navigator.pop(context);
                          context.push('/chat/search');
                        },
                        child: Padding(
                          padding: const EdgeInsets.all(14),
                          child: Row(
                            children: [
                              Icon(
                                CupertinoIcons.search,
                                size: 22,
                                color: c.textGray,
                              ),
                              const SizedBox(width: 12),
                              Text(
                                '搜索聊天',
                                style: TextStyle(
                                  fontSize: 16,
                                  color: c.textGray,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                  _buildNavItem(
                    icon: CupertinoIcons.square_pencil,
                    label: '新聊天',
                    onTap: () {
                      ref.read(activeConversationIdProvider.notifier).state =
                          null;
                      ref.read(activeStudyModeProvider.notifier).state = 'CHAT';
                      Navigator.pop(context);
                      context.go('/chat');
                    },
                  ),
                  _buildNavItem(
                    icon: CupertinoIcons.photo,
                    label: '图片',
                    onTap: () {
                      Navigator.pop(context);
                      context.push('/images');
                    },
                  ),
                  _buildNavItem(
                    icon: CupertinoIcons.archivebox,
                    label: archived ? '返回最近聊天' : '已归档聊天',
                    onTap: () {
                      ref.read(historyQueryProvider.notifier).update('');
                      ref.read(historyArchivedProvider.notifier).setArchived(!archived);
                    },
                  ),
                  _buildNavItem(
                    icon: CupertinoIcons.doc,
                    label: '文件库',
                    onTap: () {
                      Navigator.pop(context);
                      context.push('/files');
                    },
                  ),
                  const SizedBox(height: 16),
                  _buildNavItem(
                    icon: CupertinoIcons.folder,
                    label: '项目',
                    onTap: () =>
                        setState(() => _projectsExpanded = !_projectsExpanded),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          tooltip: '新建项目',
                          icon: const Icon(Icons.add, size: 22),
                          onPressed: () {
                            setState(() => _projectsExpanded = true);
                            editProject(context, ref);
                          },
                        ),
                        Icon(
                          _projectsExpanded
                              ? Icons.keyboard_arrow_down
                              : Icons.chevron_right,
                          size: 22,
                        ),
                      ],
                    ),
                  ),
                  if (_projectsExpanded && !archived) const ProjectGroups(),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(10, 24, 10, 8),
                    child: Text(
                      archived ? '已归档聊天' : '最近聊天',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: c.textBlack,
                      ),
                    ),
                  ),
                  ...conversations.when(
                    loading: () => [
                      const Padding(
                        padding: EdgeInsets.all(24),
                        child: Center(
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      ),
                    ],
                    error: (error, _) => [
                      Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(
                          '加载历史失败: $error',
                          style: TextStyle(color: c.textGray),
                        ),
                      ),
                    ],
                    data: (items) => items.isEmpty
                        ? [
                            Padding(
                              padding: const EdgeInsets.all(12),
                              child: Text(
                                '暂无历史聊天',
                                style: TextStyle(color: c.textGray),
                              ),
                            ),
                          ]
                        : _buildGroupedList(
                            items
                                .where((item) => item.isArchived == archived &&
                                    (archived || !groupedIds.contains(item.id)))
                                .toList(),
                            activeId,
                          ),
                  ),
                  if (conversations.hasValue &&
                      ref.read(conversationsProvider.notifier).hasMore)
                    TextButton(
                      onPressed: () async {
                        try {
                          await ref.read(conversationsProvider.notifier).loadMore();
                        } catch (error) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text('加载历史失败：$error')));
                          }
                        }
                      },
                      child: const Text('加载更多'),
                    ),
                ],
              ),
            ),
            Divider(color: c.borderGray, height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: InkWell(
                      borderRadius: BorderRadius.circular(12),
                      onTap: () => showProfilePickerSheet(context),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 8,
                        ),
                        child: Row(
                          children: [
                            CircleAvatar(
                              radius: 20,
                              backgroundColor: c.selectionFill,
                              child: Icon(
                                CupertinoIcons.person,
                                color: c.textBlack,
                                size: 22,
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '个人档案',
                                    style: TextStyle(
                                      fontSize: 15,
                                      fontWeight: FontWeight.w500,
                                      color: c.textBlack,
                                    ),
                                  ),
                                  Text(
                                    profileName,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: c.textGray,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '设置',
                    icon: const Icon(CupertinoIcons.gear, size: 22),
                    onPressed: () {
                      Navigator.pop(context);
                      context.push('/settings');
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNavItem({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
    Widget? trailing,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 48),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 0),
          child: Row(
            children: [
              Icon(icon, size: 22, color: StudyPalette.of(context).textBlack),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
              ),
              ?trailing,
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _buildGroupedList(
    List<ConversationSummaryDto> items,
    String? activeId,
  ) {
    final now = DateTime.now();
    final pinnedItems = <ConversationSummaryDto>[];
    final today = <ConversationSummaryDto>[];
    final yesterday = <ConversationSummaryDto>[];
    final past7Days = <ConversationSummaryDto>[];
    final earlier = <ConversationSummaryDto>[];

    for (final item in items) {
      if (item.isPinned) {
        pinnedItems.add(item);
        continue;
      }
      DateTime date;
      final millis = int.tryParse(item.updatedAt);
      if (millis != null) {
        date = DateTime.fromMillisecondsSinceEpoch(millis);
      } else {
        date = DateTime.tryParse(item.updatedAt) ?? now;
      }
      final diffDays = now.difference(date).inDays;

      if (date.year == now.year &&
          date.month == now.month &&
          date.day == now.day) {
        today.add(item);
      } else if (diffDays <= 1) {
        yesterday.add(item);
      } else if (diffDays <= 7) {
        past7Days.add(item);
      } else {
        earlier.add(item);
      }
    }

    return [
      if (pinnedItems.isNotEmpty) ...[
        _buildGroupHeader('置顶'),
        ...pinnedItems.map((item) => _buildConversationTile(item, activeId)),
      ],
      if (today.isNotEmpty) ...[
        _buildGroupHeader('今天'),
        ...today.map((item) => _buildConversationTile(item, activeId)),
      ],
      if (yesterday.isNotEmpty) ...[
        _buildGroupHeader('昨天'),
        ...yesterday.map(
          (item) => _buildConversationTile(item, activeId),
        ),
      ],
      if (past7Days.isNotEmpty) ...[
        _buildGroupHeader('过去 7 天'),
        ...past7Days.map(
          (item) => _buildConversationTile(item, activeId),
        ),
      ],
      if (earlier.isNotEmpty) ...[
        _buildGroupHeader('更早'),
        ...earlier.map(
          (item) => _buildConversationTile(item, activeId),
        ),
      ],
    ];
  }

  Widget _buildGroupHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 16, 10, 8),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.normal,
          color: StudyPalette.of(context).textGray,
        ),
      ),
    );
  }

  Widget _buildConversationTile(
    ConversationSummaryDto item,
    String? activeId,
  ) {
    final isSelected = item.id == activeId;
    final isPinned = item.isPinned;

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2),
      decoration: BoxDecoration(
        color: isSelected
            ? StudyPalette.of(context).selectionFill
            : Colors.transparent,
        borderRadius: BorderRadius.circular(10),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: () {
          ref.read(activeConversationIdProvider.notifier).state = item.id;
          ref.read(activeStudyModeProvider.notifier).state = item.studyMode;
          Navigator.pop(context);
          context.go('/chat');
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(10, 0, 0, 0),
          child: Row(
            children: [
              if (isPinned)
                Padding(
                  padding: EdgeInsets.only(right: 6),
                  child: Icon(
                    Icons.push_pin,
                    size: 13,
                    color: StudyPalette.of(context).textGray,
                  ),
                ),
              Expanded(
                child: Text(
                  item.title.isNotEmpty ? item.title : '未命名对话',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: isSelected
                        ? FontWeight.w600
                        : FontWeight.normal,
                    color: StudyPalette.of(context).textBlack,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              // More options button -> opens P03 sheet
              IconButton(
                icon: Icon(
                  Icons.more_horiz,
                  size: 16,
                  color: StudyPalette.of(context).textGray,
                ),
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
                onPressed: () => showChatMoreSheet(context, item),
                tooltip: '更多操作',
              ),
            ],
          ),
        ),
      ),
    );
  }
}
