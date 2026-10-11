import 'dart:convert';

import 'package:flutter/material.dart';

import '../bridge/study_host_api.g.dart';

import 'interactive_surface.dart';
import 'math_markdown.dart';
import 'surface_data_views.dart';
import 'surface_semantics.dart';
import 'surface_layout.dart';

bool shouldPreviewSurface(MessagePartDto part, String? generationState) =>
    generationState == 'RUNNING' &&
    [
      'render_interactive_ui',
      'create_interactive_surface',
      'render_math_plot',
    ].contains(part.toolName) &&
    ['NOT_STARTED', 'RUNNING'].contains(part.executionState) &&
    (part.approvalState == null || part.approvalState!.toLowerCase() == 'auto');

/// Only complete JSON values enter the normal catalog validator. Preview never saves or submits.
class SurfaceStreamSnapshot {
  final String title;
  final List<Map<String, dynamic>> components;
  const SurfaceStreamSnapshot(this.title, this.components);
  static SurfaceStreamSnapshot? parse(String input) {
    if (input.length > 196608) return null;
    try {
      String? raw;
      final wrapper = RegExp(r'^\s*\{\s*"definition"\s*:\s*"')
          .firstMatch(input);
      if (wrapper != null) {
        final start = wrapper.end - 1;
        var safe = start + 1;
        for (var i = safe; i < input.length;) {
          if (input[i] == '"') {
            safe = i;
            break;
          }
          if (input[i] == r'\') {
            if (i + 1 >= input.length) break;
            if (input[i + 1] == 'u') {
              if (i + 6 > input.length) break;
              i += 6;
            } else {
              i += 2;
            }
          } else {
            i++;
          }
          safe = i;
        }
        raw = jsonDecode('${input.substring(start, safe)}"') as String;
      } else {
        raw = input;
      }
      if (raw.length > 65536) return null;
      final prefix = _prefix(raw);
      if (prefix['version'] is! int || prefix['version'] != 4) return null;
      final title = prefix['title'];
      if (title is! String || title.isEmpty || title.length > 200) return null;
      final approved = <Map<String, dynamic>>[];
      final ids = <String>{};
      for (final item in prefix['components'] as List? ?? []) {
        if (approved.length == 24) break;
        if (item is! Map) continue;
        final c = Map<String, dynamic>.from(item);
        if (ids.contains(c['id'])) continue;
        // References requiring later controls remain skeletons until the complete surface is accepted.
        if (!SurfaceSemantics.fields.containsKey(c['type']) &&
            ![
              'text',
              'card',
              'table',
              'line_chart',
              'bar_chart',
              'scatter_chart',
            ].contains(c['type']))
          continue;
        try {
          SurfaceDefinition.parse({
            'version': 4,
            'title': title,
            'components': [c],
          });
          ids.add(c['id'] as String);
          approved.add(c);
        } catch (_) {
          /* An unfinished or invalid component is never rendered. */
        }
      }
      return SurfaceStreamSnapshot(title, approved);
    } catch (_) {
      return null;
    }
  }

  static Map<String, dynamic> _prefix(String raw) {
    final result = <String, dynamic>{};
    var i = _space(raw, 0);
    if (i >= raw.length || raw[i++] != '{') return result;
    while (i < raw.length) {
      i = _space(raw, i);
      final keyEnd = _end(raw, i);
      if (keyEnd < 0 || raw[i] != '"') break;
      final key = jsonDecode(raw.substring(i, keyEnd)) as String;
      if (result.containsKey(key))
        throw const FormatException('Duplicate prefix key');
      i = _space(raw, keyEnd);
      if (i >= raw.length || raw[i++] != ':') break;
      i = _space(raw, i);
      if (key == 'components' && i < raw.length && raw[i] == '[') {
        final items = <dynamic>[];
        var cursor = _space(raw, i + 1);
        while (cursor < raw.length && raw[cursor] != ']' && items.length < 24) {
          final end = _end(raw, cursor);
          if (end < 0) break;
          items.add(jsonDecode(raw.substring(cursor, end)));
          cursor = _space(raw, end);
          if (cursor < raw.length && raw[cursor] == ',') {
            cursor = _space(raw, cursor + 1);
          } else {
            break;
          }
        }
        result[key] = items;
      }
      final end = _end(raw, i);
      if (end < 0) break;
      result[key] = jsonDecode(raw.substring(i, end));
      i = _space(raw, end);
      if (i >= raw.length || raw[i] != ',') break;
      i++;
    }
    return result;
  }

