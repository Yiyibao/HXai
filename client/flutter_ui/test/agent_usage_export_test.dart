import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/providers/profile_resource.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

import 'agent_usage_view_test.dart' show page;
import 'agent_usage_groups_test.dart' as group_fixture show grouped, group;

AgentUsageExportDto receipt(
  List<Object?> args, {
  String? owner,
  String? request,
  int? count,
  bool reused = false,
}) => AgentUsageExportDto(
  owner: owner ?? args[0]! as String,
  taskId: args[1] as String?,
  from: args[2] as int?,
  until: args[3] as int?,
  groupBy: args[4]! as String,
  requestId: request ?? args[5]! as String,
  artifactId: '00000000-0000-4000-8000-000000000001',
  sha256: 'a' * 64,
  size: 2048,
  sourceSha256: 'b' * 64,
  capturedAt: 1760000000000,
  requestCount: count ?? 75,
  recordCount: count ?? (args[4] == 'requests' ? 75 : 3),
  reused: reused,
);
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final channels = <BasicMessageChannel<Object?>>[];
  void mock(String name, Future<Object?> Function(List<Object?>) fn) {
    final c = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$name',
      StudyHostApi.pigeonChannelCodec,
    );
    channels.add(c);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<Object?>(
          c,
          (a) => fn((a! as List).cast<Object?>()),
        );
  }

  tearDown(() {
    for (final c in channels) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockDecodedMessageHandler<Object?>(c, null);
    }
    channels.clear();
  });
  Future<ProviderContainer> show(
    WidgetTester t, {
    bool dark = false,
    double scale = 1,
    String? task,
  }) async {
    mock('getAgentUsage', (a) async => [page(a)]);
    mock(
      'getAgentUsageGroups',
      (a) async => [
        group_fixture.grouped(
          a,
          rows: [
            group_fixture.group(
              provider: 'actual-provider',
              model: 'actual-model',
            ),
          ],
        ),
      ],
    );
    final c = ProviderContainer(
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
    addTearDown(c.dispose);
    await c.read(bootstrapProvider.future);
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => Scaffold(
            body: AgentUsageView(
              owner: 'owner',
              taskId: task,
              expectedEpoch: 1,
            ),
          ),
        ),
        GoRoute(
          path: '/artifacts/preview',
          builder: (_, s) => Scaffold(
            body: Text(
              'preview-${(s.extra! as ProfileResourceRoute).profileId}-${(s.extra! as ProfileResourceRoute).epoch}',
            ),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await t.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp.router(
          routerConfig: router,
          theme: ThemeData(
            brightness: dark ? Brightness.dark : Brightness.light,
          ),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
        ),
      ),
    );
    await t.pumpAndSettle();
    return c;
  }

  Future<void> tap(WidgetTester t, String label) async {
    await t.ensureVisible(find.text(label).first);
    await t.tap(find.text(label).first);
    await t.pump();
    await t.pump();
  }

  Future<void> choose(WidgetTester t, String from, String to) async {
    await tap(t, from);
    await t.pump(const Duration(milliseconds: 400));
    await t.tap(find.text(to).last);
    await t.pumpAndSettle();
  }

  testWidgets(
    'Export calls actual full scope and only opens a validated owned receipt',
    (t) async {
      final calls = <List<Object?>>[];
      mock('exportAgentUsage', (a) async {
        calls.add(a);
        return [receipt(a)];
      });
      await show(t);
      await tap(t, '导出 JSON 快照');
      await t.pumpAndSettle();
      expect(calls.single[1], isNull);
      expect(calls.single[4], 'requests');
      expect(calls.single[6], 1);
      expect(
        calls.single[5],
        matches(
          RegExp(
            r'^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$',
          ),
        ),
      );
      expect(find.textContaining('已生成并核验 75 条请求记录'), findsOneWidget);
      await tap(t, '查看导出快照');
      await t.pumpAndSettle();
      expect(find.text('preview-owner-1'), findsOneWidget);
    },
  );
  testWidgets(
    'Failure and lost ack retry same request while explicit fresh action creates another id',
    (t) async {
      final ids = <Object?>[];
      mock('exportAgentUsage', (a) async {
        ids.add(a[5]);
        if (ids.length < 3) return ['FAILED', 'synthetic', null];
        return [receipt(a, reused: true)];
      });
      await show(t);
      await tap(t, '导出 JSON 快照');
      await t.pumpAndSettle();
      expect(find.textContaining('已生成并核验'), findsNothing);
      await tap(t, '重试核验导出');
      await t.pumpAndSettle();
      expect(ids[1], ids[0]);
      await tap(t, '生成新快照');
      await t.pumpAndSettle();
      expect(ids[2], isNot(ids[0]));
      expect(find.textContaining('复用原快照'), findsOneWidget);
    },
  );
  testWidgets(
    'Task and model window are frozen during in-flight export and double click cannot send twice',
    (t) async {
      final done = Completer<Object?>();
      List<Object?>? args;
      var sends = 0;
      mock('exportAgentUsage', (a) {
        sends++;
        args = a;
        return done.future;
      });
      await show(t, task: 'task');
      await choose(t, '逐请求', '按模型');
      await tap(t, '导出 JSON 快照');
      expect(sends, 1);
      expect(args![1], 'task');
      expect(args![4], 'model');
      expect(
        t
            .widget<TextButton>(find.widgetWithText(TextButton, '导出 JSON 快照'))
            .onPressed,
        isNull,
      );
      expect(
        t
            .widget<DropdownButton<String>>(
              find.widgetWithText(DropdownButton<String>, '按模型'),
            )
            .onChanged,
        isNull,
      );
      done.complete([receipt(args!)]);
      await t.pumpAndSettle();
      expect(find.textContaining('3 条分组记录'), findsOneWidget);
    },
  );
  testWidgets(
    'Wrong owner request hash or impossible count never produces an open button',
    (t) async {
      var variant = 0;
      mock('exportAgentUsage', (a) async {
        final r = receipt(
          a,
          owner: variant == 0 ? 'other' : null,
          request: variant == 1 ? 'wrong' : null,
          count: variant == 2 ? 10001 : null,
        );
        return [r];
      });
      await show(t);
      for (variant = 0; variant < 3; variant++) {
        await tap(t, variant == 0 ? '导出 JSON 快照' : '重试核验导出');
        await t.pumpAndSettle();
        expect(find.text('查看导出快照'), findsNothing);
        expect(find.textContaining('导出未交付'), findsOneWidget);
      }
    },
  );
  testWidgets('Owner epoch change discards old export acknowledgement', (
    t,
  ) async {
    final done = Completer<Object?>();
    List<Object?>? args;
    mock('exportAgentUsage', (a) {
      args = a;
      return done.future;
    });
    final c = await show(t);
    await tap(t, '导出 JSON 快照');
    c.read(activeProfileIdProvider.notifier).state = 'other';
    c.read(contextEpochProvider.notifier).state = 2;
    await t.pump();
    done.complete([receipt(args!)]);
    await t.pumpAndSettle();
    expect(find.text('查看导出快照'), findsNothing);
    expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
  });
  for (final dark in [false, true]) {
    testWidgets('Export receipt remains accessible at 320px 200percent $dark', (
      t,
    ) async {
      await t.binding.setSurfaceSize(const Size(320, 640));
      addTearDown(() => t.binding.setSurfaceSize(null));
      mock('exportAgentUsage', (a) async => [receipt(a)]);
      await show(t, dark: dark, scale: 2);
      await tap(t, '导出 JSON 快照');
      await t.pumpAndSettle();
      await t.ensureVisible(find.text('查看导出快照'));
      expect(t.takeException(), isNull);
      expect(
        t
            .widget<TextButton>(find.widgetWithText(TextButton, '查看导出快照'))
            .onPressed,
        isNotNull,
      );
    });
  }
}
