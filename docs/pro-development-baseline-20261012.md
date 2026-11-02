# Pro 交付包研究与后续开发基线

## v1.39 R27-05 离线安全控制接续（2026-10-30）

来源 `HXai_CHANGE_REVIEW/R30-05-DURABLE-CONTROLS-20261030/`，协议与源码地图为[r27 v4](r27-cloud-device-protocol-v1.md)。普通任务详情的暂停/取消/撤销托管现在共用原安全 journal，在任何网络读回前封存意图；恢复/补充仍要求所见修订，不自动重放。保留唯一 Scheduler、原 Chat/工具/Verifier、模型锁、UNKNOWN、原件及成果；Room33/TaskStore13/网络schema4/Pigeon/RPC1/noBackup1不迁移。

旧版API26/API34真实反例均证实离线普通暂停没有 controls；新同源冻结QA包在两设备各12场景通过，包括三类离线安全动作、暂停后真实进程退出读回、PIN锁屏、陈旧确认、停止/档案切换及claim/receipt丢ACK恢复。取消/撤权零DOCX且无后续模型请求，暂停仅明确恢复后生成一份独立XML/hash读回的DOCX；不是云Markdown或真实模型质量测试。App525/AI214 JVM、Flutter18/analyze、新Debug AAR/QA两包/lint及38资源/引擎绑定通过；准确字节和命令取本批最终JSON，不累计历史完成率。

首次冷启20秒超时、旧API34安全键盘未打开、共享ADB传输变化/重复实例启动拒绝、API26多传输reverse及API34失效连接安装失败均保留原日志；恢复只重启自有AVD并保数据，未杀共享ADB或操作06–09设备。完整/独立审查NOT_EVALUATED，真实无线网/跨主机、Mate50/TalkBack语音、普通最终Release/生产未由本批放行；跨App/截图仍DISABLED。未启用子代理或提交/推送/合并/部署。

## v1.38 R27-06–09 质量接续（2026-10-29）

来源为`HXai_CHANGE_REVIEW/R29-QUALITY-06-09-20261029/`与[合同1](quality-06-09-contract1.md)。在原任务详情/唯一Scheduler/Store接本机公开回答、锁定配置、计划/回执引用及用量的不可变封存；原材料意图可发现并核验丢ACK的原修订，未知文件不重写。32题沿原离线逐义务评分，导出不认证Provider/数学正确性，endpoint未知不从Settings猜测。OCR评分跟随App非空latex优先语义；WinRT本机裁切试验没有改善44.09%的基线，保持默认none和强制原页确认。Office读回补OMML结构、非目标文字/表格、逻辑页/隐藏设置、公式/类型及真实缓存，提供同SHA的WPS独占环境准备与离线检查，未接管已有用户WPS。

Room33/TaskStore13/Pigeon/授权/模型/UNKNOWN保持；新本机评测snapshot协议1、OCR evaluatorRevision2/WinRT adapter2的适用字节、原FAIL/修复及构建/定向测试均以本批SOURCE_FINAL/TEST_RESULTS为准，不由本节预授通过。已从项目记录核对固定公开37项的真实Provider授权，沿既有06–09专用工作树/加密App配置续测，源码与根目录增量分开取证。独立参考、真实手写、MinerU新增调用范围、实际WPS、目标设备/正式Release/生产另验，独立及完整项目审查NOT_EVALUATED。未启用子代理或推送/合并/部署。

## v1.37 R27云执行与手机协作接续（2026-10-28/29）

`codex/r27-cloud-device-20261028` / HEAD `070f3d83c294ba0d4a2197d591055e75cff59ac3` 加本批未提交候选；精确源码/包/日志见 `HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/`。普通App配置/逐任务托管、唯一网络admission、任务级file-key vault、共享V6语义独立worker与原生四工具接管已接线，协议和源码地图见 [R27 v1](r27-cloud-device-protocol-v1.md)。Room33/TaskStore13/Pigeon不变，网络库schema3与noBackup委派journal1分别维护，不装入原Pro包。

本机真实手机进程退出→独立worker生成Markdown→重启原会话/成果读回，及WAITING_DEVICE→后台拒绝→前台原生DOCX→桌面独立XML读回通过；同源QA模型锁/重复ACK/委派不重跑保持。修复换钥后待设备动作epoch陈旧/旧READY确认、原生首次并发建凭据目录竞态与旧单测调用破坏；原FAIL保留。新普通Debug/unsigned Release/双AAR/双lint与APK资源/engine/AOT读回、普通Debug冷启/真实云配置页面均有本轮证据。

准确计数和最终源码仅取本批TEST_RESULTS/SOURCE_FINAL，不累加历史。API34合成服务/设备证据不代授真实模型质量、远端TLS托管/KMS、Mate50/API26新云路径、锁屏/全部停止故障、TalkBack朗读、可选跨App屏幕接管、正式发行或生产；跨App/截图工具仍DISABLED。独立审查和完整全项目审查NOT_EVALUATED。保原未提交文件和四张QA图字节；无子代理、提交/推送/部署或真实私料/付费调用。


## v1.36 当前 A/B 线集成（2026-10-28）

集成分支 `codex/ab28-integration-20261028`，保留原未提交 A27/B27 成果后形成本地源码快照；来源为 A27-SETTINGS-COMBINED、B27-B-LINE-CLOSURE、B27-MATH-OBLIGATION-SCORING 的最终封印。产品源码无缺失/冲突，原共享文档保留 B27 全文与 A27 后追加交接；不重复 apply、不覆盖历史证据。Room33/TaskStore13/Pigeon及既有授权、锁模型、唯一执行器与 UNKNOWN 保持。

本轮 JVM App525/AI214/搜索33、Flutter416及analyze、Python123及22子用例、文档服务154、API26混合31方法、普通Debug实际冷启与Host任务查询、评分真实CLI及拒绝反例通过。Android包只复用与当前client源码字节完全绑定的A27冻结产物，不声称本轮重建APK/AAR/Release。精确来源/命令/日志/差异和裁决取 `HXai_CHANGE_REVIEW/AB28-INTEGRATION-20261028/`；接续说明见[AB28集成基线](ab28-integration-20261028.md)。无真实模型/私人材料外发/部署；完整A/B质量、云端、目标设备、正式发行与独立/完整审查仍未放行，不改历史FAIL。

## v1.35 B线数学离线逐义务评分（2026-10-27）

来源 `HXai_CHANGE_REVIEW/B27-MATH-OBLIGATION-SCORING-20261027/` 与[评分合同1](b27-math-obligation-scoring-contract1.md)。复用corpusVersion2原32题分离包，新增本机seal/template/score入口及评估contractVersion1，封存回答、后载参考、精确Unicode证据定位、全题全义务和完整分母，数学结果与工程状态分别汇总。未改App/共享调度器/数学operation/题库，Room33/TaskStore13/Pigeon不迁移。

实际CLI合成输入与反例、定向回归以本批TEST_RESULTS/COMMANDS/SMOKE_RESULTS为准。PASS只授离线字节绑定和评估一致性，不认证执行/授权/时间顺序/数学全文或参考独立性。真实Provider质量、手写/MinerU、WPS/目标设备、当前组合包与API26混合失败仍继承原边界；独立/完整全项目审查NOT_EVALUATED。未调用模型、读取密钥、操作设备、安装、提交/推送/部署，不改原Pro或历史失败。

## v1.34 B线文件与数学质量增量（2026-10-27）

来源 `HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/` 与[领域合同2](b27-file-math-quality-contract2.md)，接续B26-ALL-DOMAINS未交接实现。保留原唯一Chat/Scheduler/模型锁、Room33/TaskStore13/Pigeon。新增候选列表/读取一致拒绝取消持久状态，完整编码材料元数据防动态createdAt重读漂移；继承B26的空白覆盖/取消Word复用和失败证书不反驳目标语义。

真实MSOffice保存/重开/重算与全逻辑页（含隐藏页）PDF/PNG独立读回、当前QA原生领域与共享Chat、纯Kotlin/服务回归分别取证。真实WinRT评分已执行但免确认数学转录质量FAIL，不能因原页人工修订烟测升级为识别通过。32题分离样本不是实际模型或独立盲评。

本批精确源码/包/产物/原始FAIL及隔离运行结果只取SOURCE_FINAL、FINAL_BUILD_BINDING、TEST_RESULTS与SOL_ACCEPTANCE_MATRIX；API26混合批次有无模型入队FAIL，未改模型锁或掩盖。完整B质量、真实Provider/学生手写/MinerU、WPS/Mate50、TalkBack朗读、Release/生产仍未放行。独立/完整全项目审查NOT_EVALUATED；未开子代理、读取密钥、安装依赖、提交/推送/部署。旧规范及失败证据不回写。


## v1.33 本机账户鉴权与移交接收端增量（2026-10-26）

来源 `HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/` 与 [协议v1](agent-account-transport-protocol-v1.md)。接续 A24-02 第二小批的本机合成接线，TaskStore11→13，在原权威事务中增加账户/档案、每请求 bearer scope/epoch、任务级 Keystore 密文引用/轮换撤权、原 requestId 幂等 journal 与输出 hash 读回。受监督接收端只接原 slot/fence 和实际 Job，provider 返回仍最多 COMPLETED_UNVERIFIED。

Room33/Pigeon及原 Chat/模型锁/唯一Scheduler、B材料/数学/文档服务不变。普通App无账户管理/远端启用入口；当前是本机合成账户/Provider与共享原生SQLite，非服务器KMS、网络鉴权服务或独立云进程。手机离线后的云持续执行、真实托管/模型、设备接管、Release/Mate50及独立/完整全项目审查仍未验收。当前精确代码、测试和裁决以本批 SOURCE_FINAL/TEST_RESULTS/HANDOFF 为准，不累计历史计数。原Pro规范和失败证据保留。

## v1.32 原生执行器移交协议增量（2026-10-24）

来源 `HXai_CHANGE_REVIEW/A24-02-EXECUTOR-HANDOFF-20261024/` 与 [协议v1](agent-executor-handoff-protocol-v1.md)。TaskStore10→11，slot增加executor identity，移交journal绑定原owner/task/fence/grantEpoch/目标输入及锁模型快照；Room33/Pigeon和领域schema保持。现有Scheduler.run绑定实际Job，先请求持久化，再cancel/join后记录停止；激活与安全撤销均检查未知副作用并推进原全局fence，不建立第二套调度权威。

