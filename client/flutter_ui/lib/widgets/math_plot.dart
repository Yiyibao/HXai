import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import 'package:flutter/material.dart';

typedef MathEvaluator = double Function(Map<String, double> variables);

/// The same bounded arithmetic grammar as the host; no JS, eval or arbitrary code.
class MathExpression {
  final MathEvaluator evaluate;
  MathExpression._(this.evaluate);
  static MathExpression parse(String source, Set<String> variables) {
    if (source.isEmpty || source.length > 256) {
      throw const FormatException('公式长度无效');
    }
    return MathExpression._(_MathParser(source, variables).parse());
  }
}

class _MathParser {
  final String source;
  final Set<String> variables;
  int position = 0, nodes = 0, depth = 0;
  _MathParser(this.source, this.variables);
  bool token(String c) {
    while (position < source.length && source[position].trim().isEmpty) {
      position++;
    }
    if (position < source.length && source[position] == c) {
      position++;
      return true;
    }
    return false;
  }

  void count() {
    if (++nodes > 96) throw const FormatException('公式过于复杂');
  }

  MathEvaluator parse() {
    final result = sum();
    token(' ');
    if (position != source.length) {
      throw const FormatException('公式含未支持的符号或缺少 *');
    }
    return result;
  }

  MathEvaluator sum() {
    var result = product();
    while (true) {
      final op = token('+')
          ? '+'
          : token('-')
          ? '-'
          : '';
      if (op.isEmpty) return result;
      final left = result, right = product();
      count();
      result = (v) => op == '+' ? left(v) + right(v) : left(v) - right(v);
    }
  }

  MathEvaluator product() {
    var result = unary();
    while (true) {
      final op = token('*')
          ? '*'
          : token('/')
          ? '/'
          : '';
      if (op.isEmpty) return result;
      final left = result, right = unary();
      count();
      result = (v) => op == '*' ? left(v) * right(v) : left(v) / right(v);
    }
  }

  MathEvaluator unary() {
    if (++depth > 16) throw const FormatException('公式嵌套过深');
    try {
      if (token('+')) return unary();
      if (token('-')) {
        final arg = unary();
        count();
        return (v) => -arg(v);
      }
      return power();
    } finally {
      depth--;
    }
  }

  MathEvaluator power() {
    final base = atom();
    if (!token('^')) return base;
    final exponent = unary();
    count();
    return (v) => math.pow(base(v), exponent(v)).toDouble();
  }

  MathEvaluator atom() {
    count();
    if (token('(')) {
      final result = sum();
      if (!token(')')) throw const FormatException('缺少右括号');
      return result;
    }
    token(' ');
    final rest = source.substring(position);
    final number = RegExp(
      r'^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?',
    ).firstMatch(rest);
    if (number != null) {
      position += number[0]!.length;
      final n = double.parse(number[0]!);
      if (!n.isFinite || n.abs() > 1e12) throw const FormatException('常数无效');
      return (_) => n;
    }
    final name = RegExp(r'^[A-Za-z][A-Za-z0-9_]*').firstMatch(rest)?[0];
    if (name == null) throw const FormatException('无效公式');
    position += name.length;
    if (token('(')) {
      if (!{
        'sin',
        'cos',
        'tan',
        'sqrt',
        'abs',
        'ln',
        'log',
        'exp',
      }.contains(name)) {
        throw const FormatException('不支持的数学函数');
      }
      final arg = sum();
      if (!token(')')) throw const FormatException('缺少右括号');
      return (v) {
        final x = arg(v);
        return switch (name) {
          'sin' => math.sin(x),
          'cos' => math.cos(x),
          'tan' => math.tan(x),
          'sqrt' => math.sqrt(x),
          'abs' => x.abs(),
          'ln' => math.log(x),
          'log' => math.log(x) / math.ln10,
          _ => math.exp(x),
        };
      };
    }
    if (name == 'pi') return (_) => math.pi;
    if (name == 'e') return (_) => math.e;
    if (!variables.contains(name)) throw FormatException('未知变量 $name');
    return (v) => v[name] ?? double.nan;
  }
}

class MathCurve {
  final String label;
  final List<List<Offset>> segments;
  MathCurve(this.label, this.segments);
}

class MathPlot {
  static const fields = {
    'xMin',
    'xMax',
    'yMin',
    'yMax',
    'curves',
    'points',
    'equalAspect',
  };
  static double number(Map<String, dynamic> obj, String key) {
    final n = obj[key];
    if (n is! num || !n.isFinite || n.abs() > 1e6) {
      throw const FormatException('坐标或参数范围无效');
    }
    return n.toDouble();
  }

