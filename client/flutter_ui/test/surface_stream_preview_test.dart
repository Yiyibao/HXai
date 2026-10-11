import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/surface_layout.dart';
import 'package:flutter_ui/widgets/surface_semantics.dart';
import 'package:flutter_ui/widgets/surface_stream_preview.dart';

Map<String, dynamic> fixture() => {
  'version': 4,
  'title': 'Streaming',
  'components': [
    {
      'id': 'intro',
      'type': 'rich_text',
      'text': 'Quote " and { braces } \\ path',
    },
    {'id': 'metric', 'type': 'metric', 'label': 'Total', 'value': 123},
  ],
};

Widget host(Widget child) => MaterialApp(
  home: Scaffold(body: SingleChildScrollView(child: child)),
);

void main() {
  test('Every character prefix only exposes whole validated components', () {
    final raw = jsonEncode(fixture());
    final wrapped = jsonEncode({'definition': raw});
    for (final text in [raw, wrapped]) {
      var previous = 0;
      for (var i = 0; i <= text.length; i++) {
        final snapshot = SurfaceStreamSnapshot.parse(text.substring(0, i));
        final count = snapshot?.components.length ?? 0;
        expect(count, greaterThanOrEqualTo(previous), reason: 'prefix $i');
        previous = count;
        for (final c in snapshot?.components ?? <Map<String, dynamic>>[]) {
          expect(
            (fixture()['components'] as List).map(jsonEncode),
            contains(jsonEncode(c)),
          );
        }
      }
      expect(previous, 2);
    }
  });

  test('Escaped unicode and half escape sequences do not throw', () {
    const input =
        r'{"definition":"{\"version\":4,\"title\":\"\u4e2d\u6587\",\"components\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"ok\"}]}"}';
    for (var i = 0; i <= input.length; i++) {
      SurfaceStreamSnapshot.parse(input.substring(0, i));
    }
    expect(SurfaceStreamSnapshot.parse(input)!.title, '\u4e2d\u6587');
    expect(SurfaceStreamSnapshot.parse(input)!.components, hasLength(1));
  });

  test('Unsafe, referenced and malformed nodes never become previews', () {
    final snapshot = SurfaceStreamSnapshot.parse(
      jsonEncode({
        ...fixture(),
        'components': [
          {
            'id': 'button',
            'type': 'button',
            'label': 'Go',
            'action': 'continue',
          },
          {
            'id': 'slider',
            'type': 'slider',
            'label': 'N',
            'min': 0,
            'max': 2,
            'value': 1,
          },
          {
            'id': 'image',
            'type': 'image',
            'url': 'https://example.com/img.png',
          },
          {'id': 'bad', 'type': 'heading', 'text': 'Bad', 'level': 9},
          {
            'id': 'ref',
            'type': 'line_chart',
            'points': [
              [0, 1],
            ],
            'scale': 'slider',
          },
          ...fixture()['components'] as List,
        ],
      }),
    )!;
    expect(snapshot.components.map((c) => c['id']), ['intro', 'metric']);
    for (final version in [1, 3, 4.0, 5]) {
      expect(
        SurfaceStreamSnapshot.parse(
          jsonEncode({...fixture(), 'version': version}),
        ),
        isNull,
      );
    }
    expect(SurfaceStreamSnapshot.parse('x' * 196609), isNull);
    expect(SurfaceStreamSnapshot.parse('{"version":4,"version":3,'), isNull);
  });

  test(
    'Approval, completion and stopped messages never show loading previews',
    () {
      final part = MessagePartDto(
        type: 'tool',
        toolName: 'render_interactive_ui',
        executionState: 'NOT_STARTED',
        approvalState: 'Auto',
      );
      expect(shouldPreviewSurface(part, 'RUNNING'), isTrue);
      for (final state in [
        null,
        'COMPLETED',
        'CANCELLED',
        'INTERRUPTED',
        'FAILED',
      ]) {
        expect(shouldPreviewSurface(part, state), isFalse);
      }
      for (final approval in ['Pending', 'Denied', 'Approved']) {
        part.approvalState = approval;
        expect(shouldPreviewSurface(part, 'RUNNING'), isFalse);
      }
      part.approvalState = 'Auto';
      part.executionState = 'COMPLETED';
      expect(shouldPreviewSurface(part, 'RUNNING'), isFalse);
    },
  );

  testWidgets('Preview coalesces updates without controls or callbacks', (
    tester,
  ) async {
    final raw = jsonEncode(fixture());
    await tester.pumpWidget(host(const SurfaceStreamingPreview(input: '{')));
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    await tester.pumpWidget(host(SurfaceStreamingPreview(input: raw)));
    await tester.pump();
    expect(find.text('Streaming'), findsOneWidget);
    expect(find.byType(SurfaceSemanticView), findsNWidgets(2));
    expect(find.byType(TextField), findsNothing);
    expect(find.byType(Slider), findsNothing);
    expect(find.byType(FilledButton), findsNothing);
    final node = tester.widget(find.byType(SurfaceSemanticView).first);
    await tester.pumpWidget(host(SurfaceStreamingPreview(input: '$raw ')));
    await tester.pump();
    expect(
      identical(node, tester.widget(find.byType(SurfaceSemanticView).first)),
      isTrue,
    );
    await tester.pumpWidget(host(const SizedBox()));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'Unrelated nodes are retained while dependent calculations update',
    (tester) async {
      final definition = SurfaceDefinition.parse({
        ...fixture(),
        'components': [
          ...fixture()['components'] as List,
          {
            'id': 'n',
            'type': 'slider',
            'label': 'N',
            'min': 0,
            'max': 10,
            'value': 2,
          },
          {
            'id': 'calc',
            'type': 'calculation',
            'label': 'Result',
            'expression': {'parameter': 'n'},
          },
        ],
      });
      await tester.pumpWidget(
        host(
          InteractiveSurfaceView(
            definition: definition,
            save: (_, _, _) async {},
          ),
        ),
      );
      await tester.pump();
      final intro = find.byWidgetPredicate(
        (w) => w is SurfaceSemanticView && w.component['id'] == 'intro',
      );
      final staticNode = tester.widget(intro);
      tester.widget<Slider>(find.byType(Slider)).onChanged!(7);
      await tester.pump();
      expect(identical(staticNode, tester.widget(intro)), isTrue);
      expect(find.text('7'), findsWidgets);
      await tester.pump(const Duration(milliseconds: 500));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Presentation-only update preserves edits and refreshes density',
    (tester) async {
      final data = {
        ...fixture(),
        'components': [
          ...fixture()['components'] as List,
          {
            'id': 'n',
            'type': 'slider',
            'label': 'N',
            'min': 0,
            'max': 10,
            'value': 2,
          },
        ],
      };
      Future<void> render(Map<String, dynamic> json) => tester.pumpWidget(
        host(
          InteractiveSurfaceView(
            definition: SurfaceDefinition.parse(json),
            save: (_, _, _) async {},
          ),
        ),
      );
      await render(data);
      await tester.pump();
      tester.widget<Slider>(find.byType(Slider)).onChanged!(7);
      await tester.pump();
      await render({...data, 'density': 'compact', 'presentation': 'panel'});
      await tester.pump();
      expect(tester.widget<Slider>(find.byType(Slider)).value, 7);
      expect(
        tester
            .widgetList<SurfaceComponentStyle>(
              find.byType(SurfaceComponentStyle),
            )
            .every((s) => s.density == 'compact'),
        isTrue,
      );
      await tester.pump(const Duration(milliseconds: 500));
      expect(tester.takeException(), isNull);
    },
  );
}
