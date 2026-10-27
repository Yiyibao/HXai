import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/memories_page.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/main.dart' as app;
import 'package:flutter_ui/router.dart' as app_routes;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String name, Future<Object?> Function(List<Object?>) handler) {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$name',
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

  setUp(() {
    mock(
      'getBootstrap',
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: 'default-learner',
          contextEpoch: 1,
          profiles: [],
          studyMode: 'CHAT',
          reduceMotion: false,
          darkMode: false,
        ),
      ],
    );
    mock('getMemories', (_) async => [<MemoryItemDto>[]]);
  });

  MemoryProtectionDto status(
    List<Object?> args, {
    bool enabled = true,
    bool blocked = false,
    String? owner,
    int? epoch,
  }) => MemoryProtectionDto(
    profileId: owner ?? args[0]! as String,
    contextEpoch: epoch ?? args[1]! as int,
    configuredEnabled: enabled,
    recoveryBlocked: blocked,
  );

  Future<ProviderContainer> show(
    WidgetTester tester, {
    bool dark = false,
    double scale = 1,
  }) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: dark ? StudyTheme.darkTheme() : StudyTheme.lightTheme(),
          home: MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: const MemoriesPage(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets(
    'Unknown protection never defaults to enabled or accepts a toggle',
    (tester) async {
      final pending = Completer<Object?>();
      mock('getMemoryProtection', (_) => pending.future);
      await tester.pumpWidget(
        const ProviderScope(child: MaterialApp(home: MemoriesPage())),
      );
      await tester.pump();
      final toggle = tester.widget<Switch>(find.byType(Switch));
      expect(toggle.onChanged, isNull);
      expect(toggle.value, isFalse);
      expect(find.text('正在核对记忆状态'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      pending.complete([null]);
    },
  );

  testWidgets(
    'Protection is distinct from the stored switch and disabling preserves management',
    (tester) async {
      var enabled = true;
      final commands = <List<Object?>>[];
      mock(
        'getMemoryProtection',
        (a) async => [status(a, enabled: enabled, blocked: true)],
      );
      mock('toggleAutoMemory', (a) async {
        commands.add(a);
        enabled = a[1]! as bool;
        return [SimpleCommandResult(success: true)];
      });
      await show(tester);
      expect(find.textContaining('记忆保护中：'), findsOneWidget);
      expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(commands.single, ['default-learner', false, 1]);
      final toggle = tester.widget<Switch>(find.byType(Switch));
      expect(toggle.value, isFalse);
      expect(toggle.onChanged, isNull);
      expect(find.textContaining('记忆保护中：'), findsOneWidget);
      expect(
        tester
            .widget<IconButton>(
              find.byWidgetPredicate(
                (w) => w is IconButton && w.tooltip == '手动添加记忆',
              ),
            )
            .onPressed,
        isNotNull,
      );
    },
  );

  testWidgets('Failed status stays unknown until a real refresh succeeds', (
    tester,
  ) async {
    var failed = true;
    mock(
      'getMemoryProtection',
      (a) async => failed
          ? ['READ_ERROR', 'synthetic unavailable', null]
          : [status(a, enabled: false)],
    );
    await show(tester);
    expect(find.textContaining('记忆状态未能核对'), findsOneWidget);
    expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
    failed = false;
    await tester.tap(find.byTooltip('刷新记忆'));
    await tester.pumpAndSettle();
    expect(find.textContaining('记忆已关闭：'), findsOneWidget);
    expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNotNull);
  });

  for (final mismatch in ['owner', 'epoch']) {
    testWidgets('Mismatched $mismatch DTO cannot enable controls', (
      tester,
    ) async {
      mock(
        'getMemoryProtection',
        (a) async => [
          status(
            a,
            owner: mismatch == 'owner' ? 'other' : null,
            epoch: mismatch == 'epoch' ? 2 : null,
          ),
        ],
      );
      await show(tester);
      expect(find.textContaining('记忆状态未能核对'), findsOneWidget);
      expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
    });
  }

  for (final oldError in [false, true]) {
    test(
      'Late ${oldError ? 'error' : 'success'} cannot replace the new profile status',
      () async {
        final delayed = Completer<Object?>();
        var calls = 0;
        mock('getMemoryProtection', (a) async {
          if (++calls == 2) return delayed.future;
          return [status(a, blocked: a[0] == 'B')];
        });
        final c = ProviderContainer();
        addTearDown(c.dispose);
        await c.read(bootstrapProvider.future);
        c.read(activeProfileIdProvider.notifier).state = 'A';
        final sub = c.listen(memoryProtectionProvider, (_, _) {});
        addTearDown(sub.close);
        await c.read(memoryProtectionProvider.future);
        final refresh = c.read(memoryProtectionProvider.notifier).refresh();
        await Future<void>.delayed(Duration.zero);
        c.read(activeProfileIdProvider.notifier).state = 'B';
        c.read(contextEpochProvider.notifier).state = 2;
        await c.read(memoryProtectionProvider.future);
        delayed.complete(
          oldError
              ? ['FAILED', 'A private failure', null]
              : [
                  status(['A', 1]),
                ],
        );
        await refresh;
        expect(c.read(memoryProtectionProvider).requireValue.profileId, 'B');
        expect(
          c.read(memoryProtectionProvider).requireValue.recoveryBlocked,
          isTrue,
        );
      },
    );
  }

  testWidgets('Refresh hides an old healthy state and latest refresh wins', (
    tester,
  ) async {
    final delayed = Completer<Object?>();
    var calls = 0;
    mock('getMemoryProtection', (a) async {
      if (++calls == 2) return delayed.future;
      return [status(a, blocked: calls > 2)];
    });
    final c = await show(tester);
    final first = c.read(memoryProtectionProvider.notifier).refresh();
    await tester.pump();
    expect(find.text('正在核对记忆状态'), findsOneWidget);
    expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
    await c.read(memoryProtectionProvider.notifier).refresh();
    delayed.complete([
      status(['default-learner', 1]),
    ]);
    await first;
    await tester.pumpAndSettle();
    expect(find.textContaining('记忆保护中：'), findsOneWidget);
  });

  testWidgets('Failed command reads back durable setting and reports failure', (
    tester,
  ) async {
    mock('getMemoryProtection', (a) async => [status(a, enabled: false)]);
    mock(
      'toggleAutoMemory',
      (_) async => [
        SimpleCommandResult(
          success: false,
          error: StudyErrorDto(
            code: 'FAILED',
            message: '写入失败哨兵',
            retryable: true,
            requestId: 'test',
          ),
        ),
      ],
    );
    await show(tester);
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(find.textContaining('写入失败哨兵'), findsOneWidget);
    expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
    expect(find.textContaining('记忆已关闭：'), findsOneWidget);
  });

  testWidgets(
    'Pending toggle cannot repeat or publish old status through refresh',
    (tester) async {
      final delayed = Completer<Object?>();
      var calls = 0;
      var enabled = false;
      mock('getMemoryProtection', (a) async => [status(a, enabled: enabled)]);
      mock('toggleAutoMemory', (_) {
        calls++;
        return delayed.future;
      });
      final c = await show(tester);
      await tester.tap(find.byType(Switch));
      await tester.pump();
      expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
      expect(
        await c.read(memoryProtectionProvider.notifier).setEnabled(true),
        isFalse,
      );
      await tester.tap(find.byTooltip('刷新记忆'));
      await tester.pump();
      expect(find.text('正在核对记忆状态'), findsOneWidget);
      enabled = true;
      delayed.complete([SimpleCommandResult(success: true)]);
      await tester.pumpAndSettle();
      expect(calls, 1);
      expect(find.textContaining('记忆已开启：'), findsOneWidget);
    },
  );

  for (final newProfile in [false, true]) {
    testWidgets(
      'Old toggle failure is discarded after ${newProfile ? 'profile' : 'epoch'} changes',
      (tester) async {
        final delayed = Completer<Object?>();
        mock('getMemoryProtection', (a) async => [status(a, enabled: false)]);
        mock('toggleAutoMemory', (_) => delayed.future);
        final c = await show(tester);
        await tester.tap(find.byType(Switch));
        await tester.pump();
        if (newProfile) c.read(activeProfileIdProvider.notifier).state = 'B';
        c.read(contextEpochProvider.notifier).state = 2;
        await tester.pumpAndSettle();
        delayed.complete(['FAILED', '旧档案错误哨兵', null]);
        await tester.pumpAndSettle();
        expect(find.textContaining('旧档案错误哨兵'), findsNothing);
        expect(c.read(memoryProtectionProvider).requireValue.contextEpoch, 2);
        expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
      },
    );
  }

  for (final dark in [false, true]) {
    testWidgets(
      'Protected ${dark ? 'dark' : 'light'} 320x480 at 200 percent scrolls with semantics',
      (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(320, 480);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final semantics = tester.ensureSemantics();
        mock(
          'getMemoryProtection',
          (a) async => [status(a, blocked: true, enabled: false)],
        );
        mock(
          'getMemories',
          (_) async => [
            [
              MemoryItemDto(
                id: 'retained',
                type: 'PREFERENCE',
                content: '原记录保留哨兵' * 15,
                sourceTopic: '手动添加',
                sourceFact: '',
                evidenceType: 'EXPLICIT',
                status: 'ACTIVE',
                revision: 1,
                createdAt: 0,
                updatedAt: 0,
              ),
            ],
          ],
        );
        await show(tester, dark: dark, scale: 2);
        expect(tester.takeException(), isNull);
        expect(find.bySemanticsLabel(RegExp('记忆保护中：')), findsOneWidget);
        await tester.scrollUntilVisible(
          find.text('原记录保留哨兵' * 15),
          160,
          scrollable: find
              .descendant(
                of: find.byType(CustomScrollView),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        expect(tester.takeException(), isNull);
        expect(find.text('原记录保留哨兵' * 15), findsOneWidget);
        semantics.dispose();
      },
    );
  }

  testWidgets(
    'Actual app resume invalidates protection even with the same identity',
    (tester) async {
      final pending = Completer<Object?>();
      var calls = 0;
      mock('getMemoryProtection', (a) async {
        if (++calls > 1) return pending.future;
        return [status(a)];
      });
      final c = ProviderContainer();
      addTearDown(c.dispose);
      app_routes.router.go('/settings/memories');
      try {
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: c,
            child: const app.MathStudyApp(),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.textContaining('记忆已开启：'), findsOneWidget);
        for (final state in [
          AppLifecycleState.inactive,
          AppLifecycleState.hidden,
          AppLifecycleState.paused,
        ]) {
          tester.binding.handleAppLifecycleStateChanged(state);
        }
        await tester.pump();
        for (final state in [
          AppLifecycleState.hidden,
          AppLifecycleState.inactive,
          AppLifecycleState.resumed,
        ]) {
          tester.binding.handleAppLifecycleStateChanged(state);
        }
        await tester.pump();
        expect(calls, 2);
        expect(find.text('正在核对记忆状态'), findsOneWidget);
        expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
        pending.complete([
          status(['default-learner', 1], blocked: true),
        ]);
        await tester.pumpAndSettle();
        expect(find.textContaining('记忆保护中：'), findsOneWidget);
      } finally {
        if (!pending.isCompleted) {
          pending.complete([
            status(['default-learner', 1], blocked: true),
          ]);
        }
        await tester.pumpWidget(const SizedBox.shrink());
        app_routes.router.go('/chat');
      }
    },
  );

  testWidgets(
    'Resume keeps an in-flight setting pending and reads its final protection',
    (tester) async {
      final pending = Completer<Object?>();
      var reads = 0;
      var writes = 0;
      var enabled = false;
      mock('getMemoryProtection', (a) async {
        reads++;
        return [status(a, enabled: enabled, blocked: enabled)];
      });
      mock('toggleAutoMemory', (_) {
        writes++;
        return pending.future;
      });
      final c = ProviderContainer();
      addTearDown(c.dispose);
      app_routes.router.go('/settings/memories');
      try {
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: c,
            child: const app.MathStudyApp(),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byType(Switch));
        await tester.pump();
        for (final state in [
          AppLifecycleState.inactive,
          AppLifecycleState.hidden,
          AppLifecycleState.paused,
          AppLifecycleState.hidden,
          AppLifecycleState.inactive,
          AppLifecycleState.resumed,
        ]) {
          tester.binding.handleAppLifecycleStateChanged(state);
        }
        await tester.pump();
        expect(reads, 1);
        expect(find.text('正在核对记忆状态'), findsOneWidget);
        expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
        enabled = true;
        pending.complete([SimpleCommandResult(success: true)]);
        await tester.pumpAndSettle();
        expect(writes, 1);
        expect(reads, 2);
        expect(find.textContaining('记忆保护中：'), findsOneWidget);
      } finally {
        if (!pending.isCompleted) {
          pending.complete([SimpleCommandResult(success: true)]);
        }
        await tester.pumpWidget(const SizedBox.shrink());
        app_routes.router.go('/chat');
      }
    },
  );

  testWidgets(
    'Refresh during the first read cannot publish its obsolete healthy result',
    (tester) async {
      final first = Completer<Object?>();
      var reads = 0;
      mock('getMemoryProtection', (a) async {
        if (++reads == 1) return first.future;
        return [status(a, blocked: true)];
      });
      final c = await show(tester);
      await c.read(memoryProtectionProvider.notifier).refresh();
      await tester.pumpAndSettle();
      expect(find.textContaining('记忆保护中：'), findsOneWidget);
      first.complete([
        status(['default-learner', 1]),
      ]);
      await tester.pumpAndSettle();
      expect(find.textContaining('记忆保护中：'), findsOneWidget);
      expect(find.textContaining('记忆已开启：'), findsNothing);
    },
  );
}
