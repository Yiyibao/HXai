# S1 真实 Responses：网络、队列与工具恢复

环境：AOSP API 34 x86_64，emulator-5554，现有 OpenAI Responses 配置、gpt-6.1-sol / medium。沿用设备内接口地址、密钥和模型，不打印配置或请求头。仅使用合成数学输入、QuickJS 计算和 ask_user；没有外部写入工具。

## 实际执行

| 子项 | 实际结果 | 状态 |
|---|---|---|
| 真实远端流中开启飞行模式 | 保留 188 字符、原回复 ID 和队列 ID；中断后队列暂停；网络恢复 3 秒内无生成任务/消息变化；人工恢复完成，用户消息仅 2 条，排队 ID 直接成为第二条用户 ID | PASS |
| 真实远端 Wi-Fi→CELLULAR | ConnectivityManager 断言两种实际 transport；切换前收到真实模型文本，保留 202 字符；切换后中断且无自动恢复；人工恢复排队输入，真实远端完成回复 | PASS |
| 真实远端流中 force-stop | 流仍在 RUNNING 时由宿主终止；回复 596 字符落盘；新进程恢复为 INTERRUPTED，ID 和文本 SHA256 不变，排队 ID 不变且暂停 | PASS |
| 冷启动队列界面与实际恢复 | 看见部分回复、中断提示、重新生成按钮、Queue paused · 1 / Resume sending / Edit / Remove；实际点击 Resume sending，真实接口返回 12，队列清空；旧回复、两条用户 ID 保留 | PASS |
| 真实 ask_user 审批与恢复 | 模型生成调用后 WAITING_TOOL / Pending 持久化；重开看见询问、选项、Submit 和审批等待提示；实际选“继续”并提交，原调用完成、真实模型答复，用户消息仅 1 条 | PASS |
| 真实模型两次计算的逐工具检查点 | 同一轮生成两个 eval_javascript；第二个 execute 入口读取 Room，断言第一个已完成、第二个 RUNNING 均已提交；宿主随后强杀，新页面第一个保持完成、第二个 INTERRUPTED、明确未知提示和跳过按钮 | PASS |
| 未知工具跳过后的状态表达 | 初轮点击跳过后零工具执行、一次 Responses 请求，ID 与第一个结果哈希稳定；但第二个被标成 COMPLETED，仅结果正文 unknown | 初轮 FAIL → 修复后 PASS（子项） |

“无自动重发”上述网络观察窗口为恢复后 3 秒；结合当前队列暂停、生成任务为空和消息快照不变确认。没有声称无限时段或任意系统后台行为均被覆盖。Wi-Fi 和模拟蜂窝是 Android 不同默认网络，最终均经过宿主联网；不是两条独立运营商公网链路。

## 修复

新增持久化 `SKIPPED_UNKNOWN`：接续可把明确未知结果返回模型，但不再标为工具执行完成；恢复后仍可辨识且禁止再次执行。工具卡片在中断时显示“执行结果未知”，跳过后显示“已跳过 … · 结果未知”。新增序列化与进程恢复 JVM 检查，并在 Android 接续测试中断言该状态。

修复后的构建、回归、真实工具复测结果以 [结构化总证据](real-responses-recovery-20261006.json) 为准；失败证据 `real-tools-resumed-20261006.json` 保留，不覆盖成新状态。

最终构建成功；app **245**、ai **197** 项 JVM 回归零失败，Android 故障 **16** 项及跨进程 **2** 阶段通过。新真实工具样本强杀后从页面点击跳过：第二项持久化为 SKIPPED_UNKNOWN，零工具执行、一次 Responses 请求、原消息/调用 ID 和第一项结果哈希不变。再次 force-stop 并从页面重开，状态与文本哈希不变，仍显示“已跳过 eval_javascript · 结果未知”。新版截图 `real-tools-fixed-unknown-20261006.png` / `real-tools-fixed-skipped-20261006.png` 与 fixed 系列 JSON 分列保存。

测试结束恢复原助手选择，仅删除本批明确 ID 的 QA 会话与助手；接口/provider 配置未改，未清应用数据。临时 Firebase 占位文件已移除。提交基线为现有工作区（包含此前未提交开发），应用前后 APK SHA256 固定在结构化证据中，未冒充独立 Git 提交。

## 证据与重跑

- `real-kill-recovered-20261006.json` 与 `real-kill-ui-resumed-20261006.json`：消息 ID、字符数、哈希与队列 ID；`real-kill-queue-20261006.png`：恢复页面。
- `real-approval-waiting-20261006.json` / `real-approval-completed-20261006.json`、`real-approval-20261006.png`：审批等待与真实提交。
- `real-tools-stopped-20261006.json` / `real-tools-recovered-20261006.json` / `real-tools-resumed-20261006.json`、`real-tools-unknown-20261006.png`：修复前强杀、未知状态与跳过结果。
- Android 入口 `RealResponsesRecoveryTest`：必须显式传 `allowRemote=true`、`phase`、`fixtureId`，默认套件跳过，不会意外请求真实接口。`network` 可传 `route=switch`；`kill` 等待 `qa_kill_ready` 后宿主 force-stop，再执行 `verify`；`tools` 等待 `qa_tool_kill_ready` 后 force-stop，在页面核查后点击跳过；`approval` 生成真实审批等待。`select` 只切 QA 助手，`restore` 恢复原助手，`cleanup` 只删除指定 QA 测试数据。
- `real_recovery_evidence.py` 只读取明确指定的 `[QA] real …` 会话，输出 ID、状态、哈希、计数；临时数据库不入仓库。原始 build/instrumentation 日志存 `.local`，汇总仅提取 `qa_*` 状态及 JUnit 结论。

初轮验收脚本遗漏 `initializeConversation` 导致 verify 等待超时；修正后在同一强杀保存数据上重跑通过。临时证据读取脚本的 SQLite 连接未显式关闭，Windows 清理失败；修正为 closing，随后成功提取。测试方法新增分支时 Kotlin 推断非 Unit 导致 JUnit 拒绝；已固定 runBlocking<Unit>。这些是测试设施失败，不计为业务 PASS，也未作为应用故障结论。

## 未覆盖

独立公网双链路、任意时点强杀（提交前后所有竞态）、真实外部写入效果核查、生产十分钟读超时、完整后台限制、拒绝审批与迟到审批的全部真实模型组合仍未覆盖。拒绝、未知阻断和 Auto 重新审批已有合成回归，但不能替代真实模型对应场景。C04/C05/R01 的本批子项通过不代表完整 S1 通过。
