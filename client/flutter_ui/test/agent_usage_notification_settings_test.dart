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

void main() {
  testWidgets(
    'Settings opens real host route once, waits for ACK and reports a rejection',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final reply = Completer<Object?>();
      var opens = 0;
      for (final method in [
        'getAgentUsage',
        'getAgentUsageLastExport',
        'getAgentUsageAlerts',
        'openNativePage',
      ]) {
        final channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
          StudyHostApi.pigeonChannelCodec,
        );
        messenger.setMockDecodedMessageHandler<Object?>(channel, (args) async {
          if (method == 'openNativePage') {
            expect(args, ['notifications']);
            opens++;
            return reply.future;
          }
          return ['READ_FAILED', 'synthetic', null];
        });
        addTearDown(
          () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
        );
      }
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            activeProfileIdProvider.overrideWith(_Profile.new),
            contextEpochProvider.overrideWith(_Epoch.new),
          ],
          child: const MaterialApp(
            home: Scaffold(
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
      await tester.tap(find.text('后台用量提醒设置'));
      await tester.pump();
      await tester.tap(find.text('后台用量提醒设置'));
      await tester.pump();
      expect(opens, 1);
      expect(find.textContaining('已开启'), findsNothing);
      reply.complete([
        SimpleCommandResult(success: false),
      ]);
      await tester.pumpAndSettle();
      expect(find.text('提醒设置打开失败，请重试。'), findsOneWidget);
    },
  );
  testWidgets('Old profile ACK cannot add an error to the next profile', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final reply = Completer<Object?>();
    final container = ProviderContainer(
      overrides: [
        activeProfileIdProvider.overrideWith(_Profile.new),
        contextEpochProvider.overrideWith(_Epoch.new),
      ],
    );
    addTearDown(container.dispose);
    for (final method in [
      'getAgentUsage',
      'getAgentUsageLastExport',
      'getAgentUsageAlerts',
      'openNativePage',
    ]) {
      final channel = BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
        StudyHostApi.pigeonChannelCodec,
      );
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => method == 'openNativePage'
            ? reply.future
            : ['READ_FAILED', 'synthetic', null],
      );
      addTearDown(
        () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
      );
    }
    Future<void> show(String owner, int epoch) async {
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            home: Scaffold(
              body: AgentUsageView(
                owner: owner,
                taskId: null,
                expectedEpoch: epoch,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    await show('owner', 1);
    await tester.tap(find.text('后台用量提醒设置'));
    await tester.pump();
    container.read(activeProfileIdProvider.notifier).state = 'next';
    container.read(contextEpochProvider.notifier).state = 2;
    await show('next', 2);
    reply.complete(['DENIED', 'old profile', null]);
    await tester.pumpAndSettle();
    expect(find.text('提醒设置打开失败，请重试。'), findsNothing);
    expect(
      tester
          .widget<TextButton>(find.widgetWithText(TextButton, '后台用量提醒设置'))
          .onPressed,
      isNotNull,
    );
  });
}
