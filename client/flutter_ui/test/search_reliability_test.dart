import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/enhanced_cards.dart';
import 'package:flutter_ui/widgets/citations_sheet.dart';

void main() {
  MessagePartDto part(Map<String, Object?> output) => MessagePartDto(
    type: 'tool',
    toolName: 'search_web',
    executionState: 'COMPLETED',
    toolInput: '{"query":"导数定义"}',
    toolOutput: jsonEncode(output),
  );
  testWidgets(
    'search failure is visible even though tool execution completed',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: EnhancedToolCard(
              messageId: 'message',
              part: part({'status': 'error', 'items': []}),
            ),
          ),
        ),
      );
      expect(find.textContaining('搜索暂不可用'), findsOneWidget);
      expect(find.textContaining('导数定义'), findsOneWidget);
    },
  );
  testWidgets('source count reflects actual results', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: EnhancedToolCard(
            messageId: 'message',
            part: part({
              'status': 'ok',
              'items': [
                {'url': 'https://source.org/'},
              ],
            }),
          ),
        ),
      ),
    );
    expect(find.textContaining('找到 1 个来源'), findsOneWidget);
    await tester.tap(find.textContaining('找到 1 个来源'));
    await tester.pump();
    expect(find.byIcon(Icons.open_in_new), findsOneWidget);
    expect(find.text('参数'), findsNothing);
    expect(find.text('结果'), findsNothing);
  });
  test(
    'sources preserve evidence and publication dates and deduplicate URLs',
    () {
      final sources = messageCitations(
        MessageDto(
          id: 'm',
          nodeId: 'n',
          role: 'assistant',
          createdAt: '',
          revision: 0,
          parts: [
            part({
              'items': [
                {
                  'title': 'Original',
                  'url': 'https://source.org/',
                  'text': 'Evidence',
                  'publishedDate': '2026-10-01',
                },
                {'title': 'Duplicate', 'url': 'https://source.org/'},
                {'title': 'Unsafe', 'url': 'javascript:bad'},
              ],
            }),
          ],
        ),
      );
      expect(sources, hasLength(1));
      expect(sources.single.summary, 'Evidence');
      expect(sources.single.date, '2026-10-01');
    },
  );
}
