from pathlib import Path
r=Path(__file__).resolve().parents[2]
def edit(path, old, new):
 p=r/path;s=p.read_text(encoding='utf-8');assert old in s,path;p.write_text(s.replace(old,new,1),encoding='utf-8')
edit('client/app/src/main/java/me/rerere/rikkahub/feature/interactive/InteractiveSurface.kt',
'''name == TOOL || name == CREATE_TOOL || name == MATH_TOOL''', '''name == TOOL || name == CREATE_TOOL || name == MATH_TOOL || name == "revise_interactive_surface"''')
edit('client/app/src/main/java/me/rerere/rikkahub/feature/interactive/InteractiveSurface.kt',
'''tool.toolName == TOOL || tool.toolName == MATH_TOOL''', '''tool.toolName == TOOL || tool.toolName == MATH_TOOL || tool.toolName == "revise_interactive_surface"''')
edit('client/app/src/main/java/me/rerere/rikkahub/feature/interactive/InteractiveSurface.kt',
'''put("definition", validate(raw))''', '''put("definition", validate(raw)); tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { runCatching { Json.parseToJsonElement(it.text).jsonObject["parent"] }.getOrNull() }?.let { put("parent", it) }''')
edit('client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt',
'''        allowRemoteDocuments: Boolean = false,''', '''        allowRemoteDocuments: Boolean = false,
        sourceConversation: me.rerere.rikkahub.data.model.Conversation? = null,''')
edit('client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt',
'''        add(displayImageTool())''', '''        sourceConversation?.let { source -> add(me.rerere.rikkahub.feature.interactive.surfaceRevisionTool {
            conversationRepository.getConversationById(source.id, profileId = profileId) ?: error("会话已不存在")
        }) }
        add(displayImageTool())''')
edit('client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt',
'''                    workspaceCwd = conversation.workspaceCwd,''', '''                    workspaceCwd = conversation.workspaceCwd,
                    sourceConversation = conversation,''')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''                        "resumeChat" ->''', '''                        "artifactVersions" -> {
                            val family = me.rerere.rikkahub.feature.material.MaterialVersions.family(materialFiles.list(owner), args["id"] as? String ?: error("缺少成果ID"))
                            family.map { mapOf("id" to it.id, "name" to it.name, "sha256" to it.sha256, "sourceId" to it.sourceId, "createdAt" to it.createdAt, "state" to it.state) }
                        }
                        "resumeChat" ->''')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
'''val text = "用户提交交互界面 ${surface.getValue("title").jsonPrimitive.content} 的操作：${button.getValue("label").jsonPrimitive.content}。\\n用户选择数据：$values"''',
'''val text = "用户提交交互界面 ${surface.getValue("title").jsonPrimitive.content} 的操作：${button.getValue("label").jsonPrimitive.content}。\\n成果引用 message_id=$messageId tool_call_id=$toolCallId expected_revision=$revision。\\n用户选择数据：$values"''')
edit('client/flutter_ui/lib/widgets/interactive_surface_message.dart',
'''${source.title}；界面 ID：${source.surfaceId}；当前筛选和参数：${jsonEncode(values())}''',
'''${source.title}；成果引用 message_id=${source.messageId} tool_call_id=${source.surfaceId} expected_revision=${source.revision}；当前筛选和参数：${jsonEncode(values())}''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''              'render_math_plot',
            ].contains(part.toolName)''', '''              'render_math_plot',
              'revise_interactive_surface',
            ].contains(part.toolName)''')
edit('client/flutter_ui/lib/widgets/enhanced_cards.dart',
'''      'render_math_plot' => '绘制数学图形',''', '''      'render_math_plot' => '绘制数学图形',
      'revise_interactive_surface' => '修改交互成果',''')
edit('client/flutter_ui/lib/pages/chat_page.dart',
'''        MessagePhase(''', '''        if (message.generationState == 'COMPLETED') Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Wrap(spacing: 8, children: [
            if (message.parts.any((p) => p.type == 'tool' && const ['render_interactive_ui', 'create_interactive_surface', 'render_math_plot', 'revise_interactive_surface'].contains(p.toolName)))
              ActionChip(label: const Text('基于当前成果修改'), onPressed: () { _textController.text = '请修改当前成果：'; _focusNode.requestFocus(); }),
            if (messageCitations(message).isNotEmpty)
              ActionChip(label: const Text('查看来源'), onPressed: () => CitationsSheet.show(context, citations: messageCitations(message))),
          ]),
        ),
        MessagePhase(''')
