import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/surface_layout.dart';

Map<String, dynamic> layoutFixture() => {
  'version': 4,
  'title': 'Comparison',
  'presentation': 'inline',
  'density': 'compact',
  'components': [
    {
      'id': 'a',
      'type': 'metric',
      'label': 'First',
      'value': 120,
      'variant': 'emphasis',
      'tone': 'info',
    },
    {
      'id': 'b',
      'type': 'metric',
      'label': 'Second',
      'value': 140,
      'variant': 'outline',
    },
    {'id': 'details', 'type': 'rich_text', 'text': '**Details**'},
  ],
  'composition': {
    'type': 'column',
    'children': [
      {
        'type': 'row',
        'weights': [2, 1],
        'children': ['a', 'b'],
      },
      {
        'type': 'accordion',
        'children': [
          {
            'type': 'column',
            'label': 'More',
            'children': ['details'],
          },
        ],
      },
    ],
  },
};

void main() {
  test('Composition depth is bounded', () {
    dynamic node = 'a';
    for (var i = 0; i < 7; i++) {
      node = {
        'type': 'column',
        'children': [node],
      };
    }
    expect(
      () => SurfaceLayout.validateComposition(node, {'a'}),
      throwsFormatException,
    );
  });
  test(
    'Layout rejects duplicate refs, missing refs, excessive depth and styles',
    () {
      for (final composition in [
        {
          'type': 'row',
          'children': ['a', 'a', 'details'],
        },
        {
          'type': 'column',
          'children': ['a'],
        },
        {
          'type': 'row',
          'weights': [0, 2, 3],
          'children': ['a', 'b', 'details'],
        },
        {
          'type': 'tabs',
          'children': ['a', 'b', 'details'],
        },
        {
          'type': 'grid',
          'columns': 4,
          'children': ['a', 'b', 'details'],
        },
      ]) {
        expect(
          () => SurfaceDefinition.parse({
            ...layoutFixture(),
            'composition': composition,
          }),
          throwsFormatException,
        );
      }
      expect(
        () => SurfaceDefinition.parse({...layoutFixture(), 'density': 'tiny'}),
        throwsFormatException,
      );
      expect(
        () => SurfaceDefinition.parse({
          ...layoutFixture(),
          'layout': [
            ['a', 'b', 'details'],
          ],
        }),
        throwsFormatException,
      );
    },
  );
  testWidgets('Narrow large text wraps weighted columns and opens accordion', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.6)),
          child: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: SurfaceDefinition.parse(layoutFixture()),
                save: (_, _, _) async {},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('120'), findsOneWidget);
    await tester.tap(find.text('More'));
    await tester.pumpAndSettle();
    expect(find.text('Details'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('Tabs preserve controls while switching presentation', (
    tester,
  ) async {
    final fixture = {
      'version': 4,
      'title': 'Tabs',
      'components': [
        {'id': 'input', 'type': 'input', 'label': 'Name', 'value': 'A'},
        {'id': 'note', 'type': 'rich_text', 'text': 'Other content'},
      ],
      'composition': {
        'type': 'tabs',
        'children': [
          {
            'type': 'column',
            'label': 'Edit',
            'children': ['input'],
          },
          {
            'type': 'column',
            'label': 'Read',
            'children': ['note'],
          },
        ],
      },
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
    await tester.enterText(find.byType(TextField), 'Changed');
    await tester.tap(find.text('Read'));
    await tester.pump();
    expect(find.text('Other content'), findsOneWidget);
    await tester.tap(find.text('Edit'));
    await tester.pump();
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller!.text,
      'Changed',
    );
    await tester.pump(const Duration(milliseconds: 450));
    expect(tester.takeException(), isNull);
  });
  testWidgets('Analysis template groups metrics and applies semantic tone', (
    tester,
  ) async {
    final fixture = layoutFixture()..remove('composition');
    fixture['template'] = 'analysis';
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
    expect(find.byType(SurfaceComponentStyle), findsNWidgets(3));
    expect(find.text('120'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
