import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

import 'agent_usage_view_test.dart' show page, metric;

AgentUsageGroupDto group({
  String? provider,
  String? model,
  String? role,
  int unknown = 1,
}) => AgentUsageGroupDto(
  providerId: provider,
  modelId: model,
  role: role,
  requestCount: 2,
  reportedRequests: 1,
  estimatedRequests: 0,
  unknownRequests: 1,
  input: metric(reported: 0, unknown: unknown),
  output: metric(unknown: 2),
  cached: metric(unknown: 2),
  total: metric(unknown: 2),
);
AgentUsageGroupsPageDto grouped(
  List<Object?> args, {
  List<AgentUsageGroupDto>? rows,
  String? next,
  String? owner,
  String? mode,
  int count = 100,
  int groups = 40,
}) => AgentUsageGroupsPageDto(
  owner: owner ?? args[0]! as String,
  taskId: args[1] as String?,
  from: args[2] as int?,
  until: args[3] as int?,
  groupBy: mode ?? args[4]! as String,
  requestCount: count,
  groupCount: groups,
  undatedRequests: 1,
  groups:
      rows ??
      [
        group(
          provider: 'actual-provider',
          model: (args[4] == 'model' ? 'actual-model' : null),
        ),
      ],
  nextCursor: next,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final channels = <BasicMessageChannel<Object?>>[];
  void mock(String name, Future<Object?> Function(List<Object?>) callback) {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$name',
      StudyHostApi.pigeonChannelCodec,
    );
    channels.add(channel);
    messenger.setMockDecodedMessageHandler<Object?>(
      channel,
      (args) => callback((args! as List).cast<Object?>()),
    );
  }

  tearDown(() {
    for (final c in channels) {
      messenger.setMockDecodedMessageHandler<Object?>(c, null);
    }
    channels.clear();
  });
  Future<ProviderContainer> show(
    WidgetTester tester, {
    bool dark = false,
    double scale = 1,
  }) async {
    mock('getAgentUsage', (args) async => [page(args)]);
    final container = ProviderContainer(
      overrides: [
        bootstrapProvider.overrideWith(
          (_) async => StudyBootstrapDto(
            currentProfileId: 'owner',
            contextEpoch: 1,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: false,
            darkMode: false,
          ),
        ),
      ],
    );
    addTearDown(container.dispose);
    await container.read(bootstrapProvider.future);
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
          home: const Scaffold(
            body: AgentUsageView(
              owner: 'owner',
              taskId: null,
              expectedEpoch: 1,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  Future<void> choose(
    WidgetTester tester,
    String current,
    String desired,
  ) async {
    await tester.ensureVisible(find.text(current).first);
    await tester.tap(find.text(current).first);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text(desired).last);
    await tester.pump();
    await tester.pump();
  }

  testWidgets(
    'Groups use full window counts physical identity and nullable fields',
    (tester) async {
      final calls = <List<Object?>>[];
      mock('getAgentUsageGroups', (a) async {
        calls.add(a);
        return [
          grouped(a, rows: [group()]),
        ];
      });
      final container = await show(tester);
      await choose(tester, '逐请求', '按模型');
      await tester.pumpAndSettle();
      expect(container.read(activeConversationIdProvider), isNull);
      expect(calls.single[1], isNull);
      expect(calls.single[4], 'model');
      expect(calls.single[7], 1);
      expect(
        find.textContaining('范围内 100 次请求 · 共 40 个分组 · 已显示 1 个'),
        findsOneWidget,
      );
      expect(find.textContaining('提供商：未知提供商\n模型：未知模型'), findsOneWidget);
      expect(find.text('输入 Token：报告 0 · 1 次未知'), findsOneWidget);
      expect(find.text('总 Token：2 次未知'), findsOneWidget);
      expect(find.textContaining('费用未知'), findsOneWidget);
    },
  );
  testWidgets(
    'Role summary and period share the actual scope and retries retain group cursor',
    (tester) async {
      final cursors = <Object?>[];
      var failures = 0;
      mock('getAgentUsageGroups', (a) async {
        cursors.add(a[5]);
        if (a[5] == null) {
          return [
            grouped(
              a,
              rows: [group(role: 'ocr')],
              next: 'next',
            ),
          ];
        }
        if (failures++ == 0) {
          return ['FAILED', 'synthetic', null];
        }
        return [
          grouped(
            a,
            rows: [
              group(role: 'ocr'),
              group(role: 'title'),
            ],
          ),
        ];
      });
      await show(tester);
      await choose(tester, '逐请求', '按角色');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('加载更多分组'));
      await tester.tap(find.text('加载更多分组'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('重试读取'));
      await tester.tap(find.text('重试读取'));
      await tester.pumpAndSettle();
      expect(cursors, [null, 'next', 'next']);
      expect(find.text('角色：OCR'), findsOneWidget);
      expect(find.text('角色：标题'), findsOneWidget);
      expect(find.textContaining('已显示 2 个'), findsOneWidget);
      await choose(tester, '全部', '今日');
      await tester.pumpAndSettle();
      expect(find.textContaining('未计入本周期'), findsOneWidget);
    },
  );
  testWidgets(
    'Late model grouping cannot replace a newer role or requests view',
    (tester) async {
      final stale = Completer<Object?>();
      List<Object?>? oldArgs;
      mock('getAgentUsageGroups', (a) async {
        if (a[4] == 'model') {
          oldArgs = a;
          return stale.future;
        }
        return [
          grouped(a, rows: [group(role: 'compression')]),
        ];
      });
      await show(tester);
      await choose(tester, '逐请求', '按模型');
      await choose(tester, '按模型', '按角色');
      await tester.pumpAndSettle();
      stale.complete([
        grouped(
          oldArgs!,
          rows: [group(provider: 'stale-provider', model: 'stale-model')],
        ),
      ]);
      await tester.pumpAndSettle();
      expect(find.textContaining('stale-model'), findsNothing);
      expect(find.text('角色：上下文压缩'), findsOneWidget);
      await choose(tester, '按角色', '逐请求');
      await tester.pumpAndSettle();
      expect(find.textContaining('个分组'), findsNothing);
    },
  );
  testWidgets('Wrong scope dimension or impossible field counts are rejected', (
    tester,
  ) async {
    mock(
      'getAgentUsageGroups',
      (a) async => [
        grouped(
          a,
          owner: 'foreign',
          rows: [group(provider: 'forbidden')],
        ),
      ],
    );
    await show(tester);
    await choose(tester, '逐请求', '按提供商');
    await tester.pumpAndSettle();
    expect(find.textContaining('forbidden'), findsNothing);
    expect(find.textContaining('用量读取失败'), findsOneWidget);
    mock(
      'getAgentUsageGroups',
      (a) async => [
        grouped(a, rows: [group(provider: 'bad-count', unknown: 3)]),
      ],
    );
    await tester.tap(find.text('重试读取'));
    await tester.pumpAndSettle();
    expect(find.textContaining('bad-count'), findsNothing);
    mock(
      'getAgentUsageGroups',
      (a) async => [
        grouped(
          a,
          mode: 'role',
          rows: [group(role: 'wrong-dimension')],
        ),
      ],
    );
    await tester.tap(find.text('重试读取'));
    await tester.pumpAndSettle();
    expect(find.textContaining('wrong-dimension'), findsNothing);
  });
  testWidgets(
    'Owner epoch switch discards an outstanding private group result',
    (tester) async {
      final stale = Completer<Object?>();
      List<Object?>? args;
      mock('getAgentUsageGroups', (a) async {
        args = a;
        return stale.future;
      });
      final container = await show(tester);
      await choose(tester, '逐请求', '按模型');
      container.read(activeProfileIdProvider.notifier).state = 'other';
      container.read(contextEpochProvider.notifier).state = 2;
      await tester.pump();
      stale.complete([
        grouped(
          args!,
          rows: [group(provider: 'private-old', model: 'private-model')],
        ),
      ]);
      await tester.pumpAndSettle();
      expect(find.textContaining('private-model'), findsNothing);
      expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
    },
  );
  for (final dark in [false, true]) {
    testWidgets(
      '320px 200 percent grouped metadata remains scrollable ${dark ? 'dark' : 'light'}',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(320, 640));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        mock(
          'getAgentUsageGroups',
          (a) async => [
            grouped(
              a,
              rows: [
                group(provider: 'p-${'x' * 200}', model: 'm-${'数' * 200}'),
              ],
              next: 'next',
            ),
          ],
        );
        await show(tester, dark: dark, scale: 2);
        await choose(tester, '逐请求', '按模型');
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('加载更多分组'));
        expect(tester.takeException(), isNull);
        expect(
          tester
              .widget<TextButton>(find.widgetWithText(TextButton, '加载更多分组'))
              .onPressed,
          isNotNull,
        );
      },
    );
  }
}
