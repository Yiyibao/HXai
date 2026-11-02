import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/agent_task_detail_page.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';

import 'agent_task_catalog_test.dart' show bootstrap, resource;

AgentCatalogDetailDto safetyDetail({
  String state = 'QUEUED',
  int revision = 1,
  bool paused = false,
  String authorization = 'ACTIVE',
  String id = 'task',
}) => AgentCatalogDetailDto(
  task: AgentCatalogTaskDto(
    id: id,
    owner: 'owner',
    conversationId: 'deleted-conversation',
    role: 'main',
    goal: 'preserved goal',
    state: state,
    providerId: 'provider',
    modelId: 'locked-model',
    reasoning: 'HIGH',
    revision: revision,
    fence: 8,
    manualPaused: paused,
    authorization: authorization,
    inputBlocked: true,
    deviceBlocked: true,
    deviceConfirmation: true,
    approvalBlocked: true,
    goalRevision: 1,
    plannedRevision: 1,
    acceptanceVerified: state == 'SUCCEEDED',
  ),
  steps: [],
  calls: [],
  artifacts: [],
  resultsPartial: false,
  conversationAvailable: false,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final channels = <BasicMessageChannel<Object?>>[];
  void mock(String name, Future<Object?> Function(List<Object?>) handler) {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$name',
      StudyHostApi.pigeonChannelCodec,
    );
    channels.add(channel);
    messenger.setMockDecodedMessageHandler<Object?>(
      channel,
      (args) => handler((args! as List).cast<Object?>()),
    );
  }

  setUp(() {
    mock('getTaskResumeReceipt', (_) async => [null]);
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      if (call.method == 'cloud.state') return {'enabled': false, 'delegations': <String>[]};
      throw MissingPluginException(call.method);
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, null);
    for (final channel in channels) {
      messenger.setMockDecodedMessageHandler<Object?>(channel, null);
    }
    channels.clear();
  });
  Future<ProviderContainer> show(
    WidgetTester tester, {
    double scale = 1,
    bool dark = false,
  }) async {
    final container = ProviderContainer(
      overrides: [bootstrapProvider.overrideWith((_) async => bootstrap())],
    );
    addTearDown(container.dispose);
    await container.read(bootstrapProvider.future);
    container.read(activeProfileIdProvider);
    container.read(contextEpochProvider);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: ThemeData(
            brightness: dark ? Brightness.dark : Brightness.light,
          ),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: const AgentTaskDetailPage(resource: resource),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  Future<void> click(WidgetTester tester, String label) async {
    await tester.ensureVisible(find.widgetWithText(TextButton, label));
    await tester.tap(find.widgetWithText(TextButton, label));
    await tester.pump();
    await tester.pump();
  }

  testWidgets(
    'missing conversation can pause exact task and only refreshed DTO changes state',
    (tester) async {
      var current = safetyDetail();
      final ack = Completer<Object?>();
      final requests = <List<Object?>>[];
      mock('getAgentTaskDetail', (_) async => [current]);
      mock('taskSafetyCommand', (args) {
        requests.add(args);
        return ack.future;
      });
      final container = await show(tester);
      expect(find.widgetWithText(TextButton, '暂停任务'), findsOneWidget);
      await click(tester, '暂停任务');
      expect(requests.single.sublist(0, 2), ['owner', 'task']);
      expect(requests.single.sublist(3), [1, 1, 8, 'pause']);
      expect(find.text('已暂停'), findsNothing);
      expect(container.read(activeConversationIdProvider), isNull);
      current = safetyDetail(
        state: 'PAUSED_BY_USER',
        revision: 2,
        paused: true,
      );
      ack.complete([SimpleCommandResult(success: true, idResult: 'task')]);
      await tester.pumpAndSettle();
      expect(find.text('已暂停'), findsOneWidget);
      expect(find.textContaining('缺少输入'), findsOneWidget);
      expect(find.textContaining('locked-model'), findsOneWidget);
    },
  );

  testWidgets(
    'lost ACK reuses exact request while cancellation remains available',
    (tester) async {
      final requests = <List<Object?>>[];
      mock('getAgentTaskDetail', (_) async => [safetyDetail()]);
      mock('taskSafetyCommand', (args) async {
        requests.add(args);
        if (requests.length == 1) {
          return ['DISCONNECTED', 'synthetic lost ACK', null];
        }
        return [SimpleCommandResult(success: true, idResult: 'task')];
      });
      await show(tester);
      await click(tester, '暂停任务');
      expect(find.textContaining('回执未确认'), findsOneWidget);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '取消任务'))
            .onPressed,
        isNotNull,
      );
      await click(tester, '查询暂停回执');
      await tester.pumpAndSettle();
      expect(requests[1], requests[0]);
      expect(find.text('已暂停'), findsNothing);
      expect(find.text('排队中'), findsOneWidget);
    },
  );

  testWidgets(
    'rejected revision refreshes task without retrying rejected command',
    (tester) async {
      var reads = 0, writes = 0;
      mock(
        'getAgentTaskDetail',
        (_) async => [
          ++reads == 1
              ? safetyDetail()
              : safetyDetail(state: 'SUCCEEDED', revision: 2),
        ],
      );
      mock('taskSafetyCommand', (_) async {
        writes++;
        return [
          SimpleCommandResult(
            success: false,
            error: StudyErrorDto(
              code: 'TASK_PRECONDITION_REJECTED',
              message: '任务状态已变化',
              retryable: false,
              requestId: 'request',
            ),
          ),
        ];
      });
      await show(tester);
      await click(tester, '取消任务');
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(reads, 2);
      expect(find.text('任务记录：目标验收通过'), findsOneWidget);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '暂停任务'))
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '取消任务'))
            .onPressed,
        isNull,
      );
      expect(find.text('查询取消回执'), findsNothing);
    },
  );

  testWidgets('profile switch hides task and ignores late safety ACK', (
    tester,
  ) async {
    final ack = Completer<Object?>();
    var reads = 0;
    mock('getAgentTaskDetail', (_) async {
      reads++;
      return [safetyDetail()];
    });
    mock('taskSafetyCommand', (_) => ack.future);
    final container = await show(tester);
    await click(tester, '主动撤权');
    container.read(activeProfileIdProvider.notifier).state = 'other';
    container.read(contextEpochProvider.notifier).state = 2;
    await tester.pump();
    ack.complete([SimpleCommandResult(success: true, idResult: 'task')]);
    await tester.pumpAndSettle();
    expect(reads, 1);
    expect(find.textContaining('preserved goal'), findsNothing);
    expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
  });

  testWidgets(
    'wrong task ACK stays uncertain and retry keeps original request',
    (tester) async {
      final requests = <List<Object?>>[];
      mock(
        'getAgentTaskDetail',
        (_) async => [safetyDetail(state: 'UNCERTAIN_SIDE_EFFECT')],
      );
      mock('taskSafetyCommand', (args) async {
        requests.add(args);
        return [SimpleCommandResult(success: true, idResult: 'foreign-task')];
      });
      await show(tester);
      await click(tester, '取消任务');
      expect(find.textContaining('回执未确认'), findsOneWidget);
      expect(find.text('结果未知，禁止重复执行'), findsOneWidget);
      await click(tester, '查询取消回执');
      expect(requests[1], requests[0]);
      expect(find.text('已取消'), findsNothing);
    },
  );

  testWidgets(
    '200 percent dark narrow layout keeps controls and truthful stop status',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      mock(
        'getAgentTaskDetail',
        (_) async => [safetyDetail(state: 'STOP_REQUESTED')],
      );
      await show(tester, scale: 2, dark: true);
      await tester.ensureVisible(find.widgetWithText(TextButton, '主动撤权'));
      expect(find.widgetWithText(TextButton, '主动撤权'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('已请求停止，尚未确认停止'),
        100,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.text('已请求停止，尚未确认停止'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'manual refresh preserves pending request and original revision',
    (tester) async {
      final requests = <List<Object?>>[];
      var reads = 0;
      mock(
        'getAgentTaskDetail',
        (_) async => [safetyDetail(revision: ++reads)],
      );
      mock('taskSafetyCommand', (args) async {
        requests.add(args);
        return ['DISCONNECTED', 'lost ACK', null];
      });
      await show(tester);
      await click(tester, '暂停任务');
      await click(tester, '刷新详情');
      await tester.pumpAndSettle();
      expect(reads, 2);
      expect(find.text('查询暂停回执'), findsOneWidget);
      await click(tester, '查询暂停回执');
      expect(requests[1], requests[0]);
      expect(requests[1][4], 1);
    },
  );

  testWidgets(
    'failed refresh retries full detail and keeps pending safety ACK',
    (tester) async {
      final cursors = <Object?>[];
      mock('getAgentTaskDetail', (args) async {
        cursors.add(args[2]);
        if (cursors.length == 2) {
          return ['FAILED', 'synthetic read failure', null];
        }
        return [safetyDetail(revision: cursors.length)];
      });
      mock(
        'taskSafetyCommand',
        (_) async => ['DISCONNECTED', 'lost ACK', null],
      );
      await show(tester);
      await click(tester, '暂停任务');
      await click(tester, '刷新详情');
      await tester.pumpAndSettle();
      expect(find.textContaining('任务详情读取失败'), findsOneWidget);
      await click(tester, '重试详情读取');
      await tester.pumpAndSettle();
      expect(cursors, [null, null, null]);
      expect(find.text('查询暂停回执'), findsOneWidget);
      expect(find.textContaining('任务详情读取失败'), findsNothing);
    },
  );
}
