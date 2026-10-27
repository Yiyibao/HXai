# A02 消息翻译来源合同 — 统一 Agent 增量 v1.24

来源为 `HXai_CHANGE_REVIEW/A-TRANSLATION-SOURCE-20261019/CHANGE_CONTRACT.md`，继承原 Pro F/FILE-10、v1.19 未映射旧墓碑保全及 v1.23 现有主链。原文不修改。

`ChatService.translateMessage` 从当前 session 固定 owner/lifecycle/backgroundToken/完整分支节点；caller 仅选择真实 message ID，正文与 role 必须匹配。完整 `projectHistory` 才能辨认已删除用户消息对应的 assistant 回答以及无来源清单的旧摘要。孤立 v0 保护继续拒绝历史外发。新 ID 的明确引用不按正文字符串误杀。

`TranslationHandler.translateText` 新增可选 conversationId、validateRequest、suspend validateSource 参数；onStreamUpdate 保持尾参数，独立翻译页面不需要聊天来源并保留流式结果。消息翻译缓冲后验证并发布，错误不清除旧译文，不持久化“翻译中”。仍接受显式 translateModeId/translateThinkingBudget、原 ModelRequestRunner 不可变快照、single slot 和实际 translation 用量角色。

`ProviderRequestGuard` 是可选 coroutine context，只做宿主本地来源验证，`beginProviderRequest` 在实际 accounting.begin 前挂起检查。没有 guard 的现有主/辅助调用保持相同 accounting；来源变化不记为已经发出或已报告零用量。该 guard 不能授权、换模型、释放 UNKNOWN 或提供预算。工具/外部描述无权构造可信授权。

`ConversationSession.applyTranslation` 与 `ConversationRepository.updateMessageTranslation`：owner、生命周期、原完整节点、选中消息、当前档案和来源在最终 Room 事务内复核，update-only 单节点 JSON；持久成功后发布，保留并发 metadata/queue/其余节点。不存在的会话不能插入；原件、墓碑和已有译文保持。

Room32/sidecar9/Pigeon164/MemoryState schema1或2 均保持，无迁移或桥生成变化。适用源码 SHA、测试/APK、精确patch以本批 SOURCE_FINAL/TEST_RESULTS 为准；本协议不预授通过。新 suspend guard 需配同批 ai/app 二进制；回退可停用消息翻译，不降级删除保护或清任务。

本批仅覆盖消息翻译及最小物理请求验证钩子。旧已保存 title/suggestion/摘要/译文的历史派生物清理、其余辅助入口、全备份语料、真实模型质量、完整A/P0、Release/Mate50/生产和独立/完整项目审查分别待验。
