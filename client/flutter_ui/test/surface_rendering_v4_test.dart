import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';
import 'package:flutter_ui/widgets/surface_semantics.dart';

Map<String, dynamic> semanticFixture() => {
  'version': 4,
  'title': 'Analysis',
  'components': [
    {'id': 'heading', 'type': 'heading', 'text': 'Results', 'level': 2},
    {'id': 'intro', 'type': 'rich_text', 'text': '**Evidence** and \$x^2\$'},
    {
      'id': 'metric',
      'type': 'metric',
      'label': 'Total',
      'value': 12500,
      'unit': 'USD',
      'delta': '+5%',
    },
    {
      'id': 'note',
      'type': 'callout',
      'title': 'Assumptions',
      'text': '*Estimated*',
      'tone': 'warning',
    },
    {
      'id': 'steps',
      'type': 'steps',
      'items': [
        {'title': 'Inspect', 'text': 'Check **data**'},
      ],
    },
    {
      'id': 'compare',
      'type': 'comparison',
      'items': [
        {'title': 'A', 'text': 'First'},
        {'title': 'B', 'text': 'Second'},
      ],
    },
    {
      'id': 'code',
      'type': 'code_block',
      'code': 'final total = 12;',
      'language': 'dart',
      'collapsed': true,
    },
  ],
};

void main() {
  testWidgets('Many comparison items use readable bounded columns', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: SizedBox(
              width: 800,
              child: SurfaceSemanticView(
                component: {
                  'type': 'comparison',
                  'items': List.generate(
                    12,
                    (i) => {'title': 'Option $i', 'text': 'Comparison content'},
                  ),
                },
              ),
            ),
          ),
        ),
      ),
    );
    final width = tester.getSize(find.text('Option 0')).width;
    expect(width, lessThanOrEqualTo(800));
    final option = find
        .ancestor(of: find.text('Option 0'), matching: find.byType(SizedBox))
        .first;
    expect(tester.getSize(option).width, greaterThan(200));
    expect(tester.takeException(), isNull);
  });
  testWidgets('Unknown code language falls back and copy keeps source', (
    tester,
  ) async {
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData')
          copied = (call.arguments as Map)['text'] as String;
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    final fixture = {
      'version': 4,
      'title': 'Code',
      'components': [
        {
          'id': 'code',
          'type': 'code_block',
          'code': 'source <tag>',
          'language': 'unknown_language',
        },
      ],
    };
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: InteractiveSurfaceView(
            definition: SurfaceDefinition.parse(fixture),
            save: (_, _, _) async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byTooltip('复制代码'));
    await tester.pump();
    expect(copied, 'source <tag>');
    expect(tester.takeException(), isNull);
  });
  test('Semantic protocol rejects bad types and stays version gated', () {
    for (final c in [
      {'id': 'x', 'type': 'heading', 'text': 'Title', 'level': 4},
      {'id': 'x', 'type': 'metric', 'label': 'Total', 'value': double.infinity},
      {
        'id': 'x',
        'type': 'steps',
        'items': [
          {'title': 'A', 'text': 'B', 'html': true},
        ],
      },
      {'id': 'x', 'type': 'code_block', 'code': 'x', 'collapsed': 'true'},
    ]) {
      expect(
        () => SurfaceDefinition.parse({
          'version': 4,
          'title': 'Bad',
          'components': [c],
        }),
        throwsFormatException,
      );
    }
    expect(
      () => SurfaceDefinition.parse({...semanticFixture(), 'version': 3}),
      throwsFormatException,
    );
  });
  testWidgets('Rich semantics render on narrow dark screens with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.5)),
          child: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: SurfaceDefinition.parse(semanticFixture()),
                save: (_, _, _) async {},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('12500'), findsOneWidget);
    expect(find.byType(MathMarkdownView), findsNWidgets(5));
    await tester.ensureVisible(find.byTooltip('展开代码'));
    await tester.tap(find.byTooltip('展开代码'));
    await tester.pump();
    expect(find.byTooltip('折叠代码'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
