import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

class _Profile extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}
class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 1;
}

AgentUsageMetricDto metric({int? reported, int? estimated, int unknown = 0}) =>
    AgentUsageMetricDto(reported: reported, estimated: estimated, unknownRequests: unknown);
AgentUsageEntryDto entry(String id, {String provenance = 'unknown', int? input}) => AgentUsageEntryDto(
    requestId: id, taskId: 'task', conversationId: 'conversation', role: 'ocr', state: 'UNKNOWN',
    provenance: provenance, inputTokens: input);
AgentUsagePageDto page(List<Object?> args, {String? next, List<AgentUsageEntryDto> entries = const [],
    int count = 2, AgentUsageMetricDto? input, String? owner}) => AgentUsagePageDto(
      owner: owner ?? args[0]! as String, taskId: args[1] as String?, from: args[2] as int?, until: args[3] as int?,
      requestCount: count, reportedRequests: 1, estimatedRequests: 0, unknownRequests: 1, undatedRequests: 1,
      input: input ?? metric(reported: 0, unknown: 1), output: metric(unknown: 2),
      cached: metric(unknown: 2), total: metric(unknown: 2), entries: entries, nextCursor: next);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.getAgentUsage', StudyHostApi.pigeonChannelCodec);
  tearDown(() => messenger.setMockDecodedMessageHandler<Object?>(channel, null));

  Future<ProviderContainer> show(WidgetTester tester, {double scale = 1, bool dark = false}) async {
    final container = ProviderContainer(overrides: [activeProfileIdProvider.overrideWith(_Profile.new),
      contextEpochProvider.overrideWith(_Epoch.new)]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: MaterialApp(
      theme: ThemeData(brightness: dark ? Brightness.dark : Brightness.light),
      home: MediaQuery(data: MediaQueryData(textScaler: TextScaler.linear(scale)), child: const Scaffold(
        body: AgentUsageView(owner: 'owner', taskId: 'task', expectedEpoch: 1))))));
    await tester.pump(); await tester.pump();
    return container;
  }

  testWidgets('Unknown counters never become zero and report zero stays visible', (tester) async {
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async =>
        [page((args! as List).cast<Object?>(), entries: [entry('unknown-request')])]);
    await show(tester);
    expect(find.text('输入 Token：报告 0 · 1 次未知'), findsOneWidget);
    expect(find.text('总 Token：2 次未知'), findsOneWidget);
    expect(find.textContaining('费用未知'), findsOneWidget);
    expect(find.textContaining('模型：未知'), findsOneWidget);
    expect(find.textContaining('总计 未知'), findsOneWidget);
  });

  testWidgets('Profile filter includes auxiliary requests and preserves estimates', (tester) async {
    final calls = <List<Object?>>[];
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async {
      final a = (args! as List).cast<Object?>(); calls.add(a);
      return [page(a, input: metric(reported: 3, estimated: 5, unknown: 1))];
    });
    await show(tester);
    await tester.tap(find.text('此任务')); await tester.pumpAndSettle();
    await tester.tap(find.text('当前档案（含辅助任务）').last); await tester.pumpAndSettle();
    expect(calls.last[1], isNull);
    expect(calls.last[6], 1);
    expect(find.text('输入 Token：报告 3 · 估算 5 · 1 次未知'), findsOneWidget);
  });

  testWidgets('Late old period result cannot replace newest filtered data', (tester) async {
    final old = Completer<Object?>();
    final calls = <List<Object?>>[];
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async {
      final a = (args! as List).cast<Object?>(); calls.add(a);
      if (a[2] == null) { return old.future; }
      return [page(a, entries: [entry('new-period')])];
    });
    await show(tester);
    await tester.tap(find.text('全部')); await tester.pump(const Duration(milliseconds: 500));
    await tester.tap(find.text('今日').last); await tester.pumpAndSettle();
    expect(calls.last[2], isA<int>());
    expect((calls.last[3]! as int), greaterThan(calls.last[2]! as int));
    expect(find.textContaining('new-period'), findsOneWidget);
    old.complete([page(calls.first, entries: [entry('stale-period')])]);
    await tester.pumpAndSettle();
    expect(find.textContaining('stale-period'), findsNothing);
    expect(find.textContaining('未计入本周期'), findsOneWidget);
  });

  testWidgets('Profile epoch change discards an outstanding response', (tester) async {
    final ack = Completer<Object?>();
    List<Object?>? args;
    messenger.setMockDecodedMessageHandler<Object?>(channel, (a) async {
      args = (a! as List).cast<Object?>(); return ack.future;
    });
    final container = await show(tester);
    container.read(activeProfileIdProvider.notifier).state = 'other-owner';
    container.read(contextEpochProvider.notifier).state = 2;
    await tester.pump();
    ack.complete([page(args!, entries: [entry('private-old-response')])]);
    await tester.pumpAndSettle();
    expect(find.textContaining('private-old-response'), findsNothing);
    expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
  });

  testWidgets('Failed continuation can retry cursor and keeps full summary', (tester) async {
    var attempts = 0;
    final cursors = <Object?>[];
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async {
      final a = (args! as List).cast<Object?>(); cursors.add(a[4]);
      if (a[4] == null) { return [page(a, next: 'page-2', entries: [entry('first')], count: 45)]; }
      if (attempts++ == 0) { return ['READ_FAILED', 'synthetic', null]; }
      return [page(a, entries: [entry('second')], count: 45)];
    });
    await show(tester);
    await tester.ensureVisible(find.text('加载更多请求'));
    await tester.tap(find.text('加载更多请求')); await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('重试读取'));
    await tester.tap(find.text('重试读取')); await tester.pumpAndSettle();
    expect(cursors, [null, 'page-2', 'page-2']);
    expect(find.textContaining('范围内 45 次请求'), findsOneWidget);
    expect(find.textContaining('请求：first'), findsOneWidget);
    expect(find.textContaining('请求：second'), findsOneWidget);
  });

  testWidgets('Wrong-owner DTO is rejected instead of rendered', (tester) async {
    messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async => [page(
      (args! as List).cast<Object?>(), owner: 'other-owner', entries: [entry('forbidden')])]);
    await show(tester);
    expect(find.textContaining('forbidden'), findsNothing);
    expect(find.textContaining('用量读取失败'), findsOneWidget);
  });

  for (final dark in [false, true]) {
    testWidgets('320px and 200% text remains scrollable (${dark ? 'dark' : 'light'})', (tester) async {
      await tester.binding.setSurfaceSize(const Size(320, 640));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async => [page(
        (args! as List).cast<Object?>(), entries: [entry('long-request-${'x' * 120}')], next: 'next')]);
      await show(tester, scale: 2, dark: dark);
      await tester.ensureVisible(find.text('加载更多请求'));
      expect(tester.takeException(), isNull);
      expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '加载更多请求')).onPressed, isNotNull);
    });
  }
}
