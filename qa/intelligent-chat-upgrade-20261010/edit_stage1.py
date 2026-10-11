from pathlib import Path
root = Path(__file__).resolve().parents[2]
def edit(path, old, new):
    p=root/path
    s=p.read_text(encoding='utf-8')
    assert old in s, path
    p.write_text(s.replace(old,new,1),encoding='utf-8')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''            observation = scope.launch {
                combine(chatService.getConversationFlow(conv.id), chatService.getGenerationJobStateFlow(conv.id), documents.changes) { current, _, _ -> current }
                    .collect { emitEvent("conversation_changed", profileId, mapOf("conversationId" to conversationId)) }
            }''',
'''            observation = scope.launch {
                var previous: Conversation? = null
                var wasGenerating: Boolean? = null
                var documentVersion: Any? = null
                combine(chatService.getConversationFlow(conv.id), chatService.getGenerationJobStateFlow(conv.id), documents.changes) { current, job, documents -> Triple(current, job.isActive, documents) }
                    .collect { (current, generating, documents) ->
                        requireProfile(profileId, expectedEpoch)
                        val patches = if (generating == wasGenerating && documentVersion == documents) ChatDisplayPatch.between(previous, current) else null
                        if (patches == null) emitEvent("conversation_changed", profileId, mapOf("conversationId" to conversationId))
                        else if (patches.isNotEmpty()) emitEvent("message_patch", profileId, mapOf("conversationId" to conversationId, "changes" to patches))
                        previous = current
                        wasGenerating = generating
                        documentVersion = documents
                    }
            }''')
edit('client/flutter_ui/lib/providers/study_providers.dart',"import 'dart:async';", "import 'dart:async';\nimport '../bridge/chat_display_patch.dart';")
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''  SendMessageRequest? _pendingRequest;
  @override''',
'''  SendMessageRequest? _pendingRequest;
  Timer? _displayTimer;
  final List<Map> _displayPatches = [];
  int _lastSequence = 0;

  void _flushDisplay() {
    _displayTimer?.cancel();
    _displayTimer = null;
    if (_refreshing || !ref.mounted) return;
    var detail = state.value;
    if (detail == null) { _displayPatches.clear(); return; }
    for (final patch in _displayPatches) {
      detail = applyChatDisplayPatch(detail!, patch);
      if (detail == null) break;
    }
    _displayPatches.clear();
    if (detail == null) { unawaited(refresh()); }
    else { state = AsyncValue.data(detail); }
  }
  @override''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''    _pendingRequest = null;
    _refreshing = false;''',
'''    _pendingRequest = null;
    _displayTimer?.cancel();
    _displayPatches.clear();
    _lastSequence = 0;
    ref.onDispose(() { _displayTimer?.cancel(); _displayPatches.clear(); });
    _refreshing = false;''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''      if (event['type'] == 'conversation_changed' && (event['payload'] as Map?)?['conversationId'] == convId) {
        unawaited(refresh());
      }''',
'''      final sequence = event['sequence'] as int?;
      if (sequence != null && sequence <= _lastSequence) return;
      final gap = sequence != null && _lastSequence > 0 && sequence != _lastSequence + 1;
      if (sequence != null) _lastSequence = sequence;
      if ((event['payload'] as Map?)?['conversationId'] != convId) return;
      if (gap || event['type'] == 'conversation_changed') {
        _displayTimer?.cancel();
        _displayTimer = null;
        _displayPatches.clear();
        unawaited(refresh());
      } else if (event['type'] == 'message_patch') {
        _displayPatches.add(event['payload'] as Map);
        _displayTimer ??= Timer(const Duration(milliseconds: 40), _flushDisplay);
      }''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''    } finally { if (requestRef.mounted) _refreshing = false; }

  }''',
'''    } finally {
      if (requestRef.mounted) {
        _refreshing = false;
        if (_displayPatches.isNotEmpty) _flushDisplay();
      }
    }

  }''')
edit('client/flutter_ui/lib/pages/chat_page.dart',"import '../widgets/math_markdown.dart';", "import '../widgets/math_markdown.dart';\nimport '../bridge/chat_display_patch.dart';")
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''    setState(() => _sending = true);''', '''    HapticFeedback.lightImpact();
    setState(() => _sending = true);''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''    final convDetailAsync = ref.watch(activeConversationDetailProvider);''',
'''    ref.watch(activeConversationDetailProvider.select((value) =>
      ChatLayoutSnapshot(value.value, value.isLoading, value.error)));
    final convDetailAsync = ref.read(activeConversationDetailProvider);''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''        return _buildMessageItem(context, node, message, isUser);''',
'''        return Consumer(
          key: ValueKey('message:${node.nodeId}'),
          builder: (context, rowRef, _) {
            final liveNode = rowRef.watch(activeConversationDetailProvider.select((value) =>
              value.value?.nodes.where((n) => n.nodeId == node.nodeId).firstOrNull));
            if (liveNode == null || liveNode.branches.isEmpty) return const SizedBox.shrink();
            final live = liveNode.branches[liveNode.selectIndex.toInt().clamp(0, liveNode.branches.length - 1)];
            return _buildMessageItem(context, liveNode, live, live.role.toLowerCase() == 'user');
          },
        );''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''    return Padding(
      padding: EdgeInsets.only(bottom: isUser ? 24 : 28),''',
'''    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 720),
      child: Padding(
      padding: EdgeInsets.only(bottom: isUser ? 24 : 28),''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''          : _buildAssistantMessage(context, node, message),
    );''',
'''          : _buildAssistantMessage(context, node, message),
    )));
''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''        return MathMarkdownView(content: part.text ?? '');''',
'''        return MathMarkdownView(content: part.text ?? '', streaming: generationState == 'RUNNING');''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''        ref.watch(activeConversationDetailProvider).value?.nodes.isEmpty ??''',
'''        ref.read(activeConversationDetailProvider).value?.nodes.isEmpty ??''')
