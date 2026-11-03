# 后续开发清单（2026-10-27）

下一批先完成 **B27 接入最终组合包，并定位 API26 混合批次的无模型入队失败**；随后继续 **独立网络权威 → 云端完整执行 → 手机协作**。真实 Agent、数学、OCR 质量可以利用现有本机统一 Chat 提前验证，不必等云端全部开发完成。

本文更新[10月26日清单](remaining-development-20261026.md)和[A/B路线计划](parallel-development-20261026/PLAN.md)的当前状态判断，保留原任务编号。A/B表示平台与领域职责，由同一助手按依赖串行推进，禁止子代理。本文是开发规划，不是全项目审查、产品测试或发布放行。

## 1. 本轮起点与核对结果

| 项目 | 当前事实 |
|---|---|
| 分支 | `codex/b27-file-math-closure-20261027` |
| 完整 HEAD | `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` |
| 工作树 | 大量未提交与 untracked 成果；HEAD 本身不代表当前产品。完整状态、tracked/staged diff 已另存[本轮快照](../qa/remaining-development-20261027/SNAPSHOT.json) |
| 数据与桥接 | 当前源码为 Room33、TaskStore13；B27未新增Pigeon/schema，继承既有费用字段与双端桥接 |
| B27来源核对 | `SOURCE_FINAL.json` 所列311个路径与当前字节全部一致，仅证明所列文件匹配，不授完整工作树或包资格 |
| A26-R01来源核对 | 所列2964个路径中18个已变化，包括材料/数学/服务源码、测试与文档；完整结果见[SOURCE_COMPARISON](../qa/remaining-development-20261027/SOURCE_COMPARISON.json)。未列入旧清单的新文件还须纳入下批冻结 |
| 远端 | 本轮未查询默认分支、main或PR，不对其当前状态作结论；需要远端集成时重新查询 |
| 本轮验证 | 仅文档、源码定位和字节核对；产品构建/测试 NOT_RUN，独立审查及完整全项目审查 NOT_EVALUATED |

本轮读取了根规则、Pro首次接手文件/续作/审查原文、最终需求01—04/13/14、现有开发基线、最新A/B任务书与相关交接。当前调用入口核对包括原ChatService/Scheduler、账户接收器、设置流和数据库版本；没有逐行审查整个产品diff，不把本次整理称为完整代码审查。

## 2. 已有成果，后续应直接继承

| 已有范围 | 已具备的内容 | 必须保留的限制 |
|---|---|---|
| 统一Chat任务底座 | TaskStore/Scheduler、用户模型快照、全局执行位、计划/工具journal、独立结果验收、暂停/撤权/恢复及UNKNOWN保护已接线 | 真实模型的规划与全目标质量仍需评估；不要重建另一套执行器 |
| 任务与用量界面 | 任务中心、详情/控制、费用和Token分组/导出/提醒已有实现 | 供应商覆盖和真实账单对照仍有边界；未知不能记零，无费用硬预算 |
| 资料库与文件主链 | 授权目录、标签/检索/章节、来源读回、不可变版本、预览/另存、删除与备份约束；合成两讲义Word及同任务追加故事已有证据 | 真实Provider自主完成、多种复杂文件保真、目标设备不能由合成故事代授 |
| 专业数学 | 已扩展至合同1—17，含有理式、部分严格误差界/微积分、证明证书与批改工作流；32题题干和参考分离包已准备 | 不再停留在Pro最初的Q[x]，但也不是通用数学证明器；真实整题质量与独立参考裁决仍缺 |
| MCP/Skills/OAuth与费用 | 通用扩展管理、受支持传输/授权、独立脚本沙箱、OAuth、可信费用子集已有实现 | 不等于所有MCP/脚本/写操作和真实OAuth服务已兼容 |
| 账户与执行器移交 | 同一本机权威库上的账户、scope/epoch、Keystore引用、持久实例绑定、原slot/fence移交已实现 | `AgentAccountExecutor`无普通UI/DI/网络启用入口；当前接收器不具备独立云端完整Agent循环 |
| A26-R01组合构建 | 已有冻结源码的QA/普通Debug/Release构建、lint、冷启、包隔离与真实进程退出读回证据 | 当时未含后续B27。Release原件unsigned，测试签名副本只授本机范围；不要写成“Release从未构建”或“当前最终包已通过” |
| B27文件与数学收尾 | 取消候选一致性、空白/覆盖矛盾拒绝、材料createdAt落盘保全、失败证书与目标语义区分；本机MSOffice保存/重开/实际重算及含隐藏页全逻辑页PDF已验证 | 本机合成副本的有限范围，不推广任意Office保真/WPS/真实设备 |
| B27真实OCR | Windows.Media.Ocr已实际识别并评分 | 数学转录质量FAIL；它是本机评估驱动，不是Android默认OCR或MinerU。人工修订烟测不等于免确认识别通过 |

