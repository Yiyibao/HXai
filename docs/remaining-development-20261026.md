# 后续开发任务清单（2026-10-26）

当前主开发方向是 **P0 云端持续 Agent 与手机协作**；近期交付前置是把 **TaskStore13 账户增量与 B26 数学修复放进同一套最终包验证**。真实模型、OCR、跨 Office 和目标设备分别验收，不能用本机合成链路代替。

本清单接续 [10月24日待办](remaining-development-20261024.md) 和 [A/B计划](parallel-development-20261024/PLAN.md)，更新它们的当前状态判断。A/B仅表示职责，按用户规则由本助手串行开发，不创建子代理或新会话。产品目标仍以 Pro inputs/01—04、13 为准。

### B线2026-10-27增量

[合同2](b27-file-math-quality-contract2.md)及 `HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/` 接续B26未交接增量：已做真实WinRT识别评分、MSOffice保存/重开/重算/含隐藏页全逻辑页PDF、原生候选→确认→数学核验→Word读回、当前API26/API34领域和共享Chat定向验证；修复取消状态遗漏及实际复现的材料createdAt落盘漂移。具体测试/失败与来源只取该批封印，不累加计数。

R26-06仍缺真实Provider授权/回答与独立质量裁决；R26-07本机OCR已执行但符号质量FAIL，真实学生手写/MinerU/真实主Chat完整故事仍缺前置；R26-08的MSOffice本机子范围已验，WPS/目标设备仍NOT_RUN；B26-R04领域冻结已交A。API26混合批次的无模型入队FAIL与逐方法隔离结果单列，不授完整B线/App/Release/生产通过。以下旧表保留原时点，不应用其“MSOffice/真实OCR均未执行”覆盖该增量。


## 1. 当前起点与证据边界

- 分支：`codex/a26-01-b-math-integration-20261026`。
- 完整 HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。大量产品实现位于未提交/untracked 工作树；不得裸 checkout 旧 HEAD 作为当前产品。
- 当前原生 TaskStore 为 **13**，Room 为 **33**；Pigeon 继承既有费用字段，B26 数学修复没有新增桥接/schema。依据当前源码及 [账户协议](agent-account-transport-protocol-v1.md)。
- 本轮只整理文档、追踪关键源码、比较冻结清单并保存工作树/diff；未重跑产品行为测试，未调用模型/账号、操作设备或查询远端。最新 main/PR 状态不作判断。
- 本轮 [快照及来源比对](../qa/remaining-development-20261026/SNAPSHOT.json) 与各 `SOURCE-COMPARISON.json` 只证明当前字节关系。下文的 PASS 是原批次的限定历史证据，不是本轮复跑。独立审查、完整全项目审查均为 **NOT_EVALUATED**。

本次快照记录196项tracked变更，staged为空；另有大量untracked源码与历史证据，不能整体当产品纳入。最新A数学集成清单2463项全部匹配，B26领域清单36项全部匹配。相对账户批次2868项，2864项匹配，4项不同：`ProofDerivation.kt` 与 A_TASK、B_TASK、数学合同16三份文档；这是后续数学/接续增量，不能用账户旧包替代当前组合包。

## 2. 已有成果：从剩余开发中扣除

| 已有能力 | 最新状态与出处 | 仍需区分的边界 |
|---|---|---|
| 原生统一 Chat、TaskStore/Scheduler、模型接受快照、journal、独立验收与任务中心 | 当前主链已接线；继承原 AGENT/MODEL 各批 | 不等于独立云端运行或真实模型质量已通过 |
| A02 数据保全、A03 两讲义 Word/同任务追加、PPT/PDF 主 Chat 故事 | A02-A03-COMPLETION 与 AB-MERGE 已有局部证据 | 新增平台/领域变更后按影响回归，不重做整套功能 |
| Release AAR/宿主接线及本机普通包冷启 | [A24-01交接](../HXai_CHANGE_REVIEW/A24-01-RELEASE-INTEGRATION-20261024/HANDOFF_STATUS.md)：PASS_SCOPED | 已接入当前工程；其包早于 TaskStore13/B26，不授最新组合包或正式签名资格 |
| 数学合同17领域收尾、候选修订/确认与表面证据 | [B24-01交接](../HXai_CHANGE_REVIEW/B24-01-C17-CLOSURE-20261024/HANDOFF_STATUS.md)：PASS_SCOPED | 不再列“合同17整批无交接”；完整P2与最新最终包另验 |
| 本机执行器移交、账户鉴权、任务级密文引用与受监督接收端 | [A26账户交接](../HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/HANDOFF.md)：TaskStore13，本机原生限定通过 | 共享原本机权威；无网络监听、服务器KMS、真实登录或普通App远端启用入口 |
| B26 重复前提合法证明修复、24题及参考义务 | [B26交接](../HXai_CHANGE_REVIEW/B26-01-MATH-QUALITY-20261026/HANDOFF.md)；[A接收](../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/HANDOFF.md)已核六文件并做实际 App JVM 数学验证 | A本批41项数学JUnit及2例App编译类烟测属E2，没有生成/安装最新组合APK，也没有真实模型请求 |
| OCR五页原创合成语料、封印真值与离线评分器 | [B24-02交接](../HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/HANDOFF_STATUS.md) | 是评估准备；真实识别、学生手写和完整扫描故事未执行 |
| 文件资料库、Office局部编辑/真重算、Linux隔离Office、MCP/Skills/OAuth和费用账本 | B04/B05、B06-LINUX-OFFICE、A04/A05 各批已有明确支持范围 | MSOffice/WPS、真实OAuth/供应商账单、最新版本API26与目标设备仍分别验证 |

