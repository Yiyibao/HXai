import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_export_history.dart';

import 'agent_usage_export_test.dart' show receipt;

const id = '00000000-0000-4000-8000-000000000002';
AgentUsageExportIntentDto saved({
  String owner = 'owner',
  String request = id,
  String group = 'role',
  int time = 1000,
}) => AgentUsageExportIntentDto(
  owner: owner,
  taskId: '00000000-0000-4000-8000-000000000009',
  from: 100,
  until: 200,
  groupBy: group,
  requestId: request,
  artifactId: '00000000-0000-4000-8000-000000000001',
  sha256: 'a' * 64,
  size: 2048,
  createdAt: time,
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
  }) async {
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
    await t.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          theme: ThemeData(
            brightness: dark ? Brightness.dark : Brightness.light,
          ),
          home: MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: const Scaffold(
              body: AgentUsageExportHistoryView(
                owner: 'owner',
                expectedEpoch: 1,
              ),
            ),
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

  testWidgets(
    'Cross-scope original period and task are displayed; manual verification never exports',
    (t) async {
      final calls = <List<Object?>>[];
      int writes = 0;
      mock(
        'getAgentUsageExportHistory',
        (a) async => [
          AgentUsageExportHistoryPageDto(owner: 'owner', entries: [saved()]),
        ],
      );
      mock('exportAgentUsage', (a) async {
        writes++;
        throw StateError('write forbidden');
      });
      mock('verifyAgentUsageExport', (a) async {
        calls.add(a);
        return [receipt(a, reused: true)];
      });
      await show(t);
      expect(find.textContaining('原范围：任务'), findsOneWidget);
      expect(find.textContaining('原周期（起含止不含）'), findsOneWidget);
      expect(find.textContaining('已核验原快照'), findsNothing);
      await tap(t, '按原范围核验');
      await t.pumpAndSettle();
      expect(calls.single, [
        'owner',
        saved().taskId,
        100,
        200,
        'role',
        id,
        'a' * 64,
        1,
      ]);
      expect(writes, 0);
      expect(find.textContaining('已核验原快照'), findsOneWidget);
      expect(find.text('查看历史快照'), findsOneWidget);
    },
  );
  testWidgets(
    'Paging follows native cursor; directory change requires refresh retaining valid old entries',
    (t) async {
      final calls = <List<Object?>>[];
      bool changed = true;
      mock('getAgentUsageExportHistory', (a) async {
        calls.add(a);
        if (a[1] != null && changed) throw StateError('changed');
        return [
          AgentUsageExportHistoryPageDto(
            owner: 'owner',
            entries: [saved()],
            nextCursor: 'cursor',
          ),
        ];
      });
      await show(t);
      await tap(t, '加载更多历史');
      await t.pumpAndSettle();
      expect(calls.last, ['owner', 'cursor', 20, 1]);
      expect(find.textContaining('请刷新历史'), findsOneWidget);
      expect(find.byKey(const ValueKey(id)), findsOneWidget);
      changed = false;
      await tap(t, '刷新历史');
      await t.pumpAndSettle();
      expect(calls.last[1], null);
    },
  );
  for (final bad in ['owner', 'duplicate', 'order', 'cursor', 'range']) {
    testWidgets(
      'Reject malformed history $bad instead of showing usable record',
      (t) async {
        final i = saved(owner: bad == 'owner' ? 'foreign' : 'owner');
        if (bad == 'range') i.until = 50;
        mock(
          'getAgentUsageExportHistory',
          (a) async => [
            AgentUsageExportHistoryPageDto(
              owner: 'owner',
              entries: [
                i,
                if (bad == 'duplicate') saved(),
                if (bad == 'order')
                  saved(
                    request: '00000000-0000-4000-8000-000000000003',
                    time: 2000,
                  ),
              ],
              nextCursor: bad == 'cursor' ? '' : null,
            ),
          ],
        );
        await show(t);
        expect(find.textContaining('请刷新历史'), findsOneWidget);
        expect(find.text('按原范围核验'), findsNothing);
      },
    );
  }
  testWidgets('Valid pagination appends older scopes without changing the first saved intent', (t) async {
    final first = saved();
    final older = saved(request: '00000000-0000-4000-8000-000000000001', group: 'model', time: 500);
    mock('getAgentUsageExportHistory', (a) async => [AgentUsageExportHistoryPageDto(
      owner: 'owner', entries: a[1] == null ? [first] : [older], nextCursor: a[1] == null ? 'next' : null)]);
    await show(t);
    await tap(t, '加载更多历史'); await t.pumpAndSettle();
    expect(find.byKey(const ValueKey(id)), findsOneWidget);
    expect(find.byKey(const ValueKey('00000000-0000-4000-8000-000000000001')), findsOneWidget);
    expect(find.text('加载更多历史'), findsNothing);
    expect(find.textContaining('请刷新历史'), findsNothing);
  });
  for (final bad in ['hash', 'scope', 'reused', 'size', 'source']) {
    testWidgets(
      'Reject false verification $bad and retry identical saved request',
      (t) async {
        final calls = <List<Object?>>[];
        mock(
          'getAgentUsageExportHistory',
          (a) async => [
            AgentUsageExportHistoryPageDto(owner: 'owner', entries: [saved()]),
          ],
        );
        mock('verifyAgentUsageExport', (a) async {
          calls.add(a);
          final r = receipt(a, reused: bad != 'reused');
          if (bad == 'hash') r.sha256 = 'b' * 64;
          if (bad == 'scope') r.from = null;
          if (bad == 'size') r.size = 1;
          if (bad == 'source') r.sourceSha256 = 'invalid';
          return [r];
        });
        await show(t);
        await tap(t, '按原范围核验');
        await t.pumpAndSettle();
        expect(find.textContaining('没有重新写出'), findsOneWidget);
        expect(find.text('查看历史快照'), findsNothing);
        await tap(t, '按原范围核验');
        await t.pumpAndSettle();
        expect(calls[0], calls[1]);
      },
    );
  }
  testWidgets(
    'Old epoch readback is discarded and old private content disappears',
    (t) async {
      final pending = Completer<Object?>();
      mock(
        'getAgentUsageExportHistory',
        (a) async => [
          AgentUsageExportHistoryPageDto(owner: 'owner', entries: [saved()]),
        ],
      );
      mock('verifyAgentUsageExport', (a) => pending.future);
      final c = await show(t);
      await tap(t, '按原范围核验');
      c.read(contextEpochProvider.notifier).state = 2;
      pending.complete([
        receipt(['owner', saved().taskId, 100, 200, 'role', id], reused: true),
      ]);
      await t.pumpAndSettle();
      expect(find.textContaining('档案或上下文已变化'), findsOneWidget);
      expect(find.byKey(const ValueKey(id)), findsNothing);
    },
  );
  for (final dark in [false, true]) {
    testWidgets(
      'Narrow 200 percent history $dark exposes original scope and receipt semantics',
      (t) async {
        t.view.physicalSize = const Size(360, 800);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        mock(
          'getAgentUsageExportHistory',
          (a) async => [
            AgentUsageExportHistoryPageDto(owner: 'owner', entries: [saved()]),
          ],
        );
        mock('verifyAgentUsageExport', (a) async => [receipt(a, reused: true)]);
        final semantics = t.ensureSemantics();
        await show(t, dark: dark, scale: 2);
        await tap(t, '按原范围核验');
        await t.pumpAndSettle();
        await t.ensureVisible(find.textContaining('已核验原快照'));
        await t.pumpAndSettle();
        expect(
          t.getSemantics(find.textContaining('已核验原快照')).label,
          contains('已核验原快照'),
        );
        expect(t.takeException(), null);
        semantics.dispose();
      },
    );
  }
}