主要证据：[A26-R01交接](../HXai_CHANGE_REVIEW/A26-R01-COMBINED-BUILD-20261026/HANDOFF_STATUS.md)、[B27验收矩阵](../HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/SOL_ACCEPTANCE_MATRIX.md)、[本机账户协议](agent-account-transport-protocol-v1.md)、[开发基线](pro-development-baseline-20261012.md)。历史通过只适用于各自源码/环境，不累加为完成率。

## 3. 按依赖排序的后续批次

下表的实现、证据和测试状态分别记录。证据引用的是已存在批次，本次未重跑。新增R27编号只拆分当前接续工作，不替换A26/B26原任务身份。

| 顺序 / 任务 | 当前状态 | 具体开发或验证内容 | 完成条件 |
|---|---|---|---|
| **0 / R27-01 B27正式接收与当前组合包**；A26-R01续批、B26-R04 | B领域冻结WIRED；旧A组合E4局部通过；含B27最终普通两包 NOT_RUN | 以B27冻结为输入核before/candidate/current，不重复apply已在树内的字节；冻结必要untracked；按影响重建AAR/QA/普通Debug与Release，接账户/移交、数学、材料来源、备份迁移和包隔离回归 | 同一源码→AAR→APK→实际安装SHA→日志/产物可对应；普通包无QA入口。旧A包和B的nativeqa领域包各自不能替代当前组合资格 |
| **0 / R27-02 API26混合入队失败**；A平台修复、B共享Chat回归 | E4：混合212方法有210 PASS/1 FAIL/1 SKIP；23方法隔离PASS；原因未确定 | 保留`No chat model selected`反例，核SettingsStore.update持久化/热流、夹具设置恢复顺序、Chat接受快照及Scheduler.admission；先稳定复现，再确定修产品、测试隔离还是两者 | 同进程原混合顺序和合法无模型拒绝均验证；排队后配置锁、跨档案和重启保持。不得放宽无模型门禁、暗换模型或用隔离PASS抹除混合FAIL |
| **1 / R27-03 独立网络权威与进程恢复**；A26-R02 | 本机协议WIRED/E3-E4；跨主机权威与认证网络入口ABSENT/PARTIAL | 定义每用户工作域唯一权威、账户/设备注册、认证transport、实例身份/进程代次、停止证明、结果读回；先以两个实际独立进程和合成身份实现 | 双端争抢、旧fence/实例晚提交、撤权、断网、写后退出和移交丢ACK不重复。失联/心跳超时不证明已停，UNKNOWN不能自动放位 |
| **2 / R27-04 服务器vault与云端完整执行**；A26-R03 | Keystore/fake接收器WIRED；服务器KMS/vault、独立完整云Agent PARTIAL/ABSENT | 依托R27-03实现最小托管grant、密钥加密引用/轮换/验证和配置快照传输；接回原Planner/GenerationLoop/可信工具/journal/Verifier/费用链；补可信普通App配置与状态入口 | 原provider/model/reasoning不变；实际物理出站复核scope/epoch；响应不能直接当目标成功。手机进程退出后，独立宿主仍真实完成已授权非设备步骤才算云持续执行 |
| **3 / R27-05 手机执行器与前台接管**；A26-R04 | blocker/确认与排队基础PRESENT/PARTIAL；完整设备adapter未闭环 | 锁屏/离线检查点、确认冲突动作停止后释放位置、恢复优先排队不抢占、前台接管确认、可见停止、截图临时生命周期；可选跨App最后接入 | 联网/解锁/换key不解除用户暂停、主动撤权、缺输入或前台确认；未知发送先核实；截图不自动入长期记忆/备份，跨App默认关闭 |
| **4 / R27-06 真实Agent任务质量**；A26-R05 | 主链WIRED/E4合成；真实Provider质量 NOT_RUN | 用原Chat评闲聊零工具、多步讲义合成、同任务追加、漏目标/假完成、失败重规划、不支持工具、断流/未知收费和来源变化；先封存回答/receipt/成果/用量，再评分 | 真实结果满足完整目标，不靠模型自评/工具200；保留失败和未知。不必等待云端开发完成；真实配置、角色、传输与收费须有明确范围 |
| **5 / R27-07 专业数学真实质量与错例补能力**；B26-R01、A26-R05 | 合同1—17/32题WIRED；真实回答BLOCKED，独立参考裁决NOT_EVALUATED | 只提交BLIND_PROMPTS；完善江苏高中、高数/分析/实变的合法分层参考与义务评分，扩正确异法/首错前缀/定理条件/量词/参数分支；从实际错例补核验能力 | 逐义务MET/VIOLATED/UNDETERMINED，数学结果与工程状态分开；证书失败不反驳目标、UNSUPPORTED不等于学生错、局部数值界不冒充完整证明。32题自推参考不称权威盲评 |
| **6 / R27-08 OCR质量修复与真实手写到Word**；B26-R02、A26-R05 | 真实WinRT驱动PRESENT/E3，免确认转录质量FAIL；真实学生手写/MinerU/真实主Chat整故事BLOCKED | 按已有错字符分类选定引擎/明确语言/版本评估，保原五页及真值；补有许可多页手写与逐符号人工标注；真实识别→覆盖/歧义确认→原Chat批改→可编辑Word/来源/预览保存 | OCR符号、覆盖、数学判断、版式和任务完成分别判。保强制修订确认；原页、输入/输出SHA与引擎receipt可核；取消/删源/变更不能复用旧确认，未知上传不重传 |
| **7 / R27-09 WPS与剩余领域兼容**；B26-R03 | MSOffice与既有Windows/Linux LibreOffice有受限通过；WPS及最终设备领域NOT_RUN | 在独占WPS环境对同SHA副本真实打开/另存/重开/重算/导出；补复杂文件已声明支持范围；领域服务/镜像变化后补受影响Linux/validator/取消撤权/kill/ACK回归 | DOCX公式及非目标内容、PPT全部逻辑页/隐藏设置、XLSX日期类型/跨表/图表、PDF原页分别读回；不可接管用户Office，不把一种软件通过推广到另一种 |
| **8 / R27-10 扩展、网页登录与费用覆盖**；A26-R05维护 | 已支持子集WIRED；真实服务和新增适配PARTIAL | 根据实际任务指定HTTPS/OAuth/MCP服务，补所需transport/schema、外部写操作专属verifier、受限沙箱二进制成果；按真实需要核授权登录站点的会话/操作/撤权；补供应商费用/账单对照 | OAuth不自动赋工具权限；读一致性不证明发信/删除/支付；网页登录不扩大Cookie范围；reported/estimated/unknown分开，旧金额不重算。费率目录所载2026-11-21 UTC期限需在使用前核实，不据本文当当前价格 |
| **9 / R27-11 最终App与目标设备交付**；A26-R06、B26-R03/R04 | 部分本机E4证据已有；最终E5/正式签名NOT_RUN；完整/独立审查NOT_EVALUATED | 接冻结领域支持矩阵与最终包，验证API26/Mate50 HarmonyOS4.2：冷启、后台/锁屏/弱网、SAF、停止恢复、档案切换、小屏/200%字/深浅色/长文件名、TalkBack真实焦点与朗读、性能；完成适用安全/迁移/恢复/许可证/签名门禁 | 所有声明范围同源同包取证；S0/S1和必要FAIL阻断相关功能。正式签名/发行/生产分别记录，不由测试签名或nativeqa放行；同一执行者复验不冒充独立审查 |

