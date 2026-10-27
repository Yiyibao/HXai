# 当前待开发与待验收清单（2026-10-21）

2026-10-22专业数学继续推进：新增合同17的有限C8积分区间余项证书、指定区间唯一根证书，以及已有已校验解析缓存→真实物理页候选→用户修订/歧义确认→原统一Chat批改。来源schema2快照与旧schema1并存，不上传、不猜缺页、不把候选或用户确认当数学证明。协议[合同17](professional-math-contract17.md)，本轮证据 `HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/`。仍需真实Provider的高中综合/分析实变解题质量、OCR逐符号真值及权威盲评；下文历史缺口不用于否认本轮已接线能力，也不缩减完整产品目标。

2026-10-22专业数学接续：合同16已接入当前统一Chat，新增精确线代/多元与归纳、解析余项高精度区间、显式条件证明/子目标与首错工作流、多页可信转录及3D/矢量导出。实现与边界见[专业数学合同16](professional-math-contract16.md)，当前验证取 `HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/`。本地计算/合成链路不替代真实模型综合题、分析/实变推理、真实OCR或权威题库质量验收，完整P2仍未授予通过；下文P2行保留为原检查时点。

2026-10-22 A04 本批完成，声明范围 PASS_SCOPED，见 [通用 MCP/Skills 协议](a04-general-mcp-skills-protocol-v1.md) 和 `HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/HANDOFF_STATUS.md`。已新增普通App的按档案管理/多服务器/逐工具参数授权、任务级调用与多次独立回读，以及独立受限Python服务。实际主Chat→Linux容器→独立回执及4种真实进程恢复通过。下文固定合成MCP/脚本ABSENT等为历史检查点；最新实现与证据取本批交付。OAuth交互授权、任意远端写入专属verifier、额外transport/schema/二进制脚本产物均按明确支持边界拒绝，不当完整协议或生产放行。

2026-10-22接续：**API26 与当前 A04 合流已完成（本机声明范围 PASS_SCOPED）**，不再列作待合流。精确接入原两文件候选，并由真实旧版反例补上后增 McpInstallationStore 的 API26 关闭接口。当前新包139原生方法、四MCP live模式、JVM/普通Debug/lint与包隔离实际证据见 `HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/HANDOFF.md`；协议 `api26-a04-integration-20261022.md`。下文旧时点关于“尚待A合流”的描述保留为历史。A04通用MCP/沙箱、A05、A06、A07以及真实Provider/OCR/目标设备/跨Office仍分别待做；本批不授独立/全项目或发布通过。

2026-10-22 B06 Linux Office 已收尾：真实专属WSL2 Linux/私有Docker/固定UNO镜像的
API→dispatcher→worker→独立validator→下载链最终24场景PASS，完整服务149项PASS。
首次Swap限额实际未生效FAIL及缺回归夹具失败保留，专属子cgroup修正后内核无Swap约束实测；
复杂DOCX/OMML、PPT全页、XLSX日期/图表与写后杀进程/真实丢ACK/停止未知恢复均有证据。
见 [Linux资格协议](parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md)、
`HXai_CHANGE_REVIEW/B06-LINUX-OFFICE-20261021/HANDOFF_STATUS.md`。
下文旧Linux缺口不再表示本批未做；MSOffice/WPS、最终整合API26、目标设备、OCR/发布/生产仍单独验收。
未做独立/完整全项目审查；原A04共享源码与API26候选未改。

本次继续开发：A04 已新增 QA 持久安装注册库、可信前台管理页、安装授权/撤权/卸载墓碑、CAS revision/授权epoch与身份旋转，以及进程恢复专属验证。协议见 `a04-durable-mcp-protocol-v2.md`；实际源码/测试/失败反例与裁决取 `HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/`。普通App的共享MCP继续关闭；通用第三方管理/逐工具scope与服务器脚本沙箱尚未完成。下述首个切片及更早检查时点记录保留为历史，不用其旧“安装不持久”覆盖最新批次。

后续开发增量：A04 的单个只读合成 MCP 已在隔离 nativeqa 的统一 Chat 接通，含输入绑定审批、安装身份、每请求撤权检查和独立回读；13项新增原生测试通过。完整结果见 `HXai_CHANGE_REVIEW/A04-SYNTHETIC-MCP-20261021/` 和 `docs/a04-synthetic-mcp-protocol-v1.md`。该入口仅 QA 显式安装，普通 App 共享 MCP 仍关闭；通用安装/配置/授权管理和服务端脚本沙箱仍待开发。

后续更新：用户要求的 A/B 合流已在 `codex/ab-merge-20261021` 完成同版本定向验证，见 [合流结果](ab-integration-20261021.md)。下文是此前检查时点的清单，其中“补合流回归”已完成；A04–A07等后续开发未由此自动完成。

本轮仅检查当前工作区、Pro 最终需求、最新 A/B 交接和关键源码入口；未修改产品代码、运行 App/构建/行为测试或操作设备。不是全项目审计，独立审查与全项目裁决均为 NOT_EVALUATED。以下历史 PASS 仅引用原批次，不代表本轮重跑通过。

