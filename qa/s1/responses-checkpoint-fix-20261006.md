# 流式回复进程终止丢失：修复与复测

2026-10-06。修复 [前次真实 Responses 验证](responses-android-20261006.md) 中唯一失败的内容保留项目。环境仍为无 GMS AOSP Android 34 x86_64 模拟器，非 Mate 50。

## 修复行为

生成增量通过 ConversationSession.checkpointGeneration 先提交 Room 消息节点事务，再发布到页面。进程被终止时，已经发布的内容有持久化记录；不依赖 onCompletion 有机会执行。检查点与会话元数据修改共用锁，按会话原始 profileId 核验所属档案。只写变化的消息节点，不覆盖标题、文件夹或档案归属；完成时保留原有保存和全文索引流程。

GenerationChunk.Messages 是累积消息快照；conflate 在写入较慢时合并中间快照，最后一帧仍在完成清理前提交。没有按时间延迟保存已展示内容，没有新增异步尾写入任务，也没有自动重发模型请求。数据库 schema 仍为 27。

## 验证

- 构建：`.local/s1-checkpoint-build.log`，BUILD SUCCESSFUL in 1m 45s。
- 回归：`.local/s1-checkpoint-tests.log`，BUILD SUCCESSFUL in 1m 12s。ConversationSessionTest 18、ConversationSessionManagerTest 6、ChatServiceTest 8、LearnerProfileCompatibilityTest 1，共 **33 项，0 failure/error/skipped**。新增三项验证提交前不展示、新会话从持久化恢复、提交失败不展示及并发元数据修改保留消息。
- 覆盖安装成功，应用数据和用户接口配置保留。已安装包与本地 APK SHA256 一致：`d6ae759d9cd1aaf6aadbce295e3fc491449a6ad547c278f61815e6d29de9768a`。
- **真实进程终止复测 PASS**：gpt-6.1-sol / medium、Responses、stream=true、store=false。观察到 83 个文本 delta、没有完成终态时开始截图和 force-stop；终止后保留 **446 字符**回复。重开历史后页面可读，数据库消息 ID/哈希相同。截图与终止之间仍有增量到达，83 是动作触发时的观察值，不是最终事件总数。
- 在恢复会话追加追问，返回导数在 x=2 时的结果 **12**，中文与公式正常显示，原 446 字符消息仍保持相同 ID/哈希。
- 主动取消回归 PASS：文本流中 Stop，保留 **2,454 字符**；后续读取一致。
- 流中切换 Learner-B 回归 PASS：旧档案保留 **3,311 字符**，归属仍为 default-learner，切换后空白聊天与 B 自有历史没有 A 内容，后续读取旧回复一致。测试结束切回原档案。
- 前次四个测试会话的消息摘要全部保持不变；本次没有 fatal exception 记录。临时 Firebase 配置已移除。

[结构化证据](responses-checkpoint-fix-20261006.json)；[终止前](responses-checkpoint-before-kill-20261006.png)、[重开后内容保留](responses-checkpoint-reopened-20261006.png)、[恢复后中文追问](responses-checkpoint-followup-20261006.png)，截图已查看。分步骤脱敏观察保存在 `.local/checkpoint-*.json`。

## 验收边界

本次修复的是已显示文本丢失。重开后从历史打开部分回复，并可人工继续追问；没有恢复原 HTTP 流、持久化明确的中断任务状态，或恢复队列和待工具审批。全文索引在正常结束/后续最终保存时重建，未验收单独中断回复的全文搜索。Mate 50、后台/锁屏、断网/429、存储故障实际 UI 及完整 C04/R01 仍待后续验收。不宣告完整 S1 或 R01 通过。
