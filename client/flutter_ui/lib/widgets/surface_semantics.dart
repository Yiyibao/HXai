import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_highlight/flutter_highlight.dart';
import 'package:flutter_highlight/themes/atom-one-dark.dart';

import 'math_markdown.dart';
import 'surface_layout.dart';

class SurfaceSemantics {
  static const fields = <String, List<String>>{
    'rich_text': ['text'],
    'heading': ['text', 'level'],
    'metric': ['label', 'value', 'unit', 'delta'],
    'callout': ['title', 'text', 'tone'],
    'steps': ['items'],
    'comparison': ['items'],
    'code_block': ['code', 'language', 'collapsed'],
  };

  static void validate(Map<String, dynamic> c) {
    void text(String key, {int limit = 4000, bool optional = false}) {
      if (optional && !c.containsKey(key)) return;
      if (c[key] is! String || (c[key] as String).length > limit) {
        throw FormatException('Invalid $key');
      }
    }

    switch (c['type']) {
      case 'rich_text':
        text('text');
      case 'heading':
        text('text', limit: 200);
        if (c.containsKey('level') &&
            (c['level'] is! int || c['level'] < 1 || c['level'] > 3)) {
          throw const FormatException('Invalid heading level');
        }
      case 'metric':
        text('label', limit: 200);
        text('unit', limit: 40, optional: true);
        text('delta', limit: 200, optional: true);
        final v = c['value'];
        if (!(v is String && v.length <= 200 ||
            v is num && v.isFinite && v.abs() <= 1e12)) {
          throw const FormatException('Invalid metric value');
        }
      case 'callout':
        text('title', limit: 200, optional: true);
        text('text');
        if (c.containsKey('tone') &&
            ![
              'neutral',
              'info',
              'success',
              'warning',
              'error',
            ].contains(c['tone'])) {
          throw const FormatException('Invalid callout tone');
        }
      case 'steps':
      case 'comparison':
        final items = c['items'];
        if (items is! List || items.isEmpty || items.length > 12) {
          throw const FormatException('Invalid items');
        }
        for (final item in items) {
          if (item is! Map ||
              item.length != 2 ||
              item['title'] is! String ||
              (item['title'] as String).isEmpty ||
              (item['title'] as String).length > 200 ||
              item['text'] is! String ||
              (item['text'] as String).length > 4000) {
            throw const FormatException('Invalid semantic item');
          }
        }
      case 'code_block':
        text('code', limit: 16000);
        text('language', limit: 40, optional: true);
        if (c.containsKey('collapsed') && c['collapsed'] is! bool) {
          throw const FormatException('Invalid code collapse');
        }
    }
  }
}

class SurfaceSemanticView extends StatelessWidget {
  final Map<String, dynamic> component;
  const SurfaceSemanticView({super.key, required this.component});

  @override
  Widget build(BuildContext context) {
    final c = component;
    final theme = Theme.of(context);
    final text = c['text'] as String? ?? '';
    Widget rich(String value) =>
        MathMarkdownView(content: value, style: theme.textTheme.bodyMedium);
    switch (c['type']) {
      case 'rich_text':
        return rich(text);
      case 'heading':
        return Text(
          text,
          style: switch (c['level'] ?? 2) {
            1 => theme.textTheme.titleLarge,
            3 => theme.textTheme.titleSmall,
            _ => theme.textTheme.titleMedium,
          },
        );
      case 'metric':
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(c['label'] as String, style: theme.textTheme.labelMedium),
            const SizedBox(height: 4),
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.end,
              spacing: 6,
              children: [
                SelectableText(
                  '${c['value']}',
                  style: theme.textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
                if (c['unit'] != null)
                  Text(c['unit'] as String, style: theme.textTheme.bodySmall),
              ],
            ),
            if (c['delta'] != null)
              Text(c['delta'] as String, style: theme.textTheme.bodySmall),
          ],
        );
      case 'callout':
        final icon = switch (c['tone']) {
          'success' => Icons.check_circle_outline,
          'warning' => Icons.warning_amber,
          'error' => Icons.error_outline,
          _ => Icons.info_outline,
        };
        final color = surfaceTone(context, c['tone'] as String? ?? 'neutral');
        return Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: color, width: 3)),
            color: color.withValues(alpha: .05),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, size: 18, color: color),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (c['title'] != null)
                      Text(
                        c['title'] as String,
                        style: theme.textTheme.titleSmall,
                      ),
                    rich(text),
                  ],
                ),
              ),
            ],
          ),
        );
      case 'steps':
      case 'comparison':
        final items = (c['items'] as List).cast<Map>();
        if (c['type'] == 'comparison') return SurfaceComparison(items: items);
        final children = [
          for (var i = 0; i < items.length; i++)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (c['type'] == 'steps') ...[
                    Text('${i + 1}.', style: theme.textTheme.titleSmall),
                    const SizedBox(width: 12),
                  ],
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          items[i]['title'] as String,
                          style: theme.textTheme.titleSmall,
                        ),
                        const SizedBox(height: 6),
                        rich(items[i]['text'] as String),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ];
        return LayoutBuilder(
          builder: (context, bounds) {
            if (c['type'] == 'comparison' &&
                bounds.maxWidth >= 600 &&
                MediaQuery.textScalerOf(context).scale(14) <= 20) {
              final columns = (bounds.maxWidth / 260).floor().clamp(1, 3);
              final width = (bounds.maxWidth - (columns - 1) * 12) / columns;
              return Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  for (final child in children)
                    SizedBox(width: width, child: child),
                ],
              );
            }
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: children,
            );
          },
        );
      case 'code_block':
        return SurfaceCodeBlock(component: c);
      default:
        return const SizedBox.shrink();
    }
  }
}

