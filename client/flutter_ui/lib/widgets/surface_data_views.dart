import 'dart:math' as math;
import 'dart:convert';

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';

import 'chat_image.dart';
import 'source_links.dart';
import 'surface_data.dart';
import 'surface_layout.dart';

const _seriesColors = [
  Color(0xFF3575A5),
  Color(0xFF35916A),
  Color(0xFFB06383),
  Color(0xFFAB7A2B),
  Color(0xFF7665A8),
  Color(0xFF428C94),
];

class SurfaceChartView extends StatefulWidget {
  final Map<String, dynamic> definition;
  final double factor;
  final int selected;
  final ValueChanged<int>? onSelect;
  const SurfaceChartView({
    super.key,
    required this.definition,
    required this.factor,
    required this.selected,
    this.onSelect,
  });
  @override
  State<SurfaceChartView> createState() => _SurfaceChartViewState();
}

class _SurfaceChartViewState extends State<SurfaceChartView> {
  final hidden = <int>{};
  @override
  void didUpdateWidget(covariant SurfaceChartView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (jsonEncode(
          oldWidget.definition['series'] ?? oldWidget.definition['points'],
        ) !=
        jsonEncode(widget.definition['series'] ?? widget.definition['points']))
      hidden.clear();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.definition;
    final scheme = Theme.of(context).colorScheme;
    final digits = c['digits'] as int? ?? 2;
    final source =
        (c['series'] as List?) ??
        [
          {'label': c['yLabel'] ?? 'Data', 'points': c['points']},
        ];
    var base = 0;
    final series =
        <({String label, int base, List<FlSpot> spots, Color color})>[];
    for (var i = 0; i < source.length; i++) {
      final spots = (source[i]['points'] as List)
          .map(
            (p) => FlSpot(
              (p[0] as num).toDouble(),
              (p[1] as num).toDouble() * widget.factor,
            ),
          )
          .toList();
      series.add((
        label: source[i]['label'] as String,
        base: base,
        spots: spots,
        color: _seriesColors[i],
      ));
      base += spots.length;
    }
    final visible = [
      for (var i = 0; i < series.length; i++)
        if (!hidden.contains(i)) i,
    ];
    final all = visible.expand((i) => series[i].spots).toList();
    double minX = all.map((p) => p.x).reduce(math.min),
        maxX = all.map((p) => p.x).reduce(math.max);
    double minY = all.map((p) => p.y).reduce(math.min),
        maxY = all.map((p) => p.y).reduce(math.max);
    final annotations = (c['annotations'] as List?) ?? [];
    for (final a in annotations) {
      final value = (a['value'] as num).toDouble();
      if (a['axis'] == 'y') {
        minY = math.min(minY, value);
        maxY = math.max(maxY, value);
      } else {
        minX = math.min(minX, value);
        maxX = math.max(maxX, value);
      }
    }
    if (minX == maxX) {
      minX -= math.max(1, minX.abs() * .05);
      maxX += math.max(1, maxX.abs() * .05);
    }
    if (c['type'] == 'bar_chart' || minY >= 0) {
      minY = math.min(minY, 0);
      maxY = math.max(maxY, 0);
    }
    final padY = math.max(
      (maxY - minY) * .12,
      maxY == minY ? math.max(1, maxY.abs() * .05) : 0.000000001,
    );
    if (minY != 0) minY -= padY;
    maxY += padY;
    String fmt(num v, String axis) => surfaceNumber(
      v,
      digits: digits,
      unit: c['${axis}Unit'] as String? ?? '',
    );
    final titles = FlTitlesData(
      topTitles: const AxisTitles(),
      rightTitles: const AxisTitles(),
      leftTitles: AxisTitles(
        sideTitles: SideTitles(
          showTitles: true,
          reservedSize: 66,
          interval: surfaceInterval(maxY - minY, digits),
          minIncluded: true,
          maxIncluded: false,
          getTitlesWidget: (v, meta) => SideTitleWidget(
            meta: meta,
            child: Text(
              surfaceNumber(v, digits: digits),
              style: Theme.of(context).textTheme.labelSmall,
            ),
          ),
        ),
      ),
      bottomTitles: AxisTitles(
        sideTitles: SideTitles(
          showTitles: true,
          reservedSize: 30,
          interval: surfaceInterval(maxX - minX, digits),
          minIncluded: true,
          maxIncluded: true,
          getTitlesWidget: (v, meta) => SideTitleWidget(
            meta: meta,
            child: Text(
              surfaceNumber(v, digits: digits),
              style: Theme.of(context).textTheme.labelSmall,
            ),
          ),
        ),
      ),
    );
    final grid = FlGridData(
      show: true,
      drawVerticalLine: false,
      getDrawingHorizontalLine: (_) => FlLine(
        color: scheme.outlineVariant.withValues(alpha: .5),
        strokeWidth: .5,
      ),
    );
    final extras = ExtraLinesData(
      horizontalLines: [
        for (final a in annotations)
          if (a['axis'] == 'y')
            HorizontalLine(
              y: (a['value'] as num).toDouble(),
              color: scheme.onSurfaceVariant,
              dashArray: [4, 4],
              strokeWidth: 1,
              label: HorizontalLineLabel(
                show: true,
                style: Theme.of(context).textTheme.labelSmall,
                labelResolver: (_) => a['label'] as String,
              ),
            ),
      ],
      verticalLines: [
        for (final a in annotations)
          if (a['axis'] == 'x')
            VerticalLine(
              x: (a['value'] as num).toDouble(),
              color: scheme.onSurfaceVariant,
              dashArray: [4, 4],
              strokeWidth: 1,
              label: VerticalLineLabel(
                show: true,
                style: Theme.of(context).textTheme.labelSmall,
                labelResolver: (_) => a['label'] as String,
              ),
            ),
      ],
    );
    final border = FlBorderData(
      show: true,
      border: Border(
        left: BorderSide(
          color: scheme.onSurface.withValues(alpha: .85),
          width: 1.5,
        ),
        bottom: BorderSide(
          color: scheme.onSurface.withValues(alpha: .85),
          width: 1.5,
        ),
      ),
    );
    Widget chart;
    if (c['type'] == 'bar_chart') {
      final xs = all.map((p) => p.x).toSet().toList()..sort();
      final groupIndexes = <List<int>>[];
      final groups = <BarChartGroupData>[];
      for (var g = 0; g < xs.length; g++) {
        final rods = <BarChartRodData>[];
        final indexes = <int>[];
        for (final i in visible) {
          for (var p = 0; p < series[i].spots.length; p++) {
            final spot = series[i].spots[p];
            if (spot.x != xs[g]) continue;
            final global = series[i].base + p;
            indexes.add(global);
            rods.add(
              BarChartRodData(
                toY: spot.y,
                width: (14 / visible.length).clamp(3, 14),
                color: series[i].color,
                borderRadius: BorderRadius.circular(2),
                borderSide: global == widget.selected
                    ? BorderSide(color: scheme.onSurface, width: 2)
                    : BorderSide.none,
              ),
            );
          }
        }
        groupIndexes.add(indexes);
        groups.add(BarChartGroupData(x: g, barRods: rods));
      }
      chart = BarChart(
        BarChartData(
          barGroups: groups,
          minY: minY,
          maxY: maxY,
          titlesData: titles.copyWith(
            bottomTitles: AxisTitles(
              sideTitles: SideTitles(
                showTitles: true,
                reservedSize: 30,
                interval: math.max(1, (xs.length / 5).ceil()).toDouble(),
                getTitlesWidget: (v, meta) =>
                    v.toInt() >= 0 && v.toInt() < xs.length
                    ? SideTitleWidget(
                        meta: meta,
                        child: Text(
                          surfaceNumber(xs[v.toInt()], digits: digits),
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
            ),
          ),
          gridData: grid,
          borderData: border,
          extraLinesData: extras,
          barTouchData: BarTouchData(
            touchTooltipData: BarTouchTooltipData(
              getTooltipColor: (_) => scheme.inverseSurface,
              fitInsideHorizontally: true,
              fitInsideVertically: true,
              getTooltipItem: (group, groupIndex, rod, rodIndex) {
                final index = groupIndexes[groupIndex][rodIndex];
                final s = series.firstWhere(
                  (s) => index >= s.base && index < s.base + s.spots.length,
                );
                final point = s.spots[index - s.base];
                return BarTooltipItem(
                  '${s.label}\n${fmt(point.x, 'x')}, ${fmt(point.y, 'y')}',
                  TextStyle(color: scheme.onInverseSurface, fontSize: 12),
                );
              },
            ),
            touchCallback: (event, response) {
              final spot = response?.spot;
              if (event is FlTapUpEvent && spot != null)
                widget.onSelect?.call(
                  groupIndexes[spot.touchedBarGroupIndex][spot
                      .touchedRodDataIndex],
                );
            },
          ),
        ),
        duration: Duration.zero,
      );
    } else {
      chart = LineChart(
        LineChartData(
          minX: minX,
          maxX: maxX,
          minY: minY,
          maxY: maxY,
          titlesData: titles,
          gridData: grid,
          borderData: border,
          extraLinesData: extras,
          lineBarsData: [
            for (final i in visible)
              LineChartBarData(
                spots: series[i].spots,
                color: series[i].color,
                barWidth: c['type'] == 'scatter_chart' ? 0 : 2,
                isCurved: false,
                dotData: FlDotData(
                  show: true,
                  getDotPainter: (spot, _, _, index) => FlDotCirclePainter(
                    radius: series[i].base + index == widget.selected ? 6 : 3,
                    color: series[i].color,
                    strokeWidth: series[i].base + index == widget.selected
                        ? 2
                        : 0,
                    strokeColor: scheme.onSurface,
                  ),
                ),
              ),
          ],
          lineTouchData: LineTouchData(
            touchTooltipData: LineTouchTooltipData(
              getTooltipColor: (_) => scheme.inverseSurface,
              fitInsideHorizontally: true,
              fitInsideVertically: true,
              getTooltipItems: (spots) => [
                for (final spot in spots)
                  LineTooltipItem(
                    '${series[visible[spot.barIndex]].label}\n${fmt(spot.x, 'x')}, ${fmt(spot.y, 'y')}',
                    TextStyle(color: scheme.onInverseSurface, fontSize: 12),
                  ),
              ],
            ),
            touchCallback: (event, response) {
              final spots = response?.lineBarSpots;
              if (event is FlTapUpEvent && spots != null && spots.isNotEmpty) {
                final spot = spots.first;
                widget.onSelect?.call(
                  series[visible[spot.barIndex]].base + spot.spotIndex,
                );
              }
            },
          ),
        ),
        duration: Duration.zero,
      );
    }
    final selected = widget.selected;
    final selectedSeries = series
        .where((s) => selected >= s.base && selected < s.base + s.spots.length)
        .firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (series.length > 1)
          Wrap(
            spacing: 8,
            children: [
              for (var i = 0; i < series.length; i++)
                TextButton.icon(
                  onPressed: () => setState(() {
                    if (hidden.contains(i)) {
                      hidden.remove(i);
                    } else if (hidden.length < series.length - 1) {
                      hidden.add(i);
                    }
                  }),
                  icon: Icon(
                    hidden.contains(i)
                        ? Icons.visibility_off_outlined
                        : Icons.circle,
                    size: 10,
                    color: series[i].color,
                  ),
                  label: Text(series[i].label),
                ),
            ],
          ),
        if (c['yLabel'] != null || c['yUnit'] != null)
          Text(
            '${c['yLabel'] ?? ''}${c['yUnit'] == null ? '' : ' (${c['yUnit']})'}',
            style: Theme.of(context).textTheme.labelSmall,
          ),
        const SizedBox(height: 12),
        SizedBox(
          height: 250,
          child: Semantics(label: '交互图表，$base个数据点', child: chart),
        ),
        if (c['xLabel'] != null || c['xUnit'] != null)
          Text(
            '${c['xLabel'] ?? ''}${c['xUnit'] == null ? '' : ' (${c['xUnit']})'}',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.labelSmall,
          ),
        if (selectedSeries != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              '${selectedSeries.label}: ${fmt(selectedSeries.spots[selected - selectedSeries.base].x, 'x')}, ${fmt(selectedSeries.spots[selected - selectedSeries.base].y, 'y')}',
            ),
          ),
        if (annotations.isNotEmpty)
          Wrap(
            spacing: 12,
            children: [
              for (final a in annotations)
                Text(
                  '${a['label']}: ${fmt(a['value'] as num, a['axis'] as String)}',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
            ],
          ),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: const Text('查看图表数据'),
          children: [
            SelectableText(
              series
                  .map(
                    (s) =>
                        '${s.label}\n${s.spots.map((p) => '${fmt(p.x, 'x')}: ${fmt(p.y, 'y')}').join('\n')}',
                  )
                  .join('\n\n'),
            ),
          ],
        ),
      ],
    );
  }
}