实现和测试状态、精确源码及APK以本批SOURCE_CANDIDATE/TEST_RESULTS/HANDOFF_STATUS为准。当前范围是同一本机权威库上的合成双端与现有原生Scheduler接线；真实账号/云vault/网络API/远端停止回读/公网部署及手机离线后云步骤持续执行仍未实现，不向模型/Pigeon/UI开放远端激活。独立审查及完整全项目审查NOT_EVALUATED；原Pro/历史schema和失败证据不回写。

## v1.31 当前Release接线增量（2026-10-24）

来源 `HXai_CHANGE_REVIEW/A24-01-RELEASE-INTEGRATION-20261024/` 与 [Release协议v2](a24-release-integration-v2.md)。将A07精确两源码候选按当前before/candidate/current核对后接到实际feature工作树；构建文档仅追加，不回套缺少后续数学/OAuth/Linux内容的旧候选。当前两variant使用对应Flutter AAR，缺正式密钥时unsigned，QA开关只在Debug显式启用。APK读回绑定实际AAR/SDK的资源、所有ABI engine与Release AOT；独立Manifest/DEX验证普通包无真实QA入口。

当前完整源码副本的两种AAR、宿主Debug/Release含Web静态构建、App516/AI214 JVM、两种lint及62个实际原生方法分别有本批新证据；普通两包冷启/Host空任务查询与独立安装SHA读回通过。原核验器缺engine假成功、命令/辅助脚本失败和首装Debug慢启动保留。当前本机范围PASS_SCOPED；正式签名/Mate50/TalkBack语音、真实Provider/OCR、A06、B合同17整批收尾、完整项目及独立审查不能由本批代授通过。

Room33/TaskStore10、既有Pigeon费用字段、模型锁/唯一任务位/授权/UNKNOWN不迁移；7份A共享差异匹配后续A04/A05冻结来源，B领域字节保全。实际精确源码、增量patch/完整字节回放及裁决以本批SOURCE_FINAL/TEST_RESULTS/HANDOFF为准，不累计历史计数。无子代理、提交/推送/合并/部署或真实模型/私有材料新增外传。

2026-10-22专业数学合同17增量：[认证有限积分、局部唯一根与解析候选确认](professional-math-contract17.md)。仍使用原Chat/模型锁/唯一Scheduler；新增有限闭区间C8积分与严格单调唯一根误差证书，解析候选只读取已有校验缓存，不新增外传或自动确认。候选schema2保留原结果快照及用户修订，旧手工schema1不迁移，缓存清理不丢转录，删源仍撤销。实现/源码与本轮实际证据取 `HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/`；原Pro与合同1–16、A04及历史失败证据保留，完整P2质量/真实OCR/目标设备与独立审查不由本批代授通过。

2026-10-22专业数学合同16增量：[协议与源码入口](professional-math-contract16.md)定义新增数学、手写确认与图形工作流，证据位于 `HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/`。原合同1–15、Room33/sidecar9/Pigeon164、唯一Scheduler、模型锁及A04授权检查保留；新转录schema1只在本机noBackup目录，来源删除后旧确认失效。当前实现与实际测试分别标注，不把条件证明、目录读回、转录确认或数值区间升级为原题完整证明；真实Provider/OCR/目标设备及独立审查另验。

2026-10-22 A04增量：通用按档案 MCP/逐工具范围/前台管理与独立 Python Skill 沙箱见 [协议v1](a04-general-mcp-skills-protocol-v1.md)。普通App新增显式授权入口，旧共享MCP manager继续不向Chat裸开放；原QA合同保留。适用源码、原FAIL、实际测试及裁决仅取 `HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/`。新增extensions注册库1、沙箱journal2、frontend payload1，Room33/TaskStore9/Pigeon164保持。未做独立或完整全项目审查，以下原批次事实按其时间保留。

建立日期：2026-10-12（Asia/Taipei）。本轮范围是研究交付包、核对版本/完整性/可复验范围，并将用户指定的提示词与审查制度持久化为项目规则；未启动整套原生功能实现或全项目审查。

### 用户再次提供的开发前需求：2026-10-12 确认

已完整读取 `C:/Users/Hfff/Downloads/02_FINAL_PRODUCT_REQUIREMENTS.md` 与 `03_P0_SMART_CHAT_SPEC.md`，两份文件与交付包 inputs 同名文件逐字节/SHA256 一致，无新增差异。它们定义最终目标，Pro 交付的 PARTIAL/NOT_RUN 仅说明当前成果，不能成为需求上限；技术选型可调整，产品冻结决定及验收目标继续有效。

| 必须持续实现的目标 | 开发理解与验收重点 |
|---|---|
| 统一 Agent Chat | 默认进入 Chat、闲聊正常回答；行动目标真实理解→持久任务→可修订计划→授权工具→独立复核→成果→总结。确定性演示不能代替模型自主规划 |
| 协作与全局调度 | 同任务补充要求更新原计划，保留已验证步骤；无关请求排队。暂停/取消/等待/未知效果按真实状态处理，设备恢复优先排队且不抢占 |
| 手机与服务器 | 仅真正托管并获授权的服务器步骤能在手机离线时继续；手机动作检查点等待，跨 App 恢复前台确认；文档服务不等同云 Agent |
| 文件系统 | 持久资料库可授权跨会话引用；Word/PPT/Excel/PDF 同等目标，编辑保原件、可追版本、验证内容/结构/版式/重算，聊天删除不误删独立材料/成果 |
| 专业数学 | 江苏高考及大学高数/数学分析/实变等严格推导、定理条件、手写首错、可核对教材引用、符号/数值独立核验、交互图；当前 Q[x] 只是起步能力 |
| 扩展与长期数据 | 网页、Skills/MCP、计算沙箱、记忆与备份为核心支撑；独立授权与撤权有效，删除抑制不因恢复复活，学习记忆区分阅读/提示/独立完成 |
| 模型、费用和界面 | 手动锁模型/推理，不暗中路由；无费用硬预算但安全有界；上报/估算/未知用量透明；可展开实际操作证据，图表控件真正续接任务 |

P0 文档的四个目标场景保留为端到端验收：A 授权目录内两份讲义合成真实可编辑 Word 并可预览/核对来源；B 同任务新增章节整理要求产出新版本而不重复发送；C 后台文档与等待手机外发分阶段，设备恢复确认、未知发送不重发；D 模型不支持工具时如实解释并待用户决定，不伪执行/换模型。本轮仅确认需求，不把这些场景写成已执行或当前 App 已支持。

来源校验与本次文档复审见 [requirements-confirmation.json](../qa/pro-baseline-20261012/requirements-confirmation.json)。

## 1. 必须沿用的指令

执行入口为根目录 [AGENTS.md](../AGENTS.md)，client 的规则也已指向该入口。权威原文保留在交付包：

| 工作 | 必须一起使用的文件 |
|---|---|
| 实现与接续 | `12_SOL_CONTINUATION_PROMPT.md`、`11_HANDOFF_TO_GPT6_1_SOL.md`，结合 `inputs/12_GPT6_1_SOL_CONTINUATION_PROMPT.md` |
| 每批功能/修复/重构 | `10_REVIEW_SYSTEM/NEW_FEATURE_REVIEW_STANDARD.md`、`PROMPT_NEW_FEATURE_REVIEW.md` |
| 全项目与架构/数据库/权限/发布里程碑 | `10_REVIEW_SYSTEM/PROJECT_FULL_REVIEW_STANDARD.md`、`PROMPT_FULL_PROJECT_AUDIT.md` |
| 裁决与出处 | `REVIEW_EVIDENCE_AND_SEVERITY.md`、`REVIEW_SCOPE_AND_SOURCE_MAP.md`、审查制度 README v1.1 |
| 产品目标 | `inputs/01—04`；验收目标为 `inputs/13`，来源规则为 `inputs/14` |

标准定义门槛，提示词定义执行动作，本轮报告定义实际事实。inputs/05、06 是制作任务书；inputs/13 默认需求用例 NOT_RUN；旧 README 不覆盖最新冻结决定。提示词作为工程工作规则使用，不直接当 App 的 Chat 系统提示词替换。

## 2. 本轮版本与完整性

