import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/study_providers.dart';
import '../widgets/interactive_surface.dart';
import '../theme/study_theme.dart';
import 'context_follow_up.dart';
import 'surface_workspace.dart';

/// 智能交互界面消息组件
///
/// 渲染模型生成的动态可交互界面,包括图表、表格、滑块等组件。
/// 用户操作后可以继续与AI对话。
class InteractiveSurfaceMessage extends ConsumerStatefulWidget {
  final String profileId;
  final String conversationId;
  final String messageId;
  final String surfaceId;
  final String title;
  final String definitionJson;
  final String revision;
  final int contextEpoch;
  final Future<void> Function()? onSubmitted;
  final bool preview, revised;
  final SurfaceSessionController? sessionController;

  const InteractiveSurfaceMessage({
    super.key,
    required this.profileId,
    required this.conversationId,
    required this.messageId,
    required this.surfaceId,
    required this.title,
    required this.definitionJson,
    required this.revision,
    required this.contextEpoch,
    this.onSubmitted,
    this.preview = false,
    this.revised = false,
    this.sessionController,
  });

  @override
  ConsumerState<InteractiveSurfaceMessage> createState() =>
      _InteractiveSurfaceMessageState();
}

class _InteractiveSurfaceMessageState
    extends ConsumerState<InteractiveSurfaceMessage> {
  late SurfaceDefinition definition;
  String? error;
  final inlineSession = SurfaceSessionController();

  @override
  void initState() {
    super.initState();
    _parseDefinition();
  }

  @override
  void didUpdateWidget(covariant InteractiveSurfaceMessage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.definitionJson != widget.definitionJson) _parseDefinition();
  }

  void _parseDefinition() {
    try {
      final json = jsonDecode(widget.definitionJson) as Map<String, dynamic>;
      definition = SurfaceDefinition.parse(json);
      error = null;
    } catch (e) {
      error = '暂时无法显示此界面的内容，请让 AI 重新生成。';
    }
  }

  @override
  Widget build(BuildContext context) {
    if (error != null) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: StudyPalette.of(context).bgWhite,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: StudyPalette.of(context).errorRed.withValues(alpha: 0.3),
            width: 1,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    Icons.error_outline,
                    color: StudyPalette.of(context).errorRed,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '智能界面加载失败',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: StudyPalette.of(context).errorRed,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                error!,
                style: TextStyle(
                  fontSize: 12,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '这可能是因为界面定义格式不正确。你可以尝试重新生成界面。',
                style: TextStyle(
                  fontSize: 11,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
            ],
          ),
        ),
      );
    }

    final source = widget;
    final scope = SurfaceWorkspaceScope.maybeOf(context);
    final identity =
        '${source.messageId}:${source.surfaceId}:${source.revision}';
    if (!source.preview && scope?.selected == identity) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Text('${source.title} · 已在预览区打开'),
      );
    }
    final view = InteractiveSurfaceView(
      key: ValueKey(
        '${source.profileId}:${source.conversationId}:${source.messageId}:${source.surfaceId}:${source.revision}:${source.contextEpoch}',
      ),
      definition: definition,
      sessionController: source.sessionController ?? inlineSession,
      allowExpand: !source.preview,
      followUpBuilder: (context, values, beforeSend, onSent) => ContextFollowUp(
        hint: '针对当前内容继续追问',
        contextTextBuilder: () =>
            '${source.title}；成果引用 message_id=${source.messageId} tool_call_id=${source.surfaceId} expected_revision=${source.revision}；当前筛选和参数：${jsonEncode(values())}',
        beforeSend: beforeSend,
        onSent: onSent,
      ),
      load: () async {
        final stateJson = await bridgeApi.getSurfaceState(
          source.profileId,
          source.conversationId,
          source.messageId,
          source.surfaceId,
          source.revision,
        );
        return Map<String, dynamic>.from(jsonDecode(stateJson) as Map);
      },
      save: (values, action, requestId) async {
        checkCommand(
          await bridgeApi.surfaceAction(
            source.profileId,
            source.conversationId,
            source.messageId,
            source.surfaceId,
            source.revision,
            jsonEncode(values),
            action,
            requestId,
            source.contextEpoch,
          ),
        );
        if (action != null) await source.onSubmitted?.call();
      },
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (source.revised)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('修改版 · 原版保留在聊天记录'),
          ),
        if (!source.preview &&
            scope != null &&
            MediaQuery.sizeOf(context).width >= 1000)
          TextButton.icon(
            icon: const Icon(Icons.view_sidebar_outlined),
            label: const Text('并排预览'),
            onPressed: () async {
              if (!await inlineSession.flush() || !mounted) return;
              final session = SurfaceSessionController();
              await scope.open(
                identity,
                (close) => InteractiveSurfaceMessage(
                  profileId: source.profileId,
                  conversationId: source.conversationId,
                  messageId: source.messageId,
                  surfaceId: source.surfaceId,
                  title: source.title,
                  definitionJson: source.definitionJson,
                  revision: source.revision,
                  contextEpoch: source.contextEpoch,
                  preview: true,
                  revised: source.revised,
                  sessionController: session,
                  onSubmitted: () async {
                    await source.onSubmitted?.call();
                    close();
                  },
                ),
                session.flush,
              );
            },
          ),
        view,
      ],
    );
  }
}

/// 智能界面工具栏(可选)
/// 显示界面信息和操作按钮
class SurfaceToolbar extends StatelessWidget {
  final String title;
  final int revision;
  final VoidCallback? onRefresh;

  const SurfaceToolbar({
    super.key,
    required this.title,
    required this.revision,
    this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
      ),
      child: Row(
        children: [
          Icon(
            Icons.dashboard_customize,
            size: 18,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              title,
              style: Theme.of(context).textTheme.titleSmall,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (onRefresh != null) ...[
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.refresh, size: 18),
              onPressed: onRefresh,
              tooltip: '重新加载',
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(),
            ),
          ],
        ],
      ),
    );
  }
}
