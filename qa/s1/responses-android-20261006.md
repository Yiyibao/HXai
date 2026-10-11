# Android 真实 Responses 聊天验证

2026-10-06；AOSP API 34 x86_64 无 GMS 模拟器，非 Mate 50。应用 org.mathstudy.agent.debug，0.1.0-dev (1)。安装包与本机构建 APK 的 SHA256 一致：`b588f82e4b7a43d3de22166929e29693530eaeb892d74f589da39705dc0ffd2c`。

这是修复前记录，保留 FAIL 原始证据。随后已加入消息持久化检查点，内容保留失败项在新版真实请求中复测通过，见 [修复与复测](responses-checkpoint-fix-20261006.md)。

真实 Android 请求到 `https://xinyue.mom/v1/responses`，gpt-6.1-sol、medium、stream=true、store=false。只发送合成问题。凭据由用户填入应用，没有复制到仓库。store=false 仅是客户端请求设置。

## 结果

| 场景 | 实际结果 |
|---|---|
| 真实流式与公式 | PASS：收到 Responses 文本增量；分式、导数、积分在页面中显示。短回答有完成终态。未宣告全部公式/长表格验收。 |
| 文本流中主动取消 | PASS：Stop 变回 Send，保存 3,045 字符部分回复；间隔读取消息 ID、字符数与 SHA256 一致；继续追问得到导数在 x=3 的结果 6。 |
| 文本流中切换学习者 | PASS：确认输出增量已增长而完成终态未增长后切到 Learner-B；旧流停止，原档案保存 4,332 字符，后续读取一致；B 页面空白、侧栏 No conversations。 |
| B 独立聊天 | PASS：返回 QA_B_ONLY 42；已完整解析的 B 请求 input 只有 B 测试标记，没有 A 标记；数据库归属是 B 的 UUID。 |
| 已保存内容重开与追问 | PASS：force-stop 后重开，取消及切档案保存的消息 ID/哈希不变；从历史打开 A 会话，追问得到积分结果 9。没有自动恢复旧 HTTP 流。 |
| **流中直接终止进程** | **FAIL：收到 479 个文本 delta、没有完成终态，截图中已有正文；force-stop 后数据库只剩用户问题，重新打开历史也没有该部分回复。** |
| 进程终止后人工追问 | PASS：追加短追问收到中文勾股定理解释；这不代表丢失内容已恢复。 |
| 快速模型/标题 | 最初主聊天成功但自动标题提示缺少快速模型。设置 gpt-6.1-sol / medium 后，真实标题生成 Pythagoras，缺模型提示不再出现。 |

## 恢复失败的源码依据

`ChatService.handleMessageComplete` 在收集 GenerationChunk.Messages 时调用 updateConversation 更新内存；onCompletion 中才由 session.finishGeneration 调用 saveConversation。主动取消能执行完成清理，进程被终止则无法执行，因此本次尚未结束的回复没有落盘。此次任务是验证，未修改聊天运行代码；需要后续实现生成中的持久化检查点与中断状态，再复测。不能把 C04、R01 或完整 S1 标为通过。

## 证据与复用

- [脱敏结构化结果](responses-android-20261006.json)：消息 ID、归属、字符数、哈希、测试标记、协议事件计数与明确失败项。
- [B 空历史](responses-profile-b-empty-20261006.png)、[重开后积分追问](responses-restart-followup-20261006.png)、[进程终止前](responses-before-process-kill-20261006.png)、[重开后回复丢失](responses-after-process-kill-20261006.png)。截图均为合成聊天，已查看。
- `python qa/s1/android_responses_evidence.py --output .local/responses-observation.json`：只读采集摘要，不输出密钥或正文。运行前需 Android Debug 包及本项目 adb；它是观察工具，不是自动端到端测试或成功判定器。生成停止后的两次摘要可比较消息 ID/哈希。日志长行可能截断，仅保留完整解析的请求字段；事件按行统计，不能把 JSON 中重复出现的事件名算作第二个事件。
- `.local/responses-*.json` 保留分步骤观察。直播时复制 SQLite/WAL 只是观察样本，不宣告原子备份。宿主 SQLite 不兼容 Android FTS integrity_check，本次没有宣告数据库完整性检查通过。

未测 Mate 50/HarmonyOS、断网/429、待工具审批切换、待发送队列进程恢复、文件与完整档案隔离。没有发布 APK，也没有修改生产凭据存储机制。
