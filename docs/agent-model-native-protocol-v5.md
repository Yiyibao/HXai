# AGENT-01 / MODEL-02 原生协议增量 v1.2（2026-10-12）

来源：Pro `10_REVIEW_SYSTEM` v1.1、`02_ARCHITECTURE_AND_PLAN.md`、`11_HANDOFF_TO_GPT6_1_SOL.md`，以及继承 `HXai_CHANGE_REVIEW/AGENT-MODEL-20261012/CHANGE_CONTRACT.md`。原始规范和原包不改动。本增量适用 base `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`、`codex/agent-model-native-resume-20261012` 工作树；最终原始字节/源码 blob 由本批 SOURCE_TESTED.json 固定。它不降低 Pro 门禁，不代表完整 App 放行。

## schema 与状态

sidecar `agent-tasks-v1.db` 版本5；Room32 不变。新增 tasks.manual_pause，兼容迁移1–4→5。1–3的历史用量来源仍置 unknown；版本4已记录的 reported/nullable 用量不降级。PAUSED_BY_USER/STOP_REQUESTED 迁移为粘性暂停。任务、调用、配置引用、slot/fence 与旧回执保留，不重建清空数据库。版本5不能交给旧版本4程序自动降级。

claim 只接受 QUEUED 且 manual_pause=0；不依据模型、Settings 更新或凭据更新自行恢复。可信用户的恢复消息/队列恢复才可清除 manual_pause；工具批准只恢复没有用户暂停的等待任务，不能顺带解除暂停。存在 IN_FLIGHT/UNKNOWN 或 UNCERTAIN_SIDE_EFFECT 时拒绝恢复。停止会话先持久化暂停标记，再取消并 join 相关执行者。在途/未知保留执行位；回执后安全停止可以释放。独立授权、设备确认和输入 blocker 集合仍须 AUTH-03 扩展，不能把本次 manual_pause 单字段冒充完整状态机。

同进程同一 sidecar 路径只允许一个 AgentScheduler；第二实例在 recover 前拒绝，避免活 executor 被观测者误恢复。该保护是 Android 本机域的生命周期门禁，不是分布式云执行锁。跨主机权威 fence 移交仍未实现。

## 真实调用链

发送/语音/重生成/恢复/审批 → ChatService → AgentScheduler → 原 ConversationSession/GenerationLoop → ProviderRequestAccounting/工具 journal → sidecar + Room checkpoint → 原消息事件与 Flutter 投影。接受时不可变配置仍在 CredentialVault 加密记录，sidecar/日志只存引用与摘要哈希。标题、建议、OCR、历史摘要与手动压缩有角色/逐物理请求账本；图片、自动记忆、翻译等完整旁路接入仍须继续，不冒称完整账本。

SDK 将用户模型/推理选择编码后，自定义 body 不得覆盖 model、已编码的 reasoning/thinking/effort 及原生嵌套字段；相同选择与不相关协议选项仍允许。支持范围是当前供应商编码器及列明字段，不声称识别任意第三方网关私有路由字段。Provider 专用 HTTP 客户端保留禁止自动重定向/重放；未知费用不置0。

## 压缩与来源

ConversationSession.commitCompressedHistory 在 metadataMutex 内复核 owner、背景 epoch、消息/分支、队列、当前 job，持久化成功后才发布。旧响应不得覆盖期间的新输入；保留最新 title 等元数据。UIMessage 新增兼容默认 false 的 isHistorySummary；isSynthetic 仍仅为请求期间的瞬态标记。摘要持久标记不进入 Provider DTO，也不能作为用户学习事实来源。旧 JSON/备份缺该字段按默认解码；不凭文本前缀倒推历史摘要或删旧记忆。

## 证据与边界

本批用 org.hxsi.agent.nativeqa 独立 emulator 包、普通 Application 和真实 Koin/Room/ChatService。不开主应用恢复/文档同步等启动任务，禁 Firebase 初始化/遥测；只用合成凭据、localhost 和合成文件。每轮仅归档并清理该新 QA 包的夹具；不清理既有 debug/生产数据。

原生例覆盖暂停/显式恢复、旧/future fence、schema迁移、模型/角色/推理锁、真实HTTP续跑与不重试、压缩正常/并发、摘要实际Room回读。五个进程检查点由宿主 force-stop 真正 QA 进程并检查 pidof，再在新进程独立回读。它证明模块级 process-crash 边界，不证明断电、真机、真实模型、云端或生产。

任务计划、语义验收、TaskDto、完整授权/设备协议仍未闭合。当前正常聊天保持 COMPLETED_UNVERIFIED，不能显示为目标已验收。独立审查 NOT_EVALUATED（用户禁止子代理）；本批同一执行者做开发及反例验证。

证据/补丁/精确接续：`HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/`。任务架构门禁记录：`HXai_FULL_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/`。
