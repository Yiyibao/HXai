import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/agent_task_detail_page.dart';
import 'package:flutter_ui/providers/study_providers.dart';

import 'agent_task_catalog_test.dart' show bootstrap, resource;
import 'agent_task_safety_test.dart' show safetyDetail;

AgentCatalogDetailDto resumable() {
  final detail = safetyDetail(state: 'PAUSED_BY_USER', paused: true);
  detail.conversationAvailable = true;
  detail.task.conversationId = 'original-conversation';
  detail.task.inputBlocked = false;
  detail.task.deviceBlocked = false;
  detail.task.deviceConfirmation = false;
  detail.task.approvalBlocked = false;
  return detail;
}

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

  setUp(() { mock('getTaskResumeReceipt', (_) async => [null]); });

  tearDown(() {
    for (final channel in channels) {
      messenger.setMockDecodedMessageHandler<Object?>(channel, null);
    }
    channels.clear();
  });
  Future<ProviderContainer> show(
    WidgetTester tester, {
    bool largeDark = false,
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
            brightness: largeDark ? Brightness.dark : Brightness.light,
          ),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(largeDark ? 2 : 1)),
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
    final finder = find.widgetWithText(TextButton, label);
    await Scrollable.ensureVisible(tester.element(finder), alignment: .5);
    await tester.pump();
    await tester.tap(finder);
    await tester.pump();
    await tester.pump();
  }

  testWidgets('each reopened detail queries durable resume receipt without execution', (tester) async {
    var reads = 0, writes = 0;
    mock('getAgentTaskDetail', (_) async => [resumable()]);
    mock('getTaskResumeReceipt', (_) async { reads++; return [null]; });
    mock('taskCommand', (_) async { writes++; return [SimpleCommandResult(success: true, idResult: 'task')]; });
    await show(tester);
    expect(reads, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    await show(tester);
    expect(reads, 2);
    expect(writes, 0);
  });

  testWidgets('resume exact catalog task only new DTO changes status', (
    tester,
  ) async {
    var detail = resumable();
    final ack = Completer<Object?>();
    final requests = <List<Object?>>[];
    mock('getAgentTaskDetail', (_) async => [detail]);
    mock('taskCommand', (args) {
      requests.add(args);
      return ack.future;
    });
    final container = await show(tester);
    await click(tester, '恢复此任务');
    expect(requests.single.sublist(0, 3), [
      'owner',
      'original-conversation',
      'task',
    ]);
    expect(requests.single.sublist(4), [1, 1, 8, 'resume', '']);
    expect(find.text('已暂停'), findsOneWidget);
    expect(container.read(activeConversationIdProvider), isNull);
    detail = resumable();
    detail.task.state = 'QUEUED';
    detail.task.manualPaused = false;
    detail.task.revision = 2;
    ack.complete([SimpleCommandResult(success: true, idResult: 'task')]);
    await tester.pumpAndSettle();
    expect(find.text('排队中'), findsOneWidget);
    expect(find.textContaining('locked-model'), findsOneWidget);
  });
  testWidgets(
    'lost resume ACK retains exact request across refresh and allows safety',
    (tester) async {
      var detail = resumable();
      final requests = <List<Object?>>[];
      mock('getAgentTaskDetail', (_) async => [detail]);
      mock('taskCommand', (args) async {
        requests.add(args);
        if (requests.length == 1) {
          return ['DISCONNECTED', 'synthetic lost ACK', null];
        }
        return [SimpleCommandResult(success: true, idResult: 'task')];
      });
      final safety = <List<Object?>>[];
      mock('taskSafetyCommand', (args) async {
        safety.add(args);
        return [SimpleCommandResult(success: true, idResult: 'task')];
      });
      await show(tester);
      await click(tester, '恢复此任务');
      expect(find.textContaining('恢复回执未确认'), findsOneWidget);
      await click(tester, '取消任务');
      expect(safety.single.last, 'cancel');
      detail = resumable();
      detail.task.revision = 99;
      detail.task.state = 'CANCELED';
      await click(tester, '刷新详情');
      await tester.pumpAndSettle();
      await click(tester, '查询恢复回执');
      await tester.pumpAndSettle();
      expect(requests.length, 1);
      await click(tester, '重试原恢复命令');
      await tester.pumpAndSettle();
      expect(requests[1], requests[0]);
    },
  );
  testWidgets(
    'precondition rejection refreshes without automatically resending',
    (tester) async {
      var reads = 0, writes = 0;
      mock('getAgentTaskDetail', (_) async {
        final detail = resumable();
        if (++reads > 1) {
          detail.conversationAvailable = false;
        }
        return [detail];
      });
      mock('taskCommand', (_) async {
        writes++;
        return [
          SimpleCommandResult(
            success: false,
            error: StudyErrorDto(
              code: 'TASK_PRECONDITION_REJECTED',
              message: 'branch changed',
              retryable: false,
              requestId: 'rejected',
            ),
          ),
        ];
      });
      await show(tester);
      await click(tester, '恢复此任务');
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(reads, 2);
      expect(find.widgetWithText(TextButton, '查询恢复回执'), findsNothing);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '恢复此任务'))
            .onPressed,
        isNull,
      );
    },
  );
  testWidgets('foreign ACK stays uncertain and old profile ACK is discarded', (
    tester,
  ) async {
    final pending = Completer<Object?>();
    var writes = 0;
    mock('getAgentTaskDetail', (_) async => [resumable()]);
    mock('taskCommand', (_) async {
      if (++writes == 1) {
        return [SimpleCommandResult(success: true, idResult: 'foreign')];
      }
      return pending.future;
    });
    final container = await show(tester);
    await click(tester, '恢复此任务');
    expect(find.textContaining('恢复回执未确认'), findsOneWidget);
    await click(tester, '重试原恢复命令');
    container.read(contextEpochProvider.notifier).state = 2;
    pending.complete([SimpleCommandResult(success: true, idResult: 'task')]);
    await tester.pumpAndSettle();
    expect(find.text('档案或上下文已变化，请重新打开任务详情。'), findsOneWidget);
  });
  testWidgets(
    'unavailable conversation independent blockers unknown and terminals disable resume',
    (tester) async {
      final variants = <AgentCatalogDetailDto>[
        resumable()..conversationAvailable = false,
        resumable()..task.authorization = 'REVOKED',
        resumable()..task.inputBlocked = true,
        resumable()..task.deviceBlocked = true,
        resumable()..task.deviceConfirmation = true,
        resumable()..task.approvalBlocked = true,
        resumable()..task.state = 'UNCERTAIN_SIDE_EFFECT',
        resumable()..task.state = 'SUCCEEDED',
        resumable()..task.state = 'STOP_REQUESTED',
      ];
      for (final detail in variants) {
        mock('getAgentTaskDetail', (_) async => [detail]);
        await show(tester);
        expect(
          tester
              .widget<TextButton>(find.widgetWithText(TextButton, '恢复此任务'))
              .onPressed,
          isNull,
        );
        await tester.pumpWidget(const SizedBox.shrink());
      }
    },
  );
  testWidgets('narrow 200 percent dark resume control is usable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    mock('getAgentTaskDetail', (_) async => [resumable()]);
    mock(
      'taskCommand',
      (_) async => [SimpleCommandResult(success: true, idResult: 'task')],
    );
    await show(tester, largeDark: true);
    await click(tester, '恢复此任务');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('reopened committed command is read only until exact coordination', (tester) async {
    final requests = <List<Object?>>[];
    mock('getAgentTaskDetail', (_) async => [resumable()]);
    mock('getTaskResumeReceipt', (_) async => [TaskResumeReceiptDto(
      owner: 'owner', taskId: 'task', conversationId: 'original-conversation',
      requestId: 'durable-original', expectedRevision: 0, expectedFence: 8, canCoordinate: true)]);
    mock('taskCommand', (args) async {
      requests.add(args);
      return [SimpleCommandResult(success: true, idResult: 'task')];
    });
    await show(tester);
    expect(find.textContaining('原恢复命令已记录'), findsOneWidget);
    expect(requests, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    await show(tester, largeDark: true);
    expect(requests, isEmpty);
    await click(tester, '继续协调原恢复');
    expect(requests.single, ['owner', 'original-conversation', 'task', 'durable-original', 1, 0, 8, 'resume', '']);
    expect(find.text('已暂停'), findsOneWidget);
  });

  testWidgets('receipt query failure and foreign receipt block new resume', (tester) async {
    var mode = 0, writes = 0;
    mock('getAgentTaskDetail', (_) async => [resumable()]);
    mock('taskCommand', (_) async { writes++; return [SimpleCommandResult(success: true, idResult: 'task')]; });
    mock('getTaskResumeReceipt', (_) async {
      if (mode == 0) { return ['DISCONNECTED', 'synthetic', null]; }
      if (mode == 1) { return [TaskResumeReceiptDto(owner: 'foreign', taskId: 'task',
        conversationId: 'original-conversation', requestId: 'original', expectedRevision: 0, expectedFence: 8, canCoordinate: true)]; }
      return [null];
    });
    await show(tester);
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '恢复此任务')).onPressed, isNull);
    mode = 1;
    await click(tester, '重新核对恢复回执');
    await tester.pumpAndSettle();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '恢复此任务')).onPressed, isNull);
    mode = 2;
    await click(tester, '重新核对恢复回执');
    await tester.pumpAndSettle();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '恢复此任务')).onPressed, isNotNull);
    expect(writes, 0);
  });
}
