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
  testWidgets(
    'Actual usage page exposes provider model and role grouping without a Chat',
    (tester) async {
      final channel = BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.getAgentUsage',
        StudyHostApi.pigeonChannelCodec,
      );
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (args) async => [page((args! as List).cast<Object?>())],
      );
      addTearDown(
        () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
      );
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
      expect(find.text('逐请求'), findsOneWidget);
      await tester.tap(find.text('逐请求'));
      await tester.pumpAndSettle();
      expect(find.text('按提供商'), findsOneWidget);
      expect(find.text('按模型'), findsOneWidget);
      expect(find.text('按角色'), findsOneWidget);
    },
  );
}
