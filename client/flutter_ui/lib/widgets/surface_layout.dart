import 'package:flutter/material.dart';

Color surfaceTone(BuildContext context, String tone) {
  final dark = Theme.of(context).brightness == Brightness.dark;
  return switch (tone) {
    'info' => dark ? const Color(0xFF8CBCE8) : const Color(0xFF28608A),
    'success' => dark ? const Color(0xFF91C8AC) : const Color(0xFF2C704E),
    'warning' => dark ? const Color(0xFFE2BE76) : const Color(0xFF806016),
    'error' => Theme.of(context).colorScheme.error,
    _ => Theme.of(context).colorScheme.onSurface,
  };
}

class SurfaceLayout {
  static const variants = ['plain', 'subtle', 'emphasis', 'outline'];
  static const tones = ['neutral', 'info', 'success', 'warning', 'error'];
  static const densities = ['compact', 'comfortable'];
  static const templates = [
    'analysis',
    'comparison',
    'explanation',
    'exploration',
  ];
  static void validateStyle(Map<String, dynamic> c) {
    for (final entry in {
      'variant': variants,
      'tone': tones,
      'density': densities,
    }.entries) {
      if (c.containsKey(entry.key) && !entry.value.contains(c[entry.key])) {
        throw FormatException('Invalid ${entry.key}');
      }
    }
  }

  static void validateComposition(dynamic root, Set<String> ids) {
    var count = 0;
    final used = <String>[];
    void visit(dynamic node, int depth) {
      if (++count > 64 || depth > 5)
        throw const FormatException('Layout too deep');
      if (node is String) {
        if (!ids.contains(node))
          throw const FormatException('Unknown layout reference');
        used.add(node);
        return;
      }
      if (node is! Map ||
          ![
            'column',
            'row',
            'grid',
            'tabs',
            'accordion',
          ].contains(node['type']) ||
          node.keys.any(
            (k) => ![
              'type',
              'children',
              'label',
              'weights',
              'columns',
            ].contains(k),
          ))
        throw const FormatException('Invalid layout node');
      final children = node['children'];
      if (children is! List || children.isEmpty || children.length > 24)
        throw const FormatException('Invalid layout children');
      if (node.containsKey('label') &&
          (node['label'] is! String || (node['label'] as String).length > 200))
        throw const FormatException('Invalid layout label');
      if (node.containsKey('weights')) {
        final weights = node['weights'];
        if (node['type'] != 'row' ||
            weights is! List ||
            weights.length != children.length ||
            weights.any((w) => w is! int || w < 1 || w > 12))
          throw const FormatException('Invalid row weights');
      }
      if (node.containsKey('columns') &&
          (node['type'] != 'grid' ||
              node['columns'] is! int ||
              node['columns'] < 1 ||
              node['columns'] > 3))
        throw const FormatException('Invalid grid columns');
      if (node['type'] == 'tabs' || node['type'] == 'accordion') {
        if (children.length > 8 ||
            children.any(
              (child) =>
                  child is! Map ||
                  child['label'] is! String ||
                  (child['label'] as String).isEmpty,
            ))
          throw const FormatException('Groups need labels');
      }
      for (final child in children) {
        visit(child, depth + 1);
      }
    }

    visit(root, 0);
    if (used.length != ids.length ||
        used.toSet().length != used.length ||
        !used.toSet().containsAll(ids))
      throw const FormatException('Layout must contain every component once');
  }
}

class SurfaceComponentStyle extends StatelessWidget {
  final Map<String, dynamic> definition;
  final String density;
  final Widget child;
  const SurfaceComponentStyle({
    super.key,
    required this.definition,
    required this.density,
    required this.child,
  });
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final tone = definition['tone'] ?? 'neutral';
    final color = switch (tone) {
      'info' =>
        Theme.of(context).brightness == Brightness.dark
            ? const Color(0xFF8CBCE8)
            : const Color(0xFF28608A),
      'success' =>
        Theme.of(context).brightness == Brightness.dark
            ? const Color(0xFF91C8AC)
            : const Color(0xFF2C704E),
      'warning' =>
        Theme.of(context).brightness == Brightness.dark
            ? const Color(0xFFE2BE76)
            : const Color(0xFF806016),
      'error' => scheme.error,
      _ => scheme.onSurface,
    };
    final variant = definition['variant'] ?? 'plain';
    final gap = (definition['density'] ?? density) == 'compact' ? 8.0 : 12.0;
    return Padding(
      padding: EdgeInsets.symmetric(vertical: gap / 2),
      child: Container(
        padding: variant == 'plain' ? EdgeInsets.zero : EdgeInsets.all(gap),
        decoration: BoxDecoration(
          color: ['subtle', 'emphasis'].contains(variant)
              ? color.withValues(alpha: variant == 'emphasis' ? .10 : .04)
              : null,
          border: variant == 'outline'
              ? Border.all(color: color.withValues(alpha: .25))
              : null,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Theme(
          data: Theme.of(context).copyWith(
            textTheme: Theme.of(context).textTheme
                .apply(bodyColor: color, displayColor: color),
          ),
          child: DefaultTextStyle.merge(
            style: TextStyle(color: color),
            child: Material(type: MaterialType.transparency, child: child),
          ),
        ),
      ),
    );
  }
}

