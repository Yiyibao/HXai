import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/pages/chat_page.dart';

// Review probes assert observed defects, not desired behavior.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) {
    messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), handler);
  }
  MaterialFileDto material(String profile) => MaterialFileDto(id: profile, profileId: profile,
    name: '$profile-private.txt', extension: 'txt', state: 'SUCCEEDED', detail: '',
    size: 1, sha256: '', createdAt: 0, symbolCheckPending: false);
  test('Observed: late A refresh overwrites B material list', () async {
    final delayed = Completer<Object?>();
    var aCalls = 0;
    mock('getMaterials', (args) async {
      final profile = (args as List)[0] as String;
      if (profile == 'A' && ++aCalls == 2) return delayed.future;
      return [[material(profile)]];
    });
    final container = ProviderContainer();
    container.read(activeProfileIdProvider.notifier).state = 'A';
    final sub = container.listen(materialsProvider, (_, _) {});
    await container.read(materialsProvider.future);
    final refresh = container.read(materialsProvider.notifier).refresh();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await container.read(materialsProvider.future);
    expect(container.read(materialsProvider).value!.single.profileId, 'B');
    delayed.complete([[material('A')]]);
    await refresh;
    expect(container.read(activeProfileIdProvider), 'B');
    expect(container.read(materialsProvider).value!.single.profileId, 'A');
    sub.close(); container.dispose();
  });
  test('Observed: late A send installs A conversation while B active', () async {
    final delayed = Completer<Object?>();
    mock('sendMessage', (_) => delayed.future);
    mock('getConversationDetails', (args) async => [ConversationDetailDto(id: (args as List)[1] as String,
      profileId: args[0] as String, title: '', studyMode: 'CHAT', nodes: [],
      isGenerating: false, lifecycleRevision: 0)]);
    mock('getConversations', (_) async => [[]]);
    final container = ProviderContainer();
    container.read(activeProfileIdProvider.notifier).state = 'A';
    final notifier = container.read(activeConversationDetailProvider.notifier);
    final sending = notifier.sendMessage('A private message');
    await Future<void>.delayed(const Duration(milliseconds: 20));
    container.read(activeProfileIdProvider.notifier).state = 'B';
    container.read(activeConversationIdProvider.notifier).state = null;
    delayed.complete([SimpleCommandResult(success: true, idResult: 'A-conversation')]);
    await sending;
    expect(container.read(activeProfileIdProvider), 'B');
    expect(container.read(activeConversationIdProvider), 'A-conversation');
    await Future<void>.delayed(const Duration(milliseconds: 50));
    container.dispose();
  });
  test('Observed: bootstrap switch does not reset study mode', () async {
    var profile = 'A';
    mock('getBootstrap', (_) async => [StudyBootstrapDto(currentProfileId: profile,
      contextEpoch: profile == 'A' ? 1 : 2, profiles: [],
      studyMode: profile == 'A' ? 'EXAM' : 'CHAT', reduceMotion: false, darkMode: false)]);
    final container = ProviderContainer();
    container.read(activeStudyModeProvider);
    await container.read(bootstrapProvider.future);
    expect(container.read(activeStudyModeProvider), 'EXAM');
    profile = 'B'; container.invalidate(bootstrapProvider);
    await container.read(bootstrapProvider.future);
    expect(container.read(bootstrapProvider).value!.studyMode, 'CHAT');
    expect(container.read(activeStudyModeProvider), 'EXAM');
    container.dispose();
  });
  testWidgets('Observed: A draft remains in chat after switching to B', (tester) async {
    mock('getBootstrap', (_) async => [StudyBootstrapDto(currentProfileId: 'A', contextEpoch: 1,
      profiles: [], studyMode: 'CHAT', reduceMotion: false, darkMode: false)]);
    mock('watchConversation', (_) async => [SimpleCommandResult(success: true)]);
    messenger.setMockMethodCallHandler(const MethodChannel('me.rerere.rikkahub/study_events'), (_) async => null);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: ChatPage())));
    for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 100)); }
    await tester.enterText(find.byType(TextField).first, 'A private unsent draft');
    final container = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pump();
    expect(container.read(activeProfileIdProvider), 'B');
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, 'A private unsent draft');
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
