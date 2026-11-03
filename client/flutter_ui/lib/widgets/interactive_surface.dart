import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import 'enhanced_image_view.dart';
import 'math_plot.dart';
import 'math_markdown.dart';
import 'surface_semantics.dart';
import 'surface_layout.dart';
import 'surface_data.dart';
import 'surface_data_views.dart';

class SurfaceDefinition {
  final int version;
  final String presentation, density;
  final String? template;
  final dynamic composition;
  final String title;
  final List<Map<String, dynamic>> components;
  final List<List<String>>? layout;
  SurfaceDefinition._(
    this.version,
    this.title,
    this.components,
    this.layout,
    this.presentation,
    this.density,
    this.template,
    this.composition,
  );
  static SurfaceDefinition parse(Map<String, dynamic> json) {
    if (json['version'] is! int ||
        ![1, 2, 3, 4].contains(json['version']) ||
        json['title'] is! String ||
        (json['title'] as String).isEmpty ||
        (json['title'] as String).length > 200 ||
        json.keys.any(
          (k) => ![
            'version',
            'title',
            'components',
            if (json['version'] >= 3) 'layout',
            if (json['version'] >= 4) ...[
              'presentation',
              'density',
              'template',
              'composition',
            ],
          ].contains(k),
        ))
      throw const FormatException('界面版本或字段无效');
    if (json.containsKey('presentation') &&
            !['inline', 'panel'].contains(json['presentation']) ||
        json.containsKey('density') &&
            !SurfaceLayout.densities.contains(json['density']) ||
        json.containsKey('template') &&
            !SurfaceLayout.templates.contains(json['template']))
      throw const FormatException('Invalid presentation style');
    if (json.containsKey('composition') && json.containsKey('layout'))
      throw const FormatException('Choose one layout');
    final components = (json['components'] as List)
        .map((c) => Map<String, dynamic>.from(c as Map))
        .toList();
    if (components.isEmpty || components.length > 24)
      throw const FormatException('组件数量无效');
    final ids = <String>{};
    final parameters = <String>{};
    final fields = {
      'text': ['text'],
      'card': ['text'],
      'image': [
        'url',
        'caption',
        if (json['version'] >= 4) ...SurfaceData.imageFields,
      ],
      'math_plot': MathPlot.fields.toList(),
      'table': [
        'columns',
        'rows',
        if (json['version'] >= 2) ...['filter', 'filterColumn'],
        if (json['version'] >= 4) ...SurfaceData.tableFields,
      ],
      'line_chart': [
        if (json['version'] >= 4) ...SurfaceData.chartFields,
        'points',
        'scale',
        'xLabel',
        'yLabel',
        if (json['version'] >= 3) 'selectable',
      ],
      'bar_chart': [
        if (json['version'] >= 4) ...SurfaceData.chartFields,
        'points',
        'scale',
        'xLabel',
        'yLabel',
        if (json['version'] >= 3) 'selectable',
      ],
      'scatter_chart': [
        if (json['version'] >= 4) ...SurfaceData.chartFields,
        'points',
        'scale',
        'xLabel',
        'yLabel',
        if (json['version'] >= 3) 'selectable',
      ],
      'slider': ['label', 'min', 'max', 'value'],
      'select': ['label', 'options', 'value'],
      'input': ['label', 'value'],
      'calculation': [
        'label',
        'parameter',
        'a',
        'b',
        if (json['version'] >= 3) 'expression',
      ],
      'button': ['label', 'action'],
      if (json['version'] >= 4) ...SurfaceSemantics.fields,
    };
    bool number(dynamic n) => n is num && n.isFinite && n.abs() <= 1e12;
    for (final c in components) {
      final id = c['id'];
      final type = c['type'];
      if (id is! String ||
          !RegExp(r'^[A-Za-z][A-Za-z0-9_]{0,39}$').hasMatch(id) ||
          !ids.add(id) ||
          !fields.containsKey(type) ||
          c.keys.any(
            (k) => ![
              'id',
              'type',
              ...fields[type]!,
              if (json['version'] >= 4) ...['variant', 'tone', 'density'],
            ].contains(k),
          ))
        throw const FormatException('组件或字段未登记');
      if (json['version'] >= 4) SurfaceLayout.validateStyle(c);
      if (SurfaceSemantics.fields.containsKey(type))
        SurfaceSemantics.validate(c);
      if (type == 'image') {
        if (json['version'] >= 4) SurfaceData.validateImage(c);
        final url = c['url'];
        final uri = url is String ? Uri.tryParse(url) : null;
        if (url is! String ||
            url.length > 16000 ||
            uri == null ||
            !['http', 'https'].contains(uri.scheme) ||
            uri.host.isEmpty ||
            (c.containsKey('caption') &&
                (c['caption'] is! String ||
                    (c['caption'] as String).length > 2000))) {
          throw const FormatException('图片地址或说明无效');
        }
      }
      if (['text', 'card'].contains(type) &&
          (c['text'] is! String || (c['text'] as String).length > 4000))
        throw const FormatException('文本无效');
      if ([
            'slider',
            'select',
            'input',
            'calculation',
            'button',
          ].contains(type) &&
          (c['label'] is! String || (c['label'] as String).length > 200))
        throw const FormatException('标签无效');
      if (type == 'slider') {
        if (!number(c['min']) ||
            !number(c['max']) ||
            !number(c['value']) ||
            c['min'] >= c['max'] ||
            c['value'] < c['min'] ||
            c['value'] > c['max'])
          throw const FormatException('滑块范围无效');
        parameters.add(id);
      }
      if (type == 'select') {
        final options = c['options'] as List;
        if (options.isEmpty ||
            options.length > 30 ||
            options.toSet().length != options.length ||
            !options.contains(c['value']) ||
            options.any((v) => v is! String || v.isEmpty || v.length > 200))
          throw const FormatException('选项无效');
      }
      if (type == 'input' &&
          (c['value'] is! String || (c['value'] as String).length > 4000))
        throw const FormatException('输入内容无效');
      if (type == 'calculation' &&
          (c.containsKey('expression')
              ? c.keys.any((k) => ['a', 'b', 'parameter'].contains(k))
              : (!number(c['a']) || !number(c['b']))))
        throw const FormatException('计算参数无效');
      if (type == 'button' && c['action'] != 'continue')
        throw const FormatException('动作未登记');
      if (type == 'table') {
        final columns = c['columns'] as List;
        final rows = c['rows'] as List;
        if (columns.isEmpty ||
            columns.length > 12 ||
            rows.length > 200 ||
            columns.any((v) => v is! String || v.length > 200) ||
            rows.any(
              (r) =>
                  r is! List ||
                  r.length != columns.length ||
                  r.any((v) => v is List || v is Map || '$v'.length > 2000),
            ))
          throw const FormatException('表格无效');
        if (json['version'] >= 4) SurfaceData.validateTable(c);
      }
      if (['line_chart', 'bar_chart', 'scatter_chart'].contains(type)) {
        if (c.containsKey('selectable') && c['selectable'] is! bool)
          throw const FormatException('图表选择无效');
        for (final key in ['xLabel', 'yLabel']) {
          if (c.containsKey(key) &&
              (c[key] is! String || (c[key] as String).length > 200))
            throw const FormatException('坐标标签无效');
        }
        if (json['version'] >= 4) SurfaceData.validateChart(c);
        final points = SurfaceData.points(c);
        if (points.isEmpty ||
            points.length > 500 ||
            points.any(
              (p) => p is! List || p.length != 2 || p.any((v) => !number(v)),
            ))
          throw const FormatException('图表数据无效');
      }
    }
    for (final c in components) {
      if (c['type'] == 'math_plot') {
        if (json['version'] < 3) throw const FormatException('数学图像需要版本 3');
        MathPlot.validate(c, parameters);
      }
      if (c.containsKey('filter') || c.containsKey('filterColumn')) {
        if (!components.any(
              (item) => item['type'] == 'select' && item['id'] == c['filter'],
            ) ||
            c['filterColumn'] is! int ||
            c['filterColumn'] < 0 ||
            c['filterColumn'] >= (c['columns'] as List).length)
          throw const FormatException('筛选引用无效');
      }
      if ((c.containsKey('scale') && !parameters.contains(c['scale'])) ||
          (c['type'] == 'calculation' &&
              !c.containsKey('expression') &&
              !parameters.contains(c['parameter'])))
        throw const FormatException('计算引用无效');
      if (c.containsKey('expression')) {
        var nodes = 0;
        void validateExpression(dynamic e, int depth) {
          if (++nodes > 64 || depth > 8) throw const FormatException('计算过大');
          if (number(e)) return;
          if (e is! Map) throw const FormatException('计算节点无效');
          if (e.length == 1 &&
              e.containsKey('parameter') &&
              parameters.contains(e['parameter']))
            return;
          if (e.length != 2 ||
              !e.containsKey('op') ||
              ![
                'add',
                'subtract',
                'multiply',
                'divide',
                'power',
                'min',
                'max',
              ].contains(e['op']) ||
              e['args'] is! List ||
              (e['args'] as List).length != 2)
            throw const FormatException('计算操作无效');
          for (final arg in e['args'] as List) {
            validateExpression(arg, depth + 1);
          }
        }

        validateExpression(c['expression'], 0);
      }
    }
    final layout = json['layout'] == null
        ? null
        : (json['layout'] as List)
              .map((row) => (row as List).cast<String>())
              .toList();
    if (layout != null &&
        (layout.isEmpty ||
            layout.length > 24 ||
            layout.any((r) => r.isEmpty || r.length > 3) ||
            layout.expand((r) => r).length != ids.length ||
            layout.expand((r) => r).toSet().difference(ids).isNotEmpty ||
            ids.difference(layout.expand((r) => r).toSet()).isNotEmpty))
      throw const FormatException('布局必须包含每个组件且不重复');
    if (json.containsKey('composition'))
      SurfaceLayout.validateComposition(json['composition'], ids);
    return SurfaceDefinition._(
      json['version'] as int,
      json['title'] as String,
      components,
      layout,
      json['presentation'] as String? ??
          (json['version'] >= 4 ? 'inline' : 'panel'),
      json['density'] as String? ?? 'comfortable',
      json['template'] as String?,
      json['composition'],
    );
  }

