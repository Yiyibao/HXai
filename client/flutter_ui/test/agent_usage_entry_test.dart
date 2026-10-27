import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_task_panel.dart';

class _Profile extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}
class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 1;
}
class _Conversation extends ActiveConversationIdNotifier {
  @override
  String? build() => 'conversation';
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final channel = BasicMessageChannel<Object?>(
    'dev.flutter.pigeon.flutter_ui.StudyHostApi.getConversationTask', StudyHostApi.pigeonChannelCodec);
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockDecodedMessageHandler<Object?>(channel, null));
  testWidgets('Accepted task exposes actual request ledger entry', (tester) async {
    messenger.setMockDecodedMessageHandler<Object?>(channel, (_) async => [AgentTaskDto(
      id: 'accepted-task', owner: 'owner', state: 'COMPLETED_UNVERIFIED',
      providerId: 'provider', modelId: 'locked-model', reasoning: 'OFF',
      providerName: 'Synthetic', modelName: 'Accepted model', providerOrigin: 'http://127.0.0.1',
      revision: 3, fence: 2, grantEpoch: 1, authorization: 'ACTIVE', manualPaused: false,
      inputBlocked: false, deviceBlocked: false, deviceConfirmation: false,
      goalRevision: 1, plannedRevision: 1, acceptanceVerified: false,
      verifiedSteps: [], approvalCallIds: [],
    )]);
    await tester.pumpWidget(ProviderScope(overrides: [
      activeProfileIdProvider.overrideWith(_Profile.new), contextEpochProvider.overrideWith(_Epoch.new),
      activeConversationIdProvider.overrideWith(_Conversation.new),
    ], child: const MaterialApp(home: Scaffold(body: AgentTaskPanel(conversationId: 'conversation')))));
    await tester.pump();
    await tester.pump();
    await tester.tap(find.byType(ExpansionTile));
    await tester.pumpAndSettle();
    expect(find.text('查看请求用量'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
