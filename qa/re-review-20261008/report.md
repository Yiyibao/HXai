# 再次复审（2026-10-08）

范围：当前未提交工作区中的最近审批/错误分类/workspace 修复，以及会话恢复、保存与加密备份边界。静态复查不等于全项目全部设备端到端验收。本轮未修改业务代码。

## 发现：P2 审批持久化失败后，内存决定无法再次提交

位置：client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:690（调用 saveConversation）；:1277（先发布内存再落库）。

handleToolApproval 将 Pending 改为 Approved/Denied/Answered，随后 saveConversation 先 updateConversation 更新 session，再执行数据库事务。如果数据库写入因磁盘满、I/O 或事务错误失败，catch 只暂停队列并显示错误，没有回滚内存审批。此时工具 isPending=false，新的审批请求会在入口及等待前序任务后的检查中直接返回。用户不能在当前进程重新提交该未持久化决定，内存与数据库的审批状态也不一致；重启会重新出现旧 Pending。

建议：在串行临界区内先持久化审批决定，再发布 session 状态；失败保留 Pending。增加注入持久化失败后再次提交成功的测试，验证未执行工具且决定仅提交一次。

## 验证口径

首次沙箱运行无法加载 native-platform.dll；允许使用既有主机缓存后的完整重跑遇到缺少 google-services.json。均保留失败日志。随后使用仓库合成配置执行成功，但任务 UP-TO-DATE；该结果不算本轮重新执行。最终另行以任务 --rerun 强制执行两个 JVM 测试任务，结果见 unit-tests-executed.log。临时配置由 finally 清理。

本轮不重复发送真实模型请求；前轮真实审批与 POSIX 结果仍为历史证据。本轮没有重新验收 Mate 50、增强文档服务、图片生成或全部提供商。git diff --check 通过。

最终重新执行结果：517 项 JVM 测试通过，0 failures、0 errors、0 skipped；两个测试任务实际 executed。此项未覆盖上述数据库失败审批路径。
