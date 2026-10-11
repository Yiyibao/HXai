from pathlib import Path
p=Path(__file__).resolve().parents[2]/'client/flutter_ui/lib/widgets/math_markdown.dart'
s=p.read_text(encoding='utf-8')
s=s.replace("import 'source_links.dart';", "import 'source_links.dart';\nimport 'surface_semantics.dart';")
s=s.replace('class MathMarkdownView extends StatelessWidget {','class MathMarkdownView extends StatefulWidget {')
s=s.replace('  final bool selectable;', '  final bool selectable;\n  final bool streaming;')
s=s.replace('    this.selectable = true,','    this.selectable = true,\n    this.streaming = false,')
s=s.replace('''  @override
  Widget build(BuildContext context) {
    if (content.isEmpty)''','''  @override
  State<MathMarkdownView> createState() => _MathMarkdownViewState();
}

class _MathMarkdownViewState extends State<MathMarkdownView> {
  Map<String, Widget> _blocks = {};
  ThemeData? _theme;
  TextStyle? _style;
  bool? _selectable;
  String get content => widget.content;
  TextStyle? get style => widget.style;
  bool get selectable => widget.selectable;

  @override
  Widget build(BuildContext context) {
    if (content.isEmpty)''')
s=s.replace('''    final blocks = _splitContentIntoBlocks(content);

    return Column(''','''    final blocks = _splitFencedContent(content);
    final theme = Theme.of(context);
    if (_theme != theme || _style != textStyle || _selectable != selectable) _blocks.clear();
    _theme = theme;
    _style = textStyle;
    _selectable = selectable;
    final next = <String, Widget>{};
    final children = <Widget>[];
    for (var index = 0; index < blocks.length; index++) {
      final block = blocks[index];
      final key = '$index:${block.type}:${block.language}:${block.text}';
      final child = _blocks[key] ?? _renderBlock(context, block, textStyle);
      next[key] = child;
      children.add(child);
    }
    _blocks = next; // Retain only the current document, never all growing tails.

    return Column(''')
s=s.replace('''      children: blocks
          .map((block) => _renderBlock(context, block, textStyle))
          .toList(),''','''      children: children,''')
s=s.replace('''    switch (block.type) {
      case _BlockType.displayMath:''','''    switch (block.type) {
      case _BlockType.code:
        return Padding(padding: const EdgeInsets.symmetric(vertical: 8),
          child: SurfaceCodeBlock(component: {'code': block.text, 'language': block.language ?? 'text'}));
      case _BlockType.displayMath:''')
s=s.replace('''          if (error != null && error.isNotEmpty)''','''          if (!widget.streaming && error != null && error.isNotEmpty)''')
mark='  List<_ContentBlock> _splitContentIntoBlocks(String text) {'
code=r'''
  List<_ContentBlock> _splitFencedContent(String text) {
    final result = <_ContentBlock>[];
    final lines = text.split('\n');
    final prose = StringBuffer();
    String? marker;
    int markerLength = 0;
    String language = 'text';
    final code = StringBuffer();
    void flushProse() {
      if (prose.isEmpty) return;
      final raw = prose.toString();
      // Reference links, lists and tables may depend on surrounding blocks.
      final dependent = RegExp(r'(?m)^\s*(?:\[.*\]:|[-*+]>?|\d+[.)]|>|\|)|\]\[').hasMatch(text);
      for (final block in _splitContentIntoBlocks(raw)) {
        if (block.type == _BlockType.text && !dependent) {
          for (final paragraph in block.text.split(RegExp(r'\n\s*\n'))) {
            if (paragraph.trim().isNotEmpty) result.add(_ContentBlock(_BlockType.text, paragraph));
          }
        } else { result.add(block); }
      }
      prose.clear();
    }
    for (final line in lines) {
      final fence = RegExp(r'^ {0,3}(`{3,}|~{3,})(.*)$').firstMatch(line);
      if (marker == null && fence != null &&
          !(fence.group(1)!.startsWith('`') && fence.group(2)!.contains('`'))) {
        flushProse();
        marker = fence.group(1)![0];
        markerLength = fence.group(1)!.length;
        language = fence.group(2)!.trim().split(RegExp(r'\s+')).first;
        if (language.isEmpty) language = 'text';
      } else if (marker != null && fence != null && fence.group(1)![0] == marker &&
          fence.group(1)!.length >= markerLength && fence.group(2)!.trim().isEmpty) {
        result.add(_ContentBlock(_BlockType.code, code.toString().replaceFirst(RegExp(r'\n$'), ''), language));
        code.clear();
        marker = null;
      } else if (marker != null) { code.writeln(line); }
      else { prose.writeln(line); }
    }
    if (marker != null) result.add(_ContentBlock(_BlockType.code,
      code.toString().replaceFirst(RegExp(r'\n$'), ''), language));
    flushProse();
    return result;
  }

'''
# Raw source above deliberately uses escaped backslashes for its Dart literals.
code=code.replace('\\\\','\\')
s=s.replace(mark,code+mark)
s=s.replace('enum _BlockType { text, displayMath }','enum _BlockType { text, displayMath, code }')
s=s.replace('  _ContentBlock(this.type, this.text);','  final String? language;\n  _ContentBlock(this.type, this.text, [this.language]);')
p.write_text(s,encoding='utf-8')
