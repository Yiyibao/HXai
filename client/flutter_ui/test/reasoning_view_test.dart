import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/reasoning_view.dart';

void main() {
  Future<void> render(
    WidgetTester tester,
    String content,
    ReasoningStatus status,
  ) => tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: ReasoningView(
          key: const ValueKey('same-part'),
          content: content,
          status: status,
        ),
      ),
    ),
  );

  testWidgets(
    'Collapsed reasoning expands and stays open through stream completion',
    (tester) async {
      await render(tester, '先核对来源。', ReasoningStatus.thinking);
      expect(find.text('正在思考'), findsOneWidget);
      expect(find.text('先核对来源。', findRichText: true), findsNothing);
      await tester.tap(find.text('正在思考'));
      await tester.pump();
      expect(find.text('先核对来源。', findRichText: true), findsOneWidget);
      await render(tester, '先核对来源。\n\n然后比较结果。', ReasoningStatus.completed);
      expect(find.text('已完成思考'), findsOneWidget);
      expect(
        find.textContaining('然后比较结果。', findRichText: true),
        findsOneWidget,
      );
      expect(find.byType(CircularProgressIndicator), findsNothing);
      await tester.tap(find.text('已完成思考'));
      await tester.pump();
      expect(find.textContaining('然后比较结果。', findRichText: true), findsNothing);
    },
  );

  testWidgets(
    'Empty completed reasoning is hidden and interruptions are honest',
    (tester) async {
      await render(tester, '', ReasoningStatus.thinking);
      expect(find.text('正在思考'), findsOneWidget);
      await render(tester, '', ReasoningStatus.completed);
      expect(find.text('已完成思考'), findsNothing);
      await render(tester, '尚未完成。', ReasoningStatus.interrupted);
      expect(find.text('思考已中断'), findsOneWidget);
    },
  );

  testWidgets('Long expanded reasoning remains bounded without overflow', (
    tester,
  ) async {
    await render(
      tester,
      List.filled(100, '核对资料与数据。').join('\n\n'),
      ReasoningStatus.completed,
    );
    await tester.tap(find.text('已完成思考'));
    await tester.pumpAndSettle();
    expect(
      tester.getSize(find.byType(SingleChildScrollView)).height,
      lessThanOrEqualTo(280),
    );
    await tester.drag(
      find.byType(SingleChildScrollView),
      const Offset(0, -220),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  test('Reasoning status follows host state and subsequent answer or tool', () {
    final message = MessageDto(
      id: 'm',
      nodeId: 'n',
      role: 'assistant',
      parts: [MessagePartDto(type: 'reasoning', reasoning: '核对')],
      createdAt: '',
      revision: 1,
      generationState: 'RUNNING',
    );
    expect(reasoningStatus(message, 0), ReasoningStatus.thinking);
    message.parts.add(MessagePartDto(type: 'text', text: ''));
    expect(reasoningStatus(message, 0), ReasoningStatus.thinking);
    message.parts.last.text = '答复';
    expect(reasoningStatus(message, 0), ReasoningStatus.completed);
    message.parts.removeLast();
    message.generationState = 'CANCELLED';
    expect(reasoningStatus(message, 0), ReasoningStatus.interrupted);
    message.generationState = 'WAITING_TOOL';
    message.parts.add(MessagePartDto(type: 'tool'));
    expect(reasoningStatus(message, 0), ReasoningStatus.completed);
  });

  test('One message groups reasoning segments in provider order', () {
    final message = MessageDto(
      id: 'm',
      nodeId: 'n',
      role: 'assistant',
      parts: [
        MessagePartDto(type: 'reasoning', reasoning: '第一步'),
        MessagePartDto(type: 'tool'),
        MessagePartDto(type: 'reasoning', reasoning: ''),
        MessagePartDto(type: 'reasoning', reasoning: '第二步'),
      ],
      createdAt: '',
      revision: 1,
      generationState: 'RUNNING',
    );
    expect(messageReasoning(message), '第一步\n\n---\n\n第二步');
    expect(reasoningStatus(message, 3), ReasoningStatus.thinking);
  });
}
