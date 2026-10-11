# 独立复审发现：当前生产代码未改动

## PR01 / P1：session 淘汰后，旧保存重新插入已删除会话（确定生产代码缺陷）

- 位置：`client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:1291–1306`，`ConversationSessionManager.kt:72–77`，`ConversationSession.kt:185–189`。
- 触发：删除会话；引用释放后 session 被生产 `removeIfIdle` 淘汰；持有旧 Conversation 的迟到调用再次调用 `saveConversation(id, stale)`。删除标记只在旧 session 内，新 session 无标记；`persistConversation` 将数据库不存在解释为新建并调用 `insertConversation`。
- 影响：已删除行及其原消息重新落库，删除终态无法跨 session 淘汰维持。旧状态保存入口实际存在于 `ChatVM.kt:316–325`；本轮没有证明某个真实 UI 操作必定产生上述交错。
- 直接运行：Android `deletedConversationMustRejectStaleSaveAfterSessionEviction`，UUID `e51b5cb2-6bfd-4a2b-a703-aa85eb33ad83`；新 QA 档案，真实 ChatService/Room。删除后首次查询为空；直接调用生产 idle 移除方法控制时序；再次保存成功，独立 Room 查询返回原消息1条，断言 FAIL。
- [原日志](deletedConversationMustRejectStaleSaveAfterSessionEviction-fixture-fixed.log)、[受设备时间/哈希验证的结果](probe-collected/qa-evidence/IndependentPostfixProbeTest.deletedConversationMustRejectStaleSaveAfterSessionEviction/e51b5cb2-6bfd-4a2b-a703-aa85eb33ad83/result.txt)、[FAILED manifest](probe-collected/fixture-manifest.json)、[探针源码](android/IndependentPostfixProbeTest.kt)。主 APK 为 `A9B22B…D8F8`，该批测试 APK 为 `951CF1…C594D`，完整哈希见 [版本清单](apk-final.json)。
- 范围限制：这是控制 session 淘汰后的公开保存调用，未运行真实 UI 迟到回调，也未保留复活行进行 force-stop 后第二次查询。此前旧 session 仍在场的删除负例本轮仍通过，不能替代本场景。失败结束只删除本轮确切合成行。
- 建议：将“新建”和“更新已有会话”区分，已有会话更新禁止转为 INSERT；删除终态需持久化或由保存代际/创建凭证约束。显式恢复入口应单独授权恢复，避免其与旧保存混同。

## PR02 / P2：Word 表格内容断言忽略 tab（确定测试判断缺陷）

- 位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/WordFormulaAssertions.kt:32–35`。
- 触发：源单元格为 `AB`，目标为 `<w:t>A</w:t><w:tab/><w:t>B</w:t>`。helper 只连接 `w:t`，两者都提取为 `AB`，`assertWordTablesMatch` 错误接受。
- 影响：表格“全部单元格内容一致”的 PASS 可掩盖控制字符变化。生产 `MaterialWordContent.text` 对 tab/br 有专门处理；此缺陷属于 QA 断言，不是已复现的生产 Writer 错误。
- 直接运行：额外 JVM 方法 `tableAssertionMustRejectInsertedTabAndLineBreak`，调用当前原 helper，没有复制另一套判定逻辑；[首次 XML](probe-xml-first/TEST-me.rerere.rikkahub.service.IndependentAssertionProbeTest.xml) 为 FAIL。[原探针](IndependentAssertionProbeTest-first.kt.txt)、[源 DOCX](word-negative/cell-source.docx)、[目标 DOCX](word-negative/cell-tab.docx)。
- tab 失败后方法终止，br 子场景 **NOT_RUN**，不从源码推断升级为运行结果。普通分段 run 等允许的等价结构未全面穷举。
- 建议：按 XML 内容顺序提取文本、tab、break，再比较；明确允许的格式等价，不用无差别 whitespace 归一化。

## PR03 / P2：采集器不核对 JUnit 失败终态（确定证据采集缺陷）

- 位置：`scripts/collect_run_evidence.py:64–72,98`；相关生产测试 `QaEvidenceRun.finish` 仅上报调用方 status，多个 finally 在 finish 后继续清理。
- 触发：同一方法先 finalized manifest=PASS，随后 JUnit 因断言或 cleanup 失败，日志出现 `test=synthetic`、`INSTRUMENTATION_STATUS_CODE: -2`、`FAILURES!!!`。日志 SHA、方法/UUID、执行窗口、设备 mtime 和内容均符合上下文。
- 影响：采集器仍输出 records.status=PASS，未反映该方法实际 FAIL，违反失败不得转成功的证据合同。已有“manifest=FAILED 保持 FAILED”测试仍通过；它未覆盖两个终态互相矛盾的情况。
- 直接运行：[合成探针](collector-status-probe.py)、[输入日志](collector-disagreement-input.log)、[实际采集结果](collector-disagreement-output.json)、[断言 FAIL 日志](collector-status-probe.log)。使用模拟 ADB，不连接远端或个人目录。
- 不声称历史某次真实结果已经被误报。本轮 PR01 的 manifest 原本就是 FAILED，实际设备采集仍正确保留 FAILED。
- 建议：按确切 class/test 的 instrumentation 终态验证并拒绝矛盾；也应处理 manifest 后进程异常、缺少最终终态及清理失败。不得仅凭整体 `OK` 判定混合方法日志。

## 其他分类

- 新 QA 夹具问题已改正：档案名称含完整 UUID 超过40字符，首次两个 Android 方法在创建档案阶段失败；协程异常栈恢复可能产生同类异常副本，`assertSame` 比较顶层对象过严。保留首次源码、APK、XML和日志，未改生产实现。
- 历史观察：真实教学/材料 FAILED 根因依然 UNKNOWN；分享窗口历史失败时序未还原。本轮后来的通过不关闭这些原因。
- 覆盖缺口：见 [逐项矩阵](acceptance-matrix.md)，不分配“确定代码缺陷”结论。Skills 嵌套/断链、清理/替换 I/O 故障、跨会话附件和失败交付未运行，不能按通过类或源码方向判定已经验收。
