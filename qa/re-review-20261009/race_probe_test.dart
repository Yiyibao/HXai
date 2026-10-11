import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/pages/chat_page.dart';

// These tests assert observed defects, not acceptance of correct behaviour.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) {
    messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), handler);
  }
  MaterialFileDto material(String name) => MaterialFileDto(id: name, profileId: 'A', name: name,
    extension: 'txt', state: 'SUCCEEDED', detail: '', size: 1, sha256: '', createdAt: 0, symbolCheckPending: false);
  Future<void> setupChat(WidgetTester tester) async {
    mock('getBootstrap', (_) async => [StudyBootstrapDto(currentProfileId: 'A', contextEpoch: 1,
      profiles: [], studyMode: 'CHAT', reduceMotion: false, darkMode: false)]);
    mock('watchConversation', (_) async => [SimpleCommandResult(success: true)]);
    mock('getConversations', (_) async => [[]]);
    mock('getConversationDetails', (args) async => [ConversationDetailDto(id: (args as List)[1] as String,
      profileId: args[0] as String, title: '', studyMode: 'CHAT', nodes: [], isGenerating: false, lifecycleRevision: 0)]);
    messenger.setMockMethodCallHandler(const MethodChannel('me.rerere.rikkahub/study_events'), (_) async => null);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: ChatPage())));
    for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 100)); }
  }
  test('CONTROL initial build does not overwrite a newer explicit refresh', () async {
    final initial = Completer<Object?>(); var calls = 0;
    mock('getMaterials', (_) async => ++calls == 1 ? initial.future : [[material('latest')]]);
    final c = ProviderContainer();
    final sub = c.listen(materialsProvider, (_, _) {});
    await Future<void>.delayed(const Duration(milliseconds: 20));
    await c.read(materialsProvider.notifier).refresh();
    expect(c.read(materialsProvider).value!.single.id, 'latest');
    initial.complete([[material('stale-initial')]]);
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(c.read(materialsProvider).value!.single.id, 'latest');
    sub.close(); c.dispose();
  });
  test('CONTROL A to B to A drops the old explicit refresh', () async {
    final old = Completer<Object?>(); var calls = 0;
    mock('getMaterials', (_) async => ++calls == 2 ? old.future : [[material('call-$calls')]]);
    final c = ProviderContainer(); c.read(activeProfileIdProvider.notifier).state = 'A';
    final sub = c.listen(materialsProvider, (_, _) {}); await c.read(materialsProvider.future);
    final refreshing = c.read(materialsProvider.notifier).refresh();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    c.read(activeProfileIdProvider.notifier).state = 'B'; await c.read(materialsProvider.future);
    c.read(activeProfileIdProvider.notifier).state = 'A'; await c.read(materialsProvider.future);
    old.complete([[material('old-A')]]); await refreshing;
    expect(c.read(materialsProvider).value!.single.id, 'call-4');
    sub.close(); c.dispose();
  });
  test('PROBE pagination started during refresh discards refreshed first page', () async {
    final delayed = Completer<Object?>(); var calls = 0;
    ConversationSummaryDto item(String id) => ConversationSummaryDto(id: id, title: id,
      previewText: '', updatedAt: '', isPinned: false, studyMode: 'CHAT', profileId: 'A');
    mock('getConversations', (args) async {
      calls++;
      if (calls == 2) return delayed.future;
      return [[item(calls == 1 ? 'old-first-page' : 'next-page')]];
    });
    final c = ProviderContainer(); final sub = c.listen(conversationsProvider, (_, _) {});
    await c.read(conversationsProvider.future);
    final refreshing = c.read(conversationsProvider.notifier).refresh();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    await c.read(conversationsProvider.notifier).loadMore();
    delayed.complete([[item('new-first-page')]]); await refreshing;
    expect(c.read(conversationsProvider).value!.map((it) => it.id), ['old-first-page', 'next-page']);
    sub.close(); c.dispose();
  });
  testWidgets('PROBE attachment picker returning after A B A reintroduces cleared attachment', (tester) async {
    await setupChat(tester); final delayed = Completer<Object?>();
    mock('pickAttachments', (_) => delayed.future);
    await tester.tap(find.byIcon(Icons.attach_file)); await tester.pump();
    final c = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    c.read(activeProfileIdProvider.notifier).state = 'B'; c.read(contextEpochProvider.notifier).state = 2;
    await tester.pump();
    c.read(activeProfileIdProvider.notifier).state = 'A'; c.read(contextEpochProvider.notifier).state = 3;
    await tester.pump();
    delayed.complete([['/qa/A-private.txt']]); await tester.pump();
    expect(find.text('A-private.txt'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('PROBE same profile epoch change retains draft and attachment', (tester) async {
    await setupChat(tester); mock('pickAttachments', (_) async => [['/qa/old-context.txt']]);
    await tester.enterText(find.byType(TextField).first, 'old context draft');
    await tester.tap(find.byIcon(Icons.attach_file)); await tester.pump();
    final c = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    c.read(contextEpochProvider.notifier).state = 2; await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, 'old context draft');
    expect(find.text('old-context.txt'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('PROBE late mode acknowledgement changes the newly selected conversation mode', (tester) async {
    await setupChat(tester); final delayed = Completer<Object?>();
    mock('setStudyMode', (_) => delayed.future);
    final c = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    c.read(activeConversationIdProvider.notifier).state = 'chat-A'; await tester.pump();
    // Open the mode selector from the app bar.
    await tester.tap(find.text('通用聊天').first);
    for (var i = 0; i < 5; i++) { await tester.pump(const Duration(milliseconds: 100)); }
    await tester.tap(find.text('完整讲解')); await tester.pump();
    c.read(activeConversationIdProvider.notifier).state = 'chat-B';
    c.read(activeStudyModeProvider.notifier).state = 'CHAT'; await tester.pump();
    delayed.complete([SimpleCommandResult(success: true)]); await tester.pump();
    expect(c.read(activeConversationIdProvider), 'chat-B');
    expect(c.read(activeStudyModeProvider), 'EXPLAIN');
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('PROBE send receipt after new chat clears the new chat draft', (tester) async {
    await setupChat(tester); final delayed = Completer<Object?>();
    mock('sendMessage', (_) => delayed.future);
    await tester.enterText(find.byType(TextField).first, 'same draft');
    await tester.tap(find.byIcon(Icons.send_rounded)); await tester.pump();
    final c = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    c.read(activeConversationIdProvider.notifier).state = null; await tester.pump();
    await tester.enterText(find.byType(TextField).first, 'same draft');
    delayed.complete([SimpleCommandResult(success: true, idResult: 'old-chat')]); await tester.pump();
    expect(c.read(activeConversationIdProvider), isNull);
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('PROBE old send failure appears in a newly selected chat', (tester) async {
    await setupChat(tester); final delayed = Completer<Object?>();
    mock('sendMessage', (_) => delayed.future);
    await tester.enterText(find.byType(TextField).first, 'old send');
    await tester.tap(find.byIcon(Icons.send_rounded)); await tester.pump();
    final c = ProviderScope.containerOf(tester.element(find.byType(ChatPage)));
    c.read(activeConversationIdProvider.notifier).state = null; await tester.pump();
    await tester.enterText(find.byType(TextField).first, 'new chat draft');
    delayed.complete([SimpleCommandResult(success: false,
      error: StudyErrorDto(code: 'FAILED', message: 'old request failed', retryable: true, requestId: 'old'))]);
    await tester.pump();
    expect(find.textContaining('old request failed'), findsOneWidget);
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, 'new chat draft');
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
