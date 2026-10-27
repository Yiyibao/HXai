# 原生执行器移交协议 v1（A24-02，2026-10-24）

本增量接在现有 AgentTaskStore/AgentScheduler 上。单一权威仍是本机 noBackup 工作域的 SQLite slot，跨所有学习档案共用。合成第二端共享这一个权威库；它不是独立云数据库，也不证明手机离线后的真实云执行。账号身份、网络鉴权、远程 vault、远端停止证明和云服务部署仍 ABSENT；工具、Pigeon 与普通 UI 无远端激活入口。

## 源码与版本

- `client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentTaskStore.kt`：TaskStore11，原 slot/calls/plans/control/usage 继续使用；claim/check/beginCall/provider journal/finish 都受同一 fence 与 executor identity 约束。
- 同目录 `AgentExecutorHandoff.kt`：schema、不可变移交记录和结构化 Job 退出证明。Room33、Pigeon、费用/材料/数学 schema 不改。
- `AgentScheduler.kt`：run 取得原租约后绑定实际 Job；prepareHandoff 先写请求，再取消并等待该 Job 完成，再记停止证明。ChatService/GenerationLoop 仍走原有单执行器。
- `ExecutorStopBoundaryTest`、`ExecutorHandoffRuntimeTest`、`ExecutorHandoffSchedulerTest` 和 `ExecutorHandoffProcessProbe` 提供合成合同与进程退出验证。精确源码/产物/结果以本批 SOURCE_CANDIDATE/TEST_RESULTS 为准。

## 持久协议

slot 增 `executor_id`，历史行迁移为 `local`。executor_handoffs 的主键是 task_id/request_id；绑定 source_executor/source_fence、target_executor、grant_epoch、goal/goal_revision 的 hash、包含 owner/task/provider/model/reasoning/configurationReference/revision 的快照 hash。单任务只能有一个 REQUESTED/STOPPED 记录；全域仍只有原来的一个 slot。

| 状态 | 允许行为 | 禁止行为 |
|---|---|---|
| REQUESTED | 原同 fence 的已发调用读回；等待可信退出 | 原执行器新派发、另一任务 claim、超时抢占、finish/recover 释放位 |
| STOPPED | 独立读回 UNKNOWN；按控制条件激活或安全撤销 | 把退出当副作用已核验；有 UNKNOWN 时放位 |
| ACTIVATED | 返回绑定目标 identity 的新 fence 租约；重读未过期 ACK | 旧 fence 发布；旧激活记录复活已失效目标 |
| ABORTED | 重读撤销记录；由正常授权/用户恢复流程接续 | 清除暂停/撤权/缺输入/设备确认；恢复旧 executor |

激活在一个事务中检查 STOPPED、slot/source/fence、owner、grantEpoch、锁模型/目标 hash、所有独立 blocker 及未决 calls，然后推进全局 fence、切换 executor_id、写 ACTIVATED。故障回滚不能留下半个新 fence。fence 不允许 Long 溢出。

请求/停止/激活的 ACK 不定义执行成功：以数据库记录为准，重试不能再 dispatch。STOP_REQUESTED 仍是请求停止，只有实际绑定 Job cancelAndJoin 完成才形成停止证明；未协作退出的 Job 会继续占位。停止对象必须事先登记在同一 Store，其他完成 Job 的证明无效。

abandonHandoff 仅限 STOPPED 且无未决 calls，推进 fence，保留所有控制条件并按原 finish 规则产生 INTERRUPTED/PAUSED_BY_USER/WAITING_*/CANCELED，释放原位。不改变模型配置或重新授权。目标或 epoch 改变导致旧移交失效时，先安全撤销，再走正常恢复；不强行重用旧快照。

## 重启和边界

迁移10→11及更早受支持版本只增字段/表，保留历史 UNKNOWN、用量、配置和控制。未来版本/降级拒绝打开，不能删库重建。普通本机执行继续原 recover；存在移交或非 local executor 的位不能由本机启动释放。未落盘的停止证明丢失时保持阻塞；STOPPED 已落盘则可经完整条件检查激活。ACTIVATED 的合成第二端在本机重启后进入保守 STOP_REQUESTED，不能自动恢复网络执行。

当前停止证明仅覆盖结构化 Kotlin Job 生命周期。外部网络请求、子进程或远程宿主的结果仍由 call journal/独立核验决定；真实远端不能上传 JSON `stopped=true` 代替可信宿主验证。下一小批须实现合成账号域、鉴权传输和可信远端生命周期后才能开放 adapter。此协议的局部通过不授予真实云、完整 App、Release、Mate50 或生产通过。

本批交付目录：`HXai_CHANGE_REVIEW/A24-02-EXECUTOR-HANDOFF-20261024/`。同一执行者开发/定向验证/对抗检查，独立审查 NOT_EVALUATED；原 Pro 原文和历史证据保持原样。
