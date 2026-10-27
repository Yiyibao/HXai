import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_alerts.dart';
import 'package:flutter_ui/widgets/agent_task_panel.dart';

class _Profile extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}

class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 1;
}
class _Conversation extends ActiveConversationIdNotifier {
  @override
  String? build() => 'conversation';
}

AgentUsageAlertsDto alerts({
  String owner = 'owner',
  String? task = 'task',
  int? from,
  int? until,
  int unknown = 1,
  int unreported = 1,
  int dense = 1,
  int requests = 20,
}) => AgentUsageAlertsDto(
  owner: owner,
  taskId: task,
  from: from,
  until: until,
  evaluatedAt: 500000,
  uncertainRequests: unknown,
  unreportedCompletedRequests: unreported,
  denseTaskCount: dense,
  denseRequests: requests,
  undatedRequests: 1,
);
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final channel = BasicMessageChannel<Object?>(
    'dev.flutter.pigeon.flutter_ui.StudyHostApi.getAgentUsageAlerts',
    StudyHostApi.pigeonChannelCodec,
  );
  tearDown(
    () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
  );
  Future<ProviderContainer> show(
    WidgetTester t, {
    bool auto = false,
    bool compact = false,
    double scale = 1,
    bool dark = false,
    GlobalKey<NavigatorState>? navigatorKey,
  }) async {
    final c = ProviderContainer(
      overrides: [
        activeProfileIdProvider.overrideWith(_Profile.new),
        contextEpochProvider.overrideWith(_Epoch.new),
      ],
    );
    addTearDown(c.dispose);
    await t.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          navigatorKey: navigatorKey,
          theme: ThemeData(
            brightness: dark ? Brightness.dark : Brightness.light,
          ),
          home: MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: Scaffold(
              body: SingleChildScrollView(
                child: AgentUsageAlertsView(
                  owner: 'owner',
                  taskId: 'task',
                  expectedEpoch: 1,
                  autoRefresh: auto,
                  compact: compact,
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await t.pump();
    await t.pump();
    return c;
  }

  testWidgets('Collapsed Chat task exposes advisory without dispatching a command', (t) async {
    final taskChannel = BasicMessageChannel<Object?>('dev.flutter.pigeon.flutter_ui.StudyHostApi.getConversationTask', StudyHostApi.pigeonChannelCodec);
    messenger.setMockDecodedMessageHandler<Object?>(taskChannel, (_) async => [AgentTaskDto(
      id: 'task', owner: 'owner', state: 'UNCERTAIN_SIDE_EFFECT', providerId: 'p', modelId: 'm', reasoning: 'HIGH',
      providerName: 'Synthetic', modelName: 'Locked model', providerOrigin: 'http://127.0.0.1', revision: 1,
      fence: 1, grantEpoch: 1, authorization: 'ACTIVE', manualPaused: false, inputBlocked: false,
      deviceBlocked: false, deviceConfirmation: false, goalRevision: 1, plannedRevision: 1,
      acceptanceVerified: false, verifiedSteps: [], approvalCallIds: [])]);
    addTearDown(() => messenger.setMockDecodedMessageHandler<Object?>(taskChannel, null));
    var calls = 0;
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async { calls++; expect((args! as List)[1], 'task'); return [alerts()]; });
    await t.pumpWidget(ProviderScope(overrides: [activeProfileIdProvider.overrideWith(_Profile.new),
      contextEpochProvider.overrideWith(_Epoch.new), activeConversationIdProvider.overrideWith(_Conversation.new)],
      child: const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: AgentTaskPanel(conversationId: 'conversation'))))));
    await t.pumpAndSettle();
    expect(calls, 1);expect(find.textContaining('1 次请求结果未确认'), findsOneWidget);
    expect(find.text('明确恢复'), findsNothing);
    await t.pumpWidget(const SizedBox());
  });
  testWidgets(
    'Counts warn about uncertainty without inventing charge or retry decisions',
    (t) async {
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => [alerts()],
      );
      await show(t);
      await t.pumpAndSettle();
      expect(find.textContaining('1 次请求结果未确认'), findsOneWidget);
      expect(find.textContaining('1 次已完成请求未报告用量'), findsOneWidget);
      expect(find.textContaining('共 20 次'), findsOneWidget);
      expect(find.textContaining('不自动停止或重发'), findsOneWidget);
    },
  );
  testWidgets(
    'Clean counts do not imply free requests; compact clean surface stays hidden',
    (t) async {
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => [alerts(unknown: 0, unreported: 0, dense: 0, requests: 0)],
      );
      await show(t);
      await t.pumpAndSettle();
      expect(find.textContaining('费用仍未知'), findsOneWidget);
      await show(t, compact: true);
      await t.pumpAndSettle();
      expect(find.text('请求用量提醒'), findsNothing);
    },
  );
  for (final bad in [
    alerts(owner: 'foreign'),
    alerts(task: 'other'),
    alerts(from: 100, until: 200),
    alerts(unknown: -1),
    alerts(requests: 19),
  ]) {
    testWidgets('Untrusted DTO rejected ${bad.encode()}', (t) async {
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => [bad],
      );
      await show(t);
      await t.pumpAndSettle();
      expect(find.textContaining('提醒读取失败'), findsOneWidget);
      expect(find.textContaining('未触发上述提醒'), findsNothing);
    });
  }
  testWidgets('Foreign epoch hides a pending old-owner response', (t) async {
    final pending = Completer<Object?>();
    messenger.setMockDecodedMessageHandler<Object?>(
      channel,
      (_) => pending.future,
    );
    final c = await show(t);
    c.read(contextEpochProvider.notifier).state = 2;
    c.read(activeProfileIdProvider.notifier).state = 'other';
    await t.pump();
    pending.complete([alerts()]);
    await t.pumpAndSettle();
    expect(find.text('请求用量提醒'), findsNothing);
  });
  testWidgets(
    'Polling pauses in background, resumes from ledger and clears stale success after error',
    (t) async {
      var calls = 0;
      messenger.setMockDecodedMessageHandler<Object?>(channel, (_) async {
        calls++;
        return calls == 1 ? [alerts()] : ['READ_FAILED', 'synthetic', null];
      });
      await show(t, auto: true);
      await t.pumpAndSettle();
      expect(calls, 1);
      t.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await t.pump(const Duration(seconds: 31));
      expect(calls, 1);
      t.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await t.pumpAndSettle();
      expect(calls, 2);
      expect(find.textContaining('提醒读取失败'), findsOneWidget);
      expect(find.textContaining('共 20 次'), findsNothing);
      await t.pumpWidget(const SizedBox());
      await t.pump(const Duration(seconds: 31));
      expect(calls, 2);
    },
  );
  testWidgets('A retained Chat route stops querying while another page is current', (t) async {
    var calls = 0;
    messenger.setMockDecodedMessageHandler<Object?>(channel, (_) async { calls++; return [alerts()]; });
    final navigation = GlobalKey<NavigatorState>();
    await show(t, auto: true, navigatorKey: navigation);await t.pumpAndSettle();expect(calls, 1);
    unawaited(navigation.currentState!.push<void>(MaterialPageRoute(builder: (_) => const Scaffold(body: Text('Another page')))));
    await t.pumpAndSettle();await t.pump(const Duration(seconds: 31));expect(calls, 1);
    navigation.currentState!.pop();await t.pumpAndSettle();expect(calls, 2);
    await t.pumpWidget(const SizedBox());
  });
  for (final dark in [false, true]) {
    testWidgets(
      '320px 200% and separate semantics in ${dark ? 'dark' : 'light'}',
      (t) async {
        await t.binding.setSurfaceSize(const Size(320, 640));
        addTearDown(() => t.binding.setSurfaceSize(null));
        final semantics = t.ensureSemantics();
        messenger.setMockDecodedMessageHandler<Object?>(
          channel,
          (_) async => [alerts()],
        );
        await show(t, scale: 2, dark: dark);
        await t.pumpAndSettle();
        await t.ensureVisible(find.textContaining('不自动停止或重发'));
        expect(t.takeException(), isNull);
        expect(find.bySemanticsLabel(RegExp('.*不自动停止或重发.*')), findsOneWidget);
        semantics.dispose();
      },
    );
  }
}