- 本地分支起点 master，完整 HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`；本轮新建 `codex/pro-baseline-20261012`，HEAD 未移动。原 tracked 工作树无改动，交付包为 untracked。
- 只读 `git ls-remote --symref origin HEAD refs/heads/main refs/heads/pro/hxai-safety-delivery-20261012`：远端默认 main 与本地同 SHA；Pro 安全分支为 `d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59`。本轮未重新读取 PR 元数据，Draft/未合并字段仍来自 Pro 历史快照；当前 main 尚不含该安全提交。
- 真实枚举 225 个文件，manifest 记录 224 个其他文件。按精确路径及字节 SHA256 校验：222 项 PASS，另外 2 项路径被改名，但改名文件大小及哈希均精确匹配原条目；无发现内容不匹配。因此内容可用，严格路径完整性为 CONDITIONAL。
- Windows 大小写碰撞对应关系：`evidence/tests/document_baseline_fixed.log` → `document_baseline_fixed (2).log`；`runtime_math_final.log` → `runtime_math_final (2).log`。大写 FINAL/FIXED 文件也存在且分别匹配自身条目。原包未改名、未覆盖，后续可在支持大小写区分的文件系统恢复原路径。
- manifest 自身 SHA256：`12b700793884602939f4f938598ff3d827fa769708b36645dad744c4c20dca9f`。详情见 [integrity.json](../qa/pro-baseline-20261012/integrity.json)。清单自身不在其校验范围；本轮无外部 ZIP/.sha256。
- 输入 README 提到的 `inputs/09_PRO_ONE_TURN_MASTER_PROMPT.md` 确实缺失；已有根目录新版续作提示词可用，不编造该文件内容。

复核命令：`python scripts/verify_pro_delivery.py HXai_Pro_Delivery --output qa/pro-baseline-20261012/integrity.json`。精确路径不完整时退出 1，保持显式异常；它只读交付包，不自动修复命名。

## 3. 交付成果与当前产品必须区分

| 领域 | Pro 成果 | 本轮确认的状态/边界 |
|---|---|---|
| Android 主链 | Flutter→Pigeon→ChatService→Session→GenerationLoop/ToolFactory | 现有真正工具链应保留；本轮抽查 ChatService 执行阶段读取 Settings，尚无本轮候选 TaskStore 接线证据 |
| 安全六文件 | OCR 日志/取消、文档事务撤权栅栏、测试与 QA 说明 | 单独 PR/patch；当前本地 OCR 仍有正文和 URL 日志，安全修复未接入本地产品；patch `git apply --check` 通过，仅可应用性证据 |
| P0 Agent | Python v0.3.0 / SQLite schema2、ModelLock、单 slot/fence、journal、等待/授权、验证、用量 | 可信宿主的本机协议，未接 Android、不含 LLM 规划器/公网鉴权/云凭据库；不可替代原生任务实现 |
| 文件 | XML/PPT/工具投影 Kotlin 助手、版本化 Markdown、Flutter 文件动作候选 | 当前 Editor 仍按 slideN 文件名并用 UTF8 预检查后解析原 bytes；助手未接线；Markdown 不代表专业 Office 保真 |
| 数学 | Q[x] 精确恒等、导数、等式链首错/反例 | 不支持一般高考/分析/实变证明、手写识别和教材索引；UNSUPPORTED 保持未知 |
| UI | 96 张原图、8 张联系表；5 张 Pro 图、HTML；4 个 Dart 源文件含测试 | 已目视看四屏总览；设计资料与候选源码有清晰状态边界；原生任务页/TaskDto 尚未接入，不可用画板计数冒充实际队列 |
| 发布 | 构建建议与 release AAR 锚点 | 本轮未运行完整 Gradle/Flutter/App/Release/Mate50/服务容器/真实 Provider，全部保留 NOT_RUN |

本轮已读根目录分析/架构/实现/测试/安全/交接与续作、七份审查制度、核心需求与来源规则、集成/验证脚本、协议关键源码和当前本地风险入口；未逐行审完 225 个文件、96 个原图或完整仓库。完整项目仍不具备放行证据。

## 4. 本机复验：保留失败，限定证据范围

原始交付目录只读；将其复制到 `tmp/pro-replay-*/HXai_Pro_Delivery`，在副本运行原始 `07_CODE/run_verification.py`。Python UTF8 开启，原生产服务/模型/私有材料均未调用。新日志在 [qa/pro-baseline-20261012](../qa/pro-baseline-20261012/)，被副本继承但未新生成的历史证据已从本轮归档移除，避免混用。

| 本轮实跑 | 结果 | 证据/解释 |
|---|---|---|
| 文档撤权契约 | 15 passed | E3-local，本机 ASGI/SQLite，非 worker/Docker |
| 原撤权负对照 | 2 failed，符合 expected exit1 | 原缺陷重现；不是修复通过计数 |
| 修复后撤权回归 | 2 passed | E2/局部 Store |
| runtime + math | 63 passed，8 failed | 7 项涉及 Windows `os.open(directory)` PermissionError；1 项创建 symlink 因 WinError1314 无权限失败；不升级历史 71 passed 为本机 PASS |
| 交付保护 | 5 passed，1 failed | symlink 创建权限不足；保持 FAIL，不能删除该安全用例迎合环境 |
| 本机 demo | FAIL | 报告硬链接发布后，目录同步 `os.open(folder, O_RDONLY)` 在 Windows 失败；已有写入不能假设未发生或盲重试 |
| 原验证 runner | exit1，未正常完成 | demo 失败后无 evidence.json，runner 再次读取缺失文件中止；不能把副本旧 test_runs.json 当本轮结果，已另建 TEST_RESULTS.json |
| patch check | exit0 | 未 apply，不代表产品已修/构建通过 |
| 全候选集成 dry-run | exit1 | 原始字节 blob 检查遇到 CRLF；HEAD 四锚点 blob 都匹配，前三磁盘 CRLF、Git LF，规范化后内容一致；未跳过保护或 apply |

具体索引：[TEST_RESULTS.json](../qa/pro-baseline-20261012/local-verification/TEST_RESULTS.json)、[runner.log](../qa/pro-baseline-20261012/local-verification/runner.log)、[anchor-analysis.json](../qa/pro-baseline-20261012/anchor-analysis.json)、[integration-dry-run.log](../qa/pro-baseline-20261012/integration-dry-run.log)。Pro 原始 71/6/24 的通过记录仍有效于原环境与源码范围；以上新失败是 Windows 适配/权限及验证入口错误，不推断为 Android 全链失败。

环境：Windows 11 / Python 3.13.13 / SQLite 3.50.4 / pytest 9.1.1 / JDK21。kotlinc、Flutter、ADB 不在当前 PATH；`.local/flutter`、`.local/android-sdk` 等工具目录存在，尚未核实可用版本。NOT_RUN 不能解释为本机完全没有 SDK，也不能照搬 Pro 当时缺完整 checkout 的理由。

## 5. 后续开发顺序与明确验收

1. **集成前置/安全边界**：新建实现 feature 分支/工作树，核查当前远端与本地。审查六文件安全 patch，复用文档撤权测试，补 OCR 取消/敏感日志断言。新 LF checkout 或逐文件三方审核解决 CRLF 锚点问题，禁止为兼容绕过 blob 保护。验证 runner 的失败收集须独立修复；Python 报告落盘需确认平台持久化合同，不能简单跳过目录 fsync 后宣称同等抗断电。
2. **AGENT-01 + MODEL-02**：最小原生闭环，优先 sidecar TaskDatabase；在 ChatService 接受任务时快照 provider/model/reasoning/configRevision/secretReference，Scheduler 统一包住现 GenerationLoop，保留 ConversationSession 检查点。逐 dispatch journal/epoch/fence 与独立 verifier，杜绝旧 job 绕过执行位。验收两会话/两档案唯一运行、排队后改 Settings、写后杀进程、丢 ACK、错误验收谓词。
3. **阻断相关使用的 S1 并行排入批次**：WEB-04 修公共网页/图片全 DNS/redirect/代理/UI 二次下载链；FILE-05 接 XML/PPT 安全助手并运行完整 OOXML；TOOL-06 只截 Provider 投影，完整持久结果保持。OCR cache 按 profile/source/modelRole 隔离及删除传播。各批先合同、反例、实现，再按新增审查提示词复审。
4. **任务桥与 UI-07**：版本化 TaskDto/Pigeon 快照与操作，生成双端；Flutter 显示真实 task/receipt/blockedOn/STOP_REQUESTED。既有 artifact preview/open/save 复用可信 owner/version/hash，旧 epoch 回执丢弃。没有任务真源前禁做假全局任务 UI。
5. **AUTH-03 及支撑**：可信 UI grant、独立等待、云 vault/设备执行器和权威 fence 移交；未有真实账号/部署授权时先隔离 fake/mocks。Memory/Skills/MCP/备份分别补读和回归，不删除已有成果或绕过停用边界。
6. **P1→P2→BUILD-09**：专业 Office 真实重算/全页渲染/保真、MinerU 覆盖/页码；数学核验器注册到统一任务体系并标范围，扩展题库/OCR/教材/证明；完整 AAR/Debug/Release 和目标 Mate50 验收。

下一轮先探测项目已有 `.local/flutter/bin/flutter.bat`、Android SDK 与 Gradle 缓存，读取构建脚本后设置任务专用环境变量。对应可直接执行的门禁：

```powershell
# 在副本/隔离分支复验，不改原始交付 evidence
python HXai_Pro_Delivery/07_CODE/apply_delivery.py <isolated-LF-checkout>
# 本机 Python 修复批次以副本 cwd 为准：
python -m pytest -q tests/test_runtime.py tests/test_math.py
# Flutter/Android 工具与依赖核实后，在各自工作目录执行：
flutter analyze
flutter test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :search:testDebugUnitTest
flutter build aar --debug --release --no-profile
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

Python 恢复失败须保留 UNKNOWN/slot/现有文件；Windows symlink 用例需要支持该权限的受控环境，或有等价平台攻击用例及明确范围，不用 SKIP 冒充 PASS。完整架构/数据库/权限变更批次追加全审；目标设备、真实收费模型/账号、生产仍按既有明确授权执行。

## 6. 本轮裁决与回滚

规则采纳和交付内容研究完成；原文未改、产品功能未宣称接线完成。本轮文档/校验范围可用，路径完整性及跨平台复验为 **CONDITIONAL**；全候选 Windows 集成/原生产品/发布 **不放行**。本轮作者同时复审，非外部独立审查。

回滚本轮仅移除新增规则/文档/证据/只读校验脚本，并撤销 client/AGENTS 的补充段落；不清空数据库、原交付包、用户文件或已有任务。研究产出不移动 HEAD、不推送、不合并安全 PR。后续实现遵守原 Pro 提示词规定的六类 SOL 交付和按批审查证据。

## 7. 原生接续增量（2026-10-12，后续实际工作树）

以上章节为原轮次历史事实，不覆盖后续源码。`codex/agent-model-native-resume-20261012` 已接续原生任务/模型批次，增加sidecar schema5粘性暂停、明确恢复/批准分离、模型/推理请求体保护、压缩原子回写与持久摘要来源。原生定向22项、5真实进程終止检查点、AI209/App410 JVM、普通Debug与lint（0错误/49警告）有本轮证据；不是完整App/Release/Mate50/生产通过。

出处与适用版本：[原生协议v5增量](agent-model-native-protocol-v5.md)、`HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/HANDOFF.md`及SOURCE_TESTED/TEST_RESULTS；架构门禁在`HXai_FULL_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/`。完整AGENT-01/MODEL-02仍PARTIAL：真实模型计划/语义验收、AUTH-03、UI-07、全部旁路/费用依赖仍须继续。当前禁止子代理，独立审查NOT_EVALUATED，不沿用历史review安排。

## 8. 剩余修复接续增量（2026-10-13）

`codex/remaining-review-fixes-20261013` 在保留完整脏树和原失败证据的基础上接续 R02–R05/UI：辅助推理统一 Scheduler/账本、sidecar schema7 独立授权/等待/审批/计划与验收、Provider-only 工具投影、真实 Pigeon TaskDto/命令及 Flutter 静态门禁。原 Room32 和产品模块保持。