  static void validate(Map<String, dynamic> plot, Set<String> parameters) {
    if (plot.keys.any((k) => !{...fields, 'id', 'type'}.contains(k)) ||
        parameters.any(
          {
            'x',
            'y',
            't',
            'pi',
            'e',
            'sin',
            'cos',
            'tan',
            'sqrt',
            'abs',
            'ln',
            'log',
            'exp',
          }.contains,
        )) {
      throw const FormatException('数学图像字段或参数名称无效');
    }
    final dx = number(plot, 'xMax') - number(plot, 'xMin');
    final dy = number(plot, 'yMax') - number(plot, 'yMin');
    if (dx < 1e-4 ||
        dx > 1e6 ||
        dy < 1e-4 ||
        dy > 1e6 ||
        (plot.containsKey('equalAspect') && plot['equalAspect'] is! bool)) {
      throw const FormatException('视窗无效');
    }
    final curves = plot['curves'] as List;
    if (curves.isEmpty || curves.length > 6) {
      throw const FormatException('曲线数量无效');
    }
    for (final item in curves) {
      final c = Map<String, dynamic>.from(item as Map);
      final kind = c['kind'];
      final names = switch (kind) {
        'function' => {'expression', 'min', 'max'},
        'parametric' => {'x', 'y', 'min', 'max'},
        'implicit' => {'expression'},
        _ => throw const FormatException('不支持的数学曲线'),
      };
      if (c['label'] is! String ||
          (c['label'] as String).isEmpty ||
          (c['label'] as String).length > 100 ||
          c.keys.any((k) => !{...names, 'kind', 'label'}.contains(k))) {
        throw const FormatException('曲线字段无效');
      }
      final vars = {
        ...parameters,
        if (kind == 'function') 'x',
        if (kind == 'parametric') 't',
        if (kind == 'implicit') ...['x', 'y'],
      };
      for (final key in kind == 'parametric' ? ['x', 'y'] : ['expression']) {
        MathExpression.parse(c[key] as String, vars);
      }
      if ((kind == 'parametric' ||
              c.containsKey('min') ||
              c.containsKey('max')) &&
          number(c, 'max') <= number(c, 'min')) {
        throw const FormatException('定义域无效');
      }
    }
    if (plot.containsKey('points')) {
      final points = plot['points'] as List;
      if (points.length > 20) throw const FormatException('标注过多');
      for (final item in points) {
        final p = Map<String, dynamic>.from(item as Map);
        if (p.length != 3 ||
            !p.keys.toSet().containsAll({'x', 'y', 'label'}) ||
            p['label'] is! String ||
            (p['label'] as String).isEmpty ||
            (p['label'] as String).length > 80) {
          throw const FormatException('标注无效');
        }
        number(p, 'x');
        number(p, 'y');
      }
    }
  }

