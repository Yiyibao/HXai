import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import 'chat_more_sheet.dart';

final projectsProvider =
    AsyncNotifierProvider<ProjectsNotifier, List<ProjectDto>>(
      ProjectsNotifier.new,
    );

class ProjectsNotifier extends ProfileListNotifier<ProjectDto> {
  @override
  Future<List<ProjectDto>> build() {
    ref.watch(conversationsProvider);
    return super.build();
  }

  @override
  Future<List<ProjectDto>> fetch(String profileId) =>
      bridgeApi.getProjects(profileId);
}

Future<bool> projectAction(
  BuildContext context,
  WidgetRef ref,
  Future<SimpleCommandResult> Function(String, int) action,
) async {
  final profile = ref.read(activeProfileIdProvider);
  final epoch = ref.read(contextEpochProvider);
  final messenger = ScaffoldMessenger.of(context);
  try {
    checkCommand(await action(profile, epoch));
    if (!context.mounted ||
        ref.read(activeProfileIdProvider) != profile ||
        ref.read(contextEpochProvider) != epoch) {
      return false;
    }
    await ref.read(projectsProvider.notifier).refresh();
    if (context.mounted &&
        ref.read(activeProfileIdProvider) == profile &&
        ref.read(contextEpochProvider) == epoch) {
      await ref.read(conversationsProvider.notifier).refresh();
    }
    return context.mounted &&
        ref.read(activeProfileIdProvider) == profile &&
        ref.read(contextEpochProvider) == epoch;
  } catch (e) {
    if (context.mounted) messenger.showSnackBar(SnackBar(content: Text('$e')));
    return false;
  }
}

Future<void> editProject(
  BuildContext context,
  WidgetRef ref, [
  ProjectDto? project,
]) async {
  final profile = ref.read(activeProfileIdProvider);
  final epoch = ref.read(contextEpochProvider);
  final controller = TextEditingController(text: project?.name ?? '');
  final name = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(project == null ? '新建项目' : '重命名项目'),
      content: TextField(
        controller: controller,
        autofocus: true,
        maxLength: 100,
        decoration: const InputDecoration(hintText: '例如：论文写作、旅行规划'),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () {
            if (controller.text.trim().isNotEmpty) {
              Navigator.pop(ctx, controller.text.trim());
            }
          },
          child: const Text('保存'),
        ),
      ],
    ),
  );
  // The dialog's exit animation still owns its TextField for this frame.
  await Future<void>.delayed(const Duration(milliseconds: 250));
  controller.dispose();
  if (name == null ||
      !context.mounted ||
      ref.read(activeProfileIdProvider) != profile ||
      ref.read(contextEpochProvider) != epoch) {
    return;
  }
  await projectAction(
    context,
    ref,
    (p, e) => bridgeApi.saveProject(p, project?.id, name, e),
  );
}

Future<void> moveChatToProject(
  BuildContext context,
  WidgetRef ref,
  String conversationId,
) async {
  final profile = ref.read(activeProfileIdProvider);
  final epoch = ref.read(contextEpochProvider);
  try {
    final projects = await ref.read(projectsProvider.future);
    if (!context.mounted ||
        ref.read(activeProfileIdProvider) != profile ||
        ref.read(contextEpochProvider) != epoch) {
      return;
    }
    final selected = await showDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('移动到项目'),
        children: [
          SimpleDialogOption(
            onPressed: () => Navigator.pop(ctx, ''),
            child: const Text('移出项目（普通聊天）'),
          ),
          ...projects.map(
            (p) => SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, p.id),
              child: Text(p.name),
            ),
          ),
          if (projects.isEmpty)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Text('请先在侧栏创建项目'),
            ),
        ],
      ),
    );
    if (selected == null ||
        !context.mounted ||
        ref.read(activeProfileIdProvider) != profile ||
        ref.read(contextEpochProvider) != epoch) {
      return;
    }
    await projectAction(
      context,
      ref,
      (p, e) => bridgeApi.moveConversationToProject(
        p,
        conversationId,
        selected.isEmpty ? null : selected,
        e,
      ),
    );
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }
}

