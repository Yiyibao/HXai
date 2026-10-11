import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/chat_display_patch.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';
import 'package:flutter_ui/widgets/surface_semantics.dart';

ConversationDetailDto fixture() => ConversationDetailDto(
  id: 'c',
  title: 't',
  profileId: 'p',
  studyMode: 'CHAT',
  isGenerating: true,
  lifecycleRevision: 1,
  nodes: [
    for (final id in ['old', 'live'])
      MessageNodeDto(
        nodeId: id,
        selectIndex: 0,
        branches: [
          MessageDto(
            id: id,
            nodeId: id,
            role: 'assistant',
            parts: [MessagePartDto(type: 'text', text: id)],
            createdAt: '',
            revision: 0,
            generationState: 'RUNNING',
          ),
        ],
      ),
  ],
);
void main() {
  test('Cumulative patches preserve old rows, never duplicate text, reject wrong branch', () {
    final before = fixture();
    final patch = {
      'conversationId': 'c',
      'changes': [
        {
          'nodeId': 'live',
          'messageId': 'live',
          'parts': [
            {'index': 0, 'text': 'live complete text'},
          ],
        },
      ],
    };
    final after = applyChatDisplayPatch(before, patch)!;
    expect(identical(before.nodes.first, after.nodes.first), isTrue);
    expect(
      ChatLayoutSnapshot(before, false, null),
      ChatLayoutSnapshot(after, false, null),
    );
    expect(
      applyChatDisplayPatch(
        after,
        patch,
      )!.nodes.last.branches.first.parts.first.text,
      'live complete text',
    );
    patch['conversationId'] = 'different';
    expect(applyChatDisplayPatch(after, patch), isNull);
  });
  testWidgets(
    'Unclosed code keeps formula delimiters literal and copies actual content',
    (tester) async {
      String? copied;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
            if (call.method == 'Clipboard.setData')
              copied = (call.arguments as Map)['text'] as String;
            return null;
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: MathMarkdownView(
              content: '先看代码\n\n```python\nprint("\$\$x\$\$")',
              streaming: true,
            ),
          ),
        ),
      );
      expect(find.byType(SurfaceCodeBlock), findsOneWidget);
      await tester.tap(find.byTooltip('复制代码'));
      await tester.pump();
      expect(copied, 'print("\$\$x\$\$")');
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('Reference-link definitions remain in one Markdown context', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MathMarkdownView(
            content: '查看 [来源][source]\n\n[source]: https://example.com',
          ),
        ),
      ),
    );
    expect(find.textContaining('来源', findRichText: true), findsWidgets);
    expect(find.textContaining('[source]:', findRichText: true), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('Completed code widget is retained while trailing prose grows', (
    tester,
  ) async {
    Widget view(String tail) => MaterialApp(
      home: Scaffold(
        body: MathMarkdownView(
          key: const ValueKey('doc'),
          content: '```text\nstable\n```\n\n$tail',
          streaming: true,
        ),
      ),
    );
    await tester.pumpWidget(view('a'));
    final old = tester.widget<SurfaceCodeBlock>(find.byType(SurfaceCodeBlock));
    await tester.pumpWidget(view('a longer tail'));
    expect(
      identical(
        old,
        tester.widget<SurfaceCodeBlock>(find.byType(SurfaceCodeBlock)),
      ),
      isTrue,
    );
  });
}
