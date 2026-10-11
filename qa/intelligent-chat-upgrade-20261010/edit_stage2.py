from pathlib import Path
r=Path(__file__).resolve().parents[2]
def edit(path, old, new):
 p=r/path;s=p.read_text(encoding='utf-8');assert old in s,path;p.write_text(s.replace(old,new,1),encoding='utf-8')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''                        "materialPath" ->''',
'''                        "resumeChat" -> {
                            val conv = ownedConversation(owner, args["conversationId"] as? String ?: error("缺少会话"))
                            require(conv.currentMessages.lastOrNull()?.id.toString() == args["messageId"]) { "消息已改变，请刷新" }
                            require(conv.currentMessages.last().parts.filterIsInstance<UIMessagePart.Tool>().none { it.executionState in setOf(me.rerere.ai.ui.ToolExecutionState.INTERRUPTED, me.rerere.ai.ui.ToolExecutionState.SKIPPED_UNKNOWN, me.rerere.ai.ui.ToolExecutionState.RUNNING) }) { "请先核对工具执行结果" }
                            requireProfile(owner, epoch)
                            chatService.resumeInterruptedGeneration(conv.id)
                            null
                        }
                        "materialPath" ->''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
"import '../bridge/chat_display_patch.dart';", "import '../bridge/chat_display_patch.dart';\nimport '../widgets/message_phase.dart';\nimport '../providers/frontend_feature_providers.dart';")
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''              _buildInputArea(
''', '''              if (_sending) const Padding(padding: EdgeInsets.all(6), child: Text('正在准备回复…')),
              _buildInputArea(
''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''    return Align(
      alignment: Alignment.topCenter,''',
'''    return MessageArrival(
      animate: message.generationState == 'RUNNING' && !ref.read(reduceMotionSettingProvider) && !MediaQuery.disableAnimationsOf(context),
      child: Align(
      alignment: Alignment.topCenter,''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''    );
  }

  Widget _buildUserMessage''', '''    ));
  }

  Widget _buildUserMessage''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''        const SizedBox(height: 10),
        Wrap(''',
'''        MessagePhase(message: message,
          onResume: () async {
            await FrontendFeatures.fromWidget<void>(ref, 'resumeChat', {'conversationId': ref.read(activeConversationIdProvider), 'messageId': message.id});
            await ref.read(activeConversationDetailProvider.notifier).refresh();
          },
          onRetry: () => ref.read(activeConversationDetailProvider.notifier).retryMessage(node.nodeId)),
        const SizedBox(height: 10),
        Wrap(''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''            _buildActionButton(
              context,
              icon: Icons.refresh_rounded,''',
'''            if (message.generationState != 'RUNNING' && !hasUncertainTools(message)) _buildActionButton(
              context,
              icon: Icons.refresh_rounded,''')
edit('client/flutter_ui/lib/widgets/enhanced_cards.dart',
'''      'render_interactive_ui' => '生成交互界面',''',
'''      'render_interactive_ui' || 'create_interactive_surface' => '生成交互界面',
      'render_math_plot' => '绘制数学图形',
      'display_image' => '展示图片',''')
edit('client/flutter_ui/lib/widgets/enhanced_cards.dart',
'''      'RUNNING' => isSearch ? '正在搜索' : '处理中',
      'COMPLETED' => '已完成',''',
'''      'RUNNING' => isSearch ? '正在搜索' : '正在$toolLabel',
      'COMPLETED' => switch (widget.part.toolName) {
        'render_interactive_ui' || 'create_interactive_surface' || 'render_math_plot' => '已生成，可展开查看',
        'create_material_file' || 'edit_material_file' => '文件处理完成',
        'read_material_file' => '已读取文件',
        _ => '已完成',
      },''')
edit('client/flutter_ui/lib/widgets/enhanced_cards.dart',
'''                      statusLabel,
''', '''                      statusLabel,
''') # Keep status text reachable by accessibility and existing regression finders.
