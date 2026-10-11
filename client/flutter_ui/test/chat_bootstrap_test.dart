import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/pages/chat_page.dart';
import 'package:flutter_ui/widgets/chat_activity.dart';

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

  testWidgets(
    'Generation is visible before an assistant node exists and clears on finish',
    (tester) async {
      mock(
        'getBootstrap',
        (_) async => [
          StudyBootstrapDto(
            currentProfileId: 'default-learner',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            defaultConversationId: 'active',
            reduceMotion: false,
            darkMode: false,
          ),
        ],
      );
      mock(
        'watchConversation',
        (_) async => [SimpleCommandResult(success: true)],
      );
      var generating = true;
      var nodes = <MessageNodeDto>[];
      mock(
        'getConversationDetails',
        (_) async => [
          ConversationDetailDto(
            id: 'active',
            title: '测试',
            profileId: 'default-learner',
            studyMode: 'CHAT',
            nodes: nodes,
            isGenerating: generating,
            lifecycleRevision: 1,
          ),
        ],
      );
      messenger.setMockMethodCallHandler(
        const MethodChannel('me.rerere.rikkahub/study_events'),
        (_) async => null,
      );
      await tester.pumpWidget(
        const ProviderScope(child: MaterialApp(home: ChatPage())),
      );
      for (var frame = 0; frame < 8; frame++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(find.text('正在思考'), findsOneWidget);
      expect(find.byType(ChatActivityDots), findsOneWidget);
      final container = ProviderScope.containerOf(
        tester.element(find.byType(ChatPage)),
      );
      nodes = [
        MessageNodeDto(
          nodeId: 'user',
          selectIndex: 0,
          branches: [
            MessageDto(
              id: 'user',
              nodeId: 'user',
              role: 'user',
              createdAt: '',
              revision: 0,
              parts: [MessagePartDto(type: 'text', text: '你好')],
              generationState: 'COMPLETED',
            ),
          ],
        ),
      ];
      await container.read(activeConversationDetailProvider.notifier).refresh();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('正在思考'), findsOneWidget);
      generating = false;
      await container.read(activeConversationDetailProvider.notifier).refresh();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.byType(ChatActivityDots), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  test('Cold bootstrap settles empty chat and refresh preserves explicit selection', () async {
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
    final container = ProviderContainer();
    final subscription = container.listen(
      activeConversationDetailProvider,
      (_, _) {},
    );
    await container.read(bootstrapProvider.future);
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(container.read(activeConversationDetailProvider).isLoading, isFalse);
    expect(container.read(activeStudyModeProvider), 'CHAT');
    container.read(activeConversationIdProvider.notifier).state = null;
    container.invalidate(bootstrapProvider);
    await container.read(bootstrapProvider.future);
    expect(container.read(activeConversationIdProvider), isNull);
    subscription.close();
    container.dispose();
  });
  testWidgets('Startup waits for a nondefault profile before bridge commands', (
    tester,
  ) async {
    mock(
      'getBootstrap',
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: 'saved-profile',
          contextEpoch: 3,
          profiles: [],
          studyMode: 'CHAT',
          reduceMotion: false,
          darkMode: false,
        ),
      ],
    );
    mock('watchConversation', (args) async {
      expect((args as List)[0], 'saved-profile');
      expect(args[2], 3);
      return [SimpleCommandResult(success: true)];
    });
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: ChatPage())),
    );
    for (var frame = 0; frame < 8; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('提问、读文件，或把想法变成作品'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('Chat'), findsOneWidget);
  });
  testWidgets('Failed send retains draft and startup failure renders retry', (
    tester,
  ) async {
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
    mock(
      'sendMessage',
      (_) async => [
        SimpleCommandResult(
          success: false,
          error: StudyErrorDto(
            code: 'FAILED',
            message: 'synthetic disk failure',
            retryable: true,
            requestId: 'test',
          ),
        ),
      ],
    );
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: ChatPage())),
    );
    for (var frame = 0; frame < 8; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.enterText(find.byType(TextField).first, 'keep this draft');
    await tester.pump();
    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pump(const Duration(milliseconds: 100));
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      'keep this draft',
    );
    expect(find.textContaining('草稿已保留'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    mock(
      'getBootstrap',
      (_) async => ['FAILED', 'synthetic startup failure', null],
    );
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: ChatPage())),
    );
    for (var frame = 0; frame < 8; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('重试'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
  testWidgets('Chat delivers artifact actions and displays native failure', (
    tester,
  ) async {
    mock(
      'getBootstrap',
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: 'default-learner',
          contextEpoch: 1,
          profiles: [],
          studyMode: 'CHAT',
          defaultConversationId: 'files',
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
      'getConversationDetails',
      (_) async => [
        ConversationDetailDto(
          id: 'files',
          title: '文件',
          profileId: 'default-learner',
          studyMode: 'CHAT',
          nodes: [
            MessageNodeDto(
              nodeId: 'node',
              selectIndex: 0,
              branches: [
                MessageDto(
                  id: 'message',
                  nodeId: 'node',
                  role: 'assistant',
                  parts: [
                    MessagePartDto(
                      type: 'file',
                      fileName: 'result.docx',
                      artifactId: 'artifact',
                    ),
                  ],
                  createdAt: '',
                  revision: 0,
                ),
              ],
            ),
          ],
          isGenerating: false,
          lifecycleRevision: 0,
        ),
      ],
    );
    final calls = <String>[];
    for (final action in ['openArtifact', 'saveArtifact', 'shareArtifact']) {
      mock(action, (args) async {
        calls.add(action);
        expect((args as List)[1], 'artifact');
        return [
          SimpleCommandResult(
            success: action != 'saveArtifact',
            error: action == 'saveArtifact'
                ? StudyErrorDto(
                    code: 'FAILED',
                    message: 'target hash failed',
                    retryable: true,
                    requestId: '',
                  )
                : null,
          ),
        ];
      });
    }
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: ChatPage())),
    );
    for (var frame = 0; frame < 8; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('result.docx'), findsOneWidget);
    await tester.tap(find.text('预览'));
    await tester.pump();
    await tester.tap(find.text('保存'));
    await tester.pump();
    expect(find.textContaining('target hash failed'), findsOneWidget);
    await tester.tap(find.byTooltip('文件操作'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('分享文件'));
    await tester.pump();
    expect(calls, ['openArtifact', 'saveArtifact', 'shareArtifact']);
  });
}
