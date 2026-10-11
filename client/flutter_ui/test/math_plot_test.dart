import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/math_plot.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';

import 'dart:math' as math;

Map<String, dynamic> plot(Map<String, dynamic> curve) => {
  'id': 'plot',
  'type': 'math_plot',
  'xMin': -5,
  'xMax': 5,
  'yMin': -5,
  'yMax': 5,
  'curves': [curve],
};

void main() {
  testWidgets('implicit background computation uses latest parameters', (
    tester,
  ) async {
    final definition = plot({
      'kind': 'implicit',
      'label': '圆',
      'expression': 'x^2+y^2-a',
    });
    Widget scene(double a) => MaterialApp(
      home: Scaffold(
        body: MathPlotView(definition: definition, parameters: {'a': a}),
      ),
    );
    await tester.pumpWidget(scene(1));
    await tester.pumpWidget(scene(2));
    await tester.pump(const Duration(milliseconds: 100));
    for (
      var attempt = 0;
      attempt < 30 && find.text('正在重新计算曲线…').evaluate().isNotEmpty;
      attempt++
    ) {
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 100));
      });
      await tester.pump();
    }
    expect(find.text('正在重新计算曲线…'), findsNothing);
    final painter = tester
        .widgetList<CustomPaint>(find.byType(CustomPaint))
        .map((w) => w.painter)
        .whereType<MathPlotPainter>()
        .single;
    final points = painter.curves.single.segments.expand((s) => s);
    expect(points, isNotEmpty);
    for (final p in points) {
      expect(p.dx * p.dx + p.dy * p.dy, closeTo(2, 1e-5));
    }
    expect(tester.takeException(), isNull);
  });
  test('hyperbola parabola and rotated ellipse obey their equations', () {
    final hyperbola = MathPlot.sample(
      plot({
        'kind': 'parametric',
        'label': '双曲线右支',
        'x': '3*sqrt(1+t^2)',
        'y': '2*t',
        'min': -2,
        'max': 2,
      }),
      {},
    ).single.segments.expand((s) => s);
    expect(hyperbola, isNotEmpty);
    for (final p in hyperbola) {
      expect(p.dx * p.dx / 9 - p.dy * p.dy / 4, closeTo(1, 1e-9));
    }
    final parabola = MathPlot.sample(
      plot({'kind': 'function', 'label': '抛物线', 'expression': 'x^2-2*x-3'}),
      {},
    ).single.segments.expand((s) => s);
    for (final p in parabola) {
      expect(p.dy, closeTo(p.dx * p.dx - 2 * p.dx - 3, 1e-9));
    }
    final rotated = MathPlot.sample(
      plot({
        'kind': 'parametric',
        'label': '旋转椭圆',
        'x': '3*cos(t)*cos(pi/4)-2*sin(t)*sin(pi/4)',
        'y': '3*cos(t)*sin(pi/4)+2*sin(t)*cos(pi/4)',
        'min': 0,
        'max': 2 * math.pi,
      }),
      {},
    ).single.segments.expand((s) => s);
    for (final p in rotated) {
      final x = (p.dx + p.dy) / math.sqrt(2), y = (-p.dx + p.dy) / math.sqrt(2);
      expect(x * x / 9 + y * y / 4, closeTo(1, 1e-9));
    }
  });
  test('grammar precedence and mathematical functions match host', () {
    final samples = {
      '-x^2': -4.0,
      '2^3^2': 512.0,
      '2^-2': .25,
      'sqrt(9)+ln(e)+log(100)': 6.0,
      'sin(pi/2)+abs(-3)': 4.0,
      'a*x^2': 12.0,
    };
    for (final entry in samples.entries) {
      expect(
        MathExpression.parse(entry.key, {'x', 'a'}).evaluate({'x': 2, 'a': 3}),
        closeTo(entry.value, 1e-9),
      );
    }
    for (final bad in [
      '2x',
      'system(x)',
      'x;exit()',
      'x+y',
      'NaN',
      '1e999',
      '${'(' * 30}x${')' * 30}',
      '${'x+' * 100}x',
    ]) {
      expect(() => MathExpression.parse(bad, {'x'}), throwsA(anything));
    }
  });
  test('ellipse parameter sampling satisfies original equation', () {
    final p = plot({
      'kind': 'parametric',
      'label': '椭圆',
      'x': '3*cos(t)',
      'y': '2*sin(t)',
      'min': 0,
      'max': 2 * math.pi,
    });
    final points = MathPlot.sample(p, {}).single.segments.expand((s) => s);
    expect(points.length, greaterThan(1000));
    for (final p in points) {
      expect(p.dx * p.dx / 9 + p.dy * p.dy / 4, closeTo(1, 1e-9));
    }
  });
  test('parameters recompute and definition limits are respected', () {
    final p = plot({
      'kind': 'function',
      'label': '函数',
      'expression': 'a*x^2',
      'min': 0,
      'max': 1,
    });
    final first = MathPlot.sample(p, {
      'a': 1,
    }).single.segments.expand((s) => s).toList();
    final second = MathPlot.sample(p, {
      'a': 2,
    }).single.segments.expand((s) => s).toList();
    expect(second.last.dy, closeTo(first.last.dy * 2, 1e-9));
    expect(first.every((p) => p.dx >= 0 && p.dx <= 1), isTrue);
    expect(() => MathPlot.validate(p, {}), throwsA(anything));
  });
  test('discontinuities and non-real domain are not connected', () {
    final p = plot({
      'kind': 'function',
      'label': '倒数',
      'expression': '1/(x-0.0123)',
    });
    final lines = MathPlot.sample(p, {}).single.segments;
    expect(lines.length, greaterThanOrEqualTo(2));
    expect(
      lines.any(
        (line) =>
            line.any((p) => p.dx < .0123) && line.any((p) => p.dx > .0123),
      ),
      isFalse,
    );
    final root = MathPlot.sample(
      plot({'kind': 'function', 'label': '平方根', 'expression': 'sqrt(x)'}),
      {},
    ).single.segments.expand((s) => s);
    expect(root, isNotEmpty);
    expect(root.every((p) => p.dx >= 0 && p.dy >= 0), isTrue);
  });
  test('implicit zero contour has bounded residual and rejects poles', () {
    final points = MathPlot.sample(
      plot({'kind': 'implicit', 'label': '圆', 'expression': 'x^2+y^2-4'}),
      {},
    ).single.segments.expand((s) => s);
    expect(points.length, greaterThan(100));
    for (final p in points) {
      expect((p.dx * p.dx + p.dy * p.dy - 4).abs(), lessThan(.01));
    }
    expect(
      MathPlot.sample(
        plot({
          'kind': 'implicit',
          'label': '无零点',
          'expression': '1/(x-0.0123)',
        }),
        {},
      ).single.segments,
      isEmpty,
    );
  });
  test('equal aspect keeps a circle circular on a rectangular screen', () {
    final p = plot({'kind': 'function', 'label': '线', 'expression': 'x'});
    final painter = MathPlotPainter(p, [], Colors.black);
    const size = Size(500, 320);
    final origin = painter.position(Offset.zero, size);
    final dx = (painter.position(const Offset(1, 0), size) - origin).distance;
    final dy = (painter.position(const Offset(0, 1), size) - origin).distance;
    expect(dx, closeTo(dy, 1e-9));
  });
  testWidgets('surface slider recomputes plot and supports saved state', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    final p = plot({'kind': 'function', 'label': '抛物线', 'expression': 'a*x^2'});
    final definition = SurfaceDefinition.parse({
      'version': 3,
      'title': '函数探索',
      'components': [
        {
          'id': 'a',
          'type': 'slider',
          'label': '系数 a',
          'min': 1,
          'max': 3,
          'value': 1,
        },
        p,
      ],
    });
    Map<String, dynamic>? saved;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: InteractiveSurfaceView(
              definition: definition,
              load: () async => {'a': 2},
              save: (state, action, request) async {
                saved = state;
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel(RegExp('数学坐标图')), findsOneWidget);
    expect(
      tester.widget<MathPlotView>(find.byType(MathPlotView)).parameters['a'],
      2,
    );
    final slider = tester.widget<Slider>(find.byType(Slider));
    slider.onChanged!(3);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(
      tester.widget<MathPlotView>(find.byType(MathPlotView)).parameters['a'],
      3,
    );
    expect(saved?['a'], 3);
    expect(tester.takeException(), isNull);
    semantics.dispose();
  });
}
