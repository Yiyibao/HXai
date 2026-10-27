# 原生任务协议复审增量 v1.3（2026-10-12）

依据 Pro 新功能标准及提示词 v1.1，接续 `agent-model-native-protocol-v5.md` v1.2。
适用 HEAD/base `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 及
`codex/agent-model-native-resume-20261012` 工作树；精确源码以
`HXai_CHANGE_REVIEW/CURRENT-FEATURES-20261012/SOURCE_AFTER.json` 为准。

schema5/Room32 不变，无数据清空或降级迁移。`AgentTaskStore.finish` 的完成门禁收紧：
尚未持久化并独立验证用户目标的 plan/acceptance，所以传入 `SUCCEEDED` 一律落为
`COMPLETED_UNVERIFIED`。成功的模型 HTTP 请求、已核验的本地查询回执都不足以证明目标完成。
存在 IN_FLIGHT/UNKNOWN 时仍为 UNCERTAIN_SIDE_EFFECT 并持有 slot。
当前 ChatService 原有 outcome 已使用 COMPLETED_UNVERIFIED，调用行为兼容。

新增 Android 反例先复现原 TaskStore 的错误成功状态，再复验修复；同时验证合法零工具聊天
释放 slot、unknown 用量保持 null、future schema 拒绝且原库字节保全。既有目标规划、授权、
独立设备/input 等待、全部旁路调度、TaskDto 和语义验收仍未完成，不凭本补丁关闭。

本轮同一执行者复审，独立外部审查 NOT_EVALUATED。详细证据及裁决见本轮 review 目录。
