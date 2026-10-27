# A：统一 Agent 与平台能力 — Sol 开发任务

共同基线、文件归属、并行资源和集成契约见 `PLAN.md`；需求以 Pro inputs/01–04/13 为最终目标，现状以当前源码和最新交接为准。先读根/client AGENTS 和其指定权威原文，不重做已经验收的调度、锁模型、v1.8多文档门禁。

## 用户故事与职责

用户在任何 Chat 提交任务，能看到真实计划、队列、锁定模型、权限/等待、进度、成果、调用和用量；追加要求安全修订同任务，不重复副作用；失败/重启后保留状态。扩展及以后云/设备执行共用同一权威任务体系。

Sol 是任务、授权、模型和共享桥接的唯一集成负责人；负责 OMP 领域工具的最终注册、dispatch/verifier 接线与跨线验收，不覆盖其领域实现文件。

## A1：P0 任务与用量闭环（首批）

1. 从 TaskPlanner/AgentTaskStore/ChatService 当前计划与验收入口核对真实缺口，保住全部步骤 VERIFIED、全部文档证据和后复查。
2. 以合成材料/可控 Provider 建立目标覆盖、同任务追加/重规划、已验证步骤保留、错误证据、模型不支持 TOOL、正确闲聊等反例。真实 endpoint 质量验收入口独立配置；未明确授权不执行收费请求、不编通过。
3. 补/接 owner-scoped 全局任务与请求账本查询：逐调用、任务、角色、model/provider/config、时间区间、reported/estimated/unknown；从实际账本提供 DTO 和真实 UI，不能只汇总消息 token。
4. 费用仅取可核实供应商数据或有出处、版本化价格配置；否则保留 unknown。补日周月/用户时区、可能收费的失败/重试、明细/导出和异常提醒，不增加硬费用预算。
5. 任务中心能跨会话浏览授权任务、展开实际调用和成果；按钮有宿主 ACK。锁模型与当前选择分开，STOP_REQUESTED 与已停止分开，旧 profile/contextEpoch 回包丢弃。

首批允许拆为 A1a 计划/协作、A1b 账本查询、A1c UI，每批单独合同和可验证产物；先核对现有能力再写最小增量。

## A2：安全扩展与数据保全

- Skills/MCP 首次赋权、owner/path/network/data scope、撤权、安装/更新/卸载，受限脚本/计算沙箱；当前 MCP/workspace 停用边界在安全契约完整前保持。
- Memory/OCR/索引/备份/任务库：删除抑制、撤权不复活、恢复兼容、长期成果与聊天生命周期分离，配合 OMP 的材料/数学索引删除 hook。
- 旧 Compose、图片/辅助角色、搜索等旁路的统一 lease/授权/账本边界；既有功能不删，不造第二套未协调执行器。

## A3：云/设备与 App 交付

- 云任务鉴权/权威 TaskStore/加密凭据 vault/remote executor；先本机 fake/隔离协议，不假称 document-service 已承担通用 Agent。
- 旧 executor 停止证明、fence 移交、离线续作、独立等待；跨 App 默认关，设备恢复前台确认和持续可停止；未知外部效果不能按 timeout 释放/盲重试。
- 共享 Pigeon、DI/导航/依赖和领域工具注册集成；Release Flutter AAR 接线、Debug/Release 分别构建；Mate50/200%/TalkBack/生产按真实环境验收。

## 完成判据与定向验证

- 两会话/两档案并发仍仅一独立执行位；补充要求不新开无关任务、不重复已完成或 UNKNOWN 动作。
- 更新 key 不解暂停、主动撤权、输入/设备确认；模型快照和 reasoning 保持。
- 多文档全部进入证据，文件/来源变化阻断旧成功；OMP 新 verifier 不弱化既有门禁。
- 账本不会漏角色/双计，unknown 不当0；时间边界和跨 owner 查询反例成立；模型/工具文本无法伪造 UI 状态和授权。
- 现有正常闲聊/正确文件/旧数据恢复保持可用；真实进程终止和外部 ACK 丢失按适用范围复验。

先读脚本，再以新 run-id 执行规划/授权/用量/任务表面定向测试，必要旧回归和 Flutter analyze/test；共享 Android/构建资源串行。每批必须有原始日志、准确源码/APK绑定、diff 和回滚。

缺少真实 endpoint、服务器、设备或签名时，交付可运行入口和精确命令并记 NOT_RUN；继续本机实现。独立外部审查 NOT_EVALUATED。输出 `HXai_CHANGE_REVIEW/A-<feature_id>/` 和六类 SOL 交接，更新专属增量后归并共享状态。