API26混合失败的设置流时序解释目前仅为推断，不能直接宣布产品根因已查明。B27原始FAIL、隔离PASS及其限制见[B27阻碍](../HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/SOL_BLOCKERS.md)。

## 4. 代码入口与可直接接续的材料

以下K=`client/app/src/main/java/me/rerere/rikkahub/`，均相对仓库根；真正开工重新核版本和行号。

| 任务 | 代码/方法 | 下一动作与依据 |
|---|---|---|
| R27-01 | Gradle/AAR、`scripts/verify_flutter_apk.py`、共享Chat/领域测试 | 读[B27 HANDOFF](../HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/HANDOFF.md)、SOURCE_FINAL/FINAL_BUILD_BINDING、[A组合交接](../HXai_CHANGE_REVIEW/A26-R01-COMBINED-BUILD-20261026/HANDOFF.md)。用本轮比较定位18项差异，再核新增文件。B27增量和B26+B27组合patch有重叠，不能连续套用 |
| R27-02 | `K/data/datastore/PreferencesStore.kt`中的`SettingsStore.update/settingsFlow`；`ChatService.mutateMessageQueue`；`AgentScheduler.accept`；`MathSharedChatRuntimeTest` | 先对照B27 `native-current-api26/RESULT.json`、原log、`ISOLATED_SHARED_CHAT_RESULT.json`；保同进程反例和合法拒绝用例。`scripts/verify_agent_android.py`有安装、归档/清nativeqa、force-stop等副作用，先读驱动并核自有设备/匹配包/新run-id |
| R27-03/04/05 | `K/feature/agent/AgentAccountTransport.kt`的Authority/Transport；`AgentAccountExecutor.run`；`AgentScheduler.prepareHandoff`；`AgentTaskStore.activateHandoff/beginRequest`；原Chat/Loop/device control | 依据[账户阻碍](../HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/SOL_BLOCKERS.md)、[账户协议](agent-account-transport-protocol-v1.md)、[移交协议](agent-executor-handoff-protocol-v1.md)，分批新立合同；现`server/agent-runtime`仅参考，不直接当公网可信API |
| R27-06/07 | `TaskPlanner`、`TaskResultVerifier`、Provider物理请求、`ProfessionalSolution/ProfessionalReview/ProofDerivation` | 依据[A评估计划](../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/MODEL_EVALUATION_PLAN.md)与[B27阻碍](../HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/SOL_BLOCKERS.md)；本批32题为新增范围，旧24题计划不是自动调用授权 |
| R27-08 | `MathWorkInputs/MathWorkCandidates`、`MaterialPdfOcrDraft`、`DocumentServiceRepository`、MinerU及原角色授权 | 读[OCR合同](b24-ocr-evaluation-contract1.md)、B27 `OCR_QUALITY_VERDICT.json`。旧LiveTest专用五页与新评分五页不同，不能偷换。明确引擎后实际运行；下载后离线评分命令见下方 |
| R27-09 | `feature/material`、`server/document-service/office_worker`、独立Office/PDF读回器 | 读[B27领域合同2](b27-file-math-quality-contract2.md)和[Linux协议](parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md)；以`microsoft-office-final/INPUTS.json`同SHA副本接续WPS，缺专属环境不编造自动化命令 |
| R27-10/11 | `ExtensionPolicy/McpTransport/McpRuntime/OAuth/Frontend`、`TrustedCost`及Provider/Usage；Memory/Backup、Host/Pigeon、Flutter与包入口 | 读[A04/A05阻碍](../HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/SOL_BLOCKERS.md)、[A任务书](parallel-development-20261026/A_TASK.md)、[正式全项目规范](../HXai_Pro_Delivery/10_REVIEW_SYSTEM/PROJECT_FULL_REVIEW_STANDARD.md)；旧源码地图只作定位，不能继承旧SHA行号或过期旁路结论 |