  Map<String, dynamic> get defaults => {
    for (final c in components.where(
      (c) =>
          ['slider', 'select', 'input'].contains(c['type']) ||
          c['selectable'] == true,
    ))
      c['id'] as String: c['selectable'] == true ? -1 : c['value'],
  };
  void validateState(Map<String, dynamic> values) {
    if (values.length != defaults.length ||
        values.keys.any((key) => !defaults.containsKey(key)))
      throw const FormatException('界面状态字段无效');
    for (final c in components) {
      final v = values[c['id']];
      switch (c['type']) {
        case 'slider':
          if (v is! num || !v.isFinite || v < c['min'] || v > c['max'])
            throw const FormatException('滑块状态无效');
        case 'select':
          if (!(c['options'] as List).contains(v))
            throw const FormatException('选择状态无效');
        case 'input':
          if (v is! String || v.length > 4000)
            throw const FormatException('输入状态无效');
        case 'line_chart':
        case 'bar_chart':
        case 'scatter_chart':
          if (c['selectable'] == true &&
              (v is! int || v < -1 || v >= SurfaceData.points(c).length))
            throw const FormatException('图表状态无效');
      }
    }
  }
}

class SurfaceSessionController {
  Future<bool> Function()? persist;
  Future<bool> flush() async => await persist?.call() ?? false;
}

