import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:flutter/services.dart';
import 'package:flutter_math_fork/flutter_math.dart';

import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import 'enhanced_image_view.dart';
import 'source_links.dart';
import 'surface_semantics.dart';

/// Renders mixed Markdown and LaTeX content with safety fallbacks:
/// 1. Auto-detects block ($$...$$, \[...\]) and inline ($...$, \(...\)) math.
/// 2. If LaTeX expression is unclosed or malformed (e.g. streaming halfway), falls back gracefully to raw text.
/// 3. Long equations and tables wrap in horizontal SingleChildScrollView to avoid clipping.
/// 4. Provides a one-click "Copy Raw Text" action on math blocks.
class MathMarkdownView extends StatefulWidget {
  final String content;
  final TextStyle? style;
  final bool selectable;
  final bool streaming;
  final void Function(String)? onAskSelection;

  const MathMarkdownView({
    super.key,
    required this.content,
    this.style,
    this.selectable = true,
    this.streaming = false,
    this.onAskSelection,
  });

  @override
  State<MathMarkdownView> createState() => _MathMarkdownViewState();
}

class _MathMarkdownViewState extends State<MathMarkdownView> {
  String selectedText = '';
  Map<String, Widget> _blocks = {};
  ThemeData? _theme;
  TextStyle? _style;
  bool? _selectable;
  bool? _streaming;
  bool? _asksSelection;
  String get content => widget.content;
  TextStyle? get style => widget.style;
  bool get selectable => widget.selectable;

