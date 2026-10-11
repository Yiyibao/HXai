import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import '../bridge/bridge_client.dart';

/// P33 · 个人档案选择
void showProfilePickerSheet(BuildContext context) {
  showModalBottomSheet(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (context) => const _ProfilePickerSheetContent(),
  );
}

class ProfilePickerSheet {
  static void show(BuildContext context) => showProfilePickerSheet(context);
}

class _ProfilePickerSheetContent extends ConsumerWidget {
  const _ProfilePickerSheetContent();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentProfileId = ref.watch(activeProfileIdProvider);
    final profiles = ref.watch(profilesListProvider);

    return Container(
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: const EdgeInsets.symmetric(vertical: 16),
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
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: StudyPalette.of(context).borderGray,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),

            // Header
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '切换个人档案',
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
                    constraints: const BoxConstraints(
                      minWidth: 28,
                      minHeight: 28,
                    ),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),

            // Profiles list
            ...profiles.map((p) {
              final isCurrent = p.id == currentProfileId;
              return InkWell(
                onTap: isCurrent
                    ? null
                    : () async {
                        try {
                          final res = await StudyBridgeClient.instance.api
                              .switchProfile(
                                p.id,
                                ref.read(contextEpochProvider),
                              );
                          checkCommand(res);
                          if (res.success) {
                            ref.invalidate(bootstrapProvider);
                            ref.read(activeProfileIdProvider.notifier).state =
                                p.id;
                            ref
                                    .read(activeConversationIdProvider.notifier)
                                    .state =
                                null;
                            ref.read(conversationsProvider.notifier).refresh();
                            ref.read(materialsProvider.notifier).refresh();
                            ref.read(memoriesProvider.notifier).refresh();
                            ref.read(artifactsProvider.notifier).refresh();
                            if (context.mounted) Navigator.pop(context);
                          }
                        } catch (e) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text('切换档案失败: $e')),
                            );
                          }
                        }
                      },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 12,
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          color: isCurrent
                              ? StudyPalette.of(context).textBlack
                              : StudyPalette.of(context).bubbleGray,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.person_outline,
                          size: 20,
                          color: isCurrent
                              ? StudyPalette.of(context).onAccent
                              : StudyPalette.of(context).textBlack,
                        ),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              p.name,
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: isCurrent
                                    ? FontWeight.w600
                                    : FontWeight.normal,
                                color: StudyPalette.of(context).textBlack,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '独立的聊天、文件与记忆',
                              style: TextStyle(
                                fontSize: 12,
                                color: StudyPalette.of(context).textGray,
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (isCurrent)
                        Icon(
                          Icons.check_rounded,
                          color: StudyPalette.of(context).textBlack,
                          size: 20,
                        ),
                    ],
                  ),
                ),
              );
            }),

            Padding(
              padding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              child: Divider(
                color: StudyPalette.of(context).borderGray,
                height: 1,
              ),
            ),

            // Footer tip & secondary link
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              child: Text(
                '切换后显示该档案的数据',
                style: TextStyle(
                  fontSize: 12,
                  color: StudyPalette.of(context).textGray,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 8),
            Center(
              child: TextButton(
                onPressed: () {
                  Navigator.pop(context);
                  context.push('/settings');
                },
                child: Text(
                  '管理档案',
                  style: TextStyle(
                    color: StudyPalette.of(context).textBlack,
                    fontSize: 13,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