  static List<MathCurve> sample(
    Map<String, dynamic> plot,
    Map<String, double> values,
  ) {
    validate(plot, values.keys.toSet());
    final x0 = number(plot, 'xMin'),
        x1 = number(plot, 'xMax'),
        y0 = number(plot, 'yMin'),
        y1 = number(plot, 'yMax');
    return (plot['curves'] as List).map((item) {
      final c = Map<String, dynamic>.from(item as Map), kind = c['kind'];
      MathEvaluator expr(String key) => MathExpression.parse(c[key] as String, {
        ...values.keys,
        'x',
        'y',
        't',
      }).evaluate;
      final segments = <List<Offset>>[];
      if (kind == 'implicit') {
        final f = expr('expression');
        const n = 100;
        Offset point(int i, int j) =>
            Offset(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * j / n);
        double value(Offset p) => f({...values, 'x': p.dx, 'y': p.dy});
        final grid = List.generate(
          n + 1,
          (i) => List.generate(n + 1, (j) => value(point(i, j))),
        );
        for (var i = 0; i < n; i++) {
          for (var j = 0; j < n; j++) {
            final corners = [
              point(i, j),
              point(i + 1, j),
              point(i + 1, j + 1),
              point(i, j + 1),
            ];
            final v = [
              grid[i][j],
              grid[i + 1][j],
              grid[i + 1][j + 1],
              grid[i][j + 1],
            ];
            if (v.any((n) => !n.isFinite)) continue;
            final hits = <Offset>[];
            for (var k = 0; k < 4; k++) {
              final next = (k + 1) % 4;
              if ((v[k] < 0) != (v[next] < 0)) {
                var low = 0.0, high = 1.0, lowValue = v[k];
                for (var iteration = 0; iteration < 24; iteration++) {
                  final ratio = (low + high) / 2;
                  final at = value(
                    corners[k] + (corners[next] - corners[k]) * ratio,
                  );
                  if ((at < 0) == (lowValue < 0)) {
                    low = ratio;
                    lowValue = at;
                  } else {
                    high = ratio;
                  }
                }
                final ratio = (low + high) / 2;
                final hit = corners[k] + (corners[next] - corners[k]) * ratio;
                final residual = value(hit);
                if (residual.isFinite &&
                    residual.abs() <=
                        math.max(
                          1e-8,
                          v.map((n) => n.abs()).reduce(math.max) * 1e-5,
                        )) {
                  hits.add(hit);
                }
              }
            }
            if (hits.length == 2) segments.add(hits);
          }
        }
      } else {
        final fx = kind == 'parametric' ? expr('x') : null,
            fy = expr(kind == 'parametric' ? 'y' : 'expression');
        final start = (c['min'] as num?)?.toDouble() ?? x0,
            end = (c['max'] as num?)?.toDouble() ?? x1;
        var current = <Offset>[];
        void flush() {
          if (current.length >= 2) segments.add(current);
          current = <Offset>[];
        }

        for (var i = 0; i <= 1200; i++) {
          final t = start + (end - start) * i / 1200;
          final env = {...values, kind == 'parametric' ? 't' : 'x': t};
          final p = Offset(fx?.call(env) ?? t, fy(env));
          final previous = current.isEmpty ? null : current.last;
          if (!p.dx.isFinite ||
              !p.dy.isFinite ||
              p.dx < x0 ||
              p.dx > x1 ||
              p.dy < y0 ||
              p.dy > y1) {
            flush();
            continue;
          }
          if (previous != null) {
            final mid = start + (end - start) * (i - 0.5) / 1200;
            final env = {...values, kind == 'parametric' ? 't' : 'x': mid};
            final my = fy(env), mx = fx?.call(env) ?? mid;
            if (!my.isFinite ||
                !mx.isFinite ||
                discontinuous(my, p.dy, previous.dy, y1 - y0) ||
                discontinuous(mx, p.dx, previous.dx, x1 - x0)) {
              flush();
            }
          }
          current.add(p);
        }
        flush();
      }
      return MathCurve(c['label'] as String, segments);
    }).toList();
  }

  static bool discontinuous(double mid, double a, double b, double span) =>
      (mid - (a + b) / 2).abs() >
      math.max(
        1e-8,
        math.max(mid.abs(), math.max(a.abs(), b.abs())) * 0.35 + span * 1e-7,
      );
}

List<MathCurve> _sampleMathPlot(Map<String, dynamic> request) =>
    MathPlot.sample(
      Map<String, dynamic>.from(request['definition'] as Map),
      Map<String, double>.from(request['parameters'] as Map),
    );

class MathPlotView extends StatefulWidget {
  final Map<String, dynamic> definition;
  final Map<String, double> parameters;
  const MathPlotView({
    super.key,
    required this.definition,
    required this.parameters,
  });
  static const colors = [
    Color(0xff2364cd),
    Color(0xffdc4b37),
    Color(0xff149164),
    Color(0xff9150be),
    Color(0xffcd8714),
    Color(0xff2391a5),
  ];
  @override
  State<MathPlotView> createState() => _MathPlotViewState();
}

class _MathPlotViewState extends State<MathPlotView> {
  List<MathCurve> curves = [];
  Timer? debounce;
  int revision = 0;
  bool computing = false;
  String? error;
  late String signature;
  Map<String, dynamic> get definition => widget.definition;
  @override
  void initState() {
    super.initState();
    update();
  }

  @override
  void didUpdateWidget(covariant MathPlotView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (signature != jsonEncode([widget.definition, widget.parameters])) {
      update();
    }
  }