Sol 完成迟到 UNKNOWN 工具回执/无租约请求反例复验，并修复新复现的任务面板恢复错队列问题：点击恢复 B 不再发出上一任务 A 的模型请求；B 接受模型保持，重复恢复/补充 ACK 不再次生成。当前原生默认选定类列表 60 项、同 APK 五个真实进程终止点、Flutter 287 项及 analyze exit0 有新日志；最终 JVM/普通 Debug/lint、原始源码与补丁回读绑定由 `HXai_CHANGE_REVIEW/REMAINING-FIXES-20261013/TEST_RESULTS.json` 固定，不借历史计数授予当前 PASS。

详见 [协议v7的v1.5/v1.6校准](agent-model-native-protocol-v7.md) 与本批 HANDOFF_STATUS/HANDOFF/SOL_BLOCKERS。独立外部审查仍 NOT_EVALUATED；完整产品、真实模型规划/语义质量、专业 Office 引擎、Release/Mate50/生产仍未整体放行。当前工作未提交/推送/部署。

## 9. 原生故障回归与 Office 元数据增量（2026-10-13）

`codex/chat-legacy-runtime-20261013` 保留完整前批工作树后接续旧原生夹具：
直接 GenerationLoop 场景接入真实 task lease / 可信工具身份，401/429 有/无 body
独立隔离；UNKNOWN 不因网络恢复、队列恢复或再生成释放执行位。正常已完成请求的
手动再生成仍可用。Room 迁移从真正导出的 27.json 建库再开当前 Room32，
不再从当前库删几列冒充旧库。解析缓存绑定原用户、原任务、grant 和 RETURNED journal；
损坏缓存或旧 Room 无任务回执不能外发/重试。

默认验证入口新增两个完整故障类，实际枚举后逐方法运行；每次 clear 仅作用于
nativeqa，先停止并归档数据库/文件/配置/授权引用与实际截图，跳过 APK 再生代码缓存。
发现 OK 不计为执行 PASS，ADB exit0 下的断言失败仍阻断外层命令。

默认门禁新暴露真实 Office AtomicFile 并发读回退：PDF 已独立落盘成功，
但 downloaded commit 被列表读取回退，续接等待。已保存原始 59/60 日志、
数据状态与真实 Android 的确定性旧版 FAIL；两处 @Synchronized 最小修复
复用 DocumentServiceRepository 惯例，该短 monitor 不跨网络或 suspend。
原两个 Office 续接场景及新的并发提交反例定向 3 项 PASS。

本批可审阅差异、最终原生结果/源码/安装 APK 绑定、截图和续作命令见
`HXai_CHANGE_REVIEW/CHAT-LEASE-REGRESSION-20261013/TEST_RESULTS.json`、
`HANDOFF.md` 和 `SOURCE_AFTER.json`。原 Room32、sidecar7、Pigeon、
授权/模型协议未变；原历史证据不改写，不累加计数。外部独立审查
NOT_EVALUATED；完整产品、真实模型质量、专业 Office 引擎、Release/Mate50/生产
不据此放行。未提交、推送、合并或部署。

## 10. P0完整计划验收增量（2026-10-13）

`codex/plan-completion-gate-20261013` 接续完整未提交成果，修复真实服务反例：两步计划只完成第一步、验收只引用第一步，旧版仍能发布SUCCEEDED。TaskPlanner在语义请求前、TaskStore.acceptGoal事务内及finish发布边界均加入全部计划步骤VERIFIED门禁；旧PLANNED/RETURNED或partial marker不再授予新成功。合法双文件和全部步骤已核验路径保持。

原两类完整15方法11 PASS / 4 FAIL原日志保留，修复后完整默认同一run为67批量+40逐方法全部PASS；987个源码/schema字节与被测输入一致，不与历史轮次累加。Room32/sidecar7/Pigeon/授权/模型协议不变，未清库或批量修改旧终态。

精确版本/源码地图见[原生协议v1.7增量](agent-model-native-protocol-v7.md)及`HXai_CHANGE_REVIEW/PLAN-COMPLETION-GATE-20261013/`的合同、测试证据、patch和HANDOFF。完整项目审查/独立外部审查NOT_EVALUATED；开发与必要反证由同一执行者完成。真实模型质量、专业Office、Release/Mate50/生产不据此放行。未提交、推送、合并或部署。

## 11. P0多文档成果验收增量（2026-10-13）

`codex/multi-artifact-evidence-20261013` 接续完整未提交成果，在TaskPlanner补齐全部已核验Document输出的独立读回/去重/证据哈希及验收后全部文件/来源复查；不是只复查第一份被谓词引用的文件。保v1.7全步骤VERIFIED门禁、原模型/授权/等待/未知效果协议。

原完整规划类8方法6 PASS/2 FAIL日志保留：正确第二成果遗漏被拒，第二文件验收期变化却成功。初版110及追加正常/合法变化源版本后的最终72批量+40逐方法共112项均PASS，987源码/schema绑定；最终同APK五真实进程终止点PASS。同产品SHA的JVM410和最终测试源码普通Debug/lint PASS（50warnings）；来源夹具编译FAIL与修复保留，不计产品负对照，不冒称实际编辑器/保真或累加历史通过数。

精确源码/版本/回归、JVM普通Debug/lint、patch及交接见[原生协议v1.8](agent-model-native-protocol-v7.md)和`HXai_CHANGE_REVIEW/MULTI-ARTIFACT-EVIDENCE-20261013/`。Room32/sidecar7/Pigeon无schema迁移；原Pro规范/旧材料/旧证据/历史终态保留。真实模型语义、专业Office、云/设备、Release/Mate50/生产不据此放行；独立外部/完整全项目审查NOT_EVALUATED，未提交/推送/合并/部署。

## 12. A线请求账本查询增量（2026-10-14）

用户明确分两线后，Sol在`codex/agent-usage-ledger-20261014`接续完整未提交基线，先实现A1b只读切片：AgentTaskPanel→AgentUsageView→新Pigeon查询→StudyHostFacade→唯一Scheduler/Store→owner-scoped请求账本汇总/分页。当前任务/当前档案、日周月/全部、逐请求实际角色/模型、reported/estimated/unknown与字段缺失区分；费用无数据保持未知，不设置预算。Room32/sidecar7和v1.8任务状态/授权/验收门禁不变。

准确源码与结果以`HXai_CHANGE_REVIEW/A-USAGE-LEDGER-20261014/TEST_RESULTS.json`及交接为准，不借历史数值授予新PASS。新只读协议/源码地图见[请求用量协议v1](agent-usage-ledger-protocol-v1.md)。OMP领域文件未由该批修改；完整A线、账单来源/导出/异常通知、全局任务中心、真实模型/云设备/Release/真机仍须继续。独立外部和完整全项目审查NOT_EVALUATED；无真实模型、提交、推送、合并或部署。

## 13. A线计划调用覆盖与同任务追加（2026-10-14）

Sol 沿用 A 线完整未提交 feature 工作树，修复 `TaskPlanner.ensure` 按工具名存在性而非调用数量判断覆盖的问题，以及 `AgentTaskStore.beginCall` 允许额外无步骤调用/旧修订计划dispatch、`publishPlan` 保留旧投影序号的问题。副作用批次必须有足量新 PLANNED 步骤；journal 事务再次检查目标修订与剩余步骤，保留 VERIFIED 回执身份与内容并同步新计划顺序。

真实合成Provider/ChatService/Room/Pigeon/Word链证实旧版一条计划能执行两次写入并发布SUCCEEDED；其他旧版反例与正常对照一起保留。新同任务追加场景验证原任务/锁模型/旧文件保全、回执复用不重写、新成果独立读回、重复命令ACK不再执行，以及拒绝丢弃已核验步骤或用旧步骤充当新调用名额。原 Room32/sidecar7/Pigeon/授权/UNKNOWN/全部文档验收门禁保持。

源码、准确结果、未验范围、patch/回滚和六类SOL见 `HXai_CHANGE_REVIEW/A-REPLAN-COVERAGE-20261014/`；协议增量为 [v1.9](agent-model-native-protocol-v7.md)。不累加历史测试计数，不声称真实模型质量或完整A线完成。独立外部与完整全项目审查NOT_EVALUATED；不开子代理，未提交/推送/合并/部署或调用真实模型。

## 14. A线全局任务浏览与独立用量入口（2026-10-14）

Sol 接续原未完成 A-TASK-CENTER 批次，完成侧栏任务中心/请求用量入口、当前档案跨会话任务目录、
持久计划/调用回执/成果引用详情和 owner/epoch 异步隔离。查询通过新 Pigeon 双端到唯一
Scheduler/Store；会话删除不删除独立任务，缺失会话禁用导航。历史详情不提供伪执行按钮，
实际执行控制继续在关联会话当前任务面板。Room32/sidecar7、授权、模型和 UNKNOWN 不变。

协议及源码映射见 [只读协议 v1.1](agent-usage-ledger-protocol-v1.md)；精确源码、真实运行日志、
旧失败、六类 SOL、补丁和回滚在 `HXai_CHANGE_REVIEW/A-TASK-CENTER-20261014/`。
本轮保存了新的 continuation 快照，不覆盖原基线/Pro 包/历史证据；用独立 emulator-5554
隔离 B 线模拟器。准确测试计数以本批 TEST_RESULTS 为准，不累加历史通过数。
真实费用/导出/异常提醒、真实模型质量、云/设备、Release/Mate50/TalkBack/生产仍须接续；
独立外部和完整全项目审查 NOT_EVALUATED。无子代理、提交、推送、合并、部署或付费模型请求。

## 15. A线实际请求身份分组汇总（2026-10-14）

A-USAGE-GROUPS-20261014 接续完整未提交 feature 基线，原请求用量入口新增提供商、模型、
角色汇总。SQL覆盖完整当前档案/任务/周期过滤窗口，模型按(provider,model)组合区分，
缺身份仍unknown，报告/估算/字段未知与实际0分开；FAILED/辅助/重试都纳入。新增两DTO和
一只读Pigeon API，同源生成双端/AAR/宿主；分页绑定scope/rowid上界/BINARY身份键，
读取不dispatch/resume/reconcile或改变模型/授权/UNKNOWN。Room32/sidecar7没有迁移。