/// Neutral, outlined content container shared by the selected reference styles.
class SurfaceInfoCard extends StatelessWidget {
  final Widget child;
  const SurfaceInfoCard({super.key, required this.child});
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surface,
      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      borderRadius: BorderRadius.circular(16),
    ),
    child: child,
  );
}

class SurfaceComparison extends StatelessWidget {
  final List<Map> items;
  const SurfaceComparison({super.key, required this.items});

  // Only align explicitly labelled lines; preserve arbitrary Markdown otherwise.
  Map<String, String>? fields(String text) {
    final result = <String, String>{};
    for (final line in text.split('\n').where((s) => s.trim().isNotEmpty)) {
      final match = RegExp(
        r'^\s*(?:[-*]\s+)?\*{0,2}([^:：]{1,20}?)\*{0,2}[:：]\s*(.+)$',
      ).firstMatch(line);
      if (match == null) return null;
      final label = match[1]!.replaceAll('**', '').trim();
      if (result.containsKey(label)) return null;
      result[label] = match[2]!;
    }
    return result.isEmpty ? null : result;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    Widget rich(String text) => MathMarkdownView(content: text);
    final labelled = items
        .map((item) => fields(item['text'] as String))
        .toList();
    final aligned =
        labelled.every((f) => f != null) &&
        labelled.every(
          (f) => f!.keys.join('|') == labelled.first!.keys.join('|'),
        );
    if (items.length == 2) {
      return LayoutBuilder(
        builder: (context, bounds) {
          final width = bounds.maxWidth < 340 ? 340.0 : bounds.maxWidth;
          final rows = aligned ? labelled.first!.keys.toList() : ['内容'];
          Widget cell(Widget child, {bool heading = false}) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
            child: DefaultTextStyle.merge(
              style: TextStyle(
                fontWeight: heading ? FontWeight.w700 : FontWeight.w400,
              ),
              child: child,
            ),
          );
          return SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: SizedBox(
              width: width,
              child: Table(
                columnWidths: const {
                  0: FlexColumnWidth(.65),
                  1: FlexColumnWidth(1),
                  2: FlexColumnWidth(1),
                },
                defaultVerticalAlignment: TableCellVerticalAlignment.top,
                border: TableBorder(
                  horizontalInside: BorderSide(
                    color: theme.colorScheme.outlineVariant,
                    width: .6,
                  ),
                ),
                children: [
                  TableRow(
                    children: [
                      cell(const Text('对比项'), heading: true),
                      for (final item in items)
                        cell(Text(item['title'] as String), heading: true),
                    ],
                  ),
                  for (final row in rows)
                    TableRow(
                      children: [
                        cell(Text(row)),
                        for (var i = 0; i < 2; i++)
                          cell(
                            rich(
                              aligned
                                  ? labelled[i]![row]!
                                  : items[i]['text'] as String,
                            ),
                          ),
                      ],
                    ),
                ],
              ),
            ),
          );
        },
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < items.length; i++)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: SurfaceInfoCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    items[i]['title'] as String,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const Divider(height: 28),
                  if (labelled[i] != null)
                    for (final entry in labelled[i]!.entries)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 10),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(
                              width: 76,
                              child: Text(
                                entry.key,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                              ),
                            ),
                            Expanded(child: rich(entry.value)),
                          ],
                        ),
                      )
                  else
                    rich(items[i]['text'] as String),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

class SurfaceCodeBlock extends StatefulWidget {
  final Map<String, dynamic> component;
  const SurfaceCodeBlock({super.key, required this.component});
  @override
  State<SurfaceCodeBlock> createState() => _SurfaceCodeBlockState();
}

class _SurfaceCodeBlockState extends State<SurfaceCodeBlock> {
  late bool collapsed = widget.component['collapsed'] == true;
  @override
  Widget build(BuildContext context) {
    final c = widget.component;
    final code = c['code'] as String;
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF282C34),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF3E4451)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  c['language'] as String? ?? 'code',
                  style: const TextStyle(
                    color: Color(0xFFABB2BF),
                    fontSize: 12,
                  ),
                ),
              ),
              IconButton(
                tooltip: collapsed ? '展开代码' : '折叠代码',
                onPressed: () => setState(() => collapsed = !collapsed),
                icon: Icon(collapsed ? Icons.expand_more : Icons.expand_less),
                color: const Color(0xFFABB2BF),
              ),
              IconButton(
                tooltip: '复制代码',
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: code));
                },
                icon: const Icon(Icons.copy, size: 18),
                color: const Color(0xFFABB2BF),
              ),
            ],
          ),
          if (!collapsed)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 400),
              child: SingleChildScrollView(
                child: SelectionArea(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: HighlightView(
                      code,
                      language: (c['language'] as String?)?.isNotEmpty == true
                          ? c['language'] as String
                          : 'plaintext',
                      theme: atomOneDarkTheme,
                      padding: const EdgeInsets.all(12),
                      textStyle: TextStyle(
                        fontFamily: 'monospace',
                        fontFamilyFallback: [
                          if (Theme.of(context)
                                  .textTheme
                                  .bodyMedium
                                  ?.fontFamily !=
                              null)
                            Theme.of(context).textTheme.bodyMedium!.fontFamily!,
                        ],
                        fontSize: 13,
                        height: 1.5,
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