  static int _space(String text, int i) {
    while (i < text.length && ' \r\n\t'.contains(text[i])) {
      i++;
    }
    return i;
  }

  static int _end(String text, int start) {
    if (start >= text.length) return -1;
    final first = text[start];
    var depth = 0, quoted = false, escaped = false;
    for (var i = start; i < text.length; i++) {
      final ch = text[i];
      if (quoted) {
        if (escaped) {
          escaped = false;
        } else if (ch == r'\') {
          escaped = true;
        } else if (ch == '"') {
          quoted = false;
          if (first == '"' && depth == 0) return i + 1;
        }
        continue;
      }
      if (ch == '"') {
        quoted = true;
      } else if (ch == '{' || ch == '[') {
        depth++;
      } else if (ch == '}' || ch == ']') {
        if (depth == 0) return i;
        if (--depth == 0) return i + 1;
      } else if (ch == ',' && depth == 0) {
        return i;
      }
    }
    return -1;
  }
}

class SurfaceStreamingPreview extends StatefulWidget {
  final String input;
  const SurfaceStreamingPreview({super.key, required this.input});
  @override
  State<SurfaceStreamingPreview> createState() =>
      _SurfaceStreamingPreviewState();
}

class _SurfaceStreamingPreviewState extends State<SurfaceStreamingPreview> {
  SurfaceStreamSnapshot? snapshot;
  bool scheduled = false;
  final Map<String, ({String content, Widget view})> completed = {};

  Widget component(Map<String, dynamic> c) {
    final id = c['id'] as String;
    final content = jsonEncode(c);
    if (completed[id]?.content == content) return completed[id]!.view;
    final view = SurfaceComponentStyle(
      key: ValueKey(id),
      definition: c,
      density: 'comfortable',
      child: SurfaceSemantics.fields.containsKey(c['type'])
          ? SurfaceSemanticView(component: c)
          : c['type'] == 'table'
          ? SurfaceTableView(definition: c)
          : c['type'].toString().endsWith('_chart')
          ? SurfaceChartView(definition: c, factor: 1, selected: -1)
          : MathMarkdownView(content: c['text'] as String),
    );
    completed[id] = (content: content, view: view);
    return view;
  }

  @override
  void initState() {
    super.initState();
    snapshot = SurfaceStreamSnapshot.parse(widget.input);
  }

  @override
  void didUpdateWidget(covariant SurfaceStreamingPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.input == widget.input || scheduled) return;
    scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      scheduled = false;
      if (mounted)
        setState(() => snapshot = SurfaceStreamSnapshot.parse(widget.input));
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final ids = snapshot?.components.map((c) => c['id']).toSet() ?? {};
    completed.removeWhere((id, _) => !ids.contains(id));
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            snapshot?.title ?? '正在生成界面',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          Semantics(
            liveRegion: true,
            label: '界面预览，生成完成后可操作',
            child: const LinearProgressIndicator(minHeight: 2),
          ),
          const SizedBox(height: 12),
          for (final c in snapshot?.components ?? <Map<String, dynamic>>[])
            component(c),
          Container(
            height: 48,
            margin: const EdgeInsets.only(top: 12),
            decoration: BoxDecoration(
              color: scheme.onSurface.withValues(alpha: .04),
              borderRadius: BorderRadius.circular(8),
            ),
          ),
        ],
      ),
    );
  }
}
