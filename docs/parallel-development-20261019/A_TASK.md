# A线：统一Agent、共享接线和最终App集成

2026-10-22 A04 通用MCP/Skills本批已实现并在声明范围 PASS_SCOPED：多安装/按档案范围授权/前台管理 → 原统一Chat/journal/独立回读 → 独立Linux Python沙箱。实际Android→容器→独立回执故事PASS；普通Debug编译/单测/lint、43项原生方法、4种真实进程恢复与Linux15项通过。协议 `../a04-general-mcp-skills-protocol-v1.md`，精确交付 `HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/HANDOFF_STATUS.md`。下面保留历史任务书；OAuth/额外transport/任意外部写入适配器等仍按支持边界禁用，独立/完整项目审查及发布资格未评估。

这是可交给 Sol/A 开发会话的执行任务书；本轮仅规划，尚未派发或实施。先读本目录 PLAN、根/client AGENTS及指定Pro原文。使用完整产品快照，不从旧HEAD丢弃未提交成果；不开子代理，不重做已接线数学/章节/来源和用量功能。

## A01 首批：接通现有XLSX重算

用户故事：Chat请求修改/重算含多sheet、公式、日期、图表的合成工作簿，完成可信审批、真实异步服务、新版本与见证独立回读、全目标验收、预览/保存。旧PDF正常、原件字节保持。

当前入口：`XlsxRecalculationTool.kt` 已存在，但 Factory/Registry未注册。首先读取：

```powershell
Get-Content -Raw HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/SOL_SHARED_INTEGRATION.patch
Get-Content -Raw HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/SOURCE_TRACE_MATRIX.json
Get-Content -Raw HXai_CHANGE_REVIEW/A-B-SOURCE-INTEGRATION-20261019/SOL_BLOCKERS.md
```

1. 固定最新源码和B最终领域SHA；对七共享文件逐段比较 before/candidate/current。原patch仅提案，含旧数学UNSUPPORTED上下文；禁止强套或覆盖v1.19记忆guard、v1.20数学、v1.21章节、v1.22来源复查。
2. 立 `A-OFFICE-CHAT-<实际日期>` CHANGE_CONTRACT。Factory/Registry→Store远程调用身份/UNKNOWN→独立Verifier→Host异步续接/正确MIME→Flutter卡片/真实按钮全链接入。不能只加工具名称。
3. 复核TaskPlanner实际XLSX证据路径：新文档存在/可解析、公式witness一致、用户修改目标和来源都满足才完成。若旧文档验收不支持XLSX，补最小类型适配；不伪装文本或PDF通过。语义回包后复查全部成果、witness、源和取消/授权条件。
4. 本机合成Chat故事需实际走Planner、审批、lease/fence、journal、重算和独立读回；测试双sheet及非目标内容、正确0值/日期/公式/图表。另测伪witness、缺资产、改源、跨owner、撤权、取消、提交无ACK/下载后丢ACK、重启续接；UNKNOWN不重传、不因超时释放。
5. 新增专属真实Chat测试，类名在创建后写入交接；保留现有 `RemoteToolContinuationTest`、`OfficeConversionRepositoryTest`、数学/章节/来源和记忆guard定向回归。步骤成功和完整目标成功分开取证。

先读 `scripts/verify_agent_android.py` 再用新run-id，只在独占合成模拟器；该脚本有安装/强停/归档后清QA数据。现有脚本/class可用示例：

```powershell
python scripts/verify_agent_android.py --build --run-id a-office-baseline-20261019-01 --serial emulator-5554 --classes me.rerere.rikkahub.feature.agent.RemoteToolContinuationTest,me.rerere.rikkahub.service.OfficeConversionRepositoryTest
```

执行前确认run-id未使用和设备归属；示例仅旧回归，不代替新XLSX主Chat故事。B的真实UNO gateway需单独配置合成身份/端口/fixture，不能无配置套跑其测试。

依赖B01冻结重算合同和可用引擎。暂缺Linux/Docker时可用受控本机UNO完成限定集成，分别记录容器 NOT_RUN；缺真实模型授权用localhost合成模型验证执行链，真实规划质量仍 NOT_RUN。回滚仅本批共享增量/工具入口，保旧PDF、材料/witness、任务/receipt/UNKNOWN、payload2。

## 后续批次（顺序与B交接联动）