当前 branch：`codex/b04-b05-complete-20261020`；完整 HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。大量实现位于未提交及 untracked 文件，不能按 HEAD 内容推断当前功能。原始 status、tracked/staged diff、关键源码 SHA256 和交付清单比对见 `qa/remaining-development-20261021/`。本轮不查询远端，不对当前 main/PR 状态作结论。

## 一、已实现的基础，不再列为从零开发

| 已有成果 | 当前依据 | 能确认的范围 |
|---|---|---|
| 原生持久任务、全局调度、模型快照、计划与独立验收 | ChatService 的 AgentScheduler.accept/run；AgentTaskStore、TaskPlanner、TaskResultVerifier | 当前主链有接线；真实模型规划质量仍需单独验收 |
| 全局任务中心、实际调用记录、用量查询/分组/导出/提醒 | StudyHostFacade.getAgentTaskCatalog/getAgentTaskDetail/getAgentUsage/exportAgentUsage；AgentUsage* | 用量链已实现；可信金额尚未实现，不能把金额未知当零 |
| A01 XLSX 真实重算、版本与见证 | A-OFFICE-CHAT-20261019；Factory 注册 xlsxRecalculationTool | 本机 UNO/合成主 Chat 有历史局部证据，不等于 Linux/MS Office/WPS |
| A02 删除来源、辅助请求、旧派生物、备份与 Room33 保全 | A02-A03-COMPLETION-20261020 验收矩阵与冻结源码 | 已验冻结检查点，不直接覆盖后来 B 合流版 |
| A03 两讲义 Word、来源/预览/保存、同任务追加、PPT/PDF 故事 | 同批 OfficeCollaborationStoryTest、验收矩阵 | 已有 localhost 主 Chat 与实际本机 Office 产物证据 |
| B04 持久资料库、授权目录、目录/标签、固定版本引用、删除/回收/恢复 | B04-B05-COMPLETE-20261020；MaterialFiles、领域页面及 Host library 方法 | 本机声明范围 WIRED / E4 / 历史 PASS_SCOPED |
| B05 现数学合同 1–15、教材出处和完整转录确认 | MathVerification；Factory.mathVerifyTool；B04/B05 验收矩阵 | 已超出初版 Q[x]；有 Q(x)、积分、Taylor、Sturm 等限定精确能力，不能称通用证明器 |

## 二、确实还需开发

| 顺序 / ID | 当前状态及静态依据 | 剩余实现 | 验收要点 |
|---|---|---|---|
| 1 / A04 安全 MCP 与脚本沙箱 | PARTIAL：单个只读合成 MCP 已在 QA 主 Chat 接线；普通 App 的 MCP/shared workspace 继续关闭，Skills 不执行脚本 | 下一小批为持久服务身份/版本与可信配置/授权管理，再实现独立服务器隔离脚本和安装/依赖授权；不得直接开放共享工具 | 每次调用绑定 owner/task/capability/scope/grantEpoch/inputHash 与安装身份；撤权、跨档案、恶意描述不得越权；脚本网络/资源/文件受限，有独立输出验证 |
| 2 / A05 可信费用 | AgentUsageSnapshot 固定输出 cost.status=unknown、amount/currency=null；AgentUsageExport 校验相同契约 | 增加有出处、版本、有效期、币种和缓存计费规则的价格源或供应商费用；贯通逐调用/任务/周期/UI/导出 | reported/estimated/unknown 分开；失败/重试/辅助角色不漏算、不双计；不设 Token/费用硬预算 |
| 3 / A06 云端持续 Agent、凭据托管与设备协作 | Android Scheduler 使用本机 noBackupFilesDir 数据库；server/agent-runtime 自述为本机协议，无公网 API/生产云 Agent | 账户鉴权、加密 vault、唯一权威任务存储、远程 executor、停止证明与 fence 移交、设备执行器；跨 App 默认关闭、恢复前台确认、可见停止与截图生命周期 | 先 fake executor 合同；旧 executor 未确认停止/UNKNOWN 未核实时不能接管或重放；换 key 不解除暂停/缺输入/设备确认。真实账号/外传/部署另按授权范围执行 |
| 4 / A07 Release 接线 | app/build.gradle.kts 仅 debugImplementation flutter_debug:1.0，未见 releaseImplementation/flutter_release | 生成并接入 release AAR，核对签名、依赖/混淆及正式包实际入口 | Release 实际构建、包内容和冷启动分别验证；当前仅是确认的配置缺口，未复现正式包失败，不定性为已发生崩溃 |
| 5 / P2 完整专业数学 | 原生核验器有界于明确 Q[x]/Q(x) 合同；现合同未覆盖完整产品要求 | 按真实题型补高中综合题、线性代数、高数/分析/实变的定理条件与证明工作流；受限 CAS/高精度/误差界工具；多页手写批改质量体系 | 明确证明/近似/探索；首错先验前缀；正确异法不能误判；建立权威参考题库，真实模型与 OCR 各自评估 |