原无分组入口失败与新夹具/旧任务中心夹具失败均保留；仅修正验证引用、实际滚动与独立
合成档案，未放宽产品门禁。真实安装页面等待动态native统计，provider还拒绝旧model帧；
深色200%角色卡片经真实滚动验证。完整原生/JVM/Flutter/普通Debug/lint和五真实进程点的
精确结果、输入源码/APK/产物SHA与本批17文件差异，以
`HXai_CHANGE_REVIEW/A-USAGE-GROUPS-20261014/TEST_RESULTS.json`和交接为准，不借历史计数。

协议及源码映射见[只读协议v1.2](agent-usage-ledger-protocol-v1.md)。B材料同期变更只记录
各快照漂移，不进入本批patch或覆盖其源码。完整A仍PARTIAL：可信费用源、导出、异常提醒、
百万级性能与真实模型/云设备/Release/Mate50/TalkBack/生产待接续；独立外部与完整全项目
审查NOT_EVALUATED。未开子代理、提交、推送、合并、部署或调用真实收费模型。

## 16. A线完整范围JSON用量导出（2026-10-14）

A-USAGE-EXPORT-20261014 在实际请求用量页新增手动不可变JSON快照，覆盖当前档案/任务/
周期和请求/提供商/组合模型/角色的完整筛选范围。源仍是原Scheduler/Store同事务只读账本，
不是第二执行器；沿用MaterialFiles持久意图保存写前预期hash/size，实际文件独立回读后才
返回receipt。重复request复用原字节，落盘后丢ACK回读恢复；未落盘中断不盲重写。
未知null、已报告0、大整数十进制字符串与费用unknown均明确。前台/owner/epoch隔离贯穿。

新Pigeon API和类型159由同源双端生成，AAR/宿主同步构建；Room32/sidecar7没有迁移。
文件/文本/记录资源上限不构成Token或费用预算，原材料、任务控制及UNKNOWN执行位不改变。
协议及源码映射见[用量协议v1.3](agent-usage-ledger-protocol-v1.md)；本批完整失败、日志、
源码/APK/产物SHA、可审阅patch和六类SOL保存在`HXai_CHANGE_REVIEW/A-USAGE-EXPORT-20261014/`。
精确测试和源码漂移范围以该批TEST_RESULTS为准，不累加历史通过数。B线同期源码只记录
漂移和编译依赖，不混入A补丁。完整A/P0仍PARTIAL，可信费用和异常提醒等待接续；独立外部
及完整全项目审查NOT_EVALUATED，真实模型、云设备、Release/Mate50/TalkBack/生产不由本批放行。

## 17. A线导出恢复接续增量（2026-10-14）

本轮A-USAGE-RECOVERY-20261014继承完整feature脏树，新增owner/精确scope的本机不可变
导出意图及只读查询/专用核验API。用量页重开能发现“待核验”意图，手动核验独立回读原
JSON及原预期SHA/size/schema/完整范围后才显示复用receipt。该入口没有capture或写出回退；
缺失/中断/损坏拒绝交付并保原件。新增Pigeon同源双端及版本来源见用量协议v1.4；
Room32/sidecar7不变，新意图schema1仅本机noBackup，不作为授权或成功证据。
实屏还发现混合receipt Card语义合并隐藏状态/复用说明，保留负对照后关闭默认合并，
语义节点及Android重开复验另记；这不替代完整TalkBack体验验收。

实际最终源码/APK/测试裁决、历史FAIL、增量patch及回滚以
`HXai_CHANGE_REVIEW/A-USAGE-RECOVERY-20261014/`为准。此段不预授予通过，完整A/P0仍PARTIAL，
独立外部和完整全项目评估NOT_EVALUATED。不开子代理，不使用真实付费模型或私有外传，
不提交、推送、合并或部署。原有历史批次保留为其自身范围证据。

## 18. A线跨范围历史导出浏览增量（2026-10-14）

A-USAGE-HISTORY-20261014 在当前档案用量页接入本机跨任务/周期/分组历史面板。
只读扫描既有不可变意图，分页绑定全集合摘要，展示原范围，手动原文件核验后才提供预览。
错误/缺失/损坏不重写，旧无投影的导出仍可通过成果库使用。Pigeon新增codec161，
意图schema1/Room32/sidecar7不变；原授权、任务、模型锁和UNKNOWN语义保留。

实际源码/APK/原始测试、失败记录、适用裁决及回滚位于
`HXai_CHANGE_REVIEW/A-USAGE-HISTORY-20261014/`。此处不预授予测试通过；完整A/P0仍PARTIAL，
独立及完整项目评估NOT_EVALUATED。下批继续可信费用来源、异常消耗提醒或SOL_TASK的A1a/A2/A3。

## 19. A线前台请求用量提醒增量（2026-10-15）

A-USAGE-ALERTS-20261015 继续同一A线完整feature工作树，从原实际请求账本派生结果未知、
完成但未报告用量及最近5分钟单任务至少20次请求的核对提醒。Chat任务折叠面即可显示，
用量页对完整当前范围读取；无费用来源保持未知，正常已报告0不当异常，密集不等于错误重试。
原授权/模型锁/暂停/UNKNOWN执行位不变；无预算、自动停机、重发或新模型调用。
Pigeon末尾162及真实链版本见用量协议v1.6，Room32/sidecar7/导出schema1保持。

当前字节、实际测试、原始失败与范围裁决以HXai_CHANGE_REVIEW/A-USAGE-ALERTS-20261015/
为准。仅前台App提示，完整A/P0仍PARTIAL；可信费用、后台系统通知、规模性能、真机/
Release/云设备与完整TalkBack待接续，独立与全项目评估NOT_EVALUATED。不开子代理，
没有提交、推送、合并、部署或收费调用。原Pro副本Windows兼容复验失败独立保留，
不改原包或通过削弱POSIX持久性/符号链接门禁消除FAIL。

## 20. A线后台用量核对通知增量（2026-10-15）

A-USAGE-NOTIFICATIONS-20261015 在原请求账本上增加无正文失效事件、后台生命周期观察者、
独立系统通知渠道和 noBackup 提醒尝试journal。默认关闭的专用开关通过Flutter用量页
真实原生设置入口启用，不把旧生成通知开关或按钮点击当启用证据。同任务同规则最多一次
尝试；中断保留尝试后不重投歧义通知，不把系统API返回当展示回执。UNKNOWN、模型锁、
授权及原始账本不改变；无新执行器、预算、真实收费调用或云端继续执行承诺。

来源/版本/源码映射见用量协议v1.7和 HXai_CHANGE_REVIEW/A-USAGE-NOTIFICATIONS-20261015/。
原始失败、准确结果及范围由本批TEST_RESULTS/六类SOL固定，不预授予通过、不累加历史
计数。完整A/P0、真实模型/可信费用/百万级性能/Release/Mate50/云设备/完整TalkBack仍
待接续；独立及完整项目评估NOT_EVALUATED。不开子代理，不提交/推送/合并/部署。

## 21. A线后台提醒查询规模增量（2026-10-15）

A-NOTIFY-SCALE-20261015 接续完整 feature 脏树，将后台提醒的全剩余账本逐页汇总改为
owner/task 索引与相关存在性探测；候选只在当前页保存，投递前同一只读SELECT复核授权和规则。
sidecar7→8只加两个非唯一索引，Room32/Pigeon162/journal1保持；原任务/授权/UNKNOWN保全。
准确原始百万行负对照、查询计划、真实旧Android库迁移及最终结果见本批TEST_RESULTS与HANDOFF，
此记录不预授予PASS。来源/兼容/回滚见用量协议v1.8；原包、原规则和历史失败不改写。
独立与完整项目评估NOT_EVALUATED，不开子代理，不提交/推送/合并/部署或调用付费模型。

## 22. A线任务中心安全控制增量（2026-10-15）

A-TASK-SAFETY-20261015 在原任务详情接入暂停、取消、主动撤权的专用宿主入口，
当前档案可收紧已删除会话或非当前选中任务的执行权限。复用唯一 Scheduler、原事务与
task_commands；安全入口拒绝恢复、补充目标、输入/设备确认等动作，不扩原会话入口。
终态保护、owner/epoch/revision/fence、幂等 ACK 与 UNKNOWN/slot 保全均有定向验证入口。

实际结果、失败、当前字节/产物绑定及适用裁决以本批 TEST_RESULTS/HANDOFF 为准，不预授予PASS。
版本与源码映射见用量协议 v1.9；sidecar8/Room32/原 DTO codec162保持，无费用预算或新模型请求。
原 Pro 规则与历史证据保留。完整 A/P0、独立与完整项目审查仍须另评估；不开子代理，不提交、
推送、合并、部署或使用真实收费模型。



## 23. A 线任务详情恢复增量（2026-10-15）

`HXai_CHANGE_REVIEW/A-TASK-RESUME-20261015/CHANGE_CONTRACT.md` 定义本批范围：复用既有 taskCommand/TaskStore/Scheduler，接任务详情恢复；原输入/当前分支/精确检查点绑定，已锁模型保持，UNKNOWN/撤权/独立 blocker 保持。协议增量 v1.10；Room32/sidecar8/codec162 不变。原生错误分支反例、Flutter 缺失入口/布局失败分别留存。本节不预授测试/整树/App 通过；实际命令、输入/产物 SHA、结果和局限见该批 TEST_RESULTS/HANDOFF。独立审查 NOT_EVALUATED；无子代理、提交、推送、部署或付费模型请求。

## 24. A 线持久恢复命令回执（2026-10-16）

A-RESUME-RECEIPT-20261016 补齐已持久恢复命令在页面/进程重开后的发现：原 task_commands 事务存原 revision/fence，schema9 保留旧行/摘要/任务/授权/UNKNOWN；旧行不猜测映射。新只读 Pigeon 查询显示命令已记录，显式协调原命令另走既有执行路径。宿主也限制旧 ACK 只能协调尚未推进且原边界不变的 QUEUED 任务。Room32 保持；codec163 同源双端。协议/源码地图为 v1.11，准确反例、验证、产物及回滚见该批 TEST_RESULTS/HANDOFF，不由本节预授整树通过。完整 A/P0 仍 PARTIAL，独立及完整项目审查 NOT_EVALUATED；不开子代理，不提交、推送、部署或调用付费模型。

## 25. A线重新生成检查点来源（2026-10-17）

