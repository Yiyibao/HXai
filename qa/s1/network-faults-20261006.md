# Android 网络故障注入与中断恢复

2026-10-06，AOSP Android 34 x86_64 模拟器。本批完成本地 HTTP/SSE 故障注入、业务链路和恢复界面检查，未使用真实中转或真实个人内容。

## 结果

| 场景 | 结果与证据 |
|---|---|
| Responses SSE 无终态而正常关闭 | INTERRUPTED；部分中文回复落盘；服务端只收到一次请求 |
| 部分输出后 TCP reset | INTERRUPTED；部分回复保留；不自动重放 |
| 401/429，JSON 与空错误体 | FAILED；HTTP 状态码落盘；四次测试各一次请求，不走网络重试 |
| 中断后手动重新生成 | COMPLETED；只有一条用户输入；旧回复 ID、内容及 INTERRUPTED 状态均保留在旧分支 |
| 重试首个增量前再次收到 429 | 新分支 FAILED，旧中断分支不被覆盖；再次生成成功，用户消息仍只有一条 |
| 停止无后续增量的流 | CANCELLED；已接收文本落盘；没有再次请求 |
| 真实 socket read timeout | 实际 SocketTimeoutException；分类为 INTERRUPTED；使用测试客户端 300ms 超时，不代表生产 10 分钟等待已实测 |
| Chat Completions EOF/正常终态/空错误体 | 分别为 EOFException、正常完成、带 429 状态码的 HttpException；EOF 之前的文字保留 |
| 新 session 从 Room 恢复 RUNNING | INTERRUPTED 在展示前写回 Room；用户与回复 ID、内容保持一致 |

Android 仪器测试 **9 项通过**，包括 HTTP 场景内的多组子用例；app JVM **240 项**、ai JVM **197 项**均 0 failure/error。运行时应用 APK SHA256：`1cc05fff443660f7073ed53493ea7efcf8feab3fb2ad6d0b4d9d932cffb7e4e8`。

最终完整构建与 JVM 回归：`.local/s1-fault-branch-final-build.log`（28 秒成功）；补充 Chat Completions 仪器用例构建：`.local/s1-fault-chat-test-build.log`（5 秒成功）；最终 Android 结果：`.local/s1-network-fault-tests.log`。临时 Firebase 占位文件已移除，原模型/助手/网络设置在每项仪器测试结束时恢复。

[结构化结果](network-faults-20261006.json)；[仪器测试源码](../../client/app/src/androidTest/java/me/rerere/rikkahub/service/ChatNetworkFaultTest.kt)；可重复入口 `scripts/test_s1_network_faults.ps1`。默认清理本次测试会话；`-KeepEvidence` 可保留明确标注 QA 的恢复界面样本。最终九项运行没有新增保留样本，界面证据来自前次八项成功运行时保留的合成样本。

## 本批发现并修复的问题

- 空 HTTP 错误体原先会回落到 IOException，导致 401/429 被误认作网络故障。现在 HttpException 携带 statusCode，并作为本地 generationErrorCode 保存；界面区分认证失败与限流/额度不足。
- 新请求原先在旧部分回复上标 RUNNING，后续成功回复会清掉旧分支的中断状态。现在先保存独立的空回复分支，再发 HTTP 请求；这个本地检查点不会进入模型输入或占用上下文消息预算。失败发生在第一个增量之前也有独立终态。
- 恢复原先仅在内存显示 INTERRUPTED，磁盘仍保留 RUNNING。现在初始化先保存恢复终态并重建全文索引，再发布页面状态。没有自动续跑工具或重放副作用。

首轮测试服务器曾绑定 IPv6 却请求 IPv4，导致六项准备错误；修正为明确绑定 127.0.0.1。之后七项运行的唯一业务 FAIL 是旧中断分支状态丢失，新增回归并修复后八项、最终九项全部通过。原始开发日志保存在 `.local/`，不将失败轮次算作通过证据。

## 界面证据及边界

已对保存的 QA 恢复样本 force-stop 后冷启动并打开指定会话：可见原用户输入、部分回复、中断提示与“重新生成”；没有 Stop 控件或 ProgressBar。截图已实际查看：[恢复页面](network-recovery-20261006.png)，[Room 与界面摘要](network-recovery-ui-20261006.json)。这一截图验证按钮可见；按钮调用的生成链路另由上述仪器测试覆盖。

C04 的模拟器 HTTP/SSE 故障与人工重试链路已有 PASS 证据；C05 的旧中断分支保全已有本批回归。**不整体宣布完整 S1、C04、C05 或 R01 通过**：设备飞行模式/网络切换、生产超时等待、流中强杀后的完整任务状态、队列/工具接续、Mate 50 仍需补验。Room 恢复与冷启动样本不是本批新的实时 HTTP 流中强杀测试；前次真实中转流中强杀的文本保留证据仍见检查点修复记录。


## 后续设备与接续增量

以上九项与截图是此前 APK 的历史记录。当前同名结构化 JSON 已随重复测试更新到后续 16 项；新增设备断网/网络切换、队列、工具及迁移证据见 [设备与接续记录](runtime-continuation-20261006.md)。后续结构化结果包含当前 APK 哈希，不能用此前截图替代新增恢复按钮的视觉验收。