class InteractiveSurfaceView extends StatefulWidget {
  final SurfaceSessionController? sessionController;
  final SurfaceDefinition definition;
  final bool allowExpand;
  final Widget Function(
    BuildContext,
    Map<String, dynamic> Function(),
    Future<bool> Function(),
    VoidCallback,
  )?
  followUpBuilder;
  final Future<Map<String, dynamic>> Function()? load;
  final Future<void> Function(
    Map<String, dynamic> values,
    String? action,
    String requestId,
  )
  save;
  const InteractiveSurfaceView({
    super.key,
    required this.definition,
    required this.save,
    this.load,
    this.allowExpand = true,
    this.followUpBuilder,
    this.sessionController,
  });
  @override
  State<InteractiveSurfaceView> createState() => _InteractiveSurfaceViewState();
}

class _InteractiveSurfaceViewState extends State<InteractiveSurfaceView>
    with WidgetsBindingObserver {
  late Map<String, dynamic> values;
  bool ready = false;
  bool busy = false;
  bool saving = false;
  bool dirty = false;
  bool submitted = false;
  bool expanding = false;
  int generation = 0;
  int edits = 0;
  int saves = 0;
  final Set<String> invalidFields = {};
  int resetRevision = 0;
  String? error;
  Timer? debounce;
  Future<void> pendingSave = Future.value();
  String? retryRequest;
  String? retryPayload;
  final Map<String, Widget> _builtComponents = {};
  final Map<String, ValueNotifier<int>> _componentRevisions = {};
  int? _cacheSignature;
  Future<bool> persistForPreview() async {
    if (!ready || busy || invalidFields.isNotEmpty) return false;
    debounce?.cancel();
    return save(null);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _builtComponents.clear();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    values = widget.definition.defaults;
    widget.sessionController?.persist = persistForPreview;
    unawaited(load());
  }

  @override
  void didUpdateWidget(covariant InteractiveSurfaceView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.sessionController != widget.sessionController) {
      oldWidget.sessionController?.persist = null;
      widget.sessionController?.persist = persistForPreview;
    }
    if (oldWidget.definition.version != widget.definition.version ||
        oldWidget.definition.density != widget.definition.density ||
        oldWidget.definition.presentation != widget.definition.presentation ||
        oldWidget.definition.template != widget.definition.template ||
        oldWidget.definition.title != widget.definition.title ||
        jsonEncode(oldWidget.definition.layout) !=
            jsonEncode(widget.definition.layout) ||
        jsonEncode(oldWidget.definition.composition) !=
            jsonEncode(widget.definition.composition)) {
      _builtComponents.clear();
    }
    if (oldWidget.definition.version != widget.definition.version ||
        jsonEncode(oldWidget.definition.components) !=
            jsonEncode(widget.definition.components)) {
      if (ready && dirty && invalidFields.isEmpty) {
        final snapshot = Map<String, dynamic>.of(values);
        final callback = oldWidget.save;
        pendingSave = pendingSave
            .catchError((_) {})
            .then(
              (_) => callback(
                snapshot,
                null,
                'ui_${DateTime.now().microsecondsSinceEpoch}_old',
              ),
            );
        unawaited(pendingSave.catchError((_) {}));
      }
      generation++;
      _builtComponents.clear();
      for (final notifier in _componentRevisions.values) {
        notifier.dispose();
      }
      _componentRevisions.clear();
      debounce?.cancel();
      values = widget.definition.defaults;
      ready = false;
      busy = false;
      saving = false;
      dirty = false;
      submitted = false;
      error = null;
      invalidFields.clear();
      resetRevision++;
      retryRequest = null;
      retryPayload = null;
      unawaited(load());
    }
  }

  Future<void> load() async {
    final token = ++generation;
    final definition = widget.definition;
    setState(() {
      ready = false;
      error = null;
    });
    try {
      final loaded = await widget.load?.call() ?? definition.defaults;
      definition.validateState(loaded);
      if (mounted && token == generation) {
        setState(() {
          values = Map<String, dynamic>.of(loaded);
          ready = true;
          error = null;
          resetRevision++;
        });
      }
    } catch (e) {
      if (mounted && token == generation)
        setState(() => error = '暂时无法恢复已保存的状态，请重试加载');
    }
  }

  void _flush() {
    if (ready &&
        dirty &&
        !busy &&
        invalidFields.isEmpty &&
        debounce?.isActive == true) {
      debounce?.cancel();
      unawaited(save(null, notify: false));
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _flush();
  }

  @override
  void dispose() {
    widget.sessionController?.persist = null;
    _flush();
    generation++;
    debounce?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    for (final notifier in _componentRevisions.values) {
      notifier.dispose();
    }
    super.dispose();
  }

  void update(String id, dynamic value) {
    if (!ready || busy) return;
    setState(() {
      values[id] = value;
      for (final c in widget.definition.components) {
        if (_depends(c, id)) _componentRevisions[c['id']]?.value++;
      }
      dirty = true;
      submitted = false;
      edits++;
    });
    debounce?.cancel();
    debounce = Timer(const Duration(milliseconds: 400), () {
      if (invalidFields.isEmpty) unawaited(save(null));
    });
  }

  Future<bool> save(String? action, {bool notify = true}) async {
    if (action != null && busy) return false;
    final snapshot = Map<String, dynamic>.of(values);
    final callback = widget.save;
    final token = generation;
    final edit = edits;
    final serial = ++saves;
    final payload = jsonEncode([action, snapshot]);
    final request = retryPayload == payload
        ? retryRequest!
        : 'ui_${DateTime.now().microsecondsSinceEpoch}_$serial';
    debounce?.cancel();
    if (action != null) {
      retryRequest = request;
      retryPayload = payload;
    }
    if (notify && mounted)
      setState(() {
        saving = true;
        if (action != null) busy = true;
      });
    final operation = pendingSave
        .catchError((_) {})
        .then((_) => callback(snapshot, action, request));
    pendingSave = operation;
    try {
      await operation;
      if (mounted && token == generation && serial == saves) {
        setState(() {
          error = null;
          if (edit == edits) dirty = false;
          if (action != null) submitted = true;
        });
        if (action != null) {
          retryRequest = null;
          retryPayload = null;
        }
      }
      return true;
    } catch (e) {
      if (mounted && token == generation && serial == saves) {
        setState(
          () => error = action == null ? '修改尚未保存，请重试保存' : '提交未完成，请再次点击原按钮重试',
        );
      }
      return false;
    } finally {
      if (mounted && token == generation && serial == saves)
        setState(() {
          saving = false;
          if (action != null) busy = false;
        });
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _frame(
        child: Padding(
          padding: EdgeInsets.all(_isPanel ? 16 : 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.definition.title,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  if (widget.allowExpand)
                    IconButton(
                      tooltip: '展开界面',
                      constraints: const BoxConstraints(
                        minWidth: 32,
                        minHeight: 32,
                      ),
                      padding: const EdgeInsets.all(6),
                      visualDensity: VisualDensity.compact,
                      icon: const Icon(Icons.open_in_full, size: 18),
                      onPressed:
                          ready && !busy && !expanding && invalidFields.isEmpty
                          ? expand
                          : null,
                    ),
                ],
              ),
              const SizedBox(height: 8),
              if (widget.definition.version < 4 ||
                  !ready ||
                  busy ||
                  saving ||
                  error != null)
                Semantics(
                  liveRegion: true,
                  child: Text(
                    error != null
                        ? '操作未完成'
                        : !ready
                        ? '正在恢复界面状态'
                        : busy
                        ? '正在提交给 AI…'
                        : saving
                        ? '正在保存修改…'
                        : dirty
                        ? '有修改待保存'
                        : submitted
                        ? '已提交，AI 将继续分析'
                        : '状态已同步 · 可交互探索',
                    style: TextStyle(
                      fontSize: 12,
                      color: StudyPalette.of(context).textGray,
                    ),
                  ),
                ),
              const SizedBox(height: 12),
              if (!ready && error == null) const LinearProgressIndicator(),
              if (error != null)
                Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (!ready && error != null)
                TextButton(onPressed: load, child: const Text('重试加载界面')),
              if (ready && error != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: busy || saving || invalidFields.isNotEmpty
                        ? null
                        : () => unawaited(save(null)),
                    icon: const Icon(Icons.refresh, size: 18),
                    label: const Text('重试保存修改'),
                  ),
                ),
              if (widget.definition.version >= 4 &&
                  widget.definition.template == 'analysis' &&
                  widget.definition.composition == null &&
                  widget.definition.layout == null)
                SurfaceAnalysisView(
                  components: widget.definition.components,
                  render: (c) => component(c),
                )
              else if (widget.definition.version >= 4 &&
                  widget.definition.layout == null)
                SurfaceCompositionView(
                  node: _composition(),
                  minimumCellWidth: widget.definition.template == 'analysis'
                      ? 95
                      : 180,
                  component: (id, compact) => component(
                    widget.definition.components.singleWhere(
                      (c) => c['id'] == id,
                    ),
                    compact: compact,
                  ),
                )
              else if (widget.definition.layout == null)
                ...widget.definition.components.map(component)
              else
                ...widget.definition.layout!.map(
                  (row) => LayoutBuilder(
                    builder: (context, bounds) {
                      final children = row
                          .map(
                            (id) => component(
                              widget.definition.components.singleWhere(
                                (c) => c['id'] == id,
                              ),
                              compact: row.length > 1,
                            ),
                          )
                          .toList();
                      if (bounds.maxWidth < 260 ||
                          MediaQuery.textScalerOf(context).scale(14) > 20 ||
                          children.length == 1)
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: children,
                        );
                      return Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: children
                            .map(
                              (child) => Expanded(
                                child: Padding(
                                  padding: const EdgeInsets.all(4),
                                  child: child,
                                ),
                              ),
                            )
                            .toList(),
                      );
                    },
                  ),
                ),
            ],
          ),
        ),
      ),
    ],
  );

  bool get _isPanel =>
      widget.definition.presentation == 'panel' ||
      ['analysis', 'exploration'].contains(widget.definition.template);

  Widget _frame({required Widget child}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Material(
      color: _isPanel
          ? Theme.of(context).colorScheme.surface
          : Colors.transparent,
      shape: _isPanel
          ? RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
              side: BorderSide(
                color: Theme.of(context).colorScheme.outlineVariant,
              ),
            )
          : null,
      child: child,
    ),
  );

  dynamic _composition() {
    final spec = widget.definition;
    if (spec.composition != null) return spec.composition;
    if (spec.template == 'explanation') {
      final code = spec.components
          .where((c) => c['type'] == 'code_block')
          .map((c) => c['id'])
          .toList();
      return {
        'type': 'column',
        'children': [
          ...spec.components
              .where((c) => !code.contains(c['id']))
              .map((c) => c['id']),
          if (code.isNotEmpty)
            {
              'type': 'accordion',
              'children': [
                {'type': 'column', 'label': '代码', 'children': code},
              ],
            },
        ],
      };
    }
    final grouped = spec.components
        .where(
          (c) => switch (spec.template) {
            'analysis' => c['type'] == 'metric',
            'comparison' => ['metric', 'comparison'].contains(c['type']),
            'exploration' => ['slider', 'select', 'input'].contains(c['type']),
            _ => false,
          },
        )
        .map((c) => c['id'])
        .toList();
    return {
      'type': 'column',
      'children': [
        if (grouped.isNotEmpty)
          {'type': 'grid', 'columns': 2, 'children': grouped},
        ...spec.components
            .where((c) => !grouped.contains(c['id']))
            .map((c) => c['id']),
      ],
    };
  }

  Future<void> expand() async {
    if (expanding) return;
    setState(() => expanding = true);
    try {
      debounce?.cancel();
      await save(null);
      if (!mounted || error != null) return;
      final expandedKey = GlobalKey<_InteractiveSurfaceViewState>();
      bool closing = false;
      Future<void> closeExpanded(BuildContext dialogContext) async {
        final state = expandedKey.currentState;
        if (closing || state == null || !state.ready || state.busy) return;
        if (state.invalidFields.isNotEmpty) {
          state.setState(() => state.error = '请先修正无效的参数');
          return;
        }
        closing = true;
        final saved = await state.save(null);
        closing = false;
        if (saved && dialogContext.mounted) Navigator.pop(dialogContext);
      }

      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => PopScope(
          canPop: false,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) unawaited(closeExpanded(dialogContext));
          },
          child: Dialog.fullscreen(
            child: Scaffold(
              appBar: AppBar(
                title: Text(widget.definition.title),
                leading: IconButton(
                  tooltip: '保存并关闭',
                  icon: const Icon(Icons.close),
                  onPressed: () => unawaited(closeExpanded(dialogContext)),
                ),
              ),
              body: SafeArea(
                child: Column(
                  children: [
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.all(16),
                        child: InteractiveSurfaceView(
                          key: expandedKey,
                          allowExpand: false,
                          definition: widget.definition,
                          load: () async => Map<String, dynamic>.of(values),
                          save: (next, action, request) async {
                            await widget.save(next, action, request);
                            if (mounted)
                              setState(() {
                                values = Map<String, dynamic>.of(next);
                                resetRevision++;
                                dirty = false;
                                submitted = action != null;
                              });
                          },
                        ),
                      ),
                    ),
                    if (widget.followUpBuilder != null)
                      widget.followUpBuilder!(
                        dialogContext,
                        () => expandedKey.currentState?.values ?? values,
                        () async {
                          final state = expandedKey.currentState;
                          if (state == null ||
                              !state.ready ||
                              state.busy ||
                              state.invalidFields.isNotEmpty)
                            return false;
                          return state.save(null);
                        },
                        () => unawaited(closeExpanded(dialogContext)),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => expanding = false);
    }
  }

  Widget component(Map<String, dynamic> c, {bool compact = false}) {
    final signature = Object.hash(
      ready,
      busy,
      resetRevision,
      invalidFields.join(','),
    );
    if (_cacheSignature != signature) {
      _builtComponents.clear();
      _cacheSignature = signature;
    }
    final cacheKey = '${c['id']}:$compact';
    return _builtComponents.putIfAbsent(
      cacheKey,
      () => ValueListenableBuilder<int>(
        key: ValueKey(cacheKey),
        valueListenable: _componentRevisions.putIfAbsent(
          c['id'] as String,
          () => ValueNotifier(0),
        ),
        builder: (context, _, _) {
          final child = _component(c, compact: compact);
          return widget.definition.version >= 4
              ? SurfaceComponentStyle(
                  definition: c,
                  density: widget.definition.density,
                  child: child,
                )
              : child;
        },
      ),
    );
  }

  bool _depends(Map<String, dynamic> c, String id) {
    if (c['id'] == id ||
        c['scale'] == id ||
        c['filter'] == id ||
        c['parameter'] == id)
      return true;
    if (c['type'] == 'math_plot')
      return widget.definition.components.any(
        (p) => p['id'] == id && p['type'] == 'slider',
      );
    bool expression(dynamic node) =>
        node is Map &&
        (node['parameter'] == id ||
            (node['args'] as List? ?? []).any(expression));
    return expression(c['expression']);
  }

  Widget _component(Map<String, dynamic> c, {bool compact = false}) {
    if (SurfaceSemantics.fields.containsKey(c['type'])) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: SurfaceSemanticView(key: ValueKey(c['id']), component: c),
      );
    }
    final id = c['id'] as String;
    final scheme = Theme.of(context).colorScheme;
    final panelColor = Color.alphaBlend(
      scheme.onSurface.withValues(alpha: 0.04),
      scheme.surface,
    );
    switch (c['type']) {
      case 'math_plot':
        return MathPlotView(
          definition: c,
          parameters: {
            for (final control in widget.definition.components)
              if (control['type'] == 'slider')
                control['id'] as String: (values[control['id']] as num)
                    .toDouble(),
          },
        );
      case 'text':
        if (widget.definition.version >= 4)
          return MathMarkdownView(content: c['text'] as String);
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: SelectableText(
            c['text'] as String,
            style: widget.definition.components.first['id'] == id
                ? TextStyle(
                    fontSize: 13,
                    color: StudyPalette.of(context).textGray,
                    height: 1.5,
                  )
                : null,
          ),
        );
      case 'card':
        if (widget.definition.version >= 4)
          return SurfaceInfoCard(
            child: MathMarkdownView(content: c['text'] as String),
          );
        final lines = (c['text'] as String).split('\n');
        final metric =
            lines.length == 2 &&
            RegExp(r'^[¥￥\$€]?\s*[0-9]').hasMatch(lines.last);
        return Container(
          margin: const EdgeInsets.symmetric(vertical: 10),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: panelColor,
            borderRadius: BorderRadius.circular(10),
          ),
          child: metric
              ? Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      lines.first,
                      style: TextStyle(
                        fontSize: 13,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                    const SizedBox(height: 4),
                    SelectableText(
                      lines.last,
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w700,
                        height: 1.3,
                      ),
                    ),
                  ],
                )
              : SelectableText(c['text'] as String),
        );
      case 'image':
        if (widget.definition.version >= 4)
          return SurfaceImageView(definition: c);
        return EnhancedImageView(
          part: MessagePartDto(
            type: 'image',
            url: c['url'] as String,
            text: c['caption'] as String?,
          ),
          messageId: '',
        );
      case 'table':
        if (widget.definition.version >= 4)
          return SurfaceTableView(
            key: ValueKey(c['id']),
            definition: c,
            filter: c.containsKey('filter') ? values[c['filter']] : '*',
          );
        final filter = c.containsKey('filter') ? values[c['filter']] : '*';
        final rows = (c['rows'] as List).where(
          (row) =>
              filter == '*' || '${row[c['filterColumn'] as int]}' == filter,
        );
        return Container(
          margin: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            border: Border.all(color: StudyPalette.of(context).borderGray),
            borderRadius: BorderRadius.circular(10),
          ),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 320),
            child: SingleChildScrollView(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: DataTable(
                  headingRowColor: WidgetStatePropertyAll(panelColor),
                  columnSpacing: 24,
                  horizontalMargin: 16,
                  columns: (c['columns'] as List)
                      .map((v) => DataColumn(label: Text('$v')))
                      .toList(),
                  rows: rows
                      .map(
                        (row) => DataRow(
                          cells: (row as List)
                              .map((v) => DataCell(Text('$v')))
                              .toList(),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
          ),
        );
      case 'slider':
        final mathParameter = widget.definition.components.any(
          (c) => c['type'] == 'math_plot',
        );
        if (compact)
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: _NumericParameter(
              key: ValueKey('$id:$resetRevision'),
              value: (values[id] as num).toDouble(),
              min: (c['min'] as num).toDouble(),
              max: (c['max'] as num).toDouble(),
              label: c['label'] as String,
              enabled: ready && !busy,
              onChanged: (value) => update(id, value),
              onValidityChanged: (valid) => setState(() {
                if (valid) {
                  invalidFields.remove(id);
                } else {
                  invalidFields.add(id);
                }
              }),
            ),
          );
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      c['label'] as String,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                  Text(
                    _number(values[id] as num),
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ],
              ),
              SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 4,
                  activeTrackColor: StudyPalette.of(context).textBlack,
                  inactiveTrackColor: StudyPalette.of(context).borderGray,
                  thumbColor: StudyPalette.of(context).textBlack,
                  overlayColor: Colors.black12,
                ),
                child: Slider(
                  value: (values[id] as num).toDouble(),
                  min: (c['min'] as num).toDouble(),
                  max: (c['max'] as num).toDouble(),
                  divisions:
                      mathParameter &&
                          (c['max'] as num) - (c['min'] as num) <= 20
                      ? (((c['max'] as num) - (c['min'] as num)) * 10)
                            .round()
                            .clamp(1, 200)
                      : null,
                  onChanged: ready && !busy ? (v) => update(id, v) : null,
                ),
              ),
              if (mathParameter) ...[
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(_number(c['min'] as num)),
                    Text(_number(c['max'] as num)),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  children: [
                    for (final preset
                        in (<num>{c['min'] as num, -1, 0, 1, c['max'] as num}
                            .where(
                              (v) =>
                                  v >= (c['min'] as num) &&
                                  v <= (c['max'] as num),
                            )
                            .toList()
                          ..sort()))
                      ChoiceChip(
                        label: Text(_number(preset)),
                        labelStyle: Theme.of(context).textTheme.bodySmall,
                        selected: values[id] == preset,
                        onSelected: ready && !busy
                            ? (_) => update(id, preset.toDouble())
                            : null,
                      ),
                  ],
                ),
              ],
            ],
          ),
        );
      case 'select':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: DropdownButtonFormField<String>(
            key: ValueKey('$id:$ready:${values[id]}'),
            initialValue: values[id] as String,
            isExpanded: true,
            decoration: _fieldDecoration(c['label'] as String).copyWith(
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 10,
              ),
            ),
            items: (c['options'] as List)
                .map(
                  (v) => DropdownMenuItem(
                    value: '$v',
                    child: Text(v == '*' ? '全部' : '$v'),
                  ),
                )
                .toList(),
            onChanged: ready && !busy ? (v) => update(id, v) : null,
          ),
        );
      case 'input':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: TextFormField(
            key: ValueKey('$id:$ready:$resetRevision'),
            initialValue: values[id] as String,
            enabled: ready && !busy,
            maxLength: 4000,
            decoration: _fieldDecoration(c['label'] as String)
                .copyWith(counterText: ''),
            onChanged: (v) => update(id, v),
          ),
        );
      case 'calculation':
        num evaluate(dynamic e) {
          if (e is num) return e;
          if (e.containsKey('parameter')) return values[e['parameter']] as num;
          final a = evaluate(e['args'][0]), b = evaluate(e['args'][1]);
          return switch (e['op']) {
            'add' => a + b,
            'subtract' => a - b,
            'multiply' => a * b,
            'divide' => a / b,
            'power' => math.pow(a, b),
            'min' => math.min(a, b),
            'max' => math.max(a, b),
            _ => double.nan,
          };
        }
        final result = c.containsKey('expression')
            ? evaluate(c['expression'])
            : (c['a'] as num) * (values[c['parameter']] as num) +
                  (c['b'] as num);
        return Container(
          margin: const EdgeInsets.symmetric(vertical: 10),
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: panelColor,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                c['label'] as String,
                style: TextStyle(
                  fontSize: 13,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                result.isFinite ? _number(result) : '无法计算：检查参数或除数',
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w700,
                  height: 1.25,
                ),
              ),
            ],
          ),
        );
      case 'button':
        final button = FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: StudyPalette.of(context).textBlack,
            foregroundColor: StudyPalette.of(context).onAccent,
            minimumSize: const Size(48, 44),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(8),
            ),
          ),
          onPressed: ready && !busy && invalidFields.isEmpty
              ? () => unawaited(save(id))
              : null,
          child: Text(
            busy ? '正在提交…' : c['label'] as String,
            style: TextStyle(
              color: ready && !busy && invalidFields.isEmpty
                  ? StudyPalette.of(context).onAccent
                  : StudyPalette.of(context).textGray,
            ),
          ),
        );
        final reset =
            widget.definition.components
                    .where((c) => c['type'] == 'button')
                    .length ==
                1 &&
            widget.definition.components.any(
              (c) => ['slider', 'input', 'select'].contains(c['type']),
            );
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: reset
              ? LayoutBuilder(
                  builder: (context, bounds) {
                    final resetButton = OutlinedButton(
                      onPressed: ready && !busy
                          ? () {
                              debounce?.cancel();
                              setState(() {
                                values = widget.definition.defaults;
                                edits++;
                                dirty = true;
                                submitted = false;
                                invalidFields.clear();
                                resetRevision++;
                              });
                              unawaited(save(null));
                            }
                          : null,
                      child: const Text('恢复默认'),
                    );
                    if (bounds.maxWidth < 260 ||
                        MediaQuery.textScalerOf(context).scale(14) > 20)
                      return Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          button,
                          const SizedBox(height: 8),
                          resetButton,
                        ],
                      );
                    return Row(
                      children: [
                        Expanded(child: button),
                        const SizedBox(width: 10),
                        Expanded(child: resetButton),
                      ],
                    );
                  },
                )
              : button,
        );
      case 'line_chart':
      case 'bar_chart':
      case 'scatter_chart':
        if (widget.definition.version >= 4)
          return SurfaceChartView(
            key: ValueKey(c['id']),
            definition: c,
            factor: c.containsKey('scale')
                ? (values[c['scale']] as num).toDouble()
                : 1,
            selected: c['selectable'] == true ? values[id] as int : -1,
            onSelect: c['selectable'] == true && ready && !busy
                ? (index) => update(id, index)
                : null,
          );
        final chartHeight = compact ? 180.0 : 220.0;
        final factor = c.containsKey('scale')
            ? (values[c['scale']] as num).toDouble()
            : 1.0;
        final points = (c['points'] as List)
            .map(
              (p) => Offset(
                (p[0] as num).toDouble(),
                (p[1] as num).toDouble() * factor,
              ),
            )
            .toList();
        return Column(
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                c['yLabel'] as String? ?? '',
                style: TextStyle(
                  fontSize: 12,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
            ),
            SizedBox(
              height: chartHeight,
              width: double.infinity,
              child: LayoutBuilder(
                builder: (context, bounds) => Semantics(
                  label: '交互图表，${points.length}个数据点',
                  child: GestureDetector(
                    onTapDown: c['selectable'] == true && ready && !busy
                        ? (event) {
                            final painter = _ChartPainter(
                              points,
                              c['type'] as String,
                              StudyPalette.of(context).chartBlue,
                              palette: StudyPalette.of(context),
                              xLabel: c['xLabel'] as String? ?? '',
                            );
                            final size = Size(bounds.maxWidth, chartHeight);
                            var nearest = 0;
                            var distance = double.infinity;
                            for (var i = 0; i < points.length; i++) {
                              final d =
                                  (painter.position(points[i], size) -
                                          event.localPosition)
                                      .distanceSquared;
                              if (d < distance) {
                                nearest = i;
                                distance = d;
                              }
                            }
                            update(id, nearest);
                          }
                        : null,
                    child: CustomPaint(
                      painter: _ChartPainter(
                        points,
                        c['type'] as String,
                        StudyPalette.of(context).chartBlue,
                        palette: StudyPalette.of(context),
                        xLabel: c['xLabel'] as String? ?? '',
                        selected: c['selectable'] == true
                            ? values[id] as int
                            : -1,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            if (c['selectable'] == true && (values[id] as int) >= 0)
              Text(
                '选中数据点 ${values[id] + 1}: ${points[values[id] as int].dx}, ${points[values[id] as int].dy}',
              ),
            if (c['selectable'] == true && (values[id] as int) < 0)
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '点选图表查看数值',
                  style: TextStyle(
                    fontSize: 12,
                    color: StudyPalette.of(context).textGray,
                  ),
                ),
              ),
            ExpansionTile(
              dense: true,
              minTileHeight: 32,
              tilePadding: EdgeInsets.zero,
              title: Text(
                '查看图表数据',
                style: TextStyle(
                  fontSize: 12,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
              children: [
                SelectableText(
                  points.map((p) => '${p.dx}: ${p.dy}').join('\n'),
                ),
              ],
            ),
          ],
        );
      default:
        return const Text('不支持的组件');
    }
  }

  InputDecoration _fieldDecoration(String label) => InputDecoration(
    labelText: label,
    labelStyle: TextStyle(
      fontSize: 13,
      color: StudyPalette.of(context).textGray,
    ),
    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: BorderSide(color: StudyPalette.of(context).borderGray),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: BorderSide(color: StudyPalette.of(context).borderGray),
    ),
  );
}

String _number(num value) =>
    value.abs() >= 1e7 || (value != 0 && value.abs() < 1e-4)
    ? value.toStringAsExponential(2)
    : value == value.roundToDouble()
    ? value.toStringAsFixed(0)
    : value
          .toStringAsFixed(2)
          .replaceFirst(RegExp(r'0+$'), '')
          .replaceFirst(RegExp(r'\.$'), '');

class _NumericParameter extends StatefulWidget {
  final double value, min, max;
  final String label;
  final bool enabled;
  final ValueChanged<double> onChanged;
  final ValueChanged<bool> onValidityChanged;
  const _NumericParameter({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    required this.label,
    required this.enabled,
    required this.onChanged,
    required this.onValidityChanged,
  });
  @override
  State<_NumericParameter> createState() => _NumericParameterState();
}

class _NumericParameterState extends State<_NumericParameter> {
  late final TextEditingController controller = TextEditingController(
    text: _number(widget.value),
  );
  String? error;
  @override
  void didUpdateWidget(covariant _NumericParameter oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (double.tryParse(controller.text) != widget.value &&
        widget.value != oldWidget.value)
      controller.text = _number(widget.value);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
    controller: controller,
    enabled: widget.enabled,
    keyboardType: const TextInputType.numberWithOptions(
      decimal: true,
      signed: true,
    ),
    style: TextStyle(fontSize: 18, color: StudyPalette.of(context).textBlack),
    decoration: InputDecoration(
      labelText: widget.label,
      errorText: error,
      labelStyle: TextStyle(
        fontSize: 12,
        color: StudyPalette.of(context).textGray,
      ),
      contentPadding: const EdgeInsets.all(12),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: BorderSide(color: StudyPalette.of(context).borderGray),
      ),
    ),
    onChanged: (text) {
      final number = double.tryParse(text);
      if (number == null ||
          !number.isFinite ||
          number < widget.min ||
          number > widget.max) {
        widget.onValidityChanged(false);
        setState(() => error = '${_number(widget.min)}–${_number(widget.max)}');
      } else {
        widget.onValidityChanged(true);
        setState(() => error = null);
        widget.onChanged(number);
      }
    },
  );
}