A-CHECKPOINT-IDENTITY-20261017 固定重新生成的目标输入前缀与选中分支身份，在原Room消息JSON原子保存可信task来源；当前任务从所选回答的来源定位并与Store原请求双向校验，不以相同正文或更高fence代替身份。旧随机任务无确定依据仍保守拒绝。模型接受快照、授权/UNKNOWN/单执行位保持；Room32/sidecar9/Pigeon163无迁移。协议和源码映射为v1.12；真实失败、源码与测试/APK绑定、使用及下一步以本批TEST_RESULTS/HANDOFF为准，不预授完整A/App通过。独立及完整项目审查NOT_EVALUATED，无子代理、提交、推送、部署或真实收费调用。

## A线增量 v1.13（2026-10-17）：手动记忆请求删除保全

原Pro需求F/FILE-10与v1.1删除抑制门禁不变。本批A-MANUAL-MEMORY-TOMBSTONE-20261017将原手动记忆请求ID与全类型旧HMAC墓碑核对，接入原addMemory/Room及BackupDeletionMerge/MemoryRestore链，阻止换分类复活已删除请求；新request及不同profile保持独立。Room32/sidecar9/Pigeon163/MemoryState1不迁移。出处、精确patch与实测范围见该批CHANGE_CONTRACT、TEST_RESULTS和HANDOFF；不预授完整A、历史清理、全备份/独立审查或生产通过。


## A线增量 v1.14（2026-10-17）：历史已复活记忆的上下文抑制

A-MEMORY-CONTEXT-SUPPRESSION-20261017 在原 LearningMemoryStore.relevant 接入 v1.13 请求身份墓碑，阻止旧漏洞已换分类落库的原手动请求继续进入Chat记忆上下文。保留原库记录/history/删除标记和本机管理入口，合法新请求/新明确来源与其他owner保持。Room32/sidecar9/Pigeon163/MemoryState1均不迁移；无新执行器、模型切换或外部授权。

实际源码/负对照/测试/产物及精确增量见本批CHANGE_CONTRACT、TEST_RESULTS与HANDOFF；本节不预授完整A/App通过。来源/协议地图为v1.14；未映射旧指纹约束、物理历史清理与全备份语料不由本批证明完成。独立及完整项目审查NOT_EVALUATED，无子代理、提交、推送、部署或真实收费调用。

## A线增量 v1.15（2026-10-18）：旧指纹删除约束的上下文保护

A-LEGACY-MEMORY-CONTEXT-20261018 接续最新 A 线遗留，在原 LearningMemoryStore 的检索/Provider 记忆投影中消费 recoveryBlocked；旧 fingerprint0→1 迁移保留已有保护，无法映射的事实/主题墓碑仍保留。受保护上下文不写使用投影，原件仍可本机管理；自动抽取入队返回0以保持普通闲聊完成。Room32/sidecar9/Pigeon163/MemoryState1不变，协议/源码地图v1.15。

精确补丁、旧版反例、真实 loopback Chat、进程退出、定向回归和构建结果以本批 TEST_RESULTS/HANDOFF 为准，不由此节预授通过。Flutter 保护状态 DTO、历史正文/摘要、孤立 source 墓碑及完整备份语料仍需接续。完整 A/P0 仍 PARTIAL，独立与完整项目审查 NOT_EVALUATED；不开子代理，不提交/推送/合并/部署，不使用真实收费模型或新增私有外传。

## A线 Flutter记忆保护接线增量（2026-10-18）

同批恢复补强包含实际App后台恢复时的状态重查（不丢在途设置）及原生等待Settings初始化。旧真实恢复/启动默认值反例先保存，再同源重建并复验，不能用初版候选已通过的范围覆盖新发现。最终patch为16文件（含main.dart），初版15文件候选单独保留。

本批来源 HXai_CHANGE_REVIEW/A-MEMORY-PROTECTION-UI-20261018/CHANGE_CONTRACT.md。接续v1.15原生保护，Flutter新增owner/contextEpoch绑定真实保护DTO，区分持久设置与实际暂停；未知/错误不默认开启。保护中允许关闭与管理，不允许用开关解除保护，DataStore写内核对owner/epoch。Pigeon末尾codec164与双端生成码配套，Room32/sidecar9/MemoryState1保持，源码地图v1.16。

真实测试结果、同源AAR/宿主与精确补丁取该批TEST_RESULTS/HANDOFF，不据此宣称完整App或A/P0完成。历史正文/摘要、孤立source与完整备份语料仍待接续。独立与完整项目审查NOT_EVALUATED，无子代理、真实收费模型、私有新增外传、提交/推送/合并或部署。

## A线历史来源与摘要过滤增量（2026-10-18）

本批来源 `HXai_CHANGE_REVIEW/A-HISTORY-SOURCE-SUPPRESSION-20261018/CHANGE_CONTRACT.md`。继续 v1.16，在实际候选消息上核对便携 HMAC，缺少 source 行仍可识别 v1 来源墓碑；主请求、工具 prompt、自动摘要与手动压缩使用完整轮次投影。存在来源删除约束或保护状态时，无来源清单的历史摘要保守跳过；当前来源已删时拒绝续发，压缩期间变化时重查并拒绝迟到结果。原聊天、墓碑、合法新 ID 与其他 owner 保持。Room32/sidecar9/MemoryState1/Pigeon164 不迁移，源码映射 v1.17。

精确源码/原版失败/实际测试/产物/补丁以本批 TEST_RESULTS 与 HANDOFF 为准，不由本节预授通过。无法逆解的 v0 孤立 source 与 fact/topic 原文映射、摘要来源签名、title/suggestion 辅助链、全备份语料、完整A/P0及真机/Release仍待接续。独立及完整项目审查 NOT_EVALUATED；不开子代理，不提交/推送/合并/部署或调用真实收费模型。

### 2026-10-19 接续（v1.18）

同批接续合同 `CONTINUATION_CONTRACT_20261019.md` 补 title/suggestion：完整来源投影后取末尾窗口、全空零请求、取得执行位后和回包后重查。元数据/手动压缩最后提交与来源校验共用原 Room 事务，旧删除变化不能被迟到结果覆盖。无 schema/桥/模型/权限/执行器变更。原回归清理通过 ChatService 删除会话，保留生产旧会话拒绝校验。

真实本轮结果见同批 TEST_RESULTS/SOL_TEST_EVIDENCE/HANDOFF；原 2026-10-18 未完成记录和失败日志保持。v0 孤立墓碑、其他辅助输入与旧派生元数据清理、全备份、完整A/P0、Release/目标真机仍需接续；不以本节授予未运行范围通过。独立与完整项目审查 NOT_EVALUATED，无子代理、真实收费、私有新增外传或部署。


## v1.19 A线孤立v0来源删除约束保全（2026-10-19）

来源合同：HXai_CHANGE_REVIEW/A-LEGACY-SOURCE-PRESERVATION-20261019/CHANGE_CONTRACT.md及CONTRACT_AMENDMENT_SCHEMA2.md。LearningMemoryStore迁移逐一登记可映射v0墓碑；未映射HMAC原值保存在legacySuppressedSources，不逆解正文、不以便携key重算旧marker。此类payload显式schemaVersion2并保持recoveryBlocked，旧schema1恢复器不得消费；健康数据及旧缺字段JSON仍兼容新读取器。Room32/sidecar9/Pigeon164不变。

未映射来源存在时，主/摘要/辅助请求的历史投影及历史来源查询明确拒绝，context/relevant为空且自动抽取停用；管理记录与其他owner保留。迁移读取消息前核对会话profile，不能用其他owner正文认证来源。MemoryRestore合并两侧未映射集合和保护状态/最大payload版本；BackupManager在staging核验版本和保护一致性。没有新增执行器/权限/模型路由或费用预算。

实际测试、源码/APK/patch哈希及裁决以本批TEST_RESULTS/SOL_TEST_EVIDENCE/HANDOFF为准；本节不预授PASS。既往已经丢失的v1墓碑不能凭空恢复；全备份语料、其他辅助入口/旧派生物、B共享集成、真实模型、云设备/Release/Mate50及独立/完整项目审查继续保留原未完成状态。回滚须理解payload2，保库/key/墓碑/UNKNOWN，不降级保护。


## v1.20 A线接入B精确数学工具（2026-10-19）

来源：HXai_CHANGE_REVIEW/A-B-MATH-INTEGRATION-20261019/CHANGE_CONTRACT.md；B最终来源为B-RATIONAL-STURM-BOUND-20261019/SOURCE_FINAL及四共享候选，按before/candidate/current逐字核对后仅归并数学部分。Factory注册mathVerifyTool，Registry赋local.compute/COMPUTE/EXACT_MATH；TaskResultVerifier从持久输入独立重算，拒绝伪状态/错输入/改证书/跨owner和非单Text结果。TaskPlanner仅允许明确Q[x]/Q(x)合同1–15范围的tool_verified，并保留全部步骤VERIFIED、原文档/记忆/语义hash及用户完整目标验收门禁。四共享修改不包含候选章节注册，v1.19记忆guard保持。

没有新增数学算法、执行器、外部模型、权限/费用硬预算或schema/桥接字段；Room32/sidecar9/记忆payload1或2/Pigeon164保持。B领域十文件SHA与最终交付一致；领域算法证据不能当全部Chat质量证据。本批真实Chat代表场景为恒等与有理闭区间误差界，错误/未知及语义hash不符不发布任务成功。其他合同虽统一可达，其逐题型完整目标质量仍需实际故事/真实模型验证。

准确原版FAIL、原生/JVM/Debug/lint、六任务持久输出与Fraction独立核验、产物/源码SHA和patch见本批TEST_RESULTS与HANDOFF；本节不预授未运行范围PASS。数学UI专用呈现、章节/Office主链、真实Provider、Release/目标Mate50、全A/P0、独立及完整项目审查仍不因本批自动完成。回滚只停数学入口/逆本批共享差异，保任务/receipt/UNKNOWN/材料/记忆payload2，不放松原门禁。