  void update() {
    signature = jsonEncode([widget.definition, widget.parameters]);
    final token = ++revision;
    debounce?.cancel();
    error = null;
    if (!(definition['curves'] as List).any((c) => c['kind'] == 'implicit')) {
      curves = MathPlot.sample(definition, widget.parameters);
      computing = false;
      return;
    }
    // Implicit contour work never blocks the UI thread, and rapid slider updates coalesce.
    computing = true;
    curves = [];
    final request = {
      'definition': Map<String, dynamic>.from(definition),
      'parameters': Map<String, double>.from(widget.parameters),
    };
    debounce = Timer(const Duration(milliseconds: 80), () async {
      try {
        final result = await compute(_sampleMathPlot, request);
        if (mounted && token == revision) {
          setState(() {
            curves = result;
            computing = false;
          });
        }
      } catch (_) {
        if (mounted && token == revision) {
          setState(() {
            error = '当前参数无法计算，请调整参数。';
            computing = false;
          });
        }
      }
    });
  }

  @override
  void dispose() {
    revision++;
    debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    String formula(Map curve) {
      var expression = curve['expression'] as String?;
      if (curve['kind'] != 'function' || expression == null)
        return curve['label'] as String;
      for (final parameter in widget.parameters.entries) {
        final value = parameter.value;
        final text = value == value.roundToDouble()
            ? value.toInt().toString()
            : value.toStringAsFixed(1);
        expression = expression!.replaceAll(
          RegExp('\\b${RegExp.escape(parameter.key)}\\b'),
          value < 0 ? '($text)' : text,
        );
      }
      return 'y = ${expression!.replaceAll('*', ' × ').replaceAll('^2', '²')}';
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('当前函数表达式', style: Theme.of(context).textTheme.bodySmall),
        for (final curve in definition['curves'] as List)
          Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 12),
            child: Text(
              formula(curve as Map),
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
          ),
        Semantics(
          container: true,
          label: '数学坐标图，支持双指缩放。${curves.map((c) => c.label).join('；')}',
          child: ClipRect(
            child: SizedBox(
              height: 360,
              child: InteractiveViewer(
                minScale: 1,
                maxScale: 5,
                child: CustomPaint(
                  size: const Size(double.infinity, 360),
                  painter: MathPlotPainter(
                    definition,
                    curves,
                    Theme.of(context).colorScheme.onSurface,
                    labelStyle: Theme.of(context).textTheme.labelSmall,
                  ),
                ),
              ),
            ),
          ),
        ),
        Wrap(
          spacing: 12,
          runSpacing: 4,
          children: [
            for (var i = 0; i < curves.length; i++)
              Text(
                '● ${curves[i].label}',
                style: TextStyle(color: MathPlotView.colors[i], fontSize: 12),
              ),
          ],
        ),
        Text(
          computing
              ? '正在重新计算曲线…'
              : error ??
                    (curves.every((c) => c.segments.isEmpty)
                        ? '当前视窗没有可绘制的曲线，请调整参数或视窗。'
                        : '双指缩放查看；滑块会重新计算曲线。'),
          style: const TextStyle(fontSize: 12),
        ),
        if ((definition['curves'] as List).any((c) => c['kind'] == 'implicit'))
          const Text(
            '隐式曲线采用数值等值线，小于采样网格的细节可能遗漏。',
            style: TextStyle(fontSize: 12),
          ),
        const Text(
          '图像辅助理解，精确结论以推导为准；PNG 是生成时的默认参数快照。',
          style: TextStyle(fontSize: 12),
        ),
      ],
    );
  }
}

class MathPlotPainter extends CustomPainter {
  final Map<String, dynamic> plot;
  final List<MathCurve> curves;
  final Color foreground;
  final TextStyle? labelStyle;
  MathPlotPainter(this.plot, this.curves, this.foreground, {this.labelStyle});
  Rect viewport(Size size) {
    final dx = MathPlot.number(plot, 'xMax') - MathPlot.number(plot, 'xMin'),
        dy = MathPlot.number(plot, 'yMax') - MathPlot.number(plot, 'yMin');
    var width = math.max(1.0, size.width - 64),
        height = math.max(1.0, size.height - 44);
    if (plot['equalAspect'] != false) {
      final scale = math.min(width / dx, height / dy);
      width = dx * scale;
      height = dy * scale;
    }
    return Rect.fromLTWH(
      44 + (size.width - 64 - width) / 2,
      12 + (size.height - 44 - height) / 2,
      width,
      height,
    );
  }

  Offset position(Offset p, Size size) {
    final r = viewport(size),
        x0 = MathPlot.number(plot, 'xMin'),
        x1 = MathPlot.number(plot, 'xMax'),
        y0 = MathPlot.number(plot, 'yMin'),
        y1 = MathPlot.number(plot, 'yMax');
    return Offset(
      r.left + (p.dx - x0) / (x1 - x0) * r.width,
      r.bottom - (p.dy - y0) / (y1 - y0) * r.height,
    );
  }

