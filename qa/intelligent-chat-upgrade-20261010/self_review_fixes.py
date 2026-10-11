from pathlib import Path
r=Path(__file__).resolve().parents[2]
def edit(path,old,new):
 p=r/path;s=p.read_text(encoding='utf-8');assert old in s,path;p.write_text(s.replace(old,new,1),encoding='utf-8')
edit('client/flutter_ui/test/chat_upgrade_stage2_test.dart',
"import 'package:flutter/material.dart';", "import 'package:flutter/material.dart';\nimport 'package:flutter_riverpod/flutter_riverpod.dart';")
edit('client/flutter_ui/test/chat_upgrade_stage2_test.dart',
'''Widget view(MessageDto m) => MaterialApp(
      home: Scaffold(body: MessagePhase(message: m)),
    );''', '''Widget view(MessageDto m) => ProviderScope(child: MaterialApp(
      home: Scaffold(body: MessagePhase(message: m)),
    ));''')
edit('client/flutter_ui/test/chat_upgrade_stage2_test.dart',
'''      MaterialApp(
        home: Scaffold(
          body: MessagePhase(''', '''      ProviderScope(child: MaterialApp(
        home: Scaffold(
          body: MessagePhase(''')
edit('client/flutter_ui/test/chat_upgrade_stage2_test.dart',
'''    expect(find.text('继续处理'), findsNothing);''', '''    expect(find.text('继续处理'), findsNothing);''')
# Close only the second fixture's ProviderScope.
p=r/'client/flutter_ui/test/chat_upgrade_stage2_test.dart';s=p.read_text(encoding='utf-8');s=s.replace("      ),\n    );\n    expect(find.text('继续处理')", "      )),\n    );\n    expect(find.text('继续处理')",1);p.write_text(s,encoding='utf-8')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''  int _lastSequence = 0;''', '''  int _lastSequence = 0;
  int _lifetime = 0;''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''      _displayPatches.clear();
      return;
    }
    for (final patch''', '''      return; // Initial snapshot may still be loading; keep cumulative replacements.
    }
    for (final patch''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''  Future<ConversationDetailDto?> build() async {
    _pendingRequest''', '''  Future<ConversationDetailDto?> build() async {
    _lifetime++;
    ref.listenSelf((_, next) {
      if (next.hasValue && _displayPatches.isNotEmpty && !_refreshing) scheduleMicrotask(_flushDisplay);
    });
    _pendingRequest''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''    final requestRef = ref;
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    if (convId == null) return;''',
'''    final requestRef = ref;
    final lifetime = _lifetime;
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    if (convId == null) return;''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''        if (!requestRef.mounted ||
            requestRef.read(activeProfileIdProvider) != profileId''',
'''        if (!requestRef.mounted || lifetime != _lifetime ||
            requestRef.read(activeProfileIdProvider) != profileId''')
edit('client/flutter_ui/lib/providers/study_providers.dart',
'''      if (requestRef.mounted) {
        _refreshing = false;''', '''      if (requestRef.mounted && lifetime == _lifetime) {
        _refreshing = false;''')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''    private val sequence = AtomicLong()''', '''    private val sequence = AtomicLong()
    private val watchVersion = AtomicLong()''')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''        requireProfile(profileId, expectedEpoch)
        observation?.cancel()''', '''        requireProfile(profileId, expectedEpoch)
        val watch = watchVersion.incrementAndGet()
        observation?.cancel()''')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''            val conv = ownedConversation(profileId, conversationId)
            chatService.addConversationReference''', '''            val conv = ownedConversation(profileId, conversationId)
            requireProfile(profileId, expectedEpoch)
            if (watch != watchVersion.get()) return SimpleCommandResult(success = true)
            chatService.addConversationReference''')
edit('client/flutter_ui/lib/widgets/message_phase.dart',
'''    final status = widget.conversationId == null''', '''    final status = widget.conversationId == null''')
edit('client/flutter_ui/lib/widgets/message_phase.dart',
'''        !(m.generationState == 'RUNNING' && noContent) &&''',
'''        !(m.generationState == 'RUNNING' && (noContent || status['processing'] is String)) &&''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''          conversationId: ref.read(activeConversationIdProvider),
          onResume: () async {''',
'''          conversationId: ref.read(activeConversationDetailProvider).value?.nodes.lastOrNull?.branches.lastOrNull?.id == message.id ? ref.read(activeConversationIdProvider) : null,
          onResume: ref.read(activeConversationDetailProvider).value?.nodes.lastOrNull?.branches.lastOrNull?.id != message.id ? null : () async {''')
