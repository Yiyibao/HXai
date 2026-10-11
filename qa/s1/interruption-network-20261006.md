# S1 中断状态与网络故障处理

日期：2026-10-06。本批完成代码与自动回归；未执行本批断网模拟器专项或 Mate 50 真机验收。

后续推进已完成模拟器本地 HTTP/SSE 故障注入与恢复界面验证，九项 Android 仪器测试通过；新增修复与最新验收边界以 [后续专项](network-faults-20261006.md) 为准。下文保留初次实现记录。

## 实现

- UIMessage 新增可选本地 generationState，随消息 JSON 保存，不改变 Room 表结构；旧消息默认 null。
- 请求开始保存 RUNNING；每次增量在提交数据库后显示；结束分别保存 COMPLETED、CANCELLED、INTERRUPTED、FAILED。IOException（含连接、超时、EOF）归为中断，接口及其他异常归为失败。
- 新 session 加载遗留 RUNNING 时呈现 INTERRUPTED，保留消息 ID、用户输入、部分回复和已有工具内容。原始 RUNNING 检查点仍可被后续重开识别，无自动续跑或工具重放。
- 聊天列表新增静态恢复提示及重新生成操作，沿用既有分支再生成入口；失败/取消通知不附带成功内容预览。
- OpenAI Responses 与 Chat Completions 的 SSE 没有结束事件而关闭时抛 EOFException；无异常对象的连接失败也不会正常结束。
- 自动网络重试仅允许在本次流尚未收到任何 chunk 时进行；收到增量后转人工恢复，避免重放替换已保存的内容。

## 验证

最终命令：`:app:assembleDebug :app:testDebugUnitTest :ai:testDebugUnitTest --console=plain`。

BUILD SUCCESSFUL，45 秒；app 237 项、ai 197 项，0 failure。新增 GenerationRecoveryTest 覆盖取消/连接/超时/接口错误分类、JSON 状态持久化及重开后用户输入和部分回复保留。既有检查点测试更新为重开识别 INTERRUPTED。`git diff --check` 通过。

本地完整日志：`.local/s1-recovery-final.log`。临时 Firebase 占位文件已移除。

## 验收边界

自动测试不替代真实网络与 UI 故障注入。C04/R01 不整体标为 PASS；本批未验证 429/鉴权错误的真实接口 UI、真实断网/超时、静默 SSE 截断及目标真机表现。完整任务队列、工具接续仍留在后续运行层工作，恢复不会自动重放副作用。