class _ChartPainter extends CustomPainter {
  final StudyPalette palette;
  final List<Offset> points;
  final String type;
  final Color color;
  final int selected;
  final String xLabel;
  _ChartPainter(
    this.points,
    this.type,
    this.color, {
    required this.palette,
    this.selected = -1,
    this.xLabel = '',
  });
  double get minX => points.map((p) => p.dx).reduce(math.min);
  double get maxX => points.map((p) => p.dx).reduce(math.max);
  double get minY =>
      math.min(0, points.map((p) => p.dy).reduce(math.min)).toDouble();
  double get maxY =>
      math.max(0, points.map((p) => p.dy).reduce(math.max)).toDouble();
  Rect plot(Size size) =>
      Rect.fromLTRB(42, 28, math.max(43, size.width - 18), size.height - 34);
  Offset position(Offset p, Size size) {
    final rect = plot(size);
    final inset = type == 'bar_chart'
        ? math.min(12.0, rect.width / points.length * 0.3)
        : 0.0;
    return Offset(
      maxX == minX
          ? rect.center.dx
          : rect.left +
                inset +
                (p.dx - minX) / (maxX - minX) * (rect.width - 2 * inset),
      rect.bottom -
          (p.dy - minY) / (maxY == minY ? 1 : maxY - minY) * rect.height,
    );
  }

