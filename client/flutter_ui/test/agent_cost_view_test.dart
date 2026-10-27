import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_usage_view.dart';

class _Owner extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}

class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 1;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final channel = BasicMessageChannel<Object?>(
    'dev.flutter.pigeon.flutter_ui.StudyHostApi.getAgentUsage',
    StudyHostApi.pigeonChannelCodec,
  );
  tearDown(
    () => messenger.setMockDecodedMessageHandler<Object?>(channel, null),
  );
  testWidgets(
    'reported amount stays visible with unknown token counters and partial totals',
    (tester) async {
      final m = AgentUsageMetricDto(unknownRequests: 2);
      messenger.setMockDecodedMessageHandler<Object?>(
        channel,
        (_) async => [
          AgentUsagePageDto(
            owner: 'owner',
            taskId: 'task',
            requestCount: 2,
            reportedRequests: 0,
            estimatedRequests: 0,
            unknownRequests: 2,
            undatedRequests: 0,
            input: m,
            output: m,
            cached: m,
            total: m,
            costJson: '{"version":1,"status":"partial","unknownRequests":"1","requestCount":"2","totals":[{"currency":"USD","provenance":"reported","amount":"0.000000001"}]}',
            entries: [
              AgentUsageEntryDto(
                requestId: 'physical',
                taskId: 'task',
                conversationId: 'conversation',
                role: 'main',
                state: 'VERIFIED',
                provenance: 'unknown',
                costJson: '{"version":1,"status":"reported","currency":"USD","amount":"0.000000001","basis":{"source":"https://openrouter.ai/docs/cookbook/administration/usage-accounting"}}',
              ),
            ],
          ),
        ],
      );
      final c = ProviderContainer(
        overrides: [
          activeProfileIdProvider.overrideWith(_Owner.new),
          contextEpochProvider.overrideWith(_Epoch.new),
        ],
      );
      addTearDown(c.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
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
      expect(find.textContaining('USD 0.000000001（报告）'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('费用：USD 0.000000001 · 供应商报告'),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('费用：USD 0.000000001 · 供应商报告'), findsOneWidget);
      expect(find.textContaining('总计 未知'), findsOneWidget);
      await tester.tap(find.text('费用来源'));
      await tester.pumpAndSettle();
      expect(find.textContaining('usage-accounting'), findsOneWidget);
    },
  );
}
