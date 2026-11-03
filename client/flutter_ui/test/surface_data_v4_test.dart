import 'package:fl_chart/fl_chart.dart';
import 'dart:ui' show PointerDeviceKind;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/surface_data_views.dart';

Map<String, dynamic> chartFixture([String type = 'line_chart']) => {
  'id': 'chart',
  'type': type,
  'selectable': true,
  'digits': 1,
  'xUnit': 'day',
  'yUnit': 'USD',
  'series': [
    {
      'label': 'Actual',
      'points': [
        [1, 12],
        [2, 16],
      ],
    },
    {
      'label': 'Plan',
      'points': [
        [1, 10],
        [2, 20],
      ],
    },
  ],
  'annotations': [
    {'axis': 'y', 'value': 15, 'label': 'Target'},
  ],
};
Map<String, dynamic> tableFixture() => {
  'id': 'table',
  'type': 'table',
  'columns': ['Name', 'Cost', 'Rate'],
  'columnTypes': ['text', 'currency', 'percent'],
  'units': ['', 'USD', ''],
  'digits': 1,
  'rows': [
    ['B', 12, .1],
    ['A', 2, .25],
  ],
  'highlight': [
    {'row': 0, 'column': 1, 'tone': 'warning'},
  ],
};
SurfaceDefinition spec(Map<String, dynamic> c) => SurfaceDefinition.parse({
  'version': 4,
  'title': 'Data',
  'components': [c],
});
void main() {
  testWidgets('Point selection maps to global index even with hidden series', (
    tester,
  ) async {
    int? selected;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SurfaceChartView(
            definition: chartFixture(),
            factor: 1,
            selected: -1,
            onSelect: (value) => selected = value,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.text('Actual'));
    await tester.pump();
    final data = tester.widget<LineChart>(find.byType(LineChart)).data;
    data.lineTouchData.touchCallback!(
      FlTapUpEvent(TapUpDetails(kind: PointerDeviceKind.touch)),
      LineTouchResponse(
        touchLocation: Offset.zero,
        touchChartCoordinate: Offset.zero,
        lineBarSpots: [
          TouchLineBarSpot(
            data.lineBarsData.first,
            0,
            data.lineBarsData.first.spots.last,
            0,
          ),
        ],
      ),
    );
    expect(selected, 3);
  });
  test('Validates multi-series selection and rejects malformed data', () {
    final definition = spec(chartFixture());
    definition.validateState({'chart': 3});
    expect(() => definition.validateState({'chart': 4}), throwsFormatException);
    for (final c in [
      {
        ...chartFixture(),
        'points': [
          [1, 1],
        ],
      },
      {
        ...chartFixture(),
        'series': [
          {
            'label': 'A',
            'points': [
              [1, double.infinity],
            ],
          },
        ],
      },
      {...chartFixture(), 'digits': 8},
      {
        ...tableFixture(),
        'columnTypes': ['text'],
      },
      {
        ...tableFixture(),
        'highlight': [
          {'row': 2, 'column': 1, 'tone': 'info'},
        ],
      },
    ]) {
      expect(() => spec(c), throwsFormatException);
    }
  });
  testWidgets(
    'Charts render all variants, preserve selection and legend visibility',
    (tester) async {
      for (final type in ['line_chart', 'bar_chart', 'scatter_chart']) {
        await tester.pumpWidget(
          MaterialApp(
            theme: ThemeData.dark(),
            home: Scaffold(
              body: SingleChildScrollView(
                child: SurfaceChartView(
                  key: ValueKey(type),
                  definition: chartFixture(type),
                  factor: 2,
                  selected: 3,
                ),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(find.text('Plan: 2.0 day, 40.0 USD'), findsOneWidget);
        await tester.tap(find.text('Actual'));
        await tester.pump();
        if (type != 'bar_chart')
          expect(
            tester
                .widget<LineChart>(find.byType(LineChart))
                .data
                .lineBarsData
                .length,
            1,
          );
        await tester.tap(find.text('Plan'));
        await tester.pump();
        if (type != 'bar_chart')
          expect(
            tester
                .widget<LineChart>(find.byType(LineChart))
                .data
                .lineBarsData
                .length,
            1,
          );
        expect(tester.takeException(), isNull);
      }
    },
  );
  testWidgets(
    'Table sorts numerically, formats units and pins header while scrolling',
    (tester) async {
      final c = tableFixture();
      c['rows'] = [
        ...(c['rows'] as List),
        ...List.generate(20, (i) => ['Item $i', i + 20, .2]),
      ];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: SurfaceTableView(definition: c)),
        ),
      );
      await tester.pump();
      expect(find.text('12.0 USD'), findsOneWidget);
      expect(find.text('10.0 %'), findsOneWidget);
      await tester.tap(find.text('Cost'));
      await tester.pump();
      expect(
        tester.getTopLeft(find.text('2.0 USD')).dy,
        lessThan(tester.getTopLeft(find.text('12.0 USD')).dy),
      );
      final header = tester.getTopLeft(find.text('Cost'));
      await tester.drag(find.byType(ListView), const Offset(0, -240));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('Cost')), header);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('Cell highlight follows original row after sorting', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: SurfaceTableView(definition: tableFixture())),
      ),
    );
    await tester.pump();
    final before = tester
        .widget<Container>(find.byKey(const ValueKey('cell_0_1')))
        .decoration;
    await tester.tap(find.text('Name'));
    await tester.pump();
    expect(
      tester
          .widget<Container>(find.byKey(const ValueKey('cell_0_1')))
          .decoration,
      before,
    );
    expect(
      tester.getTopLeft(find.text('A')).dy,
      lessThan(tester.getTopLeft(find.text('B')).dy),
    );
  });
}