## v1.21 — 章节共享接线（2026-10-19）
A-B-CHAPTER-INTEGRATION-20261019在v1.20上接B3o三章节工具/local.read/真实源重推导步骤Verifier。TaskPlanner对所有已VERIFIED章节步骤在目标语义验收前后绑定owner/material_id/version_id/source_sha256/index_sha256并复验真实字节，包括非谓词单列步骤；源变化/删除不得沿用旧回执成功。只支持固定版本提取标题候选与正文读取/字面搜索，不推断整章完整、印刷页或数学结论。Room32/sidecar9/Pigeon164/记忆payload1或2及数学EXACT_MATH/旧记忆guard不变。实际证据/限制以本批TEST_RESULTS/HANDOFF为准；独立及全项目审查NOT_EVALUATED。
## v1.22 — B 来源检索共享接线（2026-10-19）

来源 `HXai_CHANGE_REVIEW/A-B-SOURCE-INTEGRATION-20261019/CHANGE_CONTRACT.md`。继承 v1.21 数学/章节和 v1.19 记忆保护，核 B2d→B2i 搜索器版本增量及 B2e/B2f 精确字节后注册三来源工具。可信 local.read/READ/SOURCE_READBACK 从持久原输入独立重建完整结果；GenerationLoop→TaskPlanner 将实际输入和 journal/Room 回执绑定，在目标语义验收前后检查所有来源步骤，包括未列谓词、无命中源和材料目录变化。

Room32/sidecar9/Pigeon164/记忆payload1或2保持，无新执行器/模型路由/权限种类/费用预算或私有外传。工具成功、到提取末尾或目录末尾都不等于整文完整/数学证明。原版入口 FAIL、仅注册后源变更假成功 FAIL 和修复验证、源码/产物/patch 以本批 TEST_RESULTS/HANDOFF 为准，不预授完整 App/P0 或 Office/Release/真机通过。独立与全项目审查 NOT_EVALUATED，开发及定向反证由同一执行者完成。

## v1.23 — XLSX 主 Chat 完整本机闭环（2026-10-19）

`A-OFFICE-CHAT-20261019` 将既有 B1b 真重算接至统一 Factory/可信审批、UNKNOWN/原任务异步续接、正确 XLSX MIME、最终目标证据及真实预览/保存路径。仅接工具/下载链仍会在最终语义期间见证损坏后假成功，已保存原 FAIL；现在所有重算步骤在语义请求前后分别重验源、实际工作簿、manifest、witness、取消和原 journal/Room 回执，并将独立重算摘要纳入 evidenceHash。原 v1.19–v1.22 的记忆/数学/章节/来源门禁保持。

Room32/sidecar9/Pigeon164/记忆payload1或2与重算manifest/witness v1不变。9个实际本机UNO/HTTP/Android主Chat场景、56项原生旧回归、129服务/403Flutter/480App JVM、analyze/AAR/普通Debug/lint及真实Chat产物原生重开通过；原失败和夹具存储限额/读回器选择错误另存，不改安全限额迎合。实际源码、APK、产物、命令和局部裁决见 `HXai_CHANGE_REVIEW/A-OFFICE-CHAT-20261019/TEST_RESULTS.json`，重算证据仅保存缓存范围，版式/跨Office/容器/Release/Mate50/真实模型/生产仍分别 NOT_RUN。独立和完整全项目审查 NOT_EVALUATED，不开子代理。按用户新指令，完成本批后进入 `docs/parallel-development-20261019/B_TASK.md` 的 B01 领域稳定性首批，再按依赖接续后续 B 批次。

## B02 DOCX/OMML 领域协议 v2（2026-10-19）

用户“开发B02”。本批修复跨段TOC复杂域的运行/整段/公式编辑绕过；旧源实际写坏三种域结果的反例和修复后无输出拒绝均保留。增加 UTF-16 精确范围 `replace_paragraph_text`，跨普通不同样式运行、继承首个选中运行格式，不跨书签/公式/图片/域等结构；OMML统一生成器支持有界嵌套分数/上下标/根式/行，严格键与资源限制。编辑缓存 edit-v4、DOCX创建缓存 material-write-v3；原不可变版本保留，不改A共享接口/Room32/sidecar9/Pigeon164/权限/模型配置。

两份三页合成Word在Android真实工具检查→局部编辑→公式替换→版本回读，并逐来源读回/复核引用；实际创建一份含两源版本/摘要的可编辑讲义说明。LibreOffice分别导出两源/两新版及说明，独立PDFium实际渲染13页；修改页与页眉/目录/图片/表格/末页代表图已查看。仅本机领域验收，不宣称完整主Chat自主两讲义故事、目录自动重算、跨Office保真、Linux隔离或目标设备通过；独立/全项目审查NOT_EVALUATED。源码、当轮计数、完整失败/修复日志和交付见 `HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019/` 与 `parallel-development-20261019/B02-DOCX-PRECISION-PROTOCOL.md`，历史证据不累计当完成率。

## v1.24 — A02 消息翻译来源保护（2026-10-19）

来源 `HXai_CHANGE_REVIEW/A-TRANSLATION-SOURCE-20261019/CHANGE_CONTRACT.md`。ChatService 从当前真实消息/完整分支识别来源删除约束，拒绝被删来源、同轮回答、无清单旧摘要、伪造/旧分支/跨owner输入；TranslationHandler 固定原角色模型并接回原会话任务身份。可选 ProviderRequestGuard 在实际 accounting 前复查；排队/回包/提交再查。消息译文缓冲后以原 Room 事务 update-only 单节点 CAS 保存并发布，不整份覆盖新聊天、不复活会话、不清旧译文；独立翻译页保留流式。

Room32/sidecar9/Pigeon164/记忆payload1或2不迁移，数学/章节/来源/Office/用量原门禁保持；新请求钩子为源码/二进制配套增量，协议/源码地图见 `parallel-development-20261019/A02-TRANSLATION-SOURCE-PROTOCOL.md`。真实旧版5项失败、修复后测试/构建/APK与精确patch以本批TEST_RESULTS/HANDOFF为准，本节不预授PASS。旧派生物清理、其余辅助入口、全备份语料、完整A/P0、真实Provider/Release/Mate50和独立/完整项目审查继续待验；无子代理/提交/推送/部署或私有新增外传。

## v1.25 — A02 自动记忆抽取来源保护（2026-10-19）

来源`HXai_CHANGE_REVIEW/A-MEMORY-EXTRACTION-SOURCE-20261019/CHANGE_CONTRACT.md`。在原辅助角色任务链上核对真实选中Room消息、会话owner/lifecycle、job/source身份与删除墓碑/legacy保护，排队后和physical accounting前复查，回包及同事务保存时再查；失效job取消，旧候选不得复活。原角色模型、授权、UNKNOWN、Room32/sidecar9/Pigeon164/记忆payload1或2保持。协议/源码地图见`parallel-development-20261019/A02-MEMORY-EXTRACTION-SOURCE-PROTOCOL.md`。

原版失败、夹具失败、当前测试/源码/产物/patch与范围以本批TEST_RESULTS/HANDOFF为准，本节不预授PASS。其余辅助输入、旧派生物、全备份、完整A02/A/P0、真实Provider/Release/Mate50仍待接续；独立及完整项目审查NOT_EVALUATED，无子代理、提交/推送/合并/部署或私有新增外传。

## B03 PPTX/PDF 领域协议 v1（2026-10-19）

用户“继续开发B线”。`B03-PRESENTATION-PDF-20261019` 在原 B1d/B1e 上补 PPT 真逻辑/隐藏/可见定位、unsigned XML 身份规范值与 mc:AlternateContent 全入口保护；旧编辑器实际写入未显示分支但可见页无变化的反例保留。新版本分组文字与隐藏页编辑后，源/新版各3可见页、各5含隐藏页全部实际导出/独立渲染，母版/图片/关系/无关 DOM 与未改页保持；PDF 原页组装新增 CropBox/旋转/UserUnit/空白/扫描图重复页的真实几何和像素对照，不冒充 OCR 或文档级交互对象保全。

统一正文/manifest/真实源 PDF 页数核对，拒绝7类假覆盖/丢警告缓存。既有 edit 工具新增本地 `pdf_to_editable_docx`，复用唯一 Word writer，保留所选候选文字/字面 LaTeX/警告/出处及每页真实参考图，并使图标题在实际 Word 分页中与图片同页；receipt 明确识别未核对、只覆盖 OCR 块、未做数学核验。编辑缓存 edit-v5，候选稿独立 pdf-ocr-draft-v1；没有 A 共享接口、schema、模型或权限迁移。UNO FilterData 普通 tuple 被忽略的真实反例另存，生产导出改显式属性序列；同一份输入实际 PDF 图片 RGB 无损读回通过。

当轮499 App JVM、31 native PASS/1 Android硬链接权限SKIP、143服务测试、普通Debug/lint及相关真实Office旧回归取本批原始日志，不累计历史计数。整体 CONDITIONAL；真实 MinerU/完整扫描识别、Docker/Linux、完整主Chat、MS Office/WPS、API26/Mate50/Release/生产及独立/全项目审查未由本批验收。源码映射、残余 Windows UNO 关闭异常、环境门禁、精确补丁和回滚见 `HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/` 与 `parallel-development-20261019/B03-PRESENTATION-PDF-PROTOCOL.md`。本节追加前的同期 A02 文档保留，未纳入 B03 差异。

## v1.26 — A02 OCR 来源与缓存保护（2026-10-20）

来源 `HXai_CHANGE_REVIEW/A-OCR-SOURCE-20261020/CHANGE_CONTRACT.md`。GenerationLoop 将完整原消息的删除投影校验传播至实际输入转换；OcrTransformer 在缓存访问、physical accounting 前、回包后和本地缓存发布时核对来源、文件字节、接受 owner 与角色授权。来源失效单独中止，不被普通 OCR 失败占位符吞掉；缓存按消息身份和版本隔离，保留但不复用未经新合同验证的旧缓存。最后的来源检查和缓存写入复用原 Room 事务，不跨网络。

Room32/sidecar9/Pigeon164/记忆payload1或2、模型锁/授权/UNKNOWN 和既有 A/B 接线保持。实际旧版反例、夹具及混合 APK 归档失败、最终编译/测试/源码/产物/patch 以本批 TEST_RESULTS、BUILD_AND_FIXTURE_NOTES 和 HANDOFF 为准，本节不预授 PASS。源码地图见 `parallel-development-20261019/A02-OCR-SOURCE-PROTOCOL.md`；其他辅助请求、旧派生物/全备份、完整 A02/A/P0、真实 OCR/Provider、Release/Mate50 继续待验，独立与完整项目审查 NOT_EVALUATED。未开子代理、提交/推送/合并/部署或新增私有外传。