| ID | 实施入口/范围 | 可验收成果与依赖 |
|---|---|---|
| A02 数据保全 | `TranslationHandler.kt`、ChatService其他辅助输入、LearningMemoryStore、MemoryRestore/BackupManager、旧title/suggestion/摘要派生物 | 逐入口核对删除来源、v0/v1墓碑和payload2；旧备份/部分staging/跨owner/重启不复活，正常新来源保持。不得照搬已修title/suggestion作为新缺陷 |
| A03 Office完整任务与协作 | TaskPlanner/Store/Host、Chat文件成果、B02/B03领域接口 | 授权两讲义→Word+来源+预览；同任务补充章节要求→新版本且不重复动作；DOCX/PPT/PDF各有实际故事和回读/渲染状态 |
| A04 最小安全扩展 | ChatToolFactory、可信Registry/Dispatch、Skill/MCP管理与可信授权面 | 先单个合成只读MCP的owner/task/capability/scope/epoch/inputHash和撤权；再独立脚本沙箱。描述不能自授权限，未覆盖扩展保持关闭；不把宿主Javascript当服务器隔离沙箱 |
| A05 质量与可信费用 | 现有Provider/ProviderRequestAccounting、AgentUsageLedger、任务质量入口 | 有出处/版本/有效期/币种/缓存费率的价格或供应商费用；reported/estimated/unknown清楚、无硬预算；覆盖工具不支持、同任务追加、真实模型质量（获授权后） |
| A06 云/设备协议 | 原TaskStore/Scheduler、账户授权/vault和remote executor边界 | 先隔离fake：唯一权威、旧executorStopped、递增fence、独立等待、撤权和丢ACK；再授权环境。新服务不冒充document-service，无授权不托管真实key |
| A07 构建和目标设备 | Gradle/Flutter AAR、签名配置、启动入口/任务和文件UI | 核release AAR依赖后分别构建/启动；Mate50后台/锁屏/SAF/200%/TalkBack，输出按环境取证。准备可提前，正式发布等全部必要门禁 |

上述每行各立小批合同，不合为一次大改；新S0/S1优先。下一步发现若需schema/权限/跨档案架构变更追加全项目适用门禁。共享源码只有A直接改；领域修复向B交具体反例和最小接口需要。

交付按PLAN：A-*目录、六类SOL、原失败与新日志、完整diff/hash、准确来源/状态/NOT_RUN、下一命令和保全回滚。不要用局部E4授完整P0、Release或独立审查通过。


## A02/A03 实施结果（2026-10-21 收尾）

上述表格为原任务书；A02/A03已在 `HXai_CHANGE_REVIEW/A02-A03-COMPLETION-20261020/` 完成本机冻结检查点的开发、接线、编译和定向验证。准确源码为 `.local/a02-a03-verified-20261021` / SOURCE_FINAL.zip；共享B新合入保留且另验，不用本轮通过授整合版本通过。A02覆盖剩余physical请求入口、旧派生物、历史查询和完整备份/部分staging/跨owner/真实进程退出保全；A03覆盖两讲义Word来源/真实预览保存/同任务版本补充以及PPT/PDF主Chat故事和实际Office渲染。准确状态取本批SOL_ACCEPTANCE_MATRIX、TEST_RESULTS与SOL_BLOCKERS，不累加历史计数。

新增Room33/derived_metadata_evidence、prepared-inputs-v1、材料sourceReferences与缓存增量；sidecar9/Pigeon164/记忆payload1或2保持。精确A02→A03两补丁已在副本check/apply并逐文件完整字节比对，源码/APK冻结。主分支后来被其他B开发会话切换，本助手没有切回或覆盖；before/与SOURCE_FINAL.zip保留独立准确成果。

局部 PASS_SCOPED 仅本机合成测试；真实Provider规划质量、完整OCR、MS Office/WPS、Linux/Docker、Release/指定Mate50、生产仍NOT_RUN，独立和完整项目审查NOT_EVALUATED。后续A04–A07按本任务书及用户最新指示接续；保删除约束/Room33/材料版本/任务UNKNOWN，不降门槛或自动部署。

## A04 首个切片（2026-10-21）

单个合成只读 MCP 接入现有 Factory/Registry/审批/dispatch/独立Verifier/Planner；仅 nativeqa 显式安装，普通 App 原边界保持。MCP 2025-03-26 无 session JSON HTTP 子集，固定 record/version/installation_id，每次请求检查当前 task/owner/grant/epoch/fence/inputHash，安装描述不能赋权。已保留重装复用旧审批的真实候选失败反例，再新增安装身份绑定修复。13项新增原生协议与主Chat故事已通过；最终编译/相关回归、普通包隔离与未验收项见 `HXai_CHANGE_REVIEW/A04-SYNTHETIC-MCP-20261021/`。完整 A04（第三方管理/授权、脚本沙箱、安装与依赖权限）仍 PARTIAL，不能把本切片当通用 MCP 或宿主 JavaScript 沙箱完成。