class SurfaceTableView extends StatefulWidget {
  final Map<String, dynamic> definition;
  final dynamic filter;
  const SurfaceTableView({
    super.key,
    required this.definition,
    this.filter = '*',
  });
  @override
  State<SurfaceTableView> createState() => _SurfaceTableViewState();
}

class _SurfaceTableViewState extends State<SurfaceTableView> {
  final horizontal = ScrollController();
  @override
  void dispose() {
    horizontal.dispose();
    super.dispose();
  }

  int? sortColumn;
  bool ascending = true;
  @override
  void didUpdateWidget(covariant SurfaceTableView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (jsonEncode(oldWidget.definition['columns']) !=
        jsonEncode(widget.definition['columns'])) {
      sortColumn = null;
      ascending = true;
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.definition;
    final scheme = Theme.of(context).colorScheme;
    final columns = c['columns'] as List;
    final data = c['rows'] as List;
    final types =
        c['columnTypes'] as List? ?? List.filled(columns.length, 'text');
    final units = c['units'] as List? ?? List.filled(columns.length, '');
    final digits = c['digits'] as int? ?? 2;
    final indexed = [
      for (var i = 0; i < data.length; i++)
        if (widget.filter == '*' ||
            '${data[i][c['filterColumn']]}' == widget.filter)
          i,
    ];
    if (sortColumn != null)
      indexed.sort((a, b) {
        final av = data[a][sortColumn], bv = data[b][sortColumn];
        final result = av == null
            ? (bv == null ? 0 : 1)
            : bv == null
            ? -1
            : av is num && bv is num
            ? av.compareTo(bv)
            : '$av'.compareTo('$bv');
        final order = ascending ? result : -result;
        return order == 0 ? a.compareTo(b) : order;
      });
    String value(dynamic v, int col) {
      if (v == null) return '--';
      if (types[col] == 'date' && v is String) {
        final date = DateTime.tryParse(v);
        if (date != null)
          return '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
      }
      if (v is num && ['number', 'currency', 'percent'].contains(types[col]))
        return surfaceNumber(
          types[col] == 'percent' ? v * 100 : v,
          digits: digits,
          unit: types[col] == 'percent' ? '%' : units[col] as String,
        );
      return '$v';
    }

    return LayoutBuilder(
      builder: (context, bounds) {
        final width = math.max(bounds.maxWidth, columns.length * 150.0);
        final cellWidth = width / columns.length;
        Widget row(int original) => IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var col = 0; col < columns.length; col++)
                Container(
                  key: ValueKey('cell_${original}_$col'),
                  width: cellWidth,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color:
                        (c['highlight'] as List? ?? []).any(
                          (cell) =>
                              cell['row'] == original && cell['column'] == col,
                        )
                        ? surfaceTone(
                            context,
                            (c['highlight'] as List).firstWhere(
                                  (cell) =>
                                      cell['row'] == original &&
                                      cell['column'] == col,
                                )['tone']
                                as String,
                          ).withValues(alpha: .12)
                        : original.isOdd
                        ? scheme.onSurface.withValues(alpha: .025)
                        : null,
                    border: Border(
                      bottom: BorderSide(
                        color: scheme.outlineVariant,
                        width: .5,
                      ),
                    ),
                  ),
                  child: SelectableText(
                    value(data[original][col], col),
                    textAlign: types[col] == 'text' || types[col] == 'date'
                        ? TextAlign.left
                        : TextAlign.right,
                  ),
                ),
            ],
          ),
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Scrollbar(
              controller: horizontal,
              thumbVisibility: true,
              scrollbarOrientation: ScrollbarOrientation.bottom,
              notificationPredicate: (notification) =>
                  notification.metrics.axis == Axis.horizontal,
              child: SingleChildScrollView(
                controller: horizontal,
                scrollDirection: Axis.horizontal,
                child: SizedBox(
                  width: width,
                  child: Column(
                    children: [
                      Material(
                        color: scheme.surfaceContainerLow,
                        child: Row(
                          children: [
                            for (var col = 0; col < columns.length; col++)
                              SizedBox(
                                width: cellWidth,
                                child: TextButton(
                                  onPressed: () => setState(() {
                                    ascending = sortColumn == col
                                        ? !ascending
                                        : true;
                                    sortColumn = col;
                                  }),
                                  child: Row(
                                    children: [
                                      Expanded(
                                        child: Text(
                                          '${columns[col]}',
                                          style: const TextStyle(
                                            fontWeight: FontWeight.w600,
                                          ),
                                        ),
                                      ),
                                      Icon(
                                        sortColumn == col
                                            ? ascending
                                                  ? Icons.arrow_upward
                                                  : Icons.arrow_downward
                                            : Icons.unfold_more,
                                        size: 16,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxHeight: 320),
                        child: indexed.isEmpty
                            ? const Padding(
                                padding: EdgeInsets.all(16),
                                child: Text('没有匹配的数据'),
                              )
                            : ListView.builder(
                                primary: false,
                                shrinkWrap: true,
                                itemCount: indexed.length,
                                itemBuilder: (_, i) => row(indexed[i]),
                              ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '${indexed.length} / ${data.length}',
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          ],
        );
      },
    );
  }
}

class SurfaceImageView extends StatelessWidget {
  final Map<String, dynamic> definition;
  const SurfaceImageView({super.key, required this.definition});
  @override
  Widget build(BuildContext context) {
    final c = definition;
    final image = ChatImage(
      source: c['url'] as String,
      fit: c['fit'] == 'cover' ? BoxFit.cover : BoxFit.contain,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Stack(
          children: [
            AspectRatio(
              aspectRatio: (c['aspectRatio'] as num? ?? 1.5).toDouble(),
              child: image,
            ),
            Positioned(
              top: 4,
              right: 4,
              child: IconButton.filledTonal(
                tooltip: '查看完整图片',
                icon: const Icon(Icons.open_in_full, size: 18),
                onPressed: () => showDialog<void>(
                  context: context,
                  builder: (context) => Dialog.fullscreen(
                    child: Stack(
                      children: [
                        Positioned.fill(
                          child: InteractiveViewer(
                            minScale: .5,
                            maxScale: 5,
                            child: ChatImage(source: c['url'] as String),
                          ),
                        ),
                        Positioned(
                          top: 16,
                          right: 16,
                          child: IconButton.filledTonal(
                            tooltip: '关闭图片',
                            onPressed: () => Navigator.pop(context),
                            icon: const Icon(Icons.close),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        if (c['caption'] != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              c['caption'] as String,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        if (c['sourceUrl'] != null)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () =>
                  openSourceLink(context, c['sourceUrl'] as String),
              icon: const Icon(Icons.link, size: 16),
              label: Text(c['sourceTitle'] as String? ?? '来源'),
            ),
          ),
      ],
    );
  }
}
