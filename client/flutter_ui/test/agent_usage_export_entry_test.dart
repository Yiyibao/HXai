import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

import 'agent_usage_view_test.dart' show page;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('Actual usage page exposes a complete scope export action', (
    tester,
  ) async {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.getAgentUsage',
      StudyHostApi.pigeonChannelCodec,
    );
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockDecodedMessageHandler<Object?>(
      channel,
      (a) async => [page((a! as List).cast<Object?>())],
    );
    addTearDown(
      () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
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
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
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
    expect(find.text('导出 JSON 快照'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(find.widgetWithText(TextButton, '导出 JSON 快照'))
          .onPressed,
      isNotNull,
    );
  });
}
