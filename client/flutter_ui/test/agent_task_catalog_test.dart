import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/agent_tasks_page.dart';
import 'package:flutter_ui/pages/agent_task_detail_page.dart';
import 'package:flutter_ui/providers/profile_resource.dart';
import 'package:flutter_ui/providers/study_providers.dart';

import 'agent_usage_view_test.dart' show page;

const resource = ProfileResourceRoute(id: 'task', profileId: 'owner', epoch: 1);
AgentCatalogTaskDto task(
  String id, {
  String owner = 'owner',
  String state = 'STOP_REQUESTED',
  String? goal,
  int revision = 1,
}) => AgentCatalogTaskDto(
  id: id,
  owner: owner,
  conversationId: 'missing-conversation',
  role: 'main',
  goal: goal ?? 'goal-$id',
  state: state,
  providerId: 'accepted-provider',
  modelId: 'accepted-model',
  reasoning: 'HIGH',
  revision: revision,
  fence: 8,
  manualPaused: true,
  authorization: 'REVOKED',
  inputBlocked: true,
  deviceBlocked: true,
  deviceConfirmation: true,
  approvalBlocked: true,
  goalRevision: 2,
  plannedRevision: 1,
  acceptanceVerified: false,
);
AgentCatalogDetailDto detail(
  String id, {
  String owner = 'owner',
  int revision = 1,
  String? next,
  List<AgentCatalogCallDto> calls = const [],
}) => AgentCatalogDetailDto(
  task: task(id, owner: owner, revision: revision),
  steps: [
    AgentCatalogStepDto(
      id: 'step',
      tool: 'get_time_info',
      purpose: 'actual-purpose',
      state: 'VERIFIED',
    ),
  ],
  calls: calls,
  artifacts: [
    AgentCatalogArtifactDto(
      id: 'artifact',
      name: '长文件名${'文' * 80}.md',
      sha256: 'a' * 64,
    ),
  ],
  resultsPartial: true,
  conversationAvailable: false,
  nextCursor: next,
);
AgentCatalogCallDto call(String id) => AgentCatalogCallDto(
  id: id,
  tool: 'get_time_info',
  state: 'UNKNOWN',
  inputHash: 'b' * 64,
);
StudyBootstrapDto bootstrap() => StudyBootstrapDto(
  currentProfileId: 'owner',
  contextEpoch: 1,
  profiles: [],
  studyMode: 'CHAT',
  reduceMotion: false,
  darkMode: false,
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

  tearDown(() {
    for (final c in channels) {
      messenger.setMockDecodedMessageHandler<Object?>(c, null);
    }
    channels.clear();
  });
  Future<ProviderContainer> show(
    WidgetTester tester,
    Widget child, {
    double scale = 1,
    bool dark = false,
    Future<StudyBootstrapDto> Function()? load,
  }) async {
    final container = ProviderContainer(
      overrides: [
        bootstrapProvider.overrideWith(
          (_) => load?.call() ?? Future.value(bootstrap()),
        ),
      ],
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
          home: child,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    return container;
  }

  testWidgets(
    'catalog returns cross-conversation records and preserves stop and blocker truth',
    (tester) async {
      mock(
        'getAgentTaskCatalog',
        (a) async => [
          AgentCatalogPageDto(
            owner: 'owner',
            kind: a[1]! as String,
            tasks: [
              task('one'),
              task('two', state: 'COMPLETED_UNVERIFIED'),
            ],
          ),
        ],
      );
      final container = await show(tester, const AgentTasksPage());
      expect(container.read(activeConversationIdProvider), isNull);
      expect(find.text('已请求停止，尚未确认停止'), findsOneWidget);
      expect(find.text('执行已结束，目标尚未独立验收'), findsOneWidget);
      expect(find.textContaining('已接受模型 accepted-model'), findsNWidgets(2));
      expect(
        find.textContaining('用户暂停 · REVOKED · 缺少输入 · 设备不可用 · 前台确认 · 工具审批'),
        findsNWidgets(2),
      );
    },
  );
  testWidgets(
    'failed pagination retries same cursor and rejects duplicate IDs',
    (tester) async {
      final cursors = <Object?>[];
      var attempts = 0;
      mock('getAgentTaskCatalog', (a) async {
        cursors.add(a[2]);
        if (a[2] == null) {
          return [
            AgentCatalogPageDto(
              owner: 'owner',
              kind: 'all',
              tasks: [task('one')],
              nextCursor: 'next',
            ),
          ];
        }
        if (attempts++ == 0) return ['FAILED', 'synthetic', null];
        return [
          AgentCatalogPageDto(
            owner: 'owner',
            kind: 'all',
            tasks: [task('one'), task('two')],
          ),
        ];
      });
      await show(tester, const AgentTasksPage());
      await tester.scrollUntilVisible(
        find.text('加载更多任务'),
        250,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.tap(find.text('加载更多任务'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('重试读取任务'));
      await tester.tap(find.text('重试读取任务'));
      await tester.pumpAndSettle();
      expect(cursors, [null, 'next', 'next']);
      expect(find.text('goal-one'), findsOneWidget);
      expect(find.text('goal-two'), findsOneWidget);
    },
  );
  testWidgets('late all-task page cannot replace the auxiliary filter', (
    tester,
  ) async {
    final old = Completer<Object?>();
    mock(
      'getAgentTaskCatalog',
      (a) async => a[1] == 'all'
          ? old.future
          : [
              AgentCatalogPageDto(
                owner: 'owner',
                kind: 'auxiliary',
                tasks: [task('aux')],
              ),
            ],
    );
    await show(tester, const AgentTasksPage());
    await tester.tap(find.text('全部任务'));
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.text('辅助请求').last);
    await tester.pumpAndSettle();
    old.complete([
      AgentCatalogPageDto(
        owner: 'owner',
        kind: 'all',
        tasks: [task('private-old')],
      ),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('goal-aux'), findsOneWidget);
    expect(find.text('goal-private-old'), findsNothing);
  });
  testWidgets('wrong owner row is rejected as a whole page', (tester) async {
    mock(
      'getAgentTaskCatalog',
      (a) async => [
        AgentCatalogPageDto(
          owner: 'owner',
          kind: 'all',
          tasks: [task('forbidden', owner: 'other')],
        ),
      ],
    );
    await show(tester, const AgentTasksPage());
    expect(find.text('goal-forbidden'), findsNothing);
    expect(find.textContaining('任务目录读取失败'), findsOneWidget);
  });
  testWidgets('owner epoch switch masks pending catalog data', (tester) async {
    final old = Completer<Object?>();
    mock('getAgentTaskCatalog', (a) async => old.future);
    final container = await show(
      tester,
      const Scaffold(body: AgentTaskCatalogView(owner: 'owner', epoch: 1)),
    );
    container.read(activeProfileIdProvider.notifier).state = 'other';
    container.read(contextEpochProvider.notifier).state = 2;
    await tester.pump();
    old.complete([
      AgentCatalogPageDto(owner: 'owner', kind: 'all', tasks: [task('secret')]),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('goal-secret'), findsNothing);
    expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
  });
  testWidgets('global usage sends null task without a current chat', (
    tester,
  ) async {
    final requests = <List<Object?>>[];
    mock('getAgentUsage', (a) async {
      requests.add(a);
      return [page(a)];
    });
    final container = await show(
      tester,
      const AgentUsagePage(resource: resource),
    );
    expect(container.read(activeConversationIdProvider), isNull);
    expect(requests.single[1], isNull);
    expect(requests.single[6], 1);
    expect(find.text('此任务'), findsNothing);
    expect(find.textContaining('总 Token：2 次未知'), findsOneWidget);
  });
  testWidgets(
    'detail reads durable plan but missing conversation disables execution route',
    (tester) async {
      mock('getAgentTaskDetail', (a) async => [detail(a[1]! as String)]);
      await show(tester, const AgentTaskDetailPage(resource: resource));
      expect(find.textContaining('actual-purpose'), findsOneWidget);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '打开关联会话'))
            .onPressed,
        isNull,
      );
      expect(find.textContaining('保存的 SHA256'), findsOneWidget);
      expect(find.textContaining('部分回执过大'), findsOneWidget);
      expect(find.text('暂停'), findsNothing);
      expect(find.text('继续'), findsNothing);
    },
  );
  testWidgets('detail rejects wrong task ID and wrong owner', (tester) async {
    mock(
      'getAgentTaskDetail',
      (a) async => [detail('wrong-task', owner: 'other')],
    );
    await show(tester, const AgentTaskDetailPage(resource: resource));
    expect(find.text('goal-wrong-task'), findsNothing);
    expect(find.textContaining('任务详情读取失败'), findsOneWidget);
  });
  testWidgets('changed task route rejects an old detail completion', (
    tester,
  ) async {
    final old = Completer<Object?>();
    mock(
      'getAgentTaskDetail',
      (a) async => a[1] == 'task' ? old.future : [detail('new')],
    );
    final selected = ValueNotifier(resource);
    addTearDown(selected.dispose);
    await show(
      tester,
      ValueListenableBuilder(
        valueListenable: selected,
        builder: (context, r, _) => AgentTaskDetailPage(resource: r),
      ),
    );
    selected.value = const ProfileResourceRoute(
      id: 'new',
      profileId: 'owner',
      epoch: 1,
    );
    await tester.pumpAndSettle();
    old.complete([detail('task')]);
    await tester.pumpAndSettle();
    expect(find.text('goal-new'), findsOneWidget);
    expect(find.text('goal-task'), findsNothing);
  });
  testWidgets(
    'bootstrap loading hides detail and does not surface a late callback',
    (tester) async {
      var count = 0;
      final reload = Completer<StudyBootstrapDto>();
      final old = Completer<Object?>();
      mock('getAgentTaskDetail', (a) async => old.future);
      final container = await show(
        tester,
        const AgentTaskDetailPage(resource: resource),
        load: () => count++ == 0 ? Future.value(bootstrap()) : reload.future,
      );
      container.invalidate(bootstrapProvider);
      await tester.pump();
      old.complete([detail('task')]);
      await tester.pump();
      await tester.pump();
      expect(find.text('goal-task'), findsNothing);
      expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
      reload.complete(bootstrap());
      await tester.pumpAndSettle();
      expect(find.text('goal-task'), findsOneWidget);
    },
  );
  testWidgets(
    'revision change on later call page refreshes instead of combining stale receipts',
    (tester) async {
      var first = 0;
      final cursors = <Object?>[];
      mock('getAgentTaskDetail', (a) async {
        cursors.add(a[2]);
        if (a[2] != null) {
          return [
            detail('task', revision: 2, calls: [call('new')]),
          ];
        }
        return [
          first++ == 0
              ? detail('task', next: 'next', calls: [call('old')])
              : detail('task', revision: 2, calls: [call('fresh')]),
        ];
      });
      await show(tester, const AgentTaskDetailPage(resource: resource));
      await tester.scrollUntilVisible(
        find.text('加载更多调用'),
        250,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.tap(find.text('加载更多调用'));
      await tester.pumpAndSettle();
      expect(cursors, [null, 'next', null]);
      expect(find.textContaining('调用 fresh'), findsOneWidget);
      expect(find.textContaining('调用 old'), findsNothing);
    },
  );
  testWidgets(
    'catalog navigation passes identity and artifact preview uses actual route',
    (tester) async {
      ProfileResourceRoute? received;
      mock(
        'getAgentTaskCatalog',
        (a) async => [
          AgentCatalogPageDto(
            owner: 'owner',
            kind: 'all',
            tasks: [task('task')],
          ),
        ],
      );
      mock('getAgentTaskDetail', (a) async => [detail('task')]);
      final router = GoRouter(
        initialLocation: '/tasks',
        routes: [
          GoRoute(path: '/tasks', builder: (_, _) => const AgentTasksPage()),
          GoRoute(
            path: '/tasks/detail',
            builder: (_, s) =>
                AgentTaskDetailPage(resource: s.extra as ProfileResourceRoute),
          ),
          GoRoute(
            path: '/artifacts/preview',
            builder: (_, s) {
              received = s.extra as ProfileResourceRoute;
              return const Scaffold(body: Text('preview-route'));
            },
          ),
        ],
      );
      addTearDown(router.dispose);
      final container = ProviderContainer(
        overrides: [bootstrapProvider.overrideWith((_) async => bootstrap())],
      );
      addTearDown(container.dispose);
      await container.read(bootstrapProvider.future);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('goal-task'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('打开成果预览'));
      await tester.tap(find.text('打开成果预览'));
      await tester.pumpAndSettle();
      expect(find.text('preview-route'), findsOneWidget);
      expect(received?.id, 'artifact');
      expect(received?.profileId, 'owner');
      expect(received?.epoch, 1);
    },
  );
  for (final dark in [false, true]) {
    testWidgets(
      'catalog and detail remain usable at 320px and 200 percent ${dark ? 'dark' : 'light'}',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(320, 640));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        mock(
          'getAgentTaskCatalog',
          (a) async => [
            AgentCatalogPageDto(
              owner: 'owner',
              kind: 'all',
              tasks: [task('long', goal: '目标${'数' * 500}')],
              nextCursor: 'next',
            ),
          ],
        );
        mock(
          'getAgentTaskDetail',
          (a) async => [
            detail('task', next: 'next', calls: [call('long-${'x' * 150}')]),
          ],
        );
        await show(tester, const AgentTasksPage(), scale: 2, dark: dark);
        await tester.scrollUntilVisible(
          find.text('加载更多任务'),
          250,
          scrollable: find
              .descendant(
                of: find.byType(ListView),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        expect(tester.takeException(), isNull);
        await show(
          tester,
          const AgentTaskDetailPage(resource: resource),
          scale: 2,
          dark: dark,
        );
        await tester.scrollUntilVisible(
          find.text('加载更多调用'),
          250,
          scrollable: find
              .descendant(
                of: find.byType(ListView),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        expect(tester.takeException(), isNull);
      },
    );
  }
}
