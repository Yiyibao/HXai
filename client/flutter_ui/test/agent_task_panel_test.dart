import 'dart:async';
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
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?)? handler) {
    messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), handler);
  }
  AgentTaskDto task() => AgentTaskDto(id: 'accepted-task', owner: 'owner',
    state: 'INTERRUPTED', providerId: 'provider', modelId: 'locked-model',
    reasoning: 'OFF', providerName: 'Synthetic', modelName: 'Accepted model',
    providerOrigin: 'http://127.0.0.1', revision: 3, fence: 2, grantEpoch: 1,
    authorization: 'ACTIVE', manualPaused: false, inputBlocked: false,
    deviceBlocked: false, deviceConfirmation: false, goalRevision: 1,
    plannedRevision: 1, acceptanceVerified: false, verifiedSteps: [], approvalCallIds: []);
  SimpleCommandResult unknown(String request) => SimpleCommandResult(success: false,
    error: StudyErrorDto(code: 'UNKNOWN_ACK', message: 'Receipt unavailable',
      retryable: true, requestId: request));
  tearDown(() {
    mock('getConversationTask', null);
    mock('taskCommand', null);
  });

  testWidgets('Unconfirmed resume cannot disable pause, cancellation or revocation', (tester) async {
    final persisted = task();
    final resumeAck = Completer<Object?>();
    final pauseAck = Completer<Object?>();
    final dispatched = <List<Object?>>[];
    mock('getConversationTask', (_) async => [persisted]);
    mock('taskCommand', (args) async {
      final command = (args! as List).cast<Object?>();
      dispatched.add(command);
      if (command[7] == 'resume') { return resumeAck.future; }
      if (command[7] == 'pause') {
        persisted.state = 'STOP_REQUESTED';
        persisted.manualPaused = true;
        persisted.revision++;
        return pauseAck.future;
      }
      return [unknown(command[3]! as String)];
    });
    await tester.pumpWidget(ProviderScope(overrides: [
      activeProfileIdProvider.overrideWith(_Profile.new),
      contextEpochProvider.overrideWith(_Epoch.new),
      activeConversationIdProvider.overrideWith(_Conversation.new),
    ], child: const MaterialApp(home: Scaffold(body: AgentTaskPanel(conversationId: 'conversation')))));
    await tester.pump();
    await tester.pump();
    await tester.tap(find.byType(ExpansionTile));
    await tester.pumpAndSettle();
    await tester.tap(find.text('明确恢复'));
    await tester.pump();
    for (final label in ['暂停', '取消', '主动撤权']) {
      expect(tester.widget<TextButton>(find.widgetWithText(TextButton, label)).onPressed, isNotNull);
    }
    await tester.tap(find.text('暂停'));
    await tester.pump();
    expect(dispatched.map((command) => command[7]), ['resume', 'pause']);
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('已请求停止，尚未确认停止'), findsOneWidget);
    expect(find.text('已暂停'), findsNothing);
    resumeAck.complete([unknown(dispatched.first[3]! as String)]);
    pauseAck.complete([unknown(dispatched.last[3]! as String)]);
    await tester.pump();
    await tester.pump();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '主动撤权')).onPressed, isNotNull);
    await tester.tap(find.text('重试原命令回执'));
    await tester.pump();
    expect(dispatched.last[3], dispatched.first[3]);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