下一批第一条只读定位命令：

```powershell
rg -n -e 'suspend fun update' -e settingsFlow -e mutateMessageQueue -e 'No chat model selected' client/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentScheduler.kt
```

云端接续定位：

```powershell
rg -n -e prepareHandoff -e activateHandoff -e AgentAccountExecutor -e AgentAccountAuthority client/app/src/main/java/me/rerere/rikkahub/feature/agent
```

获得实际解析结果后的离线评分模板（`DOCUMENT`须替换为实际下载文件，输出目录名须未使用）：

```powershell
python scripts/verify_b_ocr_corpus.py score --truth HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/corpus-final/truth.json --source HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/corpus-final/source.pdf --document DOCUMENT --output .local/r27-live-ocr-NEXT.json --result-origin external-unattested
```

该评分器不认证真实引擎执行；另需引擎/任务receipt、输入/输出SHA和实际角色用量。上述命令本轮未执行；不提供绕开App授权与费用账本的通用模型调用命令。

## 5. 推进顺序与外部前置

**推荐下一批：R27-01 + R27-02**，先保原失败、定位设置/夹具生命周期并完成B27同源组合接收。随后 **R27-03 → R27-04 → R27-05** 是P0主开发链。R27-06/07/08/09/10可按环境与授权情况串行交替推进，最后统一进入R27-11；不要为了继续增加数学operation而长期推迟P0云端接线。

