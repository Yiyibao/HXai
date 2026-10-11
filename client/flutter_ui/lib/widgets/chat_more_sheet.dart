import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import 'project_groups.dart';

/// P03 · 聊天更多操作
/// Modal bottom sheet (~40% height) with neutral ChatGPT styling
void showChatMoreSheet(
  BuildContext context,
  ConversationSummaryDto conversation,
) {
  showModalBottomSheet(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (context) => _ChatMoreSheetContent(conversation: conversation),
  );
}

class _ChatMoreSheetContent extends ConsumerWidget {
  final ConversationSummaryDto conversation;

  const _ChatMoreSheetContent({required this.conversation});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pinned = conversation.isPinned;
    final archived = conversation.isArchived;

    return Container(
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: const EdgeInsets.only(top: 12, bottom: 20),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Drag handle
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 14),
                decoration: BoxDecoration(
                  color: StudyPalette.of(context).borderGray,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),

            // Conversation title
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              child: Text(
                conversation.title,
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: StudyPalette.of(context).textBlack,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(height: 8),

            _buildActionTile(
              context,
              icon: Icons.folder_outlined,
              title: '移动到项目 / 移出项目',
              onTap: () => moveChatToProject(context, ref, conversation.id),
            ),

            // Action 1: Rename
            _buildActionTile(
              context,
              icon: Icons.edit_outlined,
              title: '重命名',
              onTap: () async {
                Navigator.pop(context);
                final controller = TextEditingController(
                  text: conversation.title,
                );
                final newTitle = await showDialog<String>(
                  context: context,
                  builder: (ctx) => AlertDialog(
                    backgroundColor: StudyPalette.of(context).bgWhite,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                    title: const Text(
                      '重命名聊天',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    content: TextField(
                      controller: controller,
                      autofocus: true,
                      maxLength: 200,
                      decoration: const InputDecoration(
                        hintText: '输入新标题',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(ctx),
                        child: Text(
                          '取消',
                          style: TextStyle(
                            color: StudyPalette.of(context).textGray,
                          ),
                        ),
                      ),
                      ElevatedButton(
                        onPressed: () =>
                            Navigator.pop(ctx, controller.text.trim()),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: StudyPalette.of(context).textBlack,
                        ),
                        child: const Text('保存'),
                      ),
                    ],
                  ),
                );
                controller.dispose();
                if (newTitle != null && newTitle.isNotEmpty) {
                  final profile = ref.read(activeProfileIdProvider);
                  checkCommand(
                    await bridgeApi.renameConversation(
                      profile,
                      conversation.id,
                      newTitle,
                    ),
                  );
                  await ref.read(conversationsProvider.notifier).refresh();
                  await ref.read(projectsProvider.notifier).refresh();
                  if (ref.read(activeConversationIdProvider) ==
                      conversation.id) {
                    await ref
                        .read(activeConversationDetailProvider.notifier)
                        .refresh();
                  }
                }
              },
            ),

            // Action 2: Pin / Unpin
            _buildActionTile(
              context,
              icon: pinned ? Icons.push_pin : Icons.push_pin_outlined,
              title: pinned ? '取消置顶' : '置顶聊天',
              onTap: () async {
                final success = await projectAction(context, ref, (profile, epoch) =>
                    bridgeApi.setConversationPinned(profile, conversation.id, !pinned, epoch));
                if (success && context.mounted) Navigator.pop(context);
              },
            ),

            // Sharing stays unavailable until the application has a share API.
            ListTile(
              leading: Icon(
                Icons.share_outlined,
                color: StudyPalette.of(context).textGray,
              ),
              title: Text(
                '分享聊天',
                style: TextStyle(color: StudyPalette.of(context).textGray),
              ),
              subtitle: const Text('当前界面尚未接入分享'),
              enabled: false,
            ),

            // Action 4: Archive
            _buildActionTile(
              context,
              icon: archived
                  ? Icons.unarchive_outlined
                  : Icons.archive_outlined,
              title: archived ? '取消归档' : '归档聊天',
              onTap: () async {
                final success = await projectAction(context, ref, (profile, epoch) =>
                    bridgeApi.setConversationArchived(profile, conversation.id, !archived, epoch));
                if (success && context.mounted) Navigator.pop(context);
              },
            ),

            // Divider
            Padding(
              padding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Divider(
                color: StudyPalette.of(context).borderGray,
                height: 1,
              ),
            ),

            // Action 5: Delete
            _buildActionTile(
              context,
              icon: Icons.delete_outline,
              title: '删除聊天',
              isDestructive: true,
              onTap: () async {
                Navigator.pop(context);
                final confirmed = await showDialog<bool>(
                  context: context,
                  builder: (ctx) => AlertDialog(
                    backgroundColor: StudyPalette.of(context).bgWhite,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                    title: const Text(
                      '删除这段聊天？',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    content: Text('删除后将无法恢复：${conversation.title}'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(ctx, false),
                        child: Text(
                          '取消',
                          style: TextStyle(
                            color: StudyPalette.of(context).textGray,
                          ),
                        ),
                      ),
                      TextButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        child: Text(
                          '删除',
                          style: TextStyle(
                            color: StudyPalette.of(context).errorRed,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                );
                if (confirmed == true) {
                  final profile = ref.read(activeProfileIdProvider);
                  checkCommand(
                    await bridgeApi.deleteConversation(
                      profile,
                      conversation.id,
                    ),
                  );
                  if (ref.read(activeConversationIdProvider) ==
                      conversation.id) {
                    ref.read(activeConversationIdProvider.notifier).state =
                        null;
                  }
                  await ref.read(conversationsProvider.notifier).refresh();
                  await ref.read(projectsProvider.notifier).refresh();
                }
              },
            ),

            const SizedBox(height: 8),

            // Cancel button
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  side: BorderSide(color: StudyPalette.of(context).borderGray),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                child: Text(
                  '取消',
                  style: TextStyle(
                    color: StudyPalette.of(context).textBlack,
                    fontSize: 14,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionTile(
    BuildContext context, {
    required IconData icon,
    required String title,
    required VoidCallback onTap,
    bool isDestructive = false,
  }) {
    final color = isDestructive
        ? StudyPalette.of(context).errorRed
        : StudyPalette.of(context).textBlack;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Row(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(width: 14),
            Text(
              title,
              style: TextStyle(
                fontSize: 15,
                fontWeight: isDestructive ? FontWeight.w500 : FontWeight.normal,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