## 3. 按依赖排序的待办

下列 R26 编号仅是本清单索引，原任务归属保留。实现状态与测试状态分开；未验项不自动视为已发现缺陷。若开发中复现 S0/S1，先修相关阻断。

| 顺序 / ID / 原任务 | 当前实现 / 证据 | 下一批具体工作 | 依赖与完成判据 |
|---|---|---|---|
| **0 / R26-01 当前组合包基线** / A24-05前置、B24-05 | 组合源码 PRESENT；数学 App JVM 历史PASS；该组合 Android/Release **NOT_RUN** | 冻结当前完整产品及必要untracked；核账户/B26最小增量；重建匹配QA两包、普通Debug/Release与适用AAR，完成账户/移交/数学/来源的交叉回归和包隔离 | 需独占构建目录与已核归属设备。源码、AAR、APK、安装SHA及日志一致；普通包无QA入口，合法证明/UNKNOWN/迁移和来源保护保持。旧账户包或A24 Release不能替代 |
| **1 / R26-02 独立宿主与网络权威** / A24-02第二小批 | 本机协议 WIRED，跨主机权威/transport **ABSENT/PARTIAL** | 选定每用户工作域唯一权威；实现账户/设备注册、认证网络transport、接收器实例身份和真实进程代次、停止/恢复/读回协议。先在专属本机两进程/宿主合成环境开发 | 延续原 slot/fence，不另起未协调调度器。双端争抢、丢ACK、断网、旧实例晚提交、撤权/崩溃下不重复；UNKNOWN与未确认停止不能靠超时释放。公网部署另属授权范围 |
| **2 / R26-03 服务器vault与完整云执行** / A24-02第二小批 | 本机 Keystore/fake provider 接收器 WIRED；服务器托管与完整云 Agent **PARTIAL** | 实现服务器密钥管理/最小托管grant、轮换与实际key验证、加密配置快照传输；接回原GenerationLoop/Planner/工具/journal/Verifier及费用台账 | 依赖R26-02协议；可先做合成secret/transport。原用户provider/model/reasoning固定，每次物理出站复核授权；响应最多COMPLETED_UNVERIFIED，满足全目标才成功。真实托管须有明确范围；手机离线继续须有独立云进程证据 |
| **3 / R26-04 手机执行器与前台接管** / A24-02第三小批 | device blocker/确认字段 PRESENT；完整设备adapter与隐私链 **PARTIAL** | 锁屏/离线停步、恢复优先排队且不抢占、前台设备确认、可见停止、原生受控操作及截图临时生命周期；最后才接可选跨App | 依赖R26-02/03的真实移交和停止证明。解锁/联网/换key不清暂停、撤权、缺输入或设备确认；跨App默认关，截图不自动入长期记忆/备份；未知发送先核实 |
| **4 / R26-05 真实Agent任务质量** / A24-03 | Planner/执行/验收 WIRED；真实Provider质量 **NOT_RUN** | 固定评估任务及完整目标判据；沿原Chat评估闲聊零工具、多步文件目标、漏目标、同任务追加、失败重规划、不支持工具、断流和源变化 | 真实调用前需指定并授权provider/endpoint/model/reasoning/角色/材料。先封存回答、实际回执/成果和用量再评分；不把工具成功或模型自评算完整任务成功，未知收费不盲重发。无需等待云端功能全部完成，可在本机主链独立开展 |
| **5 / R26-06 数学真实质量与错例修复** / B24-03、A26-01评估 | 合同1–17及24题 WIRED/已准备；真实解题与独立人工裁决 **NOT_RUN/NOT_EVALUATED** | 用已分离题干包开展真实24题逐义务评估；核参考独立性，扩充江苏高中/高数/分析/实变、正确异法/首错题型；依实际错例补定理条件、量词、分支或证明工具 | 与R26-05共用原主Chat和配置锁，题干中不带参考/证书。数学结论与工程状态分开，失败/未知不删分母；UNSUPPORTED不等于学生错误，局部证书不等于整题证明。当前公开自推样本不能称权威盲评 |
| **6 / R26-07 真实OCR到Word故事** / B24-02 | 真值/离线评分 WIRED；真实识别及整条故事 **PARTIAL/NOT_RUN** | 在明确引擎/版本和传输范围下运行已封印五页；另补合法真实多页手写及人工逐符号标注；接候选覆盖→歧义确认→统一Chat批改→可编辑Word/真实来源 | 需真实引擎或获授权服务；共享Provider/角色/费用由A接线。原始SHA、物理页、覆盖、识别输出与receipt可核；区分OCR、数学、版式成绩。旧LiveTest专用五页夹具不能用新语料偷换；丢ACK不盲重传 |
| **7 / R26-08 跨Office与领域兼容** / B24-04 | Windows/Linux LibreOffice及旧API26范围有历史PASS；MSOffice/WPS及最新包 **NOT_RUN** | 对同SHA合成副本在专属MSOffice/WPS环境真实保存/重开/重算/渲染；最终API26/目标设备补文件、数学、来源故事。Linux相关代码/镜像变化后按影响复验 | DOCX公式/无关内容、PPT所有页及隐藏页、XLSX实际值/日期/图表、PDF原页分别取证。不能以一种Office推广另一种；不得接管或关闭用户Office实例 |
| **8 / R26-09 扩展与费用覆盖维护** / A24-04 | MCP/Skills/OAuth受支持子集和费用账本 WIRED；真实服务兼容/新增适配 **PARTIAL** | 指定实际HTTPS/OAuth服务验证；按用户目标补transport/schema、写操作专属verifier、沙箱二进制成果合同；增加有出处的供应商费用/费率和账单对照 | 每项先合同再启用。读一致性不能证明发信/删除/支付成功；OAuth不自动赋工具权限。费用reported/estimated/unknown分开，旧金额不重算。现目录注明2026-11-21 UTC到期，新增估算前核有效性；不设费用硬预算 |
| **9 / R26-10 最终交付资格** / A24-05、B24-05 | 分批局部证据存在；最新最终资格 **NOT_RUN**，全审/独立审查 **NOT_EVALUATED** | 冻结领域支持矩阵和同源最终包；Mate50/HarmonyOS4.2、后台/锁屏/弱网/SAF/停止恢复、档案切换、小屏/200%字/深浅色、TalkBack真实朗读；完成适用全项目门禁、正式签名与交付准备 | B交领域证据、A汇总系统/包/迁移/安全和依赖许可证。S0/S1及必要FAIL阻断对应范围；正式签名、发布和生产分开，缺环境保持NOT_RUN。开发自验不能写成独立通过，继续禁止子代理 |

