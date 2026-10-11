import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/pages/chat_page.dart';

// Regression coverage for profile switching while bridge work is outstanding.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) {
    messenger.setMockDecodedMessageHandler<Object?>(
      BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
        StudyHostApi.pigeonChannelCodec,
      ),
      handler,
    );
  }

  MaterialFileDto material(String profile) => MaterialFileDto(
    id: profile,
    profileId: profile,
    name: '$profile-private.txt',
    extension: 'txt',
    state: 'SUCCEEDED',
    detail: '',
    size: 1,
    sha256: '',
    createdAt: 0,
    symbolCheckPending: false,
  );
  Future<void> verifyOldError<T, N extends ProfileListNotifier<T>>(
    AsyncNotifierProvider<N, List<T>> provider,
    String method,
  ) async {
    final delayed = Completer<Object?>();
    var aCalls = 0;
    mock(method, (args) async {
      if ((args as List)[0] == 'A' && ++aCalls == 2) return delayed.future;
      return [<T>[]];
    });
    final container = ProviderContainer();
    container.read(activeProfileIdProvider.notifier).state = 'A';
    final sub = container.listen(provider, (_, _) {});
    await container.read(provider.future);
    final refreshing = container.read(provider.notifier).refresh();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await container.read(provider.future);
    delayed.complete(['FAILED', 'A private error', null]);
    await refreshing;
    expect(container.read(provider).hasError, isFalse);
    expect(container.read(provider).value, isEmpty);
    sub.close();
    container.dispose();
  }

  test(
    'Materials ignore an old profile error',
    () => verifyOldError(materialsProvider, 'getMaterials'),
  );
  test(
    'Memories ignore an old profile error',
    () => verifyOldError(memoriesProvider, 'getMemories'),
  );
  test(
    'Artifacts ignore an old profile error',
    () => verifyOldError(artifactsProvider, 'getArtifacts'),
  );
  test(
    'Conversations ignore an old profile error',
    () => verifyOldError(conversationsProvider, 'getConversations'),
  );
  test(
    'Latest refresh wins when the same profile responses arrive out of order',
    () async {
      final delayed = Completer<Object?>();
      var calls = 0;
      mock('getMaterials', (_) async {
        if (++calls == 2) return delayed.future;
        return [
          [material(calls == 1 ? 'initial' : 'latest')],
        ];
      });
      final container = ProviderContainer();
      final sub = container.listen(materialsProvider, (_, _) {});
      await container.read(materialsProvider.future);
      final first = container.read(materialsProvider.notifier).refresh();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      await container.read(materialsProvider.notifier).refresh();
      delayed.complete([
        [material('stale')],
      ]);
      await first;
      expect(container.read(materialsProvider).value!.single.id, 'latest');
      sub.close();
      container.dispose();
    },
  );
  test('Late A refresh cannot overwrite B material list', () async {
    final delayed = Completer<Object?>();
    var aCalls = 0;
    mock('getMaterials', (args) async {
      final profile = (args as List)[0] as String;
      if (profile == 'A' && ++aCalls == 2) return delayed.future;
      return [
        [material(profile)],
      ];
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
    delayed.complete([
      [material('A')],
    ]);
    await refresh;
    expect(container.read(activeProfileIdProvider), 'B');
    expect(container.read(materialsProvider).value!.single.profileId, 'B');
    sub.close();
    container.dispose();
  });
  test('Late A send cannot select a conversation while B is active', () async {
    final delayed = Completer<Object?>();
    mock('sendMessage', (_) => delayed.future);
    mock(
      'getConversationDetails',
      (args) async => [
        ConversationDetailDto(
          id: (args as List)[1] as String,
          profileId: args[0] as String,
          title: '',
          studyMode: 'CHAT',
          nodes: [],
          isGenerating: false,
          lifecycleRevision: 0,
        ),
      ],
    );
    mock('getConversations', (_) async => [[]]);
    final container = ProviderContainer();
    container.read(activeProfileIdProvider.notifier).state = 'A';
    final notifier = container.read(activeConversationDetailProvider.notifier);
    final sending = notifier.sendMessage('A private message');
    await Future<void>.delayed(const Duration(milliseconds: 20));
    container.read(activeProfileIdProvider.notifier).state = 'B';
    container.read(activeConversationIdProvider.notifier).state = null;
    delayed.complete([
      SimpleCommandResult(success: true, idResult: 'A-conversation'),
    ]);
    await sending;
    expect(container.read(activeProfileIdProvider), 'B');
    expect(container.read(activeConversationIdProvider), isNull);
    await Future<void>.delayed(const Duration(milliseconds: 50));
    container.dispose();
  });
  test('Bootstrap profile switch resets study mode', () async {
    var profile = 'A';
    mock(
      'getBootstrap',
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: profile,
          contextEpoch: profile == 'A' ? 1 : 2,
          profiles: [],
          studyMode: profile == 'A' ? 'EXAM' : 'CHAT',
          reduceMotion: false,
          darkMode: false,
        ),
      ],
    );
    final container = ProviderContainer();
    container.read(activeStudyModeProvider);
    await container.read(bootstrapProvider.future);
    expect(container.read(activeStudyModeProvider), 'EXAM');
    profile = 'B';
    container.invalidate(bootstrapProvider);
    await container.read(bootstrapProvider.future);
    expect(container.read(bootstrapProvider).value!.studyMode, 'CHAT');
    expect(container.read(activeStudyModeProvider), 'CHAT');
    container.dispose();
  });
  test(
    'Refreshing bootstrap within a profile preserves explicitly selected mode',
    () async {
      mock(
        'getBootstrap',
        (_) async => [
          StudyBootstrapDto(
            currentProfileId: 'default-learner',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: false,
            darkMode: false,
          ),
        ],
      );
      final container = ProviderContainer();
      container.read(activeStudyModeProvider);
      await container.read(bootstrapProvider.future);
      container.read(activeStudyModeProvider.notifier).state = 'EXAM';
      container.invalidate(bootstrapProvider);
      await container.read(bootstrapProvider.future);
      expect(container.read(activeStudyModeProvider), 'EXAM');
      container.dispose();
    },
  );
  test(
    'New chat during initial send prevents the late receipt selecting a chat',
    () async {
      final delayed = Completer<Object?>();
      mock('sendMessage', (_) => delayed.future);
      final container = ProviderContainer();
      final sending = container
          .read(activeConversationDetailProvider.notifier)
          .sendMessage('message');
      await Future<void>.delayed(const Duration(milliseconds: 20));
      container.read(activeConversationIdProvider.notifier).state = null;
      delayed.complete([
        SimpleCommandResult(success: true, idResult: 'old-chat'),
      ]);
      await sending;
      expect(container.read(activeConversationIdProvider), isNull);
      container.dispose();
    },
  );
  test(
    'A current successful send still selects its new conversation',
    () async {
      mock(
        'getBootstrap',
        (_) async => [
          StudyBootstrapDto(
            currentProfileId: 'default-learner',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: false,
            darkMode: false,
          ),
        ],
      );
      mock(
        'watchConversation',
        (_) async => [SimpleCommandResult(success: true)],
      );
      messenger.setMockMethodCallHandler(
        const MethodChannel('me.rerere.rikkahub/study_events'),
        (_) async => null,
      );
      mock(
        'sendMessage',
        (_) async => [SimpleCommandResult(success: true, idResult: 'new-chat')],
      );
      mock('getConversations', (_) async => [[]]);
      final container = ProviderContainer();
      await container.read(bootstrapProvider.future);
      await container.read(activeConversationDetailProvider.future);
      await container
          .read(activeConversationDetailProvider.notifier)
          .sendMessage('message');
      expect(container.read(activeConversationIdProvider), 'new-chat');
      await Future<void>.delayed(const Duration(milliseconds: 30));
      container.dispose();
    },
  );
  testWidgets(
    'Profile switch clears selected attachments along with the draft',
    (tester) async {
      mock(
        'getBootstrap',
        (_) async => [
          StudyBootstrapDto(
            currentProfileId: 'A',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: false,
            darkMode: false,
          ),
        ],
      );
      mock(
        'watchConversation',
        (_) async => [SimpleCommandResult(success: true)],
      );
      mock(
        'pickAttachments',
        (_) async => [
          ['/qa/A-private.txt'],
        ],
      );
      messenger.setMockMethodCallHandler(
        const MethodChannel('me.rerere.rikkahub/study_events'),
        (_) async => null,
      );
      await tester.pumpWidget(
        const ProviderScope(child: MaterialApp(home: ChatPage())),
      );
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.tap(find.byTooltip('添加文件'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('选择文件'));
      await tester.pump();
      expect(find.text('A-private.txt'), findsOneWidget);
      final container = ProviderScope.containerOf(
        tester.element(find.byType(ChatPage)),
      );
      container.read(activeProfileIdProvider.notifier).state = 'B';
      await tester.pump();
      expect(find.text('A-private.txt'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  test('Memory toggle propagates a failed native command', () async {
    mock(
      'toggleAutoMemory',
      (_) async => [
        SimpleCommandResult(
          success: false,
          error: StudyErrorDto(
            code: 'FAILED',
            message: 'disk failure',
            retryable: true,
            requestId: 'test',
          ),
        ),
      ],
    );
    final container = ProviderContainer();
    await expectLater(
      container.read(memoriesProvider.notifier).toggleAutoMemory(false),
      throwsStateError,
    );
    container.dispose();
  });
  testWidgets('Switching to B clears A unsent draft', (tester) async {
    mock(
      'getBootstrap',
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: 'A',
          contextEpoch: 1,
          profiles: [],
          studyMode: 'CHAT',
          reduceMotion: false,
          darkMode: false,
        ),
      ],
    );
    mock(
      'watchConversation',
      (_) async => [SimpleCommandResult(success: true)],
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('me.rerere.rikkahub/study_events'),
      (_) async => null,
    );
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: ChatPage())),
    );
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.enterText(
      find.byType(TextField).first,
      'A private unsent draft',
    );
    final container = ProviderScope.containerOf(
      tester.element(find.byType(ChatPage)),
    );
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pump();
    expect(container.read(activeProfileIdProvider), 'B');
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      isEmpty,
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
