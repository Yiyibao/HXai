# 本机账户鉴权与移交接收端协议 v1（2026-10-26）

接续 A24-02 第一小批，来源 `HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/`。本批只实现第二小批的本机合成接线：原生 TaskStore13、每请求鉴权、任务级加密凭据引用、受监督接收端、provider 请求 journal/幂等与输出读回。没有网络监听、真实账户登录、服务器 KMS/vault、跨主机权威数据库或独立云进程，不能称手机关闭后云 Agent 持续运行。普通 Chat/Host/Pigeon/工具/DI 无新账户或远端启用入口。

## 唯一权威和身份

沿用 AgentTaskStore 的同一 SQLite 事务、slot、递增 fence、control、calls、usage 和计划验收。新增 AgentAccountSchema 的八表：agent_accounts、agent_account_profiles、agent_sessions、agent_host_credentials、agent_transport_audit、agent_provider_commands、agent_hosted_results、agent_executor_bindings。一个档案只映射一个账户。当前仍是一个本机工作域，不能把它当成多租户服务器隔离方案。

可信宿主 enroll/issue/revoke；第三方描述、模型、传输请求不能调用这些管理函数。Bearer 为 SecureRandom 256位随机值，库仅存 SHA256；每次请求在原 store 写事务中核账户 active/epoch、session revoked/期限、owner/task/executor、原 task grantEpoch。先鉴权再 journal，在同一事务完成；另一连接的撤权不能插在中间。期满和账户撤销不证明执行器已停止，不清任务授权或独立 blocker、不放执行位。重新 enroll 不能恢复撤销账户；本版不提供账户恢复。

## 凭据和请求

复用 Android CredentialVault 的 AES-GCM/Keystore/noBackup，但额外加密封装 account/owner/task/provider 并在解析时逐项核对。数据库只保存当前不可变密文引用/递增 revision；旧 handle、撤权 handle、跨任务 handle、grantEpoch 改变、明文伪引用或错误封装都拒绝。轮换是可信管理面动作，不改变原模型快照或暂停、缺输入、设备确认、主动撤权；不将未经验证的新 secret 安装到原生模型配置。本版合成 secret 只供固定 fake provider，真实 key 的验证/托管授权仍待接入。

AgentAccountTransport 的任务读取与移交激活使用原 store；没有 stopped=true/verified=true 输入。provider admission 强制主角色 provider/model/reasoning 和有效 configurationReference，费用 basis 不接受客户端自报。account/task/commandId 绑定输入、模型、凭据 revision 和 fence 哈希，原物理 requestId 持久回读；丢 ACK 重发 dispatch=false，变化意图拒绝，UNKNOWN 不能换 commandId 重放。新事务失败时 calls/usage/command 一起回滚。

## 受监督执行与结果

AgentAccountExecutor 是原 slot 的移交接收器，不另设 claim 权威或语言模型循环。activate 后在同一事务插入 task/fence 唯一的持久 instance 记录并绑定实际 coroutine Job；重复激活 ACK 不能在另一 Store/进程绑定相同 fence 的第二个 supervisor。绑定行不可重用，重启不据过期时间清除；它不构成停止证明，仍须原独立 Job 退出协议及新 fence 才能接续。仅可信宿主 provider adapter 接触 secret 和产出，普通请求不能提交回执；返回正文和 hash 与物理 usage 回执原子持久化，另一次查询重新算 hash 后返回。没有新增隐藏模型或推理路由。

物理响应不等于用户目标验收，接收端最多 COMPLETED_UNVERIFIED。写后失联/取消/退出仍由原 finish/recover 保 UNKNOWN 和 slot；账户撤销后允许可信 adapter 落盘已知响应，但禁止再取 secret 或新 admission。读取不到回执不重放。进程重启的非 local 位仍保守 STOP_REQUESTED，须可信停止/恢复协议，不因时间流逝自动认领。

当前 fake adapter 的 admission 是本机 dispatch 边界；它不证明远程网络字节已发送或撤回。真实 provider 接入时必须在实际物理请求入口复用账户/授权检查与 requestId，并验证断流/收费/远端停止回读；不能直接将此内部接口开放公网。

## 版本与适用门禁

TaskStore11/12→13及旧受支持版本只增表，保旧行、UNKNOWN、用量、锁模型、移交和 fence；失败迁移事务回滚，未来版本拒绝打开。Room33/Pigeon/ToolRegistry、B 数学/材料/服务不变。原 Pro 两套规范保持原文；本增量把账户 scope、token/vault 撤权、跨连接事务、幂等 ACK、输出完整性和迁移加入适用来源地图。

精确构建、测试、反例、源码/产物 hash 以本批 TEST_RESULTS 与 SOURCE_FINAL 为准。未执行独立审查及完整全项目审查，保持 NOT_EVALUATED；真实云、Mate50、正式签名与生产另验。回滚停新 adapter 并保 TaskStore13，不降库、不清 UNKNOWN、不恢复撤销凭据。
