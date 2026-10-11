import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/add_memory_dialog.dart';
import 'package:flutter_ui/widgets/project_groups.dart';

class _Projects extends ProjectsNotifier {
  @override
  Future<List<ProjectDto>> build() async => [
    ProjectDto(
      id: 'project',
      name: '专项论文',
      conversations: [
        ConversationSummaryDto(
          id: 'chat',
          title: '论文需求聊天',
          previewText: '',
          updatedAt: '1',
          isPinned: false,
          studyMode: 'CHAT',
          profileId: 'default-learner',
          isArchived: false,
        ),
      ],
    ),
  ];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(List<Object?>) handler) {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
      StudyHostApi.pigeonChannelCodec,
    );
    messenger.setMockDecodedMessageHandler<Object?>(
      channel,
      (args) => handler((args as List).cast<Object?>()),
    );
    addTearDown(
      () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
    );
  }

  testWidgets(
    'Manual save reports failure and keeps draft; profile switch disables save',
    (tester) async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      List<Object?>? submitted;
      mock('addMemory', (args) async {
        submitted = args;
        return [
          SimpleCommandResult(
            success: false,
            error: StudyErrorDto(
              code: 'ERROR',
              message: '保存失败',
              retryable: true,
              requestId: '',
            ),
          ),
        ];
      });
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (context) => TextButton(
                  onPressed: () => showAddMemoryDialog(context),
                  child: const Text('添加'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '保存'))
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(TextField), '回答先给结论');
      await tester.pump();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(submitted![1], '回答先给结论');
      expect(submitted![2], 'PREFERENCE');
      expect(find.textContaining('保存失败'), findsOneWidget);
      expect(find.text('回答先给结论'), findsOneWidget);
      container.read(activeProfileIdProvider.notifier).state = 'other';
      container.read(contextEpochProvider.notifier).state = 2;
      await tester.pumpAndSettle();
      expect(find.text('档案已切换，请关闭后重新添加。'), findsOneWidget);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '保存'))
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'Project expansion exposes grouped chats and management actions',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [projectsProvider.overrideWith(_Projects.new)],
          child: const MaterialApp(
            home: Scaffold(body: SingleChildScrollView(child: ProjectGroups())),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('专项论文'), findsOneWidget);
      expect(find.text('1 个聊天'), findsOneWidget);
      await tester.tap(find.text('专项论文'));
      await tester.pumpAndSettle();
      expect(find.text('论文需求聊天'), findsOneWidget);
      await tester.tap(find.byTooltip('项目操作'));
      await tester.pumpAndSettle();
      expect(find.text('新建项目聊天'), findsOneWidget);
      expect(find.text('重命名项目'), findsOneWidget);
      expect(find.text('删除分组'), findsOneWidget);
    },
  );
}
