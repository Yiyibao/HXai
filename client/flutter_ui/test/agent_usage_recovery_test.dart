import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

import 'agent_usage_view_test.dart' show page;
import 'agent_usage_export_test.dart' show receipt;

const request = '00000000-0000-4000-8000-000000000002';
AgentUsageExportIntentDto intent(List<Object?> a, {String? owner, int? size}) =>
    AgentUsageExportIntentDto(
      owner: owner ?? a[0]! as String,
      taskId: a[1] as String?,
      from: a[2] as int?,
      until: a[3] as int?,
      groupBy: a[4]! as String,
      requestId: request,
      artifactId: '00000000-0000-4000-8000-000000000001',
      sha256: 'a' * 64,
      size: size ?? 2048,
      createdAt: 1760000000000,
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
    ProviderContainer? existing,
    String? next,
  }) async {
    mock('getAgentUsage', (a) async => [page(a, next: next)]);
    final c =
        existing ??
        ProviderContainer(
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
    if (existing == null) {
      addTearDown(c.dispose);
      await c.read(bootstrapProvider.future);
    }
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
              body: AgentUsageView(
                owner: 'owner',
                taskId: null,
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
    'Reopened page discovers durable intent but publishes only manual readback receipt',
    (t) async {
      int exports = 0, reads = 0;
      final verifies = <List<Object?>>[];
      mock('getAgentUsageLastExport', (a) async {
        reads++;
        return [intent(a)];
      });
      mock('exportAgentUsage', (a) async {
        exports++;
        throw StateError('must not write');
      });
      mock('verifyAgentUsageExport', (a) async {
        verifies.add(a);
        return [receipt(a, reused: true)];
      });
      final c = await show(t);
      expect(reads, 1);
      expect(find.textContaining('文件尚待核验'), findsOneWidget);
      expect(find.textContaining('已生成并核验'), findsNothing);
      await t.pumpWidget(const SizedBox());
      await show(t, existing: c);
      expect(reads, 2);
      await tap(t, '核验上次导出');
      await t.pumpAndSettle();
      expect(verifies.single, [
        'owner',
        null,
        null,
        null,
        'requests',
        request,
        'a' * 64,
        1,
      ]);
      expect(exports, 0);
      expect(find.textContaining('复用原快照'), findsOneWidget);
      expect(find.textContaining('文件 SHA256'), findsOneWidget);
    },
  );
  testWidgets(
    'Verification failure and retries use dedicated API without recapture or new ID',
    (t) async {
      int exports = 0;
      final verifies = <List<Object?>>[];
      mock('getAgentUsageLastExport', (a) async => [intent(a)]);
      mock('exportAgentUsage', (a) async {
        exports++;
        return [receipt(a)];
      });
      mock('verifyAgentUsageExport', (a) async {
        verifies.add(a);
        throw StateError('missing file');
      });
      await show(t);
      await tap(t, '核验上次导出');
      await t.pumpAndSettle();
      expect(find.textContaining('没有重新写出'), findsOneWidget);
      expect(find.textContaining('已生成并核验'), findsNothing);
      await tap(t, '重试核验导出');
      await t.pumpAndSettle();
      expect(verifies.length, 2);
      expect(verifies[0], verifies[1]);
      expect(exports, 0);
    },
  );
  testWidgets(
    'Wrong owner intent and wrong original hash receipt cannot become verified',
    (t) async {
      bool wrong = true;
      mock(
        'getAgentUsageLastExport',
        (a) async => [intent(a, owner: wrong ? 'foreign' : null)],
      );
      mock('verifyAgentUsageExport', (a) async {
        final r = receipt(a, reused: true);
        r.sha256 = 'c' * 64;
        return [r];
      });
      await show(t);
      expect(find.text('核验上次导出'), findsNothing);
      expect(find.textContaining('记录读取失败'), findsOneWidget);
      wrong = false;
      await tap(t, '查询上次导出');
      await t.pumpAndSettle();
      await tap(t, '核验上次导出');
      await t.pumpAndSettle();
      expect(find.textContaining('已生成并核验'), findsNothing);
      expect(find.textContaining('没有重新写出'), findsOneWidget);
    },
  );
  testWidgets('Late epoch verification and old filter lookup are discarded', (
    t,
  ) async {
    final delayed = Completer<Object?>();
    final lookups = <List<Object?>>[];
    mock('getAgentUsageLastExport', (a) async {
      lookups.add(a);
      return a[2] == null ? [intent(a)] : [null];
    });
    mock('verifyAgentUsageExport', (a) => delayed.future);
    final c = await show(t);
    await tap(t, '核验上次导出');
    c.read(contextEpochProvider.notifier).state = 2;
    delayed.complete([
      receipt(['owner', null, null, null, 'requests', request], reused: true),
    ]);
    await t.pumpAndSettle();
    expect(find.textContaining('已生成并核验'), findsNothing);
    expect(find.textContaining('上下文已变化'), findsOneWidget);
  });
  testWidgets('Old filter lookup cannot replace current range intent', (
    t,
  ) async {
    final delayed = Completer<Object?>();
    List<Object?>? oldArgs;
    mock('getAgentUsageLastExport', (a) async {
      if (a[2] == null) {
        oldArgs = a;
        return delayed.future;
      }
      return [null];
    });
    await show(t);
    await tap(t, '全部');
    await t.pump(const Duration(milliseconds: 400));
    await t.tap(find.text('今日').last);
    await t.pumpAndSettle();
    delayed.complete([intent(oldArgs!)]);
    await t.pumpAndSettle();
    expect(find.text('核验上次导出'), findsNothing);
  });
  testWidgets('Paging keeps an outstanding lookup in the same scope usable', (
    t,
  ) async {
    final delayed = Completer<Object?>();
    List<Object?>? args;
    mock('getAgentUsageLastExport', (a) {
      args = a;
      return delayed.future;
    });
    await show(t, next: 'more');
    await tap(t, '加载更多请求');
    delayed.complete([intent(args!)]);
    await t.pumpAndSettle();
    expect(find.text('核验上次导出'), findsOneWidget);
    expect(
      t.widget<TextButton>(find.widgetWithText(TextButton, '核验上次导出')).onPressed,
      isNotNull,
    );
    expect(find.textContaining('正在读取当前筛选范围'), findsNothing);
  });
  testWidgets(
    'Receipt status and reuse expose separate accessible text nodes',
    (t) async {
      final semantics = t.ensureSemantics();
      try {
        mock('getAgentUsageLastExport', (a) async => [intent(a)]);
        mock('verifyAgentUsageExport', (a) async => [receipt(a, reused: true)]);
        await show(t);
        await tap(t, '核验上次导出');
        await t.pumpAndSettle();
        for (final phrase in ['已生成并核验', '复用原快照']) {
          final finder = find.textContaining(phrase);
          final expected = t.widget<Text>(finder).data;
          expect(t.getSemantics(finder).label, expected);
        }
      } finally {
        semantics.dispose();
      }
    },
  );
  for (final dark in [false, true]) {
    testWidgets('Manual recovery at 320px 200 percent $dark', (t) async {
      t.view.physicalSize = const Size(320, 800);
      t.view.devicePixelRatio = 1;
      addTearDown(t.view.resetPhysicalSize);
      addTearDown(t.view.resetDevicePixelRatio);
      mock('getAgentUsageLastExport', (a) async => [intent(a)]);
      mock('verifyAgentUsageExport', (a) async => [receipt(a, reused: true)]);
      await show(t, dark: dark, scale: 2);
      await tap(t, '核验上次导出');
      await t.pumpAndSettle();
      expect(find.textContaining('文件 SHA256'), findsOneWidget);
      expect(t.takeException(), isNull);
    });
  }
}
