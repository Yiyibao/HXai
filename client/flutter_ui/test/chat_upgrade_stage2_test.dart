import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/message_phase.dart';
import 'package:flutter_ui/widgets/enhanced_cards.dart';
import 'package:flutter_ui/widgets/chat_activity.dart';

MessageDto message(String state, [List<MessagePartDto>? parts]) => MessageDto(
  id: 'a',
  nodeId: 'a',
  role: 'assistant',
  parts: parts ?? [],
  createdAt: '',
  revision: 0,
  generationState: state,
);
void main() {
  testWidgets('Activity remains visible until generation finishes', (
    tester,
  ) async {
    Widget view(MessageDto m) => ProviderScope(
      child: MaterialApp(
        home: Scaffold(body: MessagePhase(message: m)),
      ),
    );
    await tester.pumpWidget(view(message('RUNNING')));
    expect(find.text('正在思考'), findsOneWidget);
    expect(find.byType(ChatActivityDots), findsOneWidget);
    await tester.pumpWidget(
      view(message('RUNNING', [MessagePartDto(type: 'text', text: 'answer')])),
    );
    expect(find.text('正在思考'), findsNothing);
    expect(find.text('正在生成回复'), findsOneWidget);
    expect(find.byType(ChatActivityDots), findsOneWidget);
    await tester.pumpWidget(
      view(
        message('COMPLETED', [MessagePartDto(type: 'text', text: 'answer')]),
      ),
    );
    expect(find.byType(ChatActivityDots), findsNothing);
  });
  testWidgets('Running tools animate and approvals remain a waiting state', (
    tester,
  ) async {
    Widget view(MessageDto m) => ProviderScope(
      child: MaterialApp(
        home: Scaffold(body: MessagePhase(message: m)),
      ),
    );
    await tester.pumpWidget(
      view(
        message('RUNNING', [
          MessagePartDto(type: 'tool', executionState: 'RUNNING'),
        ]),
      ),
    );
    expect(find.text('正在执行'), findsOneWidget);
    expect(find.byType(ChatActivityDots), findsOneWidget);
    await tester.pumpWidget(
      view(
        message('RUNNING', [
          MessagePartDto(
            type: 'tool',
            executionState: 'NOT_STARTED',
            approvalState: 'pending',
          ),
        ]),
      ),
    );
    expect(find.text('等待你确认操作'), findsOneWidget);
    expect(find.byType(ChatActivityDots), findsNothing);
  });
  testWidgets('Unknown tool result blocks retry and resume', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: MessagePhase(
              message: message('INTERRUPTED', [
                MessagePartDto(type: 'tool', executionState: 'INTERRUPTED'),
              ]),
              onResume: () async {},
              onRetry: () async {},
            ),
          ),
        ),
      ),
    );
    expect(find.text('继续处理'), findsNothing);
    expect(find.textContaining('待核对'), findsOneWidget);
  });
  testWidgets(
    'Completed call with failed structured output never shows generated success',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: EnhancedToolCard(
              messageId: 'a',
              part: MessagePartDto(
                type: 'tool',
                toolName: 'render_math_plot',
                executionState: 'COMPLETED',
                toolOutput: '{"status":"FAILED"}',
              ),
            ),
          ),
        ),
      );
      expect(find.textContaining('处理未完成'), findsOneWidget);
      expect(find.textContaining('已生成'), findsNothing);
    },
  );
}
