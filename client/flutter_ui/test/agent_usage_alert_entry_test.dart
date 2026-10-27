import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';

class _Profile extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}

class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 1;
}

void main() {
  testWidgets('Usage scope automatically has a truthful alert query surface', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    for (final method in [
      'getAgentUsage',
      'getAgentUsageLastExport',
      'getAgentUsageAlerts',
    ]) {
      final channel = BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
        StudyHostApi.pigeonChannelCodec,
      );
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => ['READ_FAILED', 'synthetic', null],
      );
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
              taskId: 'task',
              expectedEpoch: 1,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('请求用量提醒'), findsOneWidget);
    expect(find.textContaining('提醒读取失败'), findsOneWidget);
  });
}