| 可立即在合成/本机范围推进 | 需要具体前置后才能完成 |
|---|---|
| B27字节接收/组合冻结、混合测试根因定位、两独立进程协议和合成账户/secret、公开任务/题库评分准备、既有错例修复 | 真实Provider：明确provider/endpoint/model/reasoning、32题或具体任务、专用角色/收费/传输/留存范围；密钥只在可信App配置 |
| OCR错误归类、现有封印/来源/确认和Word合同维护、受支持文件夹具补充 | 真实OCR：指定引擎/服务与传输范围；合法真实学生多页手写及逐符号人工真值；真实MinerU及远端清理按专用合同 |
| 扩展/费用合成反例、设备adapter代码与状态契约 | 外部OAuth/登录站点的实际账号与注册；独占WPS；Mate50/HarmonyOS4.2、TalkBack语音；服务器托管/正式签名/发布各有明确环境和范围 |

缺前置部分维持BLOCKED/NOT_RUN，并继续独立可做的开发。用户没有要求新增后台日程、其他聊天或代发消息，本清单不创建这些对象。

## 6. 每批交付、范围与回滚

每个实现批次先写可证伪CHANGE_CONTRACT；按Pro原文追完整受影响diff/调用方，保旧版FAIL→最小修复→正常/边界/故障和相关旧回归，覆盖“假成功/错恢复”与“正确输入误拒/旧数据损坏”。任务/凭据/数据库/跨档案/发布变更追加适用全项目门禁。实现状态、E0—E6和测试结果分开；未做独立审查保持NOT_EVALUATED，不开子代理。

输出`HXai_CHANGE_REVIEW/<feature>/`的合同、TRACE_AND_DIFF、TEST_RESULTS/原logs、ADVERSARIAL_REVIEW、精确patch、VERDICT_AND_ROLLBACK、HANDOFF与六类SOL；每个遗留列代码/依赖/下一实际命令/验收/回滚。冻结支持矩阵，不能用历史计数当完成率，也不通过放宽VERIFIED/改真值消除FAIL。

回滚只停对应adapter/入口或逆本批精确增量，保TaskStore13及后续合法版本、原件/不可变成果、createdAt完整元数据、墓碑、确认/撤销、receipt、费用与UNKNOWN。历史已经丢失的创建时间无法精确重建，不能以mtime伪装恢复。禁止降库、reset/clean/stash、复活撤权或盲重放未知动作。

百度网盘、邮箱、日历专用连接器继续后置；自动换主模型/推理、Token/费用硬预算不进入开发清单。保留现有Memory/Skills/MCP/备份/Compose能力及授权边界。本文不授完整App、Release、Mate50、OCR数学质量、独立审查或生产PASS。
