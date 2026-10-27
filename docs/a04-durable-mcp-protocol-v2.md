# A04 持久安装与授权协议 v2（2026-10-21）

保全补充：SQLiteOpenHelper 绑定自定义 DatabaseErrorHandler，损坏库抛错保留，禁止默认删除重建。实际合成损坏库反例已触发系统默认删除，原FAIL/日志和修复同例保留在本批。宿主依赖未启动时工具发现关闭；损坏/未来库不伪装成空目录。

本增量继承 a04-synthetic-mcp-protocol-v1 与 Pro 正式规范，不改变其门槛。适用 codex/a04-durable-mcp-20261021 的完整工作区；共同旧 HEAD 5d826d5d7d8755ed42db834d6cd6dc52ae914c69，实际字节取 HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/SOURCE_FINAL.json。本批仍只在 nativeqa 启用固定 loopback 合成记录；不将共享 McpManager 的服务直接暴露给主 Chat。

McpInstallationStore 新增独立 noBackup SQLite schema1，每 owner 一个固定工具安装槽；持久 installation_id/revision/grant_epoch/state 与 host-authored config。config 保存端口、记录ID、内容SHA、服务名/版本、协议和工具合同SHA，不含凭据、正文、文件路径、任意URL或动态capability。只支持 MCP 2025-03-26 的原无 session JSON 子集。服务名/版本为声明值 pin，不是第三方服务器密码学认证；该机制不能直接升级为公网连接安全合同。

安装默认 DISABLED，可信前台命令 enable 才变 ENABLED。disable→DISABLED；revoke→REVOKED；uninstall→UNINSTALLED，墓碑留存。所有命令都 CAS owner+installation_id+revision；每次变化递增 revision/安装 grant_epoch 并旋转 installation_id，旧输入审批不跨重新启用、更新或重装。REVOKED/UNINSTALLED 不能直接 enable，必须显式重新安装再授权。更新必须先停用；运行中的旧结果因身份变化被拒，不无缝切换实例。未知版本或损坏配置拒绝，不重建清库。

McpFixtureManagementActivity 仅编译进 nativeqa，manifest exported=false；Kotlin 原 MCP 设置页提供 QA 入口。它读取真实 Settings 当前档案，写前再次核档案，显示持久状态和实际命令回读。该页没有模型可调用的管理工具；安装授权与原 TaskStore 逐任务输入绑定审批是两重独立许可。普通 App 没有该 Activity，也不能创建注册库或发现合成工具；当前未增加 Flutter 通用 MCP 管理桥。

SyntheticMcpRuntime 不再缓存进程内安装对象。Factory 工具发现、实际读取、独立读回都查持久安装；每个物理请求前后重新核完整安装快照及原 task/owner/fence/capability/scope/任务 grantEpoch/inputHash/journal。工具输入保留 record_id/expected_sha256/installation_id；安装身份旋转绑定新任务审批。输出增加安装revision/安装grant_epoch、服务声明和合同摘要。与任务授权epoch名称分开，彼此不替代。服务声明或远端 inputSchema 与 pin 不符则拒绝；第三方描述/instructions/annotations仍只是不可信元数据。

进程重启只读取原身份，不自动发网络或恢复任务。UNKNOWN仍由唯一 TaskStore 保持全局位；新的安装授权不解除任务撤权、manual pause、缺输入、设备确认或UNKNOWN。独立读回仍走完整协议，原 Planner 在全部步骤及语义验收前后重新核验。撤权不能撤回已admit/已发请求；观察到变化后禁止下一请求及迟到成功发布，不宣称远程瞬时取消。

Room33/TaskStore sidecar9/Pigeon164/记忆payload及模型配置不迁移。注册库不在 filesDir 备份清单内，恢复普通资料不会替换安装授权；跨设备需重新配置和授权。旧批的临时安装没有落盘身份可迁移；旧任务/receipt/原材料保持。普通App、通用第三方配置/多工具scope/TLS/OAuth/会话、脚本沙箱、Release/目标设备及真实Provider质量均不由本批授予通过。

源码映射：SettingMcpPage→nativeqa/McpFixtureManagementActivity→McpInstallationStore.install/command；Factory→SyntheticMcpRuntime.toolFor/read→AgentToolDispatch/TaskStore原门禁；TaskResultVerifier/TaskPlanner→SyntheticMcpRuntime.verify。独立和完整全项目审查 NOT_EVALUATED；适用权限/数据库/跨档案门禁由同一执行者定向验证，原失败和实际结果取本批 TEST_RESULTS.json，不预授 PASS。