class ProjectGroups extends ConsumerWidget {
  const ProjectGroups({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final projects = ref.watch(projectsProvider);
    final profile = ref.watch(activeProfileIdProvider);
    final epoch = ref.watch(contextEpochProvider);
    return projects.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(12),
        child: LinearProgressIndicator(),
      ),
      error: (e, _) => ListTile(
        title: const Text('加载项目失败'),
        trailing: const Icon(Icons.refresh),
        onTap: () => ref.read(projectsProvider.notifier).refresh(),
      ),
      data: (items) => items.isEmpty
          ? const Padding(
              padding: EdgeInsets.fromLTRB(44, 8, 12, 16),
              child: Text('点击 +，按需求整理聊天'),
            )
          : Column(
              children: items
                  .map(
                    (project) => ExpansionTile(
                      key: ValueKey('$profile:$epoch:${project.id}'),
                      leading: const Icon(Icons.folder_outlined, size: 20),
                      title: Text(
                        project.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text('${project.conversations.length} 个聊天'),
                      trailing: PopupMenuButton<String>(
                        tooltip: '项目操作',
                        itemBuilder: (_) => const [
                          PopupMenuItem(value: 'chat', child: Text('新建项目聊天')),
                          PopupMenuItem(value: 'rename', child: Text('重命名项目')),
                          PopupMenuItem(value: 'delete', child: Text('删除分组')),
                        ],
                        onSelected: (action) async {
                          if (action == 'rename') {
                            await editProject(context, ref, project);
                            return;
                          }
                          if (action == 'delete') {
                            final confirmed = await showDialog<bool>(
                              context: context,
                              builder: (ctx) => AlertDialog(
                                title: const Text('删除项目分组？'),
                                content: const Text('聊天将保留，并移回普通聊天列表。'),
                                actions: [
                                  TextButton(
                                    onPressed: () => Navigator.pop(ctx, false),
                                    child: const Text('取消'),
                                  ),
                                  TextButton(
                                    onPressed: () => Navigator.pop(ctx, true),
                                    child: const Text('删除分组'),
                                  ),
                                ],
                              ),
                            );
                            if (confirmed != true ||
                                !context.mounted ||
                                ref.read(activeProfileIdProvider) != profile ||
                                ref.read(contextEpochProvider) != epoch) {
                              return;
                            }
                            await projectAction(
                              context,
                              ref,
                              (p, e) =>
                                  bridgeApi.deleteProject(p, project.id, e),
                            );
                          } else {
                            await projectAction(context, ref, (p, e) async {
                              final result = await bridgeApi.createProjectChat(
                                p,
                                project.id,
                                e,
                              );
                              checkCommand(result);
                              if (context.mounted &&
                                  ref.read(activeProfileIdProvider) == p &&
                                  ref.read(contextEpochProvider) == e) {
                                await ref
                                    .read(conversationsProvider.notifier)
                                    .refresh();
                              }
                              if (context.mounted &&
                                  ref.read(activeProfileIdProvider) == p &&
                                  ref.read(contextEpochProvider) == e) {
                                ref
                                    .read(activeConversationIdProvider.notifier)
                                    .state = result
                                    .idResult;
                                ref
                                        .read(activeStudyModeProvider.notifier)
                                        .state =
                                    'CHAT';
                                Navigator.pop(context);
                                context.go('/chat');
                              }
                              return result;
                            });
                          }
                        },
                      ),
                      children: [
                        if (project.conversations.isEmpty)
                          const ListTile(title: Text('暂无聊天，从项目操作中新建')),
                        ...project.conversations.map(
                          (chat) => ListTile(
                            title: Text(
                              chat.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            selected:
                                ref.watch(activeConversationIdProvider) ==
                                chat.id,
                            trailing: IconButton(
                              icon: const Icon(Icons.more_horiz),
                              tooltip: '更多操作',
                              onPressed: () => showChatMoreSheet(context, chat),
                            ),
                            onTap: () {
                              ref
                                  .read(activeConversationIdProvider.notifier)
                                  .state = chat
                                  .id;
                              ref.read(activeStudyModeProvider.notifier).state =
                                  chat.studyMode;
                              Navigator.pop(context);
                              context.go('/chat');
                            },
                          ),
                        ),
                      ],
                    ),
                  )
                  .toList(),
            ),
    );
  }
}
