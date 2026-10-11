import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/bridge/chat_display_patch.dart';
import 'package:flutter_ui/providers/study_providers.dart';

import 'chat_upgrade_stage1_test.dart' show fixture;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) fn) =>
      messenger.setMockDecodedMessageHandler<Object?>(
        BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
          StudyHostApi.pigeonChannelCodec,
        ),
        fn,
      );
  test('Buffers initial patches, coalesces updates, rejects duplicate and wrong owner, recovers gaps', () async {
    final initial = Completer<Object?>();
    var calls = 0;
    mock(
      'watchConversation',
      (_) async => [SimpleCommandResult(success: true)],
    );
    mock('getConversationDetails', (_) async {
      calls++;
      if (calls == 1) return await initial.future;
      return [fixture()];
    });
    final events = StreamController<Map<String, dynamic>>.broadcast();
    final container = ProviderContainer(
      overrides: [
        bootstrapProvider.overrideWith(
          (_) async => StudyBootstrapDto(
            currentProfileId: 'p',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: true,
            darkMode: false,
            defaultConversationId: 'c',
          ),
        ),
        bridgeEventsProvider.overrideWith((_) => events.stream),
      ],
    );
    final sub = container.listen(activeConversationDetailProvider, (_, _) {});
    await container.read(bootstrapProvider.future);
    await Future<void>.delayed(const Duration(milliseconds: 20));
    void patch(int seq, String text, {String owner = 'p'}) => events.add({
      'type': 'message_patch',
      'profileId': owner,
      'contextEpoch': 1,
      'sequence': seq,
      'payload': {
        'conversationId': 'c',
        'changes': [
          {
            'nodeId': 'live',
            'messageId': 'live',
            'parts': [
              {'index': 0, 'text': text},
            ],
          },
        ],
      },
    });
    patch(1, 'live buffered');
    await Future<void>.delayed(const Duration(milliseconds: 60));
    initial.complete([fixture()]);
    await container.read(activeConversationDetailProvider.future);
    await Future<void>.delayed(const Duration(milliseconds: 20));
    String text() => container
        .read(activeConversationDetailProvider)
        .value!
        .nodes
        .last
        .branches
        .first
        .parts
        .first
        .text!;
    expect(text(), 'live buffered');
    final old = container
        .read(activeConversationDetailProvider)
        .value!
        .nodes
        .first;
    patch(2, 'live buffered a');
    patch(3, 'live buffered abc');
    await Future<void>.delayed(const Duration(milliseconds: 70));
    expect(text(), 'live buffered abc');
    expect(calls, 1);
    expect(
      identical(
        old,
        container.read(activeConversationDetailProvider).value!.nodes.first,
      ),
      isTrue,
    );
    patch(3, 'live wrong duplicate');
    patch(4, 'live wrong owner', owner: 'other');
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(text(), 'live buffered abc');
    patch(5, 'live gap');
    await Future<void>.delayed(const Duration(milliseconds: 70));
    expect(calls, 2);
    expect(text(), 'live');
    events.add({'type': 'materials_changed', 'profileId': 'p', 'contextEpoch': 1, 'sequence': 7, 'payload': {'conversationId': 'different'}});
    await Future<void>.delayed(const Duration(milliseconds: 70));
    expect(calls, 3);
    sub.close();
    container.dispose();
    await events.close();
  });
  test(
    'A snapshot already ahead of a queued cumulative patch never regresses',
    () {
      final detail = fixture();
      detail.nodes.last.branches.first.parts.first.text = 'live newest';
      final result = applyChatDisplayPatch(detail, {
        'conversationId': 'c',
        'changes': [
          {
            'nodeId': 'live',
            'messageId': 'live',
            'parts': [
              {'index': 0, 'text': 'live'},
            ],
          },
        ],
      })!;
      expect(result.nodes.last.branches.first.parts.first.text, 'live newest');
    },
  );
}