## B04a 资料库真实源读取协议 v1（2026-10-20）

来源 `HXai_CHANGE_REVIEW/B04A-LIBRARY-READ-LIFECYCLE-20261020/CHANGE_CONTRACT.md`。本批接续B04首个子批，在既有MaterialFiles.readContent复用冻结/hash/最终源读回，停止消费/写入未绑定源的read-v6磁盘正文缓存；最近一次成功提取仅存实例内有界一份，热读仍核真实源身份/字节。提取成功/失败/取消只更新仍存在且匹配的当前元数据，保留并发校对，不复活删除记录或覆盖替换源。普通读取无摘要仍不签发来源receipt；原MaterialReader覆盖率及既有来源/章节/库检索保持。

Room32/sidecar9/Pigeon164/记忆payload1或2、模型锁/授权/UNKNOWN及A共享源不变。501 App JVM、53 native方法、普通Debug/lint与两个真实Chat附件运输及独立源回读仅授予本机合成B04a PASS_SCOPED；实际源码/APK/失败/补丁见本批TEST_RESULTS/HANDOFF，协议/源码映射见 `parallel-development-20261019/B04A-LIBRARY-READ-PROTOCOL.md`。完整B04仍PARTIAL，目录/标签/用户删除与备份语义等另立B04b；真实Provider/OCR、提取中进程kill、Release/目标设备/生产和独立/全项目审查继续未验。无子代理、提交/推送/合并/部署或私有新增外传。

## B04/B05 资料库与数学来源协议 v1（2026-10-20）

来源 `HXai_CHANGE_REVIEW/B04-B05-COMPLETE-20261020/CHANGE_CONTRACT.md` 及补充合同。用户要求完整实现B04与B05；在原B04a/现数学合同1–15上接通系统授权目录、逻辑目录/标签、原有字面/章节工具、固定版本阅读与同一Chat跟进、删除缓存hook/本机回收、新版本幂等恢复、独立回读另存及加密备份删除约束。恢复历史设置后仍携带规范无正文删除身份，不因此备份未知档案活材料或创建权限。

数学来源工作面绑定真实id/hash/出处与明确完整输入转录，旧单字符校对不冒充数学转录确认；SOURCE_READY不是数学证明。4267条固定工程语料、独立Fraction/Sturm核验和真实公开教材物理位置用于工程质量依据，不报真实模型/OCR准确率，不以可选下一Taylor合同替代本批质量门禁。

Room33/来源绑定/恢复seal继承同期A；本批材料metadata2、library协议1，sidecar9/Pigeon164及模型/权限/UNKNOWN保持。源码、实际失败/修复、Root合流字节、binary-safe重放patch及分项运行裁决取本批六类SOL/TEST_RESULTS/HANDOFF；不累计历史测试数，不预授完整App/独立审查PASS。真实模型/OCR、TalkBack语音、B06跨环境、Release/生产另验；协议与源码地图见 `parallel-development-20261019/B04-B05-LIBRARY-MATH-PROTOCOL.md`。无子代理、提交、推送、部署或私有新增外传。


## v1.27 — A02 数据保全接续完成（2026-10-21 收尾）

来源 `HXai_CHANGE_REVIEW/A02-A03-COMPLETION-20261020/`。准确验收对应 `.local/a02-a03-verified-20261021` 冻结检查点与已冻结APK；共享根目录后来B合入的状态另见SHARED_ROOT_STATUS，不借用本结论。继承 v1.24–v1.26，补主/流式/自动摘要/规划/语义及其他辅助请求的真实物理 admission 校验和晚发布复查；历史查询不再发送旧 FTS snippet/未认证派生标题，宿主保护版本失效后旧查询回执不回灌。Room32→33仅增加 owner/lifecycle/valueHash/HMAC 来源/人工命名证据表；生成元数据原子绑定来源，旧元数据保留但约束下屏蔽，正常新来源可用。

密文备份 prepare 封存 normalized staging；confirm/startup 核清单并合并当前聊天/记忆墓碑，缺库/设置、变更和跨owner拒绝。合并后按真实库复查记忆来源会话存在性与归属，来源失效/自动派生SUPERSEDED并保留记录，正常源与手动记忆可用；该遗漏的旧失败与健康对照单独保留。恢复生命周期递增、队列暂停，原 sidecar/授权/UNKNOWN 不替换。真实安装后进程 exit77 与冷启动读回通过；旧32 schema与18份原始索引一致。中途真实FAIL及修复、精准patch/源码/APK/SHA取本批TEST_RESULTS、ADVERSARIAL_REVIEW与HANDOFF，不复写旧证据。

## v1.28 — A03 Office完整任务与协作接线（2026-10-21 收尾）

复用原统一 Chat/Planner/Store/Host 与 B02/B03，材料增加可选 sourceReferences；create content.sources 绑定固定版本/真实区间/逐字引用/实际页位置和覆盖限制，父成果成对 id/hash 生成新版本并保旧版/回执。编辑缓存 edit-v6、组页 pdf-assembly-v2、带来源创建 material-write-provenance-v1；旧材料按空引用兼容，新元数据不可由旧严格解码器直接消费。

TaskResultVerifier从持久输入独立重读全部源/父版本/产物，TaskPlanner在语义验收物理发送和回包后复核；第二源变化不能发布成功。两讲义Word→真实宿主预览/保存→同任务正文/题目附录补充、PPT运行编辑、两源PDF原页组装各走实际主Chat，并在本机LibreOffice真实导出、PDFium读回/渲染。最终58项核心原生、39项隔离辅助、501 App/211 AI JVM、43 Flutter及普通Debug/lint通过；各组分列，不累计历史完成率。

仅本机合成A02/A03声明范围 PASS_SCOPED；模型仍为localhost合成，不授真实规划质量、完整扫描识别、跨Office保真、Linux/Docker、Mate50/Release、全部A/P0或生产通过。独立和完整全项目审查 NOT_EVALUATED；迁移/恢复适用门禁由同一执行者验证，记录在同批HXai_FULL_REVIEW。sidecar9/Pigeon164/记忆payload1或2保持，无新执行器/预算/权限种类或私有新增外传，未提交/推送/合并/部署。协议与源码映射见parallel-development-20261019/A02-DATA-PRESERVATION-PROTOCOL和A03-OFFICE-COLLABORATION-PROTOCOL。


## v1.29 — API26 与当前 A04 最终合流（2026-10-22）

来源 HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/CHANGE_CONTRACT 与最终 SOURCE_FINAL/TEST_RESULTS。原两文件候选已按SHA精确接入，实际旧版A04安装库另复现关闭接口错误后追加最小修复。新包API26原生139方法（含原B06全89）与四种MCP真实进程恢复通过，App502/AI211 JVM、普通Debug/lint及包隔离分别通过。旧四FAIL/辅助脚本失败与修复均保留；不累加历史计数。源码/协议见 docs/api26-a04-integration-20261022.md。

仅本机当前合流范围PASS_SCOPED；sidecar9/Room33/安装库1/Pigeon164及模型/授权/UNKNOWN不变。普通App共享MCP关闭。真实模型/OCR、通用扩展/脚本沙箱、Release/Mate50/生产另验；独立/全项目审查NOT_EVALUATED，未启动子代理或提交/推送/部署。


## v1.30 — A04 OAuth 与 A05 可信费用（2026-10-22）

接续已有通用MCP/逐工具范围/服务器沙箱，在原前台扩展通道实现公开客户端OAuth authorization_code+S256/state/resource/issuer校验、系统浏览器回调、独立noBackup密文journal和到期前台重连；回调不赋工具权限，EXCHANGING丢ACK不自动重放。TaskStore9→10仅加usage_costs，物理请求冻结价格依据/独立金额回执；OpenRouter最终官方响应usage.cost为reported，已核日期的官方GPT-4.1标准文本费率为estimated，其余未知不记零。原任务/模型/授权/UNKNOWN/材料保持。

Pigeon原类型ID保持、4种Usage DTO追加nullable costJson并生成两端；请求/任务/模型/提供商/角色/周期统计和原不可变JSON导出接费源及未知覆盖，旧快照保持回读。不设置Token/费用硬预算。协议与源码映射见 `a04-oauth-a05-trusted-cost-v1.md`，完整源码、失败/修复、当轮命令与裁决只取 `HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/`。独立与完整项目审查NOT_EVALUATED；本机定向验证不预授真实OAuth账号、付费模型、Release/Mate50或生产通过。无子代理、推送/合并/部署。


## A27 配置发布协议 v1（2026-10-27批次）

本批关闭SettingsStore乐观发布/并发函数更新竞态，并修复旧QA接受夹具的schema13收尾。原API26混合212方法及平台混合回归、实际进程退出、同源普通Debug/Release包资格见`HXai_CHANGE_REVIEW/A27-SETTINGS-COMBINED-20261027/HANDOFF.md`；协议与复验约束见`docs/a27-settings-publication-v1.md`。Release原件unsigned，临时测试签名副本仅本机。TaskStore13/Room33/Pigeon保持；真实云/模型/OCR/目标设备与独立/完整审查分别未验，不覆盖原失败。

## R27-05 前台身份与离线停止协议 v3（2026-10-29）

来源 `HXai_CHANGE_REVIEW/R29-05-FOREGROUND-BINDING-20261029/CHANGE_CONTRACT.md`。复用原云端权威与原生工具链，新前台批准逐项绑定 callId/inputHash/grantEpoch/taskRevision，旧确认不能升级为新授权；先结算原 lease/receipt，再允许新确认。Stop 在网络读前持久化，安全 journal 不得被迟到 claim/receipt 覆写，未确认安全命令阻断后续派发。

Room33/TaskStore13/网络schema4/Pigeon/RPC1保持，noBackup controls兼容增量与回滚门禁、源码地图见 `r27-cloud-device-protocol-v1.md` v3。真实旧版FAIL、同源冻结包、实际确认框/文件读回、各设备场景与限制以本批 TEST_RESULTS/六类SOL/HANDOFF为准，不用历史计数或并发变化的整树冒充最终资格。跨App/截图仍DISABLED；独立及完整全项目审查NOT_EVALUATED，真实模型/目标设备/生产另验。未启动子代理或提交/推送/部署。
