from pathlib import Path
r=Path(__file__).resolve().parents[2]
p=r/'client/flutter_ui/lib/widgets/math_markdown.dart';s=p.read_text(encoding='utf-8')
s=s.replace('  final bool streaming;', '  final bool streaming;\n  final void Function(String)? onAskSelection;')
s=s.replace('    this.streaming = false,', '    this.streaming = false,\n    this.onAskSelection,')
s=s.replace('  Map<String, Widget> _blocks = {};', "  String selectedText = '';\n  Map<String, Widget> _blocks = {};")
s=s.replace('    return MarkdownBody(', '    final markdown = MarkdownBody(')
s=s.replace('      selectable: selectable,', '      selectable: selectable && widget.onAskSelection == null,')
marker='''  List<_ContentBlock> _splitFencedContent'''
idx=s.index(marker); end=s.rfind('    );\n  }',0,idx)
assert end>0
s=s[:end]+'''    );
    if (widget.onAskSelection == null) return markdown;
    return SelectionArea(
      onSelectionChanged: (selection) => selectedText = selection?.plainText ?? '',
      contextMenuBuilder: (context, region) => AdaptiveTextSelectionToolbar.buttonItems(
        anchors: region.contextMenuAnchors,
        buttonItems: [...region.contextMenuButtonItems,
          if (selectedText.trim().isNotEmpty) ContextMenuButtonItem(label: '解释选中内容', onPressed: () {
            final selected = selectedText;
            region.hideToolbar();
            widget.onAskSelection?.call(selected);
          }),
        ],
      ),
      child: markdown,
    );
  }'''+s[end+len('    );\n  }'):]
p.write_text(s,encoding='utf-8')
p=r/'client/flutter_ui/lib/pages/chat_page.dart';s=p.read_text(encoding='utf-8')
needle="          streaming: generationState == 'RUNNING',"
assert needle in s
s=s.replace(needle,needle+'''
          onAskSelection: (selected) {
            _textController.text = '请解释这条消息中的选中内容（消息 ID：$messageId）：\\n$selected';
            _focusNode.requestFocus();
          },''',1)
s=s.replace("_textController.text = '请修改当前成果：';", "_textController.text = _surfaceEditReference(message);")
idx=s.index('  Widget _buildActionButton(')
s=s[:idx]+'''  String _surfaceEditReference(MessageDto message) {
    for (final part in message.parts.reversed) {
      if (part.type != 'tool') continue;
      try {
        final output = jsonDecode(part.toolOutput ?? '') as Map;
        if (output['kind'] != 'interactive_surface' || output['revision'] is! String) continue;
        return '请修改当前成果（message_id=${message.id} tool_call_id=${part.toolCallId} expected_revision=${output['revision']}）：';
      } catch (_) {}
    }
    return '请修改这条回复（消息 ID：${message.id}）：';
  }

'''+s[idx:]
p.write_text(s,encoding='utf-8')
