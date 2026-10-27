# A02 OCR 来源保护增量 v1.26（2026-10-20）

来源：`HXai_CHANGE_REVIEW/A-OCR-SOURCE-20261020/CHANGE_CONTRACT.md`。本文件为保留 Pro v1.1 原文的版本增量，适用当前完整工作树及本批 SOURCE_FINAL，不扩大旧证据。

源码地图：ChatService.handleMessageComplete 沿用原输入转换注册；GenerationLoop.generateInternal 将原始完整消息的删除投影校验传入转换期的 ProviderRequestGuard，组合上游 guard；OcrTransformer.transform/performOcr 在同一 AgentExecution 中消费该校验，绑定接受 owner、消息身份、实际图片 hash、锁定角色/model/provider/prompt。实际 accounting 前复核原来源与角色，回包后复核；来源失效为中止异常，不作为 OCR 普通错误正文。

缓存键为独立版本命名空间，包含消息身份；旧缓存保留且不作为可信新缓存消费。缓存访问与写入需真实来源和字节复核；提交与 LearningMemoryStore.commitWithHistory 共用 Room 事务，使来源删除不能穿插最后本地检查与缓存写入。事务不跨 Provider 网络请求。缓存只是可重建派生物，不是任务完成证据；主模型沿用原历史/授权/目标门禁。

Room32/sidecar9/Pigeon164/记忆payload1或2不迁移，不增加并行执行器、权限、模型路由、费用预算或私有外传。来源/文件变化后用户重新提交有效来源；已经发出的请求保留账本事实，不能声称已撤回或自动重放。

实际实现和测试结果以本批 TEST_RESULTS/HANDOFF 为准。此增量不预授 PASS，不声明真实 OCR 识别质量、完整历史来源库存/备份、所有 Provider、Release/Mate50、完整 A02/A/P0 或独立/全项目审查通过。
