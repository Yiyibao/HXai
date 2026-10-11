import 'dart:math' as math;

double surfaceInterval(double span, int digits) {
  final raw = span / 4;
  if (!raw.isFinite || raw <= 0) return 1;
  final power = math.pow(10, (math.log(raw) / math.ln10).floor()).toDouble();
  final normalized = raw / power;
  final step = normalized <= 1
      ? 1
      : normalized <= 2
      ? 2
      : normalized <= 5
      ? 5
      : 10;
  return math.max(step * power, math.pow(10, -digits).toDouble());
}

class SurfaceData {
  static const chartFields = [
    'series',
    'xUnit',
    'yUnit',
    'digits',
    'annotations',
  ];
  static const tableFields = ['columnTypes', 'units', 'digits', 'highlight'];
  static const imageFields = ['sourceUrl', 'sourceTitle', 'aspectRatio', 'fit'];
  static List<dynamic> points(Map<String, dynamic> c) =>
      (c['series'] == null
              ? c['points'] as List
              : (c['series'] as List)
                    .expand((s) => s['points'] as List)
                    .toList())
          .toList();
  static void digits(Map<String, dynamic> c) {
    if (c.containsKey('digits') &&
        (c['digits'] is! int || c['digits'] < 0 || c['digits'] > 6))
      throw const FormatException('Invalid digits');
  }

  static void validateChart(Map<String, dynamic> c) {
    if (c.containsKey('series') == c.containsKey('points'))
      throw const FormatException('Choose points or series');
    if (c.containsKey('series')) {
      final series = c['series'];
      if (series is! List || series.isEmpty || series.length > 6)
        throw const FormatException('Invalid series');
      for (final s in series) {
        if (s is! Map ||
            s.length != 2 ||
            s['label'] is! String ||
            (s['label'] as String).isEmpty ||
            (s['label'] as String).length > 200 ||
            s['points'] is! List ||
            (s['points'] as List).isEmpty)
          throw const FormatException('Invalid series data');
      }
    }
    if (c.containsKey('annotations')) {
      final annotations = c['annotations'];
      if (annotations is! List || annotations.length > 8)
        throw const FormatException('Invalid annotations');
      for (final a in annotations) {
        if (a is! Map ||
            a.length != 3 ||
            !['x', 'y'].contains(a['axis']) ||
            a['value'] is! num ||
            !(a['value'] as num).isFinite ||
            (a['value'] as num).abs() > 1e12 ||
            a['label'] is! String ||
            (a['label'] as String).length > 200)
          throw const FormatException('Invalid annotation');
      }
    }
    for (final key in ['xUnit', 'yUnit']) {
      if (c.containsKey(key) &&
          (c[key] is! String || (c[key] as String).length > 40))
        throw const FormatException('Invalid unit');
    }
    digits(c);
  }

  static void validateTable(Map<String, dynamic> c) {
    final count = (c['columns'] as List).length;
    final types = c['columnTypes'];
    final units = c['units'];
    if (c.containsKey('columnTypes') &&
        (types is! List ||
            types.length != count ||
            types.any(
              (t) => ![
                'text',
                'number',
                'currency',
                'percent',
                'date',
              ].contains(t),
            )))
      throw const FormatException('Invalid column types');
    if (c.containsKey('units') &&
        (units is! List ||
            units.length != count ||
            units.any((u) => u is! String || u.length > 40)))
      throw const FormatException('Invalid table units');
    if (c.containsKey('highlight')) {
      final cells = c['highlight'];
      if (cells is! List || cells.length > 32)
        throw const FormatException('Invalid highlights');
      for (final cell in cells) {
        if (cell is! Map ||
            cell.length != 3 ||
            cell['row'] is! int ||
            cell['row'] < 0 ||
            cell['row'] >= (c['rows'] as List).length ||
            cell['column'] is! int ||
            cell['column'] < 0 ||
            cell['column'] >= count ||
            !['info', 'success', 'warning', 'error'].contains(cell['tone']))
          throw const FormatException('Invalid cell highlight');
      }
    }
    digits(c);
  }

  static void validateImage(Map<String, dynamic> c) {
    if (c.containsKey('sourceUrl')) {
      final url = c['sourceUrl'];
      final uri = url is String ? Uri.tryParse(url) : null;
      if (url is! String ||
          url.length > 16000 ||
          uri == null ||
          !['http', 'https'].contains(uri.scheme) ||
          uri.host.isEmpty)
        throw const FormatException('Invalid image source');
    }
    if (c.containsKey('sourceTitle') &&
        (c['sourceTitle'] is! String ||
            (c['sourceTitle'] as String).length > 200))
      throw const FormatException('Invalid source title');
    if (c.containsKey('fit') && !['contain', 'cover'].contains(c['fit']))
      throw const FormatException('Invalid image fit');
    if (c.containsKey('aspectRatio') &&
        (c['aspectRatio'] is! num ||
            !(c['aspectRatio'] as num).isFinite ||
            c['aspectRatio'] < .2 ||
            c['aspectRatio'] > 5))
      throw const FormatException('Invalid image ratio');
  }
}

String surfaceNumber(num value, {int digits = 2, String unit = ''}) {
  if (!value.isFinite) return '--';
  final text = value.abs() >= 1e7 || value != 0 && value.abs() < 1e-4
      ? value.toStringAsExponential(digits)
      : value.toStringAsFixed(digits);
  return '$text${unit.isEmpty ? '' : ' $unit'}';
}