  void label(
    Canvas canvas,
    String text,
    Offset origin, {
    bool centered = false,
  }) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(fontSize: 10, color: palette.textGray),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, origin - Offset(centered ? painter.width / 2 : 0, 0));
  }

  @override
  void paint(Canvas canvas, Size size) {
    canvas.clipRect(Offset.zero & size);
    final rect = plot(size);
    final grid = Paint()
      ..color = palette.borderGray
      ..strokeWidth = 0.6;
    for (var i = 0; i <= 4; i++) {
      final y = rect.bottom - rect.height * i / 4;
      canvas.drawLine(Offset(rect.left, y), Offset(rect.right, y), grid);
      label(canvas, _number(minY + (maxY - minY) * i / 4), Offset(0, y - 6));
    }
    final ticks = points.length <= 6
        ? points
        : List.generate(
            6,
            (i) => points[(i * (points.length - 1) / 5).round()],
          );
    for (final p in ticks) {
      final x = position(p, size).dx;
      canvas.drawLine(Offset(x, rect.top), Offset(x, rect.bottom), grid);
      label(canvas, _number(p.dx), Offset(x, rect.bottom + 10), centered: true);
    }
    if (xLabel.isNotEmpty)
      label(
        canvas,
        xLabel,
        Offset(rect.center.dx, size.height - 12),
        centered: true,
      );
    final pen = Paint()
      ..color = color
      ..strokeWidth = 2;
    if (type == 'line_chart') {
      final path = Path()
        ..moveTo(
          position(points.first, size).dx,
          position(points.first, size).dy,
        );
      for (final p in points.skip(1)) {
        final q = position(p, size);
        path.lineTo(q.dx, q.dy);
      }
      final fill = Path.from(path)
        ..lineTo(position(points.last, size).dx, rect.bottom)
        ..lineTo(position(points.first, size).dx, rect.bottom)
        ..close();
      canvas.drawPath(fill, Paint()..color = color.withValues(alpha: 0.08));
      canvas.drawPath(path, pen..style = PaintingStyle.stroke);
      for (final p in points) {
        canvas.drawCircle(position(p, size), 3, Paint()..color = color);
      }
    } else {
      final baseline = position(Offset(minX, 0), size).dy;
      for (final p in points) {
        final q = position(p, size);
        if (type == 'bar_chart') {
          final width = math.min(24.0, rect.width / points.length * 0.6);
          canvas.drawRRect(
            RRect.fromRectAndRadius(
              Rect.fromLTRB(
                q.dx - width / 2,
                math.min(q.dy, baseline),
                q.dx + width / 2,
                math.max(q.dy, baseline),
              ),
              const Radius.circular(3),
            ),
            pen,
          );
        } else {
          canvas.drawCircle(q, 4, pen);
        }
      }
    }
    if (selected >= 0 && selected < points.length) {
      final q = position(points[selected], size);
      canvas.drawLine(
        Offset(q.dx, rect.top),
        Offset(q.dx, rect.bottom),
        Paint()
          ..color = color.withValues(alpha: 0.3)
          ..strokeWidth = 1,
      );
      canvas.drawCircle(q, 7, Paint()..color = palette.bgWhite);
      canvas.drawCircle(q, 5, Paint()..color = color);
      final text =
          '${_number(points[selected].dx)} · ${_number(points[selected].dy)}';
      final tp = TextPainter(
        text: TextSpan(
          text: text,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: palette.textBlack,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      final width = tp.width + 20;
      final left = (q.dx - width / 2)
          .clamp(rect.left, math.max(rect.left, size.width - width - 2))
          .toDouble();
      final top = math.max(0.0, q.dy - 42);
      final box = Rect.fromLTWH(left, top, width, 30);
      canvas.drawRRect(
        RRect.fromRectAndRadius(box, const Radius.circular(8)),
        Paint()..color = palette.bgWhite,
      );
      canvas.drawRRect(
        RRect.fromRectAndRadius(box, const Radius.circular(8)),
        Paint()
          ..color = palette.borderGray
          ..style = PaintingStyle.stroke,
      );
      tp.paint(canvas, Offset(left + 10, top + 7));
    }
  }

  @override
  bool shouldRepaint(_ChartPainter oldDelegate) =>
      oldDelegate.points != points ||
      oldDelegate.type != type ||
      oldDelegate.color != color ||
      oldDelegate.selected != selected ||
      oldDelegate.xLabel != xLabel ||
      oldDelegate.palette.isDark != palette.isDark;
}