P2 图形的 3D、SVG/PDF、Office 可编辑输出与病态/奇点场景仍须逐项需求追踪。本轮确认 mathPlotTool 当前直接交付 PNG，但未全面遍历所有图形出口，因此不把其他出口直接判为 ABSENT。可选 Q(x) 有限 Taylor 为下一小合同，未启动；不是完整专业数学的替代。

## 三、已有实现但还需验证或按反例修复

| 项目 | 当前证据和缺口 | 下一步 |
|---|---|---|
| A02/A03 与 B04/B05 合流回归 | 本轮 SHA256：A 冻结清单 29 文件中 25 相同，4 个不同；B 合流清单 34 文件全部相同。4 个差异为 StudyHostFacade、BackupManager、MaterialFiles、MaterialTools，恰与 A 的 SHARED_ROOT_STATUS.json 一致。B 已有本机合流构建/资料库回归，但其最终 Native 类清单未列 A 的 OfficeCollaborationStoryTest、PhysicalHistoryBoundaryTest、DerivedMetadataLifecycleTest | 在当前合流源码上重跑这些故事及备份/迁移/来源边界。差异有出处，尚不是已证实的合并缺陷；不重复套旧补丁 |
| 真实模型规划、执行与语义验收质量 | 现故事大量依赖 localhost 确定性 Provider；不能将工程语料或链路通过当真实模型准确率 | 固定用户选定 provider/model/reasoning 和允许材料/角色，验证目标覆盖、失败重规划、同任务追加、不支持工具和错误验收等；保请求/用量/成果证据 |
| 真实 MinerU/OCR 与完整扫描故事 | 已有 OCR 来源/cache 保护、协议缓存、转录确认与候选 Word；最新批次明确真实识别 NOT_RUN | 实际引擎识别公开或获授权扫描材料，逐页/逐符号对照，记录覆盖/歧义/实际页序，再做手写批改与转文档故事 |
| B06 MS Office/WPS、最终整合API26及目标设备 | Linux/WSL Docker Office已由20261022批次完成限定资格；本机UNO/API34或旧API26候选不覆盖最终合流版/其他Office | 继续专属MSOffice/WPS跨软件保真、A04最终API26合流与目标设备；Linux变更后按B06-LINUX-OFFICE协议用新run-dir与新源码/镜像封印复验 |
| Mate50 / HarmonyOS4.2、TalkBack 语音 | 最新交接均为 NOT_RUN；已有小屏/大字/深浅色与无障碍树证据 | 真机冷启、后台/锁屏、弱网、SAF、暂停恢复、200% 字体、TalkBack 焦点与朗读；不以模拟器或无障碍树代替 |
| 全产品与发布门禁 | 各批裁决均为局部 PASS_SCOPED；独立和完整全项目审查 NOT_EVALUATED | 功能闭环后按 Pro 正式标准执行适用全审；当前禁止子代理，不虚构独立通过。生产部署/发布未执行 |

## 四、建议接续顺序和直接入口

1. 当前合流版本的 A02/A03 定向回归已由 AB-MERGE-20261021 完成，另有 B04/B05 定向、三处真实进程中断及普通包隔离证据；不再列为待补回归。此前状态和交接来源保留在上表及原批次文件。
2. 开发 A04 最小安全扩展；并行准备 A05 价格源合同与 A07 Release 构建接线，不自动开启关闭中的 MCP/workspace。
3. A06 先做不接真实账号的 fake 移交合同；真实托管和设备接管留在明确授权环境。
4. 准备真实 Provider/OCR、B06 和目标设备的分环境验收；P2 按真实题型逐批扩展，避免只增加算法数量。

先读 `scripts/verify_agent_android.py`（含安装/强停/QA 数据清理）与相关类。确认独占合成模拟器、新 run-id 和源码冻结后，可在当前根目录执行如下模板；本轮未执行该命令：

```powershell
python scripts/verify_agent_android.py --build --run-id merged-a02-a03-NEW --serial OWNED_EMULATOR --classes me.rerere.rikkahub.service.PhysicalHistoryBoundaryTest,me.rerere.rikkahub.service.DerivedMetadataLifecycleTest,me.rerere.rikkahub.service.ConversationHistoryToolLifecycleTest,me.rerere.rikkahub.data.db.migrations.Migration_32_33_Test,me.rerere.rikkahub.data.sync.BackupManagerTest,me.rerere.rikkahub.feature.agent.OfficeCollaborationStoryTest,me.rerere.rikkahub.feature.agent.TaskPlanningRuntimeTest
```

任务书：`docs/parallel-development-20261019/A_TASK.md` 的 A04–A07 与 `B_TASK.md` 的 B06。涉及实现时重新建立 CHANGE_CONTRACT、冻结当前脏树、最小增量、失败反例与回滚；不得降库/清除墓碑、原件、版本、receipt 或 UNKNOWN。

百度网盘、邮箱、日历专用连接器继续明确后置；自动更换主模型/推理强度和费用硬预算不属于待实现目标。本轮不计算完成百分比。