class SurfaceCompositionView extends StatelessWidget {
  final dynamic node;
  final Widget Function(String id, bool compact) component;
  final double minimumCellWidth;
  const SurfaceCompositionView({
    super.key,
    required this.node,
    required this.component,
    this.minimumCellWidth = 180,
  });
  @override
  Widget build(BuildContext context) {
    Widget render(dynamic child, {bool compact = false}) => child is String
        ? component(child, compact)
        : SurfaceCompositionView(
            key: ValueKey(child.toString()),
            node: child,
            component: component,
            minimumCellWidth: minimumCellWidth,
          );
    if (node is String) return component(node as String, false);
    final n = node as Map;
    final children = n['children'] as List;
    if (n['type'] == 'tabs') {
      return _SurfaceTabs(node: n, render: render);
    }
    if (n['type'] == 'accordion') {
      return Column(
        children: [
          for (final child in children)
            ExpansionTile(
              key: ValueKey(child.toString()),
              tilePadding: EdgeInsets.zero,
              title: Text(child['label'] as String),
              children: [render(child)],
            ),
        ],
      );
    }
    return LayoutBuilder(
      builder: (context, bounds) {
        final largeText = MediaQuery.textScalerOf(context).scale(14) > 20;
        final weights =
            n['weights'] as List? ?? List.filled(children.length, 1);
        final totalWeight = weights.cast<int>().fold<int>(0, (a, b) => a + b);
        final horizontal =
            n['type'] == 'row' &&
            weights.every(
              (weight) =>
                  (bounds.maxWidth - 12 * (children.length - 1)) *
                      (weight as int) /
                      totalWeight >=
                  160,
            ) &&
            !largeText;
        if (horizontal)
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var i = 0; i < children.length; i++)
                Expanded(
                  flex: n['weights'] == null
                      ? 1
                      : (n['weights'] as List)[i] as int,
                  child: Padding(
                    padding: EdgeInsets.only(
                      right: i == children.length - 1 ? 0 : 12,
                    ),
                    child: render(children[i], compact: true),
                  ),
                ),
            ],
          );
        if (n['type'] == 'grid' && !largeText) {
          final columns = (n['columns'] as int? ?? 2).clamp(
            1,
            (bounds.maxWidth / minimumCellWidth).floor().clamp(1, 3),
          );
          final width = (bounds.maxWidth - (columns - 1) * 12) / columns;
          return Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              for (final child in children)
                SizedBox(
                  width: width,
                  child: render(child, compact: columns > 1),
                ),
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [for (final child in children) render(child)],
        );
      },
    );
  }
}

class _SurfaceTabs extends StatefulWidget {
  final Map node;
  final Widget Function(dynamic, {bool compact}) render;
  const _SurfaceTabs({required this.node, required this.render});
  @override
  State<_SurfaceTabs> createState() => _SurfaceTabsState();
}

class _SurfaceTabsState extends State<_SurfaceTabs> {
  int selected = 0;
  @override
  Widget build(BuildContext context) {
    final children = widget.node['children'] as List;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        LayoutBuilder(
          builder: (context, bounds) => SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (var i = 0; i < children.length; i++)
                  SizedBox(
                    width: (bounds.maxWidth / children.length).clamp(
                      100,
                      bounds.maxWidth,
                    ),
                    child: TextButton(
                      onPressed: () => setState(() => selected = i),
                      style: TextButton.styleFrom(
                        backgroundColor: selected == i
                            ? Theme.of(context).colorScheme.surfaceContainerLow
                            : Colors.transparent,
                        shape: const StadiumBorder(),
                        foregroundColor: selected == i
                            ? Theme.of(context).colorScheme.onSurface
                            : Theme.of(context).colorScheme.onSurfaceVariant,
                        textStyle: Theme.of(context).textTheme.bodyMedium
                            ?.copyWith(
                              fontWeight: selected == i
                                  ? FontWeight.w700
                                  : FontWeight.w400,
                            ),
                      ),
                      child: Text(children[i]['label'] as String),
                    ),
                  ),
              ],
            ),
          ),
        ),
        for (var i = 0; i < children.length; i++)
          Offstage(
            offstage: selected != i,
            child: TickerMode(
              enabled: selected == i,
              child: widget.render(children[i]),
            ),
          ),
      ],
    );
  }
}

/// The first metric is the lead figure; supporting metrics share a quiet strip.
class SurfaceAnalysisView extends StatelessWidget {
  final List<Map<String, dynamic>> components;
  final Widget Function(Map<String, dynamic>) render;
  const SurfaceAnalysisView({
    super.key,
    required this.components,
    required this.render,
  });
  @override
  Widget build(BuildContext context) {
    final metrics = components.where((c) => c['type'] == 'metric').toList();
    final summary = components
        .where(
          (c) =>
              ['callout', 'rich_text', 'text', 'heading'].contains(c['type']),
        )
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final c in summary) render(c),
        if (metrics.isNotEmpty) ...[
          const SizedBox(height: 12),
          render({...metrics.first, 'variant': 'plain'}),
          if (metrics.length > 1) ...[
            const Divider(height: 32),
            LayoutBuilder(
              builder: (context, bounds) {
                final large = MediaQuery.textScalerOf(context).scale(14) > 20;
                final columns = large
                    ? 1
                    : (bounds.maxWidth / 100).floor().clamp(1, 3);
                final width = (bounds.maxWidth - (columns - 1) * 12) / columns;
                return Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    for (final c in metrics.skip(1))
                      SizedBox(
                        width: width,
                        child: render({...c, 'variant': 'plain'}),
                      ),
                  ],
                );
              },
            ),
          ],
          const Divider(height: 32),
        ],
        for (final c in components.where(
          (c) => !metrics.contains(c) && !summary.contains(c),
        ))
          render(c),
      ],
    );
  }
}
