import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) => messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
    'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), handler);
  ConversationSummaryDto item(String id) => ConversationSummaryDto(id: id, title: id, previewText: '', updatedAt: '', isPinned: false, studyMode: 'CHAT', profileId: 'A', isArchived: false);
  test('Old full history search cannot replace a newer query or survive epoch change', () async {
    final old = Completer<Object?>();
    mock('getConversations', (_) async => [[item('recent')]]);
    mock('searchConversations', (args) async => (args as List)[1] == 'old' ? old.future : [[item('all-history-match')]]);
    final c = ProviderContainer(); final sub = c.listen(conversationsProvider, (_, _) {});
    await c.read(conversationsProvider.future);
    c.read(historyQueryProvider.notifier).update('old'); c.read(conversationsProvider); await Future<void>.delayed(const Duration(milliseconds: 20));
    c.read(historyQueryProvider.notifier).update('new'); await c.read(conversationsProvider.future);
    old.complete([[item('stale')]]); await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(c.read(conversationsProvider).value!.single.id, 'all-history-match');
    c.read(contextEpochProvider.notifier).state = 2; await c.read(conversationsProvider.future);
    expect(c.read(historyQueryProvider), ''); expect(c.read(conversationsProvider).value!.single.id, 'recent');
    sub.close(); c.dispose();
  });
  test('Search pagination stays on full history query and ignores duplicate request', () async {
    final page = Completer<Object?>(); final argsSeen = <List>[];
    mock('searchConversations', (args) async {
      final values = args as List; argsSeen.add(values);
      if (values[3] == 0) return [List.generate(50, (i) => item('old-$i'))];
      return await page.future;
    });
    final c = ProviderContainer(); c.read(historyQueryProvider.notifier).update('needle');
    final sub = c.listen(conversationsProvider, (_, _) {}); await c.read(conversationsProvider.future);
    final loading = c.read(conversationsProvider.notifier).loadMore();
    expect(await c.read(conversationsProvider.notifier).loadMore(), false);
    page.complete([[item('older-match')]]); expect(await loading, true);
    expect(argsSeen.last[1], 'needle'); expect(argsSeen.last[3], 50);
    expect(c.read(conversationsProvider).value!.length, 51);
    sub.close(); c.dispose();
  });
}