  @override
  Widget build(BuildContext context) {
    if (content.isEmpty) return const SizedBox.shrink();

    final textStyle = style ?? Theme.of(context).textTheme.bodyLarge!;
    final blocks = _splitFencedContent(content);
    final theme = Theme.of(context);
    if (_theme != theme ||
        _style != textStyle ||
        _selectable != selectable ||
        _streaming != widget.streaming ||
        _asksSelection != (widget.onAskSelection != null))
      _blocks.clear();
    _theme = theme;
    _style = textStyle;
    _selectable = selectable;
    _streaming = widget.streaming;
    _asksSelection = widget.onAskSelection != null;
    final next = <String, Widget>{};
    final children = <Widget>[];
    for (var index = 0; index < blocks.length; index++) {
      final block = blocks[index];
      final key = '$index:${block.type}:${block.language}:${block.text}';
      final child = _blocks[key] ?? _renderBlock(context, block, textStyle);
      next[key] = child;
      children.add(child);
    }
    _blocks =
        next; // Retain only the current document, never all growing tails.

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: children,
    );
  }

  Widget _renderBlock(
    BuildContext context,
    _ContentBlock block,
    TextStyle baseStyle,
  ) {
    switch (block.type) {
      case _BlockType.code:
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: SurfaceCodeBlock(
            component: {
              'code': block.text,
              'language': block.language ?? 'text',
            },
          ),
        );
      case _BlockType.displayMath:
        return _renderDisplayMath(context, block.text, baseStyle);
      case _BlockType.text:
        return _renderTextBlock(context, block.text, baseStyle);
    }
  }

  Widget _renderDisplayMath(
    BuildContext context,
    String rawMath,
    TextStyle baseStyle,
  ) {
    // Strip delimiter
    String mathExp = rawMath.trim();
    if (mathExp.startsWith(r'$$') &&
        mathExp.endsWith(r'$$') &&
        mathExp.length >= 4) {
      mathExp = mathExp.substring(2, mathExp.length - 2).trim();
    } else if (mathExp.startsWith(r'\[') &&
        mathExp.endsWith(r'\]') &&
        mathExp.length >= 4) {
      mathExp = mathExp.substring(2, mathExp.length - 2).trim();
    }

    Widget mathWidget;
    try {
      mathWidget = Math.tex(
        mathExp,
        mathStyle: MathStyle.display,
        textStyle: baseStyle.copyWith(
          fontSize: (baseStyle.fontSize ?? 16) * 1.05,
        ),
        onErrorFallback: (err) {
          // Fallback to clean mono raw text with error hint instead of dropping message
          return _renderMathFallback(context, rawMath, baseStyle, err.message);
        },
      );
    } catch (e) {
      mathWidget = _renderMathFallback(
        context,
        rawMath,
        baseStyle,
        e.toString(),
      );
    }

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: Theme.of(context).brightness == Brightness.dark
            ? StudyPalette.of(context).darkSurface
            : StudyPalette.of(context).bubbleGray,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).brightness == Brightness.dark
              ? StudyPalette.of(context).borderGray
              : StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      child: Stack(
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 28),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              physics: const BouncingScrollPhysics(),
              child: Padding(
                padding: const EdgeInsets.only(right: 28),
                child: mathWidget,
              ),
            ),
          ),
          Positioned(
            right: 0,
            top: 0,
            child: InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () {
                Clipboard.setData(ClipboardData(text: mathExp));
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('已复制 LaTeX 表达式'),
                    duration: Duration(milliseconds: 1200),
                  ),
                );
              },
              child: Padding(
                padding: const EdgeInsets.all(4),
                child: Icon(
                  Icons.copy_rounded,
                  size: 16,
                  color: StudyPalette.of(context).textBlack
                      .withValues(alpha: 0.7),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _renderMathFallback(
    BuildContext context,
    String raw,
    TextStyle baseStyle,
    String? error,
  ) {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bubbleGray.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          SelectableText(
            raw,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: (baseStyle.fontSize ?? 16) * 0.9,
              color: baseStyle.color,
            ),
          ),
          if (!widget.streaming && error != null && error.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                '公式解析提示: $error',
                style: TextStyle(
                  fontSize: 11,
                  color: StudyPalette.of(context).textGray,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _renderTextBlock(
    BuildContext context,
    String text,
    TextStyle baseStyle,
  ) {
    final markdown = MarkdownBody(
      data: text,
      onTapLink: (_, href, _) {
        if (href != null) openSourceLink(context, href);
      },
      imageBuilder: (uri, title, alt) => EnhancedImageView(
        part: MessagePartDto(
          type: 'image',
          url: uri.toString(),
          text: (alt?.isNotEmpty ?? false) ? alt : title,
        ),
        messageId: '',
      ),
      selectable: selectable && widget.onAskSelection == null,
      extensionSet: md.ExtensionSet.gitHubFlavored,
      inlineSyntaxes: [_MathSyntax()],
      builders: {'math': _MathBuilder(baseStyle)},
      styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
        p: baseStyle,
        h1: baseStyle.copyWith(
          fontSize: 24,
          fontWeight: FontWeight.w700,
          height: 1.4,
        ),
        h2: baseStyle.copyWith(
          fontSize: 21,
          fontWeight: FontWeight.w700,
          height: 1.4,
        ),
        h3: baseStyle.copyWith(
          fontSize: 18,
          fontWeight: FontWeight.w700,
          height: 1.5,
        ),
        strong: baseStyle.copyWith(fontWeight: FontWeight.w700),
        blockSpacing: 16,
        listIndent: 22,
        tableBorder: TableBorder.all(
          color: StudyPalette.of(context).borderGray,
        ),
        tableCellsPadding: const EdgeInsets.symmetric(
          horizontal: 12,
          vertical: 10,
        ),
        tableHead: baseStyle.copyWith(fontWeight: FontWeight.w600),
        tableBody: baseStyle.copyWith(fontSize: 14),
        code: baseStyle.copyWith(
          fontFamily: 'monospace',
          fontSize: 14,
          backgroundColor: StudyPalette.of(context).bubbleGray,
        ),
        codeblockPadding: const EdgeInsets.all(16),
        codeblockDecoration: BoxDecoration(
          color: StudyPalette.of(context).bubbleGray,
          borderRadius: BorderRadius.circular(12),
        ),
      ),
    );
    if (widget.onAskSelection == null) return markdown;
    return SelectionArea(
      onSelectionChanged: (selection) =>
          selectedText = selection?.plainText ?? '',
      contextMenuBuilder: (context, region) =>
          AdaptiveTextSelectionToolbar.buttonItems(
            anchors: region.contextMenuAnchors,
            buttonItems: [
              ...region.contextMenuButtonItems,
              if (selectedText.trim().isNotEmpty)
                ContextMenuButtonItem(
                  label: '解释选中内容',
                  onPressed: () {
                    final selected = selectedText;
                    region.hideToolbar();
                    widget.onAskSelection?.call(selected);
                  },
                ),
            ],
          ),
      child: markdown,
    );
  }

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
      final dependent = RegExp(
        r'^\s*(?:\[.*\]:|[-*+]>?|\d+[.)]|>|\|)|\]\[',
        multiLine: true,
      ).hasMatch(text);
      for (final block in _splitContentIntoBlocks(raw)) {
        if (block.type == _BlockType.text && !dependent) {
          for (final paragraph in block.text.split(RegExp(r'\n\s*\n'))) {
            if (paragraph.trim().isNotEmpty)
              result.add(_ContentBlock(_BlockType.text, paragraph));
          }
        } else {
          result.add(block);
        }
      }
      prose.clear();
    }

    for (final line in lines) {
      final fence = RegExp(r'^ {0,3}(`{3,}|~{3,})(.*)$').firstMatch(line);
      if (marker == null &&
          fence != null &&
          !(fence.group(1)!.startsWith('`') && fence.group(2)!.contains('`'))) {
        flushProse();
        marker = fence.group(1)![0];
        markerLength = fence.group(1)!.length;
        language = fence.group(2)!.trim().split(RegExp(r'\s+')).first;
        if (language.isEmpty) language = 'text';
      } else if (marker != null &&
          fence != null &&
          fence.group(1)![0] == marker &&
          fence.group(1)!.length >= markerLength &&
          fence.group(2)!.trim().isEmpty) {
        result.add(
          _ContentBlock(
            _BlockType.code,
            code.toString().replaceFirst(RegExp(r'\n$'), ''),
            language,
          ),
        );
        code.clear();
        marker = null;
      } else if (marker != null) {
        code.writeln(line);
      } else {
        prose.writeln(line);
      }
    }
    if (marker != null)
      result.add(
        _ContentBlock(
          _BlockType.code,
          code.toString().replaceFirst(RegExp(r'\n$'), ''),
          language,
        ),
      );
    flushProse();
    return result;
  }

  List<_ContentBlock> _splitContentIntoBlocks(String text) {
    final List<_ContentBlock> blocks = [];
    final blockMathPattern = RegExp(
      r'(?<!\\)\$\$[\s\S]*?(?<!\\)\$\$|(?<!\\)\\\[[\s\S]*?(?<!\\)\\\]',
    );

    final codeRanges = RegExp(r'```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]+`')
        .allMatches(text)
        .toList();
    int lastEnd = 0;
    for (final match in blockMathPattern.allMatches(text)) {
      if (codeRanges.any(
        (code) => match.start >= code.start && match.start < code.end,
      ))
        continue;
      if (match.start > lastEnd) {
        final textChunk = text.substring(lastEnd, match.start).trim();
        if (textChunk.isNotEmpty) {
          blocks.add(_ContentBlock(_BlockType.text, textChunk));
        }
      }

      blocks.add(_ContentBlock(_BlockType.displayMath, match.group(0)!));
      lastEnd = match.end;
    }

    if (lastEnd < text.length) {
      final remaining = text.substring(lastEnd).trim();
      if (remaining.isNotEmpty) {
        blocks.add(_ContentBlock(_BlockType.text, remaining));
      }
    }

    return blocks;
  }
}

enum _BlockType { text, displayMath, code }

class _ContentBlock {
  final _BlockType type;
  final String text;

  final String? language;
  _ContentBlock(this.type, this.text, [this.language]);
}

class _MathSyntax extends md.InlineSyntax {
  _MathSyntax()
    : super(r'(?<!\\)\$([^\$\n]+?)(?<!\\)\$|(?<!\\)\\\(([^\n]+?)(?<!\\)\\\)');
  @override
  bool onMatch(md.InlineParser parser, Match match) {
    parser.addNode(
      md.Element.text('math', (match.group(1) ?? match.group(2) ?? '').trim()),
    );
    return true;
  }
}

class _MathBuilder extends MarkdownElementBuilder {
  final TextStyle style;
  _MathBuilder(this.style);
  @override
  Widget? visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    return Math.tex(
      element.textContent,
      mathStyle: MathStyle.text,
      textStyle: style,
      onErrorFallback: (_) => Text(
        element.textContent,
        style: style.copyWith(fontFamily: 'monospace'),
      ),
    );
  }
}
