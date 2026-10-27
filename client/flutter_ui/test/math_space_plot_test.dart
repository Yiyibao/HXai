import 'dart:math' as math;
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/math_plot.dart';
import 'package:flutter_ui/widgets/math_space_plot.dart';

Map<String, dynamic> space(Map<String, dynamic> curve) => {
  'id': 'plot', 'type': 'math_plot_3d',
  'xMin': -1, 'xMax': 1, 'yMin': -1, 'yMax': 1,
  'zMin': -1, 'zMax': 1, 'azimuth': 45, 'elevation': 30,
  'curves': [curve],
};

void main() {
  test('spatial line projection preserves coordinates and parameter sign', () {
    final definition = space({
      'kind': 'space_curve', 'label': 'line',
      'x': 't', 'y': '0', 'z': 'a*t', 'min': -1, 'max': 1,
    });
    for (final a in [-1.0, 1.0]) {
      final end = MathSpacePlot.sample(definition, {'a': a})
          .single.segments.last.last;
      expect(end.dx, closeTo(math.sqrt(.5), 1e-12));
      expect(end.dy, closeTo(-.5 * math.sqrt(.5) + a * math.sqrt(.75), 1e-12));
    }
  });

  test('non-real surface domain and poles never create connecting segments', () {
    final root = MathSpacePlot.sampleWorld(space({
      'kind': 'surface', 'label': 'root', 'expression': 'sqrt(x)',
    }), {});
    for (final point in root.single.segments.expand((s) => s)) {
      expect(point.$1, greaterThanOrEqualTo(0));
      expect(point.$3, closeTo(math.sqrt(point.$1), 1e-12));
    }
    final pole = MathSpacePlot.sampleWorld(space({
      'kind': 'space_curve', 'label': 'pole',
      'x': 't', 'y': '0', 'z': '0.01/(t-0.0137)', 'min': -1, 'max': 1,
    }), {});
    for (final segment in pole.single.segments) {
      expect(segment.first.$1 < .0137 && segment.last.$1 > .0137, isFalse);
      for (final point in segment) {
        expect(point.$3 * (point.$1 - .0137), closeTo(.01, 1e-12));
      }
    }
  });

  test('off-grid excluded point cannot be connected after algebraic cancellation', () {
    final definition = {
      'id': 'plot', 'type': 'math_plot',
      'xMin': -1, 'xMax': 1, 'yMin': 0, 'yMax': 2,
      'curves': [
        {'kind': 'function', 'label': 'hole',
         'expression': '(x-0.137)/(x-0.137)', 'exclude': [.137]},
      ],
      'points': [{'x': .137, 'y': 1, 'label': 'undefined', 'open': true}],
    };
    final segments = MathPlot.sample(definition, {}).single.segments;
    expect(segments.any((s) => s.last.dx < .137), isTrue);
    expect(segments.any((s) => s.first.dx > .137), isTrue);
    for (final segment in segments) {
      expect(segment.first.dx < .137 && segment.last.dx > .137, isFalse);
      for (final point in segment) {
        expect(point.dy, closeTo(1, 1e-12));
      }
    }
  });
}
