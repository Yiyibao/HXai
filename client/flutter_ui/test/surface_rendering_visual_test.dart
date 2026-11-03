import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';

Map<String, dynamic> visualFixture() => {
  'version': 4,
  'title': 'Monthly spending',
  'template': 'analysis',
  'components': [
    {
      'id': 'summary',
      'type': 'rich_text',
      'text':
          'Compare **actual spending** with the plan. All values are in USD.',
    },
    {
      'id': 'actual',
      'type': 'metric',
      'label': 'Actual',
      'value': '1,240',
      'unit': 'USD',
      'variant': 'subtle',
      'tone': 'info',
    },
    {
      'id': 'plan',
      'type': 'metric',
      'label': 'Planned',
      'value': '1,400',
      'unit': 'USD',
      'variant': 'subtle',
      'tone': 'success',
    },
    {
      'id': 'trend',
      'type': 'line_chart',
      'digits': 0,
      'xLabel': 'Week',
      'yLabel': 'Spending',
      'yUnit': 'USD',
      'series': [
        {
          'label': 'Actual',
          'points': [
            [1, 220],
            [2, 360],
            [3, 280],
            [4, 380],
          ],
        },
        {
          'label': 'Planned',
          'points': [
            [1, 350],
            [2, 350],
            [3, 350],
            [4, 350],
          ],
        },
      ],
    },
    {
      'id': 'note',
      'type': 'callout',
      'title': 'Within budget',
      'text': 'Actual spending is **160 USD** below the plan.',
      'tone': 'success',
    },
    {
      'id': 'table',
      'type': 'table',
      'columns': ['Category', 'Actual', 'Planned'],
      'columnTypes': ['text', 'currency', 'currency'],
      'units': ['', 'USD', 'USD'],
      'digits': 0,
      'rows': [
        ['Food', 420, 500],
        ['Travel', 320, 400],
        ['Other', 500, 500],
      ],
    },
  ],
};

void main() {
  testWidgets('Capture mobile and wide light/dark rendering', (tester) async {
    final fontFile = File(
      '../../.local/flutter/bin/cache/artifacts/material_fonts/roboto-regular.ttf',
    );
    final loader = FontLoader('QAFont')
      ..addFont(Future.value(ByteData.sublistView(fontFile.readAsBytesSync())));
    await tester.runAsync(loader.load);
    final iconLoader = FontLoader('MaterialIcons')
      ..addFont(
        Future.value(
          ByteData.sublistView(
            File(
              '../../.local/flutter/bin/cache/artifacts/material_fonts/materialicons-regular.otf',
            ).readAsBytesSync(),
          ),
        ),
      );
    await tester.runAsync(iconLoader.load);
    final chineseLoader = FontLoader('QAChinese')
      ..addFont(
        Future.value(
          ByteData.sublistView(
            File('C:/Windows/Fonts/msyh.ttc').readAsBytesSync(),
          ),
        ),
      );
    await tester.runAsync(chineseLoader.load);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    for (final width in [390.0, 960.0]) {
      for (final dark in [false, true]) {
        tester.view.physicalSize = Size(width, 1300);
        final key = GlobalKey();
        final theme = (dark ? StudyTheme.darkTheme() : StudyTheme.lightTheme());
        await tester.pumpWidget(
          MaterialApp(
            theme: theme.copyWith(
              textTheme: theme.textTheme.apply(
                fontFamily: 'QAFont',
                fontFamilyFallback: ['QAChinese'],
              ),
            ),
            home: RepaintBoundary(
              key: key,
              child: Scaffold(
                body: SingleChildScrollView(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: InteractiveSurfaceView(
                      definition: SurfaceDefinition.parse(visualFixture()),
                      save: (_, _, _) async {},
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        final boundary =
            key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: 1);
          final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
          final file = File(
            '../../qa/rendering-upgrade-20261010/render-${width.toInt()}-${dark ? 'dark' : 'light'}.png',
          );
          await file.parent.create(recursive: true);
          await file.writeAsBytes(bytes!.buffer.asUint8List());
          image.dispose();
        });
      }
    }
  });
}