R26-02→03→04 是主要功能依赖链。R26-05/06/07/08/09 可按环境准备情况与该链串行交替推进，不必等待所有云功能；触及共享源码后重新冻结R26-01，最终统一进入R26-10。

## 4. 可直接接续的源码与材料

K = `client/app/src/main/java/me/rerere/rikkahub/`。下列均为仓库根目录相对路径；命令是后续入口，本轮未执行产品测试。

| 任务 | 源码/方法 | 已有精确接续依据 |
|---|---|---|
| R26-01 | `K/feature/agent/AgentTaskStore.kt`、`AgentAccountExecutor.kt`、`AgentScheduler.kt`，`K/feature/math/ProofDerivation.kt`；Gradle/AAR/QA脚本 | [A26账户SOL_BLOCKERS](../HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/SOL_BLOCKERS.md)、[A数学接收SOL_BLOCKERS](../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/SOL_BLOCKERS.md)、[Release协议](a24-release-integration-v2.md) |
| R26-02/03/04 | `AgentAccountAuthority/Transport/Executor`、`AgentScheduler.prepareHandoff`、`AgentTaskStore.activateHandoff/beginRequest`；原ChatService/GenerationLoop及device control | [账户协议](agent-account-transport-protocol-v1.md)、[移交协议](agent-executor-handoff-protocol-v1.md)、账户SOL_BLOCKERS逐项前置；`server/agent-runtime`仍是本机参考，不能直接暴露可信方法当公网API |
| R26-05/06 | `TaskPlanner`、`TaskResultVerifier`、`ProofDerivation/ProfessionalSolution/ProfessionalReview`，原`math_verify` | [评估计划与授权范围](../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/MODEL_EVALUATION_PLAN.md)、[B26阻碍/命令](../HXai_CHANGE_REVIEW/B26-01-MATH-QUALITY-20261026/SOL_BLOCKERS.md)、[数学质量合同](b26-math-quality-contract1.md) |
| R26-07 | `MathWorkInputs/MathWorkCandidates`、`DocumentServiceRepository`、`MaterialReader/Editor`、MinerU链 | [OCR精确命令与专用夹具区别](../HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/SOL_BLOCKERS.md)、[OCR评估合同](b24-ocr-evaluation-contract1.md) |
| R26-08 | `feature/material`、`server/document-service/office_worker`及实际读回器 | [B任务书B24-04](parallel-development-20261024/B_TASK.md)、[Linux Office协议](parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md)；以最新状态替换旧阻碍文件中的已完成API26合流判断 |
| R26-09 | `ExtensionPolicy/McpTransport/McpRuntime/OAuth/Frontend`、`TrustedCost`及Provider物理请求/Usage导出 | [A04/A05精确接续](../HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/SOL_BLOCKERS.md)、[协议](a04-oauth-a05-trusted-cost-v1.md) |
| R26-10 | 最终Gradle/Manifest/Pigeon/Flutter、Backup/迁移与各模块调用者 | [A任务书A24-05](parallel-development-20261024/A_TASK.md)、[Pro全项目规范](../HXai_Pro_Delivery/10_REVIEW_SYSTEM/PROJECT_FULL_REVIEW_STANDARD.md)及配套完整提示词；本清单不代替审查 |

