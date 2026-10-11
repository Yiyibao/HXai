# 全项目复审问题清单（2026-10-08）

审查对象：当前未提交工作区。F01–F04是代码路径核对发现，本轮通用回归未注入这些特定竞态/写库失败；F05已用故障注入复现，F06已通过真实测试及产物/生产解析器定位。P1 为数据完整性优先修复，P2 为异常处理/恢复修复。

## F01 [P1] 后台标题/建议写入整个会话，可能覆盖更新的消息

ChatService.kt:1005–1009、1061–1070。

生成标题/建议完成后，从数据库读取 Conversation，再 copy 单个字段并调用 saveConversation。读取和保存之间没有会话 metadataMutex，也没有消息 revision 检查；saveConversation 会把整个 messageNodes 写回。后台任务可与下一轮流式 checkpoint、编辑或分支切换并发。如果后台先读取旧消息、随后新 checkpoint 提交、最后后台保存，则新正文/分支被旧快照替换，内存也一起被覆盖。现有 updateMetadata + 专项列更新并未用于这两处。

建议：返回时在会话串行临界区只更新 title/chatSuggestions 对应列，基于当前内存合并，并核验被请求的消息版本。测试应阻塞后台读后保存，插入新消息 checkpoint，验证正文和分支完整保留。

## F02 [P1] 删除会话后，迟到的建议生成会重新插入会话

ChatService.kt:1061–1066、1270–1282；HistoryVM.kt:43–46；ChatVM.kt 的 deleteConversation。

历史/聊天删除直接调用 repository，未取消并等待该会话后台任务或移除 session。建议请求完成后，getConversationById 返回 null，代码退回 session.state 或原 conversation；saveConversation 判断不存在后 insertConversation。因此开启建议时，在请求未返回前删除会话，迟到响应仍有明确路径把已删除聊天重新创建，并重新 reconcile 已删除来源。

建议：删除由 ChatService 协调，设置删除终态/代次并取消等待相关任务；后台结果只能更新仍存在且版本/代次匹配的会话，不能回退后插入。测试覆盖延迟建议、删除、返回响应，确认数据库/记忆来源均不复活。

## F03 [P2] 审批决定写库失败后，当前进程无法再次提交

ChatService.kt:688、1277–1282。

saveConversation 先发布 Approved/Denied/Answered 到 session，再执行数据库事务。写库失败时 catch 仅暂停队列/报告错误，未恢复 Pending；入口的 isPending 检查拒绝再次提交。未持久化的决定留在内存，重启又读取旧 Pending。详见上一轮 qa/re-review-20261008/report.md。

建议：先提交数据库，再发布审批状态；失败保留 Pending。注入写库失败后重试验证决定只提交一次。

## F04 [P2] 消息保存提交前删除附件，数据库失败会留下失效引用

ChatService.kt:1167、1277–1282、1491；FilesManager.kt 的 deleteChatFiles。

删除消息经 saveConversation -> updateConversation -> checkFilesDelete，先永久删除 filesDir/upload 中已无新引用的文件，然后才执行会话数据库事务。事务失败后数据库仍保留原消息和附件 URL，原文件已丢失；重启不能恢复附件。materials 私有库文件有排除保护，所以该问题限定于 upload 中的旧/生成/粘贴等聊天附件，不外推为材料原件全部会被删。

建议：数据库成功后才清理无引用附件，失败不执行删除；清理时重新核对当前会话、分支及队列引用。测试注入删除消息写库失败，验证原附件仍可读。

## F05 [P2] 服务端入队写入失败留下无任务记录的材料原件，保留期限清理无法处理

server/document-service/api/store.py:enqueue；dispatcher/main.py:cleanup。

enqueue 在事务内创建 jobs/<uuid> 目录并 staged.replace 原件，然后写 request.json，最后 INSERT jobs。写 request.json 或 INSERT 失败只回滚 SQLite，不删除已搬移目录。API 的 finally 仅删除 uploads 中旧 staged 路径；dispatcher.cleanup 只枚举数据库中的过期 jobs 和 uploads 临时文件，所以该孤儿 jobs 目录无法进入期限清理，原件长期残留并占磁盘。

本轮已通过真实 Store 故障注入复现：模拟 request.json 写入 I/O 错误后，数据库 0 条任务记录，jobs 中仍有 1 份 source.pdf，uploads 中原 staged 已不存在。见 probe-orphan-upload.py、orphan-upload-evidence.json。夹具目录在探针退出时清理，不影响用户文件。

建议：入队失败清理本次创建的唯一目录，或先注册可恢复的 staging 状态；增加启动/周期孤儿目录清理并核验安全路径和宽限期。修复应覆盖元数据写失败及数据库提交失败。

## F06 [P2] 真实 Word 回归用固定提取文本断言，拒绝了等价可编辑公式

client/app/src/androidTest/java/me/rerere/rikkahub/service/MaterialRealModelTest.kt:96。

本轮实际模型生成了 OMML 分式，分子文本为 (x+1)、分母为 2，上标为 y 的 3 次方。实际生产 MaterialWordContent.text 将其正确提取为 ((x+1))/(2)，测试却要求 contains("(x+1)/(2)")，因此报错并提前退出。两种括号表示数学等价；本次并非公式丢失。实际产物中一张原始图片字节完全保留，基本表格也存在。

证据：原测试 material-real.log 失败；word-real-failed.docx / word-real-failed-document.xml；调用实际已编译生产类的 word-parser-probe-executed.log（original_fraction_assertion=false、power_assertion=true）；word-structural-check.json（OMML和原图字节核查）。未改测试或把失败重标为通过。原测试在这处提前退出，后续图片生成独立探针本轮未执行。

建议：按 OMML 分式/上标结构核验，保留明确的分子、分母、底数、指数与原图字节断言；加入带等价外括号的合法样例及错误分子/分母的负例。不能只删除失败断言或把结果一概判为成功。