  @override
  void paint(Canvas canvas, Size size) {
    final r = viewport(size),
        x0 = MathPlot.number(plot, 'xMin'),
        x1 = MathPlot.number(plot, 'xMax'),
        y0 = MathPlot.number(plot, 'yMin'),
        y1 = MathPlot.number(plot, 'yMax');
    final paint = Paint()..strokeWidth = 1;
    void text(String s, Offset p) {
      final t = TextPainter(
        text: TextSpan(
          text: s,
          style: (labelStyle ?? const TextStyle()).copyWith(
            color: foreground,
            fontSize: 11,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: 110);
      t.paint(canvas, p);
    }

    String tick(double value) => value == value.roundToDouble()
        ? value.toInt().toString()
        : value.toStringAsPrecision(3);
    double step(double span, int count) {
      final raw = span / count;
      final power = math
          .pow(10, (math.log(raw) / math.ln10).floor())
          .toDouble();
      final n = raw / power;
      return (n <= 1
              ? 1
              : n <= 2
              ? 2
              : n <= 5
              ? 5
              : 10) *
          power;
    }

    final xStep = step(x1 - x0, 10), yStep = step(y1 - y0, 8);
    paint.color = foreground.withValues(alpha: .12);
    for (
      var vx = (x0 / xStep).ceil() * xStep;
      vx <= x1 + xStep * 1e-8;
      vx += xStep
    ) {
      final px = position(Offset(vx, y0), size).dx;
      canvas.drawLine(Offset(px, r.top), Offset(px, r.bottom), paint);
      text(
        tick(vx.abs() < xStep * 1e-8 ? 0 : vx),
        Offset(px - 8, r.bottom + 6),
      );
    }
    for (
      var vy = (y0 / yStep).ceil() * yStep;
      vy <= y1 + yStep * 1e-8;
      vy += yStep
    ) {
      final py = position(Offset(x0, vy), size).dy;
      canvas.drawLine(Offset(r.left, py), Offset(r.right, py), paint);
      text(tick(vy.abs() < yStep * 1e-8 ? 0 : vy), Offset(r.left - 38, py - 5));
    }
    paint.color = foreground.withValues(alpha: 0.85);
    paint.strokeWidth = 1.8;
    final origin = position(Offset.zero, size);
    void arrow(Offset tip, bool vertical) {
      canvas.drawLine(
        tip,
        tip + (vertical ? const Offset(-5, 8) : const Offset(-8, -5)),
        paint,
      );
      canvas.drawLine(
        tip,
        tip + (vertical ? const Offset(5, 8) : const Offset(-8, 5)),
        paint,
      );
    }

    if (x0 <= 0 && x1 >= 0) {
      final x = origin.dx;
      canvas.drawLine(Offset(x, r.top), Offset(x, r.bottom), paint);
      arrow(Offset(x, r.top), true);
      text('y', Offset(x + 8, r.top - 10));
    }
    if (y0 <= 0 && y1 >= 0) {
      final y = origin.dy;
      canvas.drawLine(Offset(r.left, y), Offset(r.right, y), paint);
      arrow(Offset(r.right, y), false);
      text('x', Offset(r.right + 4, y - 15));
    }
    if (r.contains(origin)) text('O', origin + const Offset(8, 7));
    canvas.save();
    canvas.clipRect(r);
    for (var i = 0; i < curves.length; i++) {
      paint.color = MathPlotView.colors[i];
      paint.strokeWidth = 3;
      paint.style = PaintingStyle.stroke;
      for (final segment in curves[i].segments) {
        final path = Path();
        for (var j = 0; j < segment.length; j++) {
          final p = position(segment[j], size);
          if (j == 0) {
            path.moveTo(p.dx, p.dy);
          } else {
            path.lineTo(p.dx, p.dy);
          }
        }
        canvas.drawPath(path, paint);
      }
    }
    paint.color = foreground;
    paint.style = PaintingStyle.fill;
    for (final point in (plot['points'] as List? ?? [])) {
      final p = position(
        Offset(MathPlot.number(point, 'x'), MathPlot.number(point, 'y')),
        size,
      );
      canvas.drawCircle(p, 3, paint);
      text(point['label'] as String, p + const Offset(5, -15));
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant MathPlotPainter oldDelegate) => true;
}
