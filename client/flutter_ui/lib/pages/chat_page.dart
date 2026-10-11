import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import '../widgets/math_markdown.dart';
import '../bridge/chat_display_patch.dart';
import '../widgets/message_phase.dart';
import '../widgets/surface_workspace.dart';
import '../providers/frontend_feature_providers.dart';
import '../widgets/chat_composer.dart';
import '../widgets/chat_timeline.dart';
import '../widgets/attachment_options_sheet.dart';
import '../providers/image_creation_providers.dart';
import 'image_creation_page.dart';
import 'image_create_studio.dart';
import '../providers/app_settings_providers.dart';
import '../widgets/reasoning_view.dart';
import '../widgets/chat_activity.dart';
import '../widgets/interactive_surface.dart';
import '../widgets/interactive_surface_message.dart';
import '../widgets/surface_stream_preview.dart';
import '../widgets/enhanced_image_view.dart';
import '../widgets/enhanced_cards.dart';
import '../widgets/citations_sheet.dart';
import 'conversation_drawer.dart';

class ChatPage extends ConsumerStatefulWidget {
  const ChatPage({super.key});

  @override
  ConsumerState<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends ConsumerState<ChatPage>
    with WidgetsBindingObserver {
  final TextEditingController _textController = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  final Map<String, int> _feedback = {};
  bool _sending = false;
  int _sendRevision = 0;
  int _modeRevision = 0;
  int _attachmentRevision = 0;
  final List<String> _pendingAttachments = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _textController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(ref.read(activeConversationDetailProvider.notifier).refresh());
      ref.invalidate(providersConfigProvider);
      ref.invalidate(bootstrapProvider);
    }
  }

  Future<void> _handleSend() async {
    if (_sending) return;
    final text = _textController.text.trim();
    if (text.isEmpty && _pendingAttachments.isEmpty) return;
    final attachments = List<String>.of(_pendingAttachments);
    final profile = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final revision = ++_sendRevision;
    HapticFeedback.lightImpact();
    setState(() => _sending = true);
    try {
      await ref
          .read(activeConversationDetailProvider.notifier)
          .sendMessage(text, attachmentPaths: attachments);
      if (!mounted ||
          revision != _sendRevision ||
          ref.read(activeProfileIdProvider) != profile ||
          ref.read(contextEpochProvider) != epoch)
        return;
      if (_textController.text.trim() == text) _textController.clear();
      setState(() => _pendingAttachments.removeWhere(attachments.contains));
    } catch (error) {
      if (mounted && revision == _sendRevision)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('发送失败，草稿已保留：$error')));
    } finally {
      if (mounted && revision == _sendRevision)
        setState(() => _sending = false);
    }
  }

  Future<void> _selectMode(String mode) async {
    final profile = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final id = ref.read(activeConversationIdProvider);
    final selection = ref.read(conversationSelectionRevisionProvider);
    final revision = ++_modeRevision;
    bool current() =>
        mounted &&
        revision == _modeRevision &&
        ref.read(activeProfileIdProvider) == profile &&
        ref.read(contextEpochProvider) == epoch &&
        ref.read(activeConversationIdProvider) == id &&
        ref.read(conversationSelectionRevisionProvider) == selection;
    try {
      if (id != null)
        checkCommand(await bridgeApi.setStudyMode(profile, id, mode));
      if (current()) ref.read(activeStudyModeProvider.notifier).state = mode;
    } catch (error) {
      if (mounted && current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$error')));
    }
  }

  @override
  Widget build(BuildContext context) {
    void clearComposer() {
      _textController.clear();
      setState(() {
        _pendingAttachments.clear();
        _sendRevision++;
        _modeRevision++;
        _attachmentRevision++;
        _sending = false;
      });
    }

    ref.listen(activeProfileIdProvider, (previous, next) {
      if (previous != next) clearComposer();
    });
    ref.listen(contextEpochProvider, (previous, next) {
      if (previous != next) clearComposer();
    });
    ref.listen(conversationSelectionRevisionProvider, (previous, next) {
      if (previous != next) clearComposer();
    });
    final bootstrapAsync = ref.watch(bootstrapProvider);
    if (bootstrapAsync.isLoading &&
        !bootstrapAsync.hasValue &&
        !bootstrapAsync.hasError) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (bootstrapAsync.hasError) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('初始化失败：${bootstrapAsync.error}'),
              TextButton(
                onPressed: () => ref.invalidate(bootstrapProvider),
                child: const Text('重试'),
              ),
            ],
          ),
        ),
      );
    }
    ref.watch(
      activeConversationDetailProvider.select(
        (value) =>
            ChatLayoutSnapshot(value.value, value.isLoading, value.error),
      ),
    );
    final convDetailAsync = ref.read(activeConversationDetailProvider);
    final bootstrap = ref.watch(bootstrapProvider).value;
    final studyMode = ref.watch(activeStudyModeProvider);

    return Scaffold(
      drawer: const ConversationDrawer(),
      appBar: AppBar(
        leading: Builder(
          builder: (context) => IconButton(
            icon: const Icon(Icons.menu, size: 24),
            color: StudyPalette.of(context).textBlack,
            onPressed: () => Scaffold.of(context).openDrawer(),
            tooltip: '历史对话',
          ),
        ),
        title: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () => _showModePicker(context),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  _modeLabel(studyMode),
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
                const SizedBox(width: 4),
                Icon(
                  Icons.keyboard_arrow_down,
                  size: 16,
                  color: StudyPalette.of(context).textGray,
                ),
              ],
            ),
          ),
        ),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.edit_square, size: 23),
            color: StudyPalette.of(context).textBlack,
            onPressed: () {
              ref.read(activeConversationIdProvider.notifier).state = null;
              ref.read(activeStudyModeProvider.notifier).state = 'CHAT';
            },
            tooltip: '新建对话',
          ),
        ],
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      ),
      body: SurfaceWorkspace(
        identity:
            '${ref.read(activeProfileIdProvider)}:${ref.read(contextEpochProvider)}:${convDetailAsync.value?.id}:${convDetailAsync.value?.nodes.map((n) => '${n.nodeId}/${n.selectIndex}').join(',')}',
        child: Stack(
          children: [
            Column(
              children: [
                Expanded(
                  child: convDetailAsync.when(
                    skipLoadingOnRefresh: false,
                    skipLoadingOnReload: false,
                    loading: () => Center(
                      child: CircularProgressIndicator(
                        color: StudyPalette.of(context).textBlack,
                      ),
                    ),
                    error: (err, _) => Center(child: Text('加载错误: $err')),
                    data: (detail) {
                      if (detail == null ||
                          (detail.nodes.isEmpty && !detail.isGenerating)) {
                        return _buildEmptyState(context);
                      }
                      return _buildMessageList(context, detail);
                    },
                  ),
                ),
                if (_sending)
                  Padding(
                    padding: const EdgeInsets.all(6),
                    child: ChatActivity(
                      label: '正在准备回复',
                      reduceMotion: ref.watch(reduceMotionSettingProvider),
                    ),
                  ),
                _buildInputArea(
                  context,
                  convDetailAsync.value?.isGenerating ?? false,
                  bootstrap,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return LayoutBuilder(
      builder: (context, bounds) => SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: (bounds.maxHeight - 48).clamp(0, double.infinity),
          ),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Text(
                  '今天想聊些什么？',
                  style: TextStyle(
                    fontSize: 29,
                    fontWeight: FontWeight.w700,
                    color: StudyPalette.of(context).textBlack,
                    height: 1.3,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(
                  '提问、读文件，或把想法变成作品',
                  style: TextStyle(
                    fontSize: 16,
                    color: StudyPalette.of(context).textGray,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 28),
                Row(
                  children: [
                    Expanded(
                      child: _buildQuickPromptCard(
                        '总结一份文件',
                        Icons.description_outlined,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _buildQuickPromptCard(
                        '帮我写点东西',
                        Icons.edit_outlined,
                      ),
                    ),
                  ],
                ),
                _buildQuickPromptCard('解释一个概念', Icons.lightbulb_outline),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildQuickPromptCard(String prompt, IconData icon) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () {
          _textController.text = prompt;
          _focusNode.requestFocus();
        },
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: StudyPalette.of(context).bgWhite,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: StudyPalette.of(context).borderGray,
              width: 1,
            ),
          ),
          child: Row(
            children: [
              Icon(icon, size: 22, color: StudyPalette.of(context).textBlack),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  prompt,
                  style: TextStyle(
                    fontSize: 14,
                    color: StudyPalette.of(context).textBlack,
                    height: 1.4,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMessageList(BuildContext context, ConversationDetailDto detail) {
    final needsActivity =
        detail.isGenerating &&
        !detail.nodes.any(
          (node) =>
              node.branches.isNotEmpty &&
              node
                      .branches[node.selectIndex.toInt().clamp(
                        0,
                        node.branches.length - 1,
                      )]
                      .generationState ==
                  'RUNNING',
        );
    return ChatTimeline(
      key: ValueKey((
        detail.profileId,
        detail.id,
        ref.watch(contextEpochProvider),
        ref.watch(conversationSelectionRevisionProvider),
      )),
      autoFollow: ref.watch(autoFollowScrollProvider),
      itemCount: detail.nodes.length + (needsActivity ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == detail.nodes.length) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 10),
            child: ChatActivity(
              label: '正在思考',
              reduceMotion: ref.watch(reduceMotionSettingProvider),
            ),
          );
        }
        final node = detail.nodes[index];
        if (node.branches.isEmpty) return const SizedBox.shrink();
        final selectedIndex = node.selectIndex.toInt().clamp(
          0,
          node.branches.length - 1,
        );
        final message = node.branches.isNotEmpty
            ? node.branches[selectedIndex]
            : null;
        if (message == null) return const SizedBox.shrink();

        return Consumer(
          key: ValueKey('message:${node.nodeId}'),
          builder: (context, rowRef, _) {
            final liveNode = rowRef.watch(
              activeConversationDetailProvider.select(
                (value) => value.value?.nodes
                    .where((n) => n.nodeId == node.nodeId)
                    .firstOrNull,
              ),
            );
            if (liveNode == null || liveNode.branches.isEmpty)
              return const SizedBox.shrink();
            final live =
                liveNode.branches[liveNode.selectIndex.toInt().clamp(
                  0,
                  liveNode.branches.length - 1,
                )];
            return _buildMessageItem(
              context,
              liveNode,
              live,
              live.role.toLowerCase() == 'user',
            );
          },
        );
      },
    );
  }

  Widget _buildMessageItem(
    BuildContext context,
    MessageNodeDto node,
    MessageDto message,
    bool isUser,
  ) {
    final imageTask = ref
        .watch(imageTasksProvider)
        .value
        ?.where(
          (t) =>
              t.messageId == message.id &&
              t.conversationId == ref.read(activeConversationIdProvider),
        )
        .firstOrNull;
    if (!isUser && imageTask != null) return ImageCreationCard(task: imageTask);
    return MessageArrival(
      animate:
          message.generationState == 'RUNNING' &&
          !ref.read(reduceMotionSettingProvider) &&
          !MediaQuery.disableAnimationsOf(context),
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: Padding(
            padding: EdgeInsets.only(bottom: isUser ? 24 : 28),
            child: isUser
                ? _buildUserMessage(context, node, message)
                : _buildAssistantMessage(context, node, message),
          ),
        ),
      ),
    );
  }

  Widget _buildUserMessage(
    BuildContext context,
    MessageNodeDto node,
    MessageDto message,
  ) {
    return Align(
      alignment: Alignment.centerRight,
      child: GestureDetector(
        onLongPress: () => _editMessage(message),
        child: Container(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.88,
          ),
          margin: const EdgeInsets.only(left: 48),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 13),
          decoration: BoxDecoration(
            color: StudyPalette.of(context).userBubble,
            borderRadius: BorderRadius.circular(20),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...message.parts.map(
                (part) => _buildMessagePart(context, part, message.id),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAssistantMessage(
    BuildContext context,
    MessageNodeDto node,
    MessageDto message,
  ) {
    final sources = messageCitations(message);
    final surfaceReference = _surfaceEditReference(message);
    final latestNode = ref
        .read(activeConversationDetailProvider)
        .value
        ?.nodes
        .lastOrNull;
    final isLatest =
        latestNode != null &&
        latestNode.selectIndex >= 0 &&
        latestNode.selectIndex < latestNode.branches.length &&
        latestNode.branches[latestNode.selectIndex].id == message.id;
    final firstReasoning = message.parts.indexWhere(
      (part) => part.type == 'reasoning',
    );
    final lastReasoning = message.parts.lastIndexWhere(
      (part) => part.type == 'reasoning',
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...message.parts.asMap().entries.map(
          (entry) => entry.value.type == 'reasoning'
              ? entry.key == firstReasoning
                    ? ReasoningView(
                        key: ValueKey('reasoning:${message.id}'),
                        content: messageReasoning(message),
                        status: reasoningStatus(message, lastReasoning),
                        reduceMotion: ref.watch(reduceMotionSettingProvider),
                      )
                    : const SizedBox.shrink()
              : _buildMessagePart(
                  context,
                  entry.value,
                  message.id,
                  generationState: message.generationState,
                ),
        ),
        if (message.generationState == 'COMPLETED')
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Wrap(
              spacing: 8,
              children: [
                if (surfaceReference != null)
                  ActionChip(
                    label: const Text('基于当前成果修改'),
                    onPressed: () {
                      _textController.text = surfaceReference;
                      _focusNode.requestFocus();
                    },
                  ),
                if (messageCitations(message).isNotEmpty)
                  ActionChip(
                    label: const Text('查看来源'),
                    onPressed: () => CitationsSheet.show(
                      context,
                      citations: messageCitations(message),
                    ),
                  ),
              ],
            ),
          ),
        MessagePhase(
          message: message,
          conversationId: isLatest
              ? ref.read(activeConversationIdProvider)
              : null,
          onResume: !isLatest
              ? null
              : () async {
                  await FrontendFeatures.fromWidget<void>(ref, 'resumeChat', {
                    'conversationId': ref.read(activeConversationIdProvider),
                    'messageId': message.id,
                  });
                  await ref
                      .read(activeConversationDetailProvider.notifier)
                      .refresh();
                },
          onRetry: () => ref
              .read(activeConversationDetailProvider.notifier)
              .retryMessage(node.nodeId),
        ),
        const SizedBox(height: 10),
        if (sources.isNotEmpty)
          TextButton.icon(
            icon: const Icon(Icons.public, size: 16),
            label: Text('${sources.length} 个来源'),
            onPressed: () => CitationsSheet.show(context, citations: sources),
          ),
        Wrap(
          spacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            _buildActionButton(
              context,
              icon: Icons.image_outlined,
              tooltip: '用这些内容制作配图',
              onPressed: () => openImageStudio(
                context,
                ref,
                conversationId: ref.read(activeConversationIdProvider),
                prompt:
                    '根据以下内容制作配图，用清晰的视觉表达核心主题：\n${message.parts.where((p) => p.type == 'text').map((p) => p.text ?? '').join('\n\n').characters.take(6500).toString()}',
              ),
            ),
            _buildActionButton(
              context,
              icon: Icons.content_copy_outlined,
              tooltip: '复制',
              onPressed: () {
                Clipboard.setData(
                  ClipboardData(
                    text: message.parts
                        .where((p) => p.type == 'text')
                        .map((p) => p.text ?? '')
                        .join('\n\n'),
                  ),
                );
                ScaffoldMessenger.of(context)
                    .showSnackBar(const SnackBar(content: Text('已复制回答')));
              },
            ),
            _buildActionButton(
              context,
              icon: _feedback[message.id] == 1
                  ? Icons.thumb_up
                  : Icons.thumb_up_outlined,
              tooltip: '标记有帮助',
              onPressed: () => setState(
                () =>
                    _feedback[message.id] = _feedback[message.id] == 1 ? 0 : 1,
              ),
            ),
            _buildActionButton(
              context,
              icon: _feedback[message.id] == -1
                  ? Icons.thumb_down
                  : Icons.thumb_down_outlined,
              tooltip: '标记需改进',
              onPressed: () => setState(
                () => _feedback[message.id] = _feedback[message.id] == -1
                    ? 0
                    : -1,
              ),
            ),
            if (message.generationState != 'RUNNING' &&
                !hasUncertainTools(message))
              _buildActionButton(
                context,
                icon: Icons.refresh_rounded,
                tooltip: '重新生成',
                onPressed: () => ref
                    .read(activeConversationDetailProvider.notifier)
                    .retryMessage(node.nodeId),
              ),
            PopupMenuButton<String>(
              tooltip: '更多',
              icon: Icon(
                Icons.more_horiz,
                size: 20,
                color: StudyPalette.of(context).textGray,
              ),
              onSelected: (action) {
                if (action == 'sources')
                  CitationsSheet.show(
                    context,
                    citations: messageCitations(message),
                  );
              },
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'sources', child: Text('来源引用')),
              ],
            ),
            if (node.branches.length > 1) _buildBranchSelector(node),
          ],
        ),
      ],
    );
  }

  String? _surfaceEditReference(MessageDto message) {
    for (final part in message.parts.reversed) {
      if (part.type != 'tool' || part.executionState != 'COMPLETED') continue;
      try {
        final output = jsonDecode(part.toolOutput ?? '') as Map;
        if (output['kind'] != 'interactive_surface' ||
            output['revision'] is! String ||
            output['definition'] is! Map)
          continue;
        return '请调用 revise_interactive_surface 修改当前成果，生成可预览的新版并保留旧版，不要在正文输出 JSON（message_id=${message.id} tool_call_id=${part.toolCallId} expected_revision=${output['revision']}）。修改要求：';
      } catch (_) {}
    }
    return null;
  }

  Widget _buildActionButton(
    BuildContext context, {
    required IconData icon,
    required String tooltip,
    required VoidCallback onPressed,
  }) {
    return IconButton(
      icon: Icon(icon, size: 17),
      padding: const EdgeInsets.all(8),
      constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
      color: StudyPalette.of(context).textGray,
      onPressed: onPressed,
      tooltip: tooltip,
    );
  }

  Widget _buildBranchSelector(MessageNodeDto node) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          icon: const Icon(Icons.chevron_left, size: 18),
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
          color: StudyPalette.of(context).textGray,
          onPressed: node.selectIndex > 0
              ? () => ref
                    .read(activeConversationDetailProvider.notifier)
                    .switchBranch(node.nodeId, (node.selectIndex - 1).toInt())
              : null,
        ),
        Text(
          '${node.selectIndex + 1} / ${node.branches.length}',
          style: TextStyle(
            fontSize: 12,
            color: StudyPalette.of(context).textGray,
          ),
        ),
        IconButton(
          icon: const Icon(Icons.chevron_right, size: 18),
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
          color: StudyPalette.of(context).textGray,
          onPressed: node.selectIndex < node.branches.length - 1
              ? () => ref
                    .read(activeConversationDetailProvider.notifier)
                    .switchBranch(node.nodeId, (node.selectIndex + 1).toInt())
              : null,
        ),
      ],
    );
  }

  Future<void> _editMessage(MessageDto message) async {
    final profile = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final conversation = ref.read(activeConversationIdProvider);
    final selection = ref.read(conversationSelectionRevisionProvider);
    if (conversation == null) return;
    final controller = TextEditingController(
      text: message.parts
          .where((p) => p.type == 'text')
          .map((p) => p.text ?? '')
          .join('\n'),
    );
    final text = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('编辑消息'),
        content: TextField(
          controller: controller,
          minLines: 3,
          maxLines: 8,
          maxLength: 100000,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('保存新分支'),
          ),
        ],
      ),
    );
    controller.dispose();
    bool current() =>
        mounted &&
        ref.read(activeProfileIdProvider) == profile &&
        ref.read(contextEpochProvider) == epoch &&
        ref.read(activeConversationIdProvider) == conversation &&
        ref.read(conversationSelectionRevisionProvider) == selection;
    if (text == null || text.trim().isEmpty || !current()) return;
    try {
      checkCommand(
        await bridgeApi.editMessage(
          profile,
          conversation,
          message.id,
          text,
          'edit_${DateTime.now().microsecondsSinceEpoch}',
          epoch,
        ),
      );
      if (current()) {
        await ref.read(activeConversationDetailProvider.notifier).refresh();
        await ref.read(conversationsProvider.notifier).refresh();
      }
    } catch (error) {
      if (mounted && current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$error')));
    }
  }

  Future<void> _artifactAction(
    String id,
    String action, [
    String? fileName,
  ]) async {
    final owner = ref.read(activeProfileIdProvider);
    try {
      final result = switch (action) {
        'save' => await bridgeApi.saveArtifact(owner, id),
        'share' => await bridgeApi.shareArtifact(owner, id),
        _ => await bridgeApi.openArtifact(owner, id),
      };
      checkCommand(result);
      if (mounted && action == 'save')
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('已保存并校验文件')));
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Future<void> _documentAction(
    String messageId,
    String toolCall,
    String action,
  ) async {
    try {
      checkCommand(
        await bridgeApi.documentTaskAction(
          ref.read(activeProfileIdProvider),
          ref.read(activeConversationIdProvider)!,
          messageId,
          toolCall,
          action,
          ref.read(contextEpochProvider),
        ),
      );
      if (mounted)
        await ref.read(activeConversationDetailProvider.notifier).refresh();
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Widget _buildMessagePart(
    BuildContext context,
    MessagePartDto part,
    String messageId, {
    String? generationState,
  }) {
    switch (part.type) {
      case 'text':
        return MathMarkdownView(
          content: part.text ?? '',
          streaming: generationState == 'RUNNING',
          onAskSelection: (selected) {
            _textController.text =
                '请解释这条消息中的选中内容（消息 ID：$messageId）：\n$selected';
            _focusNode.requestFocus();
          },
        );

      case 'file':
        return EnhancedFileCard(
          part: part,
          onOpen: part.artifactId != null
              ? () => _artifactAction(part.artifactId!, 'open', part.fileName)
              : null,
          onSave: part.artifactId != null
              ? () => _artifactAction(part.artifactId!, 'save', part.fileName)
              : null,
          onShare: part.artifactId != null
              ? () => _artifactAction(part.artifactId!, 'share', part.fileName)
              : null,
        );

      case 'image':
        return EnhancedImageView(
          part: part,
          messageId: messageId,
          onAskAboutImage: () {
            // Set input text to ask about this image
            _textController.text = '请详细解释这张图片的内容';
            _focusNode.requestFocus();
          },
        );

      case 'interactive_surface':
        // 智能交互界面 - 新的消息类型
        if (part.surfaceId != null && part.surfaceDefinition != null) {
          try {
            final profile = ref.read(activeProfileIdProvider);
            final conversation = ref.read(activeConversationIdProvider);
            final epoch = ref.read(contextEpochProvider);

            if (conversation != null) {
              return InteractiveSurfaceMessage(
                key: ValueKey('surface:${part.surfaceId}'),
                profileId: profile,
                conversationId: conversation,
                messageId: messageId,
                surfaceId: part.surfaceId!,
                title: part.surfaceTitle ?? '交互界面',
                definitionJson: part.surfaceDefinition!,
                revision: '${part.surfaceRevision ?? 0}',
                contextEpoch: epoch,
              );
            }
          } catch (e) {
            return Card(
              color: Theme.of(context).colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text('智能界面加载失败: $e'),
              ),
            );
          }
        }
        return const Card(
          child: Padding(padding: EdgeInsets.all(12), child: Text('智能界面数据不完整')),
        );

      case 'tool':
        if (shouldPreviewSurface(part, generationState)) {
          return SurfaceStreamingPreview(
            key: ValueKey('preview:$messageId:${part.toolCallId}'),
            input: part.toolInput ?? '',
          );
        }
        // Document processing tasks with detailed progress
        if ([
              'parse_material_enhanced',
              'convert_material_to_pdf',
            ].contains(part.toolName) &&
            part.executionState == 'COMPLETED') {
          try {
            final task =
                jsonDecode(part.toolOutput ?? '') as Map<String, dynamic>;
            if (task['task_id'] is String) {
              return EnhancedToolCard(
                reduceMotion: ref.watch(reduceMotionSettingProvider),
                part: part,
                messageId: messageId,
                onRetry: () =>
                    _documentAction(messageId, part.toolCallId!, 'retry'),
                onCancel:
                    ['QUEUED', 'RUNNING', 'SUBMITTING'].contains(task['state'])
                    ? () =>
                          _documentAction(messageId, part.toolCallId!, 'cancel')
                    : null,
              );
            }
          } catch (_) {
            /* Keep readable native tool output. */
          }
        }

        // Interactive UI rendering
        if ([
              'render_interactive_ui',
              'create_interactive_surface',
              'render_math_plot',
              'revise_interactive_surface',
            ].contains(part.toolName) &&
            part.executionState == 'COMPLETED') {
          try {
            final output =
                jsonDecode(part.toolOutput ?? '') as Map<String, dynamic>;
            if (output['kind'] == 'interactive_surface' &&
                output['revision'] is String) {
              final rawDefinition = jsonEncode(output['definition']);
              SurfaceDefinition.parse(
                Map<String, dynamic>.from(output['definition'] as Map),
              );
              final profile = ref.read(activeProfileIdProvider);
              final conversation = ref.read(activeConversationIdProvider)!;
              final epoch = ref.read(contextEpochProvider);
              final toolCall = part.toolCallId!;
              final revision = output['revision'] as String;
              return InteractiveSurfaceMessage(
                key: ValueKey(
                  '$profile:$conversation:$messageId:$toolCall:$revision:$epoch',
                ),
                profileId: profile,
                conversationId: conversation,
                messageId: messageId,
                surfaceId: toolCall,
                title: output['definition']['title'] as String,
                revised: output['parent'] is Map,
                definitionJson: rawDefinition,
                revision: revision,
                contextEpoch: epoch,
                onSubmitted: () async {
                  if (mounted &&
                      ref.read(activeConversationIdProvider) == conversation &&
                      ref.read(activeProfileIdProvider) == profile &&
                      ref.read(contextEpochProvider) == epoch) {
                    await ref
                        .read(activeConversationDetailProvider.notifier)
                        .refresh();
                  }
                },
              );
            }
          } catch (_) {
            // Keep the original readable tool result below for malformed/unsupported definitions.
          }
        }

        // Generic tool display with approval handling
        return EnhancedToolCard(
          reduceMotion: ref.watch(reduceMotionSettingProvider),
          part: part,
          messageId: messageId,
          onApprove: part.approvalState?.toLowerCase() == 'pending'
              ? () => ref
                    .read(activeConversationDetailProvider.notifier)
                    .approveTool(part.toolCallId ?? '', true)
              : null,
          onDeny: part.approvalState?.toLowerCase() == 'pending'
              ? () => ref
                    .read(activeConversationDetailProvider.notifier)
                    .approveTool(part.toolCallId ?? '', false)
              : null,
        );

      case 'reasoning':
        return ReasoningView(
          content: part.reasoning ?? '',
          status: ReasoningStatus.completed,
        );

      default:
        return Text(part.text ?? '');
    }
  }

  Future<void> _attachFile() async {
    final profile = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final selection = ref.read(conversationSelectionRevisionProvider);
    final revision = ++_attachmentRevision;
    bool current() =>
        mounted &&
        revision == _attachmentRevision &&
        ref.read(activeProfileIdProvider) == profile &&
        ref.read(contextEpochProvider) == epoch &&
        ref.read(conversationSelectionRevisionProvider) == selection;
    final option = await AttachmentOptionsSheet.show(context);
    if (option == null || !current()) return;
    if (option == AttachmentSourceType.createImage) {
      ref.read(imageModeProvider.notifier).set(true);
      return;
    }
    try {
      final paths = await bridgeApi.pickAttachments(profile, epoch);
      if (current()) setState(() => _pendingAttachments.addAll(paths));
    } catch (error) {
      if (current())
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$error')));
    }
  }

  Widget _buildInputArea(
    BuildContext context,
    bool isGenerating,
    StudyBootstrapDto? bootstrap,
  ) {
    if (ref.watch(imageModeProvider)) {
      final profile = ref.watch(activeProfileIdProvider),
          epoch = ref.watch(contextEpochProvider);
      final conversation = ref.watch(activeConversationIdProvider);
      return ImageCreationInput(
        key: ValueKey(('image-input', profile, epoch, conversation)),
        conversationId: conversation,
        initialPrompt: _textController.text,
        onExit: () => ref.read(imageModeProvider.notifier).set(false),
        onSubmitted: (task) {
          _textController.clear();
          ref.read(activeConversationIdProvider.notifier).state =
              task.conversationId;
        },
      );
    }
    final empty =
        ref.read(activeConversationDetailProvider).value?.nodes.isEmpty ?? true;
    return ChatComposer(
      controller: _textController,
      focusNode: _focusNode,
      hint: empty ? '向 MathStudyAgent 发送消息' : '继续追问，或添加文件',
      busy: _sending,
      hasAttachments: _pendingAttachments.isNotEmpty,
      onSend: _handleSend,
      onStop: isGenerating
          ? () => ref
                .read(activeConversationDetailProvider.notifier)
                .stopGeneration()
          : null,
      onAttach: _attachFile,
      showDisclaimer: true,
      attachments: _pendingAttachments.isEmpty
          ? null
          : Wrap(
              spacing: 6,
              runSpacing: 6,
              children: _pendingAttachments
                  .map(
                    (path) => Chip(
                      avatar: const Icon(Icons.description_outlined, size: 18),
                      label: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 200),
                        child: Text(
                          path.split(RegExp(r'[/\\]')).last,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                      deleteIcon: const Icon(Icons.close, size: 16),
                      onDeleted: _sending
                          ? null
                          : () => setState(
                              () => _pendingAttachments.remove(path),
                            ),
                    ),
                  )
                  .toList(),
            ),
    );
  }

  void _showModePicker(BuildContext context) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '选择聊天模式',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 14),
                  _modeTile('CHAT', '通用聊天', '直接处理当前请求'),
                  _modeTile('GUIDED', '引导模式', '温和提示，分步指引核心思路'),
                  _modeTile('HINT', '逐步提示', '以反问和概念核对激发独立推导'),
                  _modeTile('CHECK', '检查步骤', '核对内容、推理与操作步骤'),
                  _modeTile('EXPLAIN', '完整讲解', '展开背景、方法与结论'),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _modeTile(String mode, String title, String subtitle) {
    final current = ref.watch(activeStudyModeProvider);
    final isSelected = current == mode;

    return ListTile(
      leading: Icon(
        isSelected ? Icons.radio_button_checked : Icons.radio_button_off,
        color: isSelected
            ? StudyPalette.of(context).textBlack
            : StudyPalette.of(context).textGray,
      ),
      title: Text(
        title,
        style: TextStyle(
          fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
        ),
      ),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
      onTap: () {
        unawaited(_selectMode(mode));
        Navigator.pop(context);
      },
    );
  }

  String _modeLabel(String mode) {
    switch (mode) {
      case 'CHAT':
        return 'Chat';
      case 'HINT':
        return '逐步提示';
      case 'CHECK':
        return '检查步骤';
      case 'EXPLAIN':
        return '完整讲解';
      case 'GUIDED':
      default:
        return '引导模式';
    }
  }
}