可以立即开展且不依赖真实账号的下一批是 **R26-02：独立进程的网络权威与停止/恢复协议**；开工前先冻结R26-01的完整源码输入，建立新CHANGE_CONTRACT。当前组合包资格也可单独成批收尾。

只读定位入口：

```powershell
rg -n -e prepareHandoff -e activateHandoff -e AgentAccountExecutor -e AgentAccountAuthority client/app/src/main/java/me/rerere/rikkahub/feature/agent
```

数学本机证书复验入口（仅在源码变化影响该范围或需复现时用新目录，不代表真实模型评估）：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir .local/b26-quality-NEXT --cases scripts/fixtures/b26_math_quality_v1.json
```

真实OCR下载后的离线评分、账户原生复验、Release构建的完整命令和副作用在上表链接中；先读脚本、核源码/夹具/设备归属、使用新输出目录。旧冻结APK复跑不能授新源码资格。没有授权配置的真实模型调用不编造通用API命令绕过App账本，也不在本轮读取密钥。

## 5. 每批完成条件与回滚

1. 新合同写清真实入口→持久层→UI、schema、owner/profile/epoch、权限/模型锁、副作用、成功/失败/恢复及兼容；任务/凭据/数据库/跨档案/发布变更追加适用全项目门禁。
2. 已知缺陷先保存旧FAIL，再最小修复、正常/边界/故障及关联回归；至少尝试“假成功/错恢复”和“正确输入被拒/旧数据损坏”两类反证。禁止通过放宽VERIFIED或改真值迎合输出。
3. 按Pro交 `HXai_CHANGE_REVIEW/<feature>/` 的合同、完整diff/patch、TEST_RESULTS/原始logs、ADVERSARIAL_REVIEW、VERDICT_AND_ROLLBACK、HANDOFF及六类SOL；实现状态、E0—E6、实际测试结果分开，不累加历史计数成完成率。
4. 回滚只停对应adapter/入口或逆本批精确增量；保原件/不可变版本、墓碑、TaskStore13、新schema数据、receipt、费用与UNKNOWN，不降库、不reset/clean/stash、不复活撤权或盲重放。

百度网盘、邮箱、日历专用连接器继续后置；自动切主模型/推理强度、Token/费用硬预算不进入待办。此次整理不授权真实账号、私有新增外传、云部署、发布或跨App接管，不创建后台任务或自动启动承诺。
