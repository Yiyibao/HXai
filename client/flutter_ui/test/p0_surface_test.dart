import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';

Map<String, dynamic> definition() => {
  'version': 3,
  'title': 'Budget',
  'components': [
    {
      'id': 'people',
      'type': 'slider',
      'label': 'People',
      'min': 1,
      'max': 10,
      'value': 2,
    },
    {
      'id': 'price',
      'type': 'slider',
      'label': 'Price',
      'min': 10,
      'max': 100,
      'value': 30,
    },
    {
      'id': 'total',
      'type': 'calculation',
      'label': 'Total',
      'expression': {
        'op': 'multiply',
        'args': [
          {'parameter': 'people'},
          {'parameter': 'price'},
        ],
      },
    },
    {
      'id': 'chart',
      'type': 'scatter_chart',
      'selectable': true,
      'points': [
        [1, 2],
        [3, 4],
      ],
    },
    {'id': 'go', 'type': 'button', 'label': 'Continue', 'action': 'continue'},
  ],
  'layout': [
    ['people', 'price'],
    ['total'],
    ['chart'],
    ['go'],
  ],
};
void main() {
  testWidgets(
    'Phone parameters recalculate, reject invalid values and continue with edited state',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      Map<String, dynamic>? sent;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: SurfaceDefinition.parse(definition()),
                save: (values, action, _) async {
                  if (action != null) sent = values;
                },
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.byType(TextField), findsNWidgets(2));
      await tester.enterText(find.byType(TextField).first, '5');
      await tester.pump();
      expect(find.text('150'), findsOneWidget);
      await tester.enterText(find.byType(TextField).first, '100');
      await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    await tester.ensureVisible(find.text('恢复默认'));
    await tester.tap(find.text('恢复默认'));
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, '2');
    expect(find.text('60'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNotNull);
    await tester.ensureVisible(find.byType(TextField).first);
      await tester.enterText(find.byType(TextField).first, '4');
      await tester.pump();
      await tester.ensureVisible(find.text('Continue'));
      await tester.tap(find.text('Continue'));
      await tester.pump();
      expect(sent!['people'], 4);
      expect(sent!['price'], 30);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Multi parameter calculation, chart selection and restored continuation',
    (tester) async {
      final spec = SurfaceDefinition.parse(definition());
      Map<String, dynamic>? sent;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: spec,
                load: () async => {'people': 3, 'price': 20, 'chart': 1},
                save: (values, action, _) async {
                  if (action != null) sent = values;
                },
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('60'), findsOneWidget);
      expect(find.textContaining('选中数据点 2'), findsOneWidget);
      final chart = find.byWidgetPredicate((widget) => widget is Semantics && widget.properties.label == '交互图表，2个数据点');
      await tester.ensureVisible(chart);
      await tester.tap(chart);
      await tester.pump();
      await tester.ensureVisible(find.text('Continue'));
      await tester.tap(find.text('Continue'));
      await tester.pump();
      expect(sent!['people'], 3);
      expect(sent!['price'], 20);
      expect(sent!['chart'], inInclusiveRange(0, 1));
    },
  );
  test(
    'Rejects duplicated layouts, foreign references and unbounded expressions',
    () {
      final bad = definition()
        ..['layout'] = [
          ['people', 'people'],
        ];
      expect(() => SurfaceDefinition.parse(bad), throwsFormatException);
      final foreign = definition();
      (foreign['components'] as List)[2]['expression'] = {
        'parameter': 'foreign',
      };
      expect(() => SurfaceDefinition.parse(foreign), throwsFormatException);
      final spec = SurfaceDefinition.parse(definition());
      expect(
        () => spec.validateState({'people': 3, 'price': 20, 'chart': 2}),
        throwsFormatException,
      );
    },
  );
}
