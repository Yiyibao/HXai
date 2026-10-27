# 当前剩余工作整理（2026-10-24）

2026-10-26 A线接续：第二小批已新增本机账户鉴权、任务级加密凭据引用和受监督移交接收端，TaskStore13。协议及当前证据见 [账户接线协议v1](agent-account-transport-protocol-v1.md) 和 `HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/`。当前只接合成账户/Provider、原本机唯一权威；下一步仍是独立可信宿主与网络transport、服务器vault/密钥验证和云端原GenerationLoop/验收接线，再接设备前台确认。不能将本机通过升级为真实云托管或手机离线持续运行。下文旧时点保留。

2026-10-24 A24-02接续：已在现有原生TaskStore/Scheduler实现第一小批移交协议，TaskStore11；合成双端仍共享同一本机权威，不是独立云服务。协议、当前验证与交接见 [协议v1](agent-executor-handoff-protocol-v1.md) 和 `HXai_CHANGE_REVIEW/A24-02-EXECUTOR-HANDOFF-20261024/`。A24-02后两小批的账号/云执行、设备隐私与真实远端停止回读仍待开发，不能据本机协议宣称手机离线后云端已持续执行。

2026-10-24 A24-01接续：**当前工程的Release接线与本机限定验证已完成**，不再是仅有旧A07候选。A所属7份来源核对、当前两种AAR/宿主构建、App516/AI214 JVM、Debug/Release lint、62方法原生交叉回归、普通两包冷启/Host读回及安装SHA已取证。精确范围见 [本批交接](../HXai_CHANGE_REVIEW/A24-01-RELEASE-INTEGRATION-20261024/HANDOFF_STATUS.md) 与 [协议v2](a24-release-integration-v2.md)。下文保留此前检查时点；B合同17领域收尾、A06、真实质量、正式签名/目标设备/生产及独立/完整审查仍分别待做。

已按用户后续要求拆为两条开发线：[A/B总计划](parallel-development-20261024/PLAN.md)、[A线任务书](parallel-development-20261024/A_TASK.md)、[B线任务书](parallel-development-20261024/B_TASK.md)。本清单保留需求与当前证据，执行归属和合流依赖以新版A/B计划为准；拆分本身不启动开发会话或子代理。

本清单以当前工作区、Pro 最终需求、最新批次交接与实际日志为依据，替代旧待办中的当前进度判断，保留旧文档及失败证据。**当前最主要的功能缺口是 A06 云端持续 Agent 与设备协作；近期还需要收尾数学合同17、集成已有 Release 候选，并开展真实模型/OCR、跨 Office 与目标设备验收。**

本轮只整理和核对源码/历史证据，新增本文和 `qa/remaining-development-20261024/`，未修改产品代码、重跑行为测试、操作设备、查询远端、调用真实账号或部署。未做独立审查或完整项目审查；二者均为 NOT_EVALUATED。下文历史 PASS 只属于原批次、源码和环境，不是本轮重跑通过。

## 1. 当前基线与需要先收尾的交付

- 分支：`codex/p2-calculus-ocr-20261022`。
- 完整 HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。
- 快照时 tracked：182 修改、13 删除，合计195文件，10731行新增/4127行删除；staged diff 为空。另有大量 untracked 实现、夹具与历史证据，不能把它们全部视作待提交产品源码，更不能按 HEAD 的旧树判断功能。
- 原始 status、完整 tracked/staged patch、源码清单比对及证据索引：`qa/remaining-development-20261024/`。不查询远端，因此不判断最新 main/PR 状态。

### 1.1 数学合同17：已有真实测试，尚未完成整批收尾

真实代码已包含有限闭区间 C8 积分证书、指定区间唯一根证书、已有解析缓存到多页候选确认链。不能再写成“只有 Q[x]”或“多页确认从零未做”。

已找到的历史证据：

| 证据 | 实际记录 | 限制 |
|---|---|---|
| Kotlin CLI | `P2-CALCULUS-OCR-20261022/engine-01/RESULT.json`：16例 PASS | 算法，不是 Android/真实模型 |
| 独立数值参考 | `independent-01/RESULT.json`：20例 PASS，mpmath 320位参考 | 数值参考交叉检查，不是独立人员审查 |
| Flutter | `logs/flutter-tests-01` exit1；`flutter-tests-02` exit0；analyze/AAR exit0 | 原失败保留；仍需在汇总中说明原因与修复 |
| 普通 Debug | `logs/ordinary-01/RUN.json`：App JVM/lint/assembleDebug exit0 | 命令排除了 `:web:buildWebUi`，不称完整重建所有模块；没有 Release |
| Android 主链 | `AGENT-MODEL-NATIVE-RESUME-20261012/runs/p2-calculus-ocr-20261022-01/TEST_RUNS.json`：34个 instrumentation 方法全部 PASS | 位于公共验证驱动的历史目录，不能因本批目录缺汇总就说未执行 |

本轮独立读回上述原生运行索引中的153份带 hash 日志，全部与索引一致；这是历史日志完整性核对，不是行为测试复跑。QA两包身份另见本批 `QA_PACKAGE_SEAL.json` 和运行目录 `APK_INPUTS.json`。

**剩余动作：**

1. 对齐测试后源码：本轮与合同17的 `TESTED_SOURCE_SHA256.json` 比对为1088相同、8不同；差异为4份扩展生产代码和4份测试代码，详见 `SOURCE_COMPARISON.json`。它们不是已证实缺陷，也不能省略验证说明。
2. 当前与后续 `A04-OAUTH-A05-COST-20261022/SOURCE_FINAL.json` 的1312个条目全部相同。说明当前存在后续冻结出处，不代表该批测试覆盖全部数学功能；需结合精确增量判断哪些数学/扩展交叉回归要补。
3. 补齐本批缺失的 `TRACE_AND_DIFF`、`TEST_RESULTS`、`ADVERSARIAL_REVIEW`、实际 patch、`VERDICT_AND_ROLLBACK`、`HANDOFF` 及六类 SOL 交付；保留原失败和日志，不将本清单充作批次审查通过。
4. 核实并补齐候选页面实际表面/原文件回读证据；确认临时 smoke 驱动没有进入普通包。仅对缺失或源码变化影响的门禁补跑，不无条件重复全部测试。
5. 更新适用版本地图：当前 `AgentTaskStore.kt` 已为 schema10，而合同17文档仍写 TaskStore9；应记录继承 A04/A05 后的增量，不回退源码或覆盖历史协议。

当前可写 **WIRED / 已有E2–E4历史局部PASS / 整批收尾裁决NOT_EVALUATED**，不能授予完整P2通过。

### 1.2 Release：已有隔离候选，尚未集成当前主工作区

`HXai_CHANGE_REVIEW/A07-RELEASE-20261021/` 有实际工作，不能继续简单说“Release从未构建”：

- 隔离工作树 `.local/a07-release` 的 Flutter Debug/Release AAR、宿主 Debug/Release/lint 有 exit0 日志。
- 该批测试签名副本在自有模拟器有 Release COLD 启动记录及 UI 产物；`LOCAL_TEST_SIGNED_COPY.json` 明确正式签名 NOT_RUN。
- **当前根目录** `client/app/build.gradle.kts:342` 仍只有 `flutter_debug` 依赖，没有 `flutter_release`；Gradle、APK核验器、构建文档与隔离候选均不同。不得借隔离副本通过宣称当前最终App Release已通过。
- A07目录也缺少完整六类SOL/最终交接，需先梳理真实补丁和验证边界。

下一步：按 before/candidate/current 三方比较，提取 Release依赖、签名缺省处理、Release隔离QA开关与APK核验器增量，建立新合同后最小集成；使用当前Flutter源码重新生成Release AAR，实际构建、包内容检查及测试签名副本冷启。正式签名、真机与发布另验。不能把旧候选三份文件整份覆盖当前实现。

## 2. 按优先级的剩余清单

| 优先级 / 项目 | 当前实现状态 | 还需要完成 | 完成判据 |
|---|---|---|---|
| 立即 / 合同17与A07收尾 | 合同17 WIRED；A07隔离候选 PRESENT，当前Release接线 PARTIAL | 补齐来源、增量、适用回归、交接；将Release候选最小集成当前工程 | 最新源码、测试输入、APK、产物和patch可相互对应；不借历史或邻批通过放行 |
| P0 / A06 云端持续Agent与设备协作 | 本机任务底座 WIRED；产品级云端/设备协作链 ABSENT/PARTIAL | 账户鉴权、服务器加密vault、唯一权威TaskStore、远程executor、可信停止证明/fence移交、设备等待与前台接管、截图生命周期 | 手机不可用时已授权服务器步骤真实继续；旧执行器不能提交，UNKNOWN不盲重放；换key不解除暂停/缺输入/设备确认 |
| P0 / 真实模型Agent质量 | Planner/执行/独立验收 WIRED；大量工程证据来自localhost合成Provider | 固定用户指定provider/model/reasoning，评估复杂目标拆解、漏目标、重规划、工具不支持、同任务追加和错误验收 | 真实成果与完整目标匹配；不把模型自评、合成请求数或工具成功算任务完成；保存实际用量/失败 |
| P0支撑 / A04协议和真实连接 | 通用MCP、Skills、逐工具范围、受限Python沙箱和OAuth受支持子集 WIRED | 验证真实HTTPS/OAuth账号兼容；按实际目标增加尚缺transport/schema、专属写操作verifier或二进制成果合同 | 授权/撤权/输入范围有效，独立读回能证明真实动作；丢ACK不重发。未支持能力继续明确拒绝，不追求无边界“支持任意MCP” |
| P0支撑 / A05费用覆盖与维护 | 上报/估算/未知费用贯通账本、UI、分组、导出 WIRED | 按实际使用供应商补可信金额/费率适配和账单对照，维护有效期/币种/缓存计费规则 | 当前支持OpenRouter Chat Completions上报和指定GPT-4.1标准文本估算；其余未知保留未知。旧金额不可随新价格重算，不设置费用硬预算 |
| P1/P2 / 真实MinerU与OCR | 来源保护、解析缓存、候选Word、多页转录确认 WIRED；真实识别质量 NOT_RUN | 用公开或获授权扫描材料实际识别，建立逐页逐符号真值；完成识别→歧义确认→批改→Word来源故事 | 页序/缺页/覆盖/符号歧义可核对；不能用人工输入或缓存候选冒充机器识别率 |
| P2 / 完整专业数学质量与按题型补能力 | 合同1–17及线代/多元/归纳/条件证明/数值证书等 WIRED，完整P2 PARTIAL | 建立江苏高中综合、高数/数学分析/实变题库和权威盲评；从真实错例补定理条件/量词/分支/证明工作流 | 正确异法不误判，首错有已核前缀；明确证明/严格误差/近似/探索和未证义务。不用再加算法数量替代专业质量 |
| P1 / B06跨Office保真 | Windows LibreOffice、专属Linux/WSL Docker和API26限定范围有历史PASS_SCOPED | MS Office/WPS真实打开、重算、公式/日期/图表和版式核验；最终版本的适用跨环境回归 | 四类文件分项实际读回；PPT全页、Word修改处及代表页、Excel实际重算分别取证；不把一种Office通过推广到另一种 |
| 交付 / Mate50和最终UI | 模拟器/局部界面证据存在；目标真机与TalkBack朗读 NOT_RUN | HarmonyOS4.2冷启、锁屏/后台/弱网、SAF、停止恢复、档案切换、小屏/大字/深浅色、TalkBack焦点与实际语音 | 真实目标设备和最终包验证；模拟器与无障碍树不能替代真机/朗读 |
| 交付 / 全产品审查与发布 | 分批局部裁决存在；独立及完整全项目审查 NOT_EVALUATED | 对最终候选按Pro适用全审门禁补全需求追踪、安全/迁移/恢复、包隔离及发布证据；正式签名和生产单列 | 未解S0/S1或必要验收FAIL阻断相关范围；不得用同一执行者复审冒称独立通过，不自动合并/发布/部署 |

## 3. 不应再列为“从零未开发”的内容

以下成果需保持并按当前变更回归，不因完整产品尚未通过而抹去：

| 已有能力 | 最新主要出处 | 仍有边界 |
|---|---|---|
| 原生持久任务、全局调度、接受时模型锁、journal、Verifier、任务中心 | 当前ChatService/AgentScheduler/AgentTaskStore/TaskPlanner/TaskResultVerifier/Host与原生交付 | 不等同云端Agent或真实模型规划质量通过 |
| A02删除来源、辅助请求、历史派生物、备份与Room33保护 | A02-A03-COMPLETION及AB-MERGE/API26合流记录 | 当前变更涉及这些边界时仍需适用回归 |
| A03两讲义Word、同任务追加、来源/预览/保存、PPT/PDF主Chat故事 | A02-A03-COMPLETION与AB-MERGE | 合成Provider，不是实际模型自主规划成绩 |
| B04资料库、授权目录、目录/标签、固定版本检索引用、删除/回收/恢复 | B04-B05-COMPLETE | 不称整书完整识别或全部设备通过 |
| XLSX实际重算、DOCX/OMML局部修改、PPT逻辑/隐藏页、PDF原页组装与候选稿 | A-OFFICE-CHAT、B02、B03 | 复杂任意格式和跨Office保真分别验收 |
| 通用MCP/Skills管理、逐工具授权、独立受限Python沙箱、OAuth | A04-GENERAL-MCP-SKILLS、A04-OAUTH-A05-COST | 受支持合同范围，真实OAuth账号另验 |
| 可信费用账本/UI/导出 | A04-OAUTH-A05-COST | 支持来源之外unknown是如实状态，不把金额未知当0 |
| Linux Office真实容器资格 | B06-LINUX-OFFICE：最终24场景、服务149测试历史PASS | 专属WSL/私有Docker/封印源码，不等于生产和MSOffice/WPS |
| API26与A04当批最终合流 | API26-A04-INTEGRATION | 后续OAuth/费用/数学增量没有自动获得API26新版本资格 |
| 专业数学、确认工作流、2D/3D与SVG/PDF/采样路径PPTX | P2-PROFESSIONAL-MATH合同16；合同17的代码/日志 | 不称通用证明器或权威题库质量通过 |
| Release隔离构建和测试签名冷启 | A07-RELEASE | 尚未在当前根目录接线；不能列为完整A07完成 |

## 4. 具体接续入口与命令

以下是接续模板，本轮均未执行。涉及设备的命令须先核独占设备/当前权限，使用新run-id和匹配源码的冻结QA两包；真实账号、收费和私有材料外传须在明确授权范围内。

### 4.1 合同17收尾

先读 `qa/remaining-development-20261024/SOURCE_COMPARISON.json` 和旧原生 `TEST_RUNS.json`，追8处差异的调用者/消费者，决定必要定向回归。原生驱动会安装、强停，并在归档后清理nativeqa，不能直接用于用户普通包。

```powershell
# 当前根目录；SERIAL、NEW_ID替换为已核实的独占设备和新ID
python scripts/verify_agent_android.py --serial SERIAL --run-id NEW_ID --apk-dir HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/qa-apks --classes me.rerere.rikkahub.feature.math.CertifiedCalculusRuntimeTest,me.rerere.rikkahub.feature.material.MathWorkCandidateRuntimeTest,me.rerere.rikkahub.feature.agent.MathSharedChatRuntimeTest --isolate-tests
```

该模板只复验旧冻结包；要宣称新源码通过，必须先重建匹配的QA两包并另存封印。算法/Flutter/普通Debug命令取 `docs/professional-math-contract17.md` 和本批 `smoke/run_verification.py`，采用新后缀，不能覆写原日志。

### 4.2 A07候选集成

```powershell
# 先比对，diff exit1表示存在差异，不代表构建失败
git diff --no-index -- client/app/build.gradle.kts .local/a07-release/client/app/build.gradle.kts
git diff --no-index -- scripts/verify_flutter_apk.py .local/a07-release/scripts/verify_flutter_apk.py
```

确认before/candidate/current后建立新CHANGE_CONTRACT，最小集成并更新版本地图。再沿 `A07-RELEASE-20261021/aar-build.json`、`final-build.json` 的原真实命令，在新隔离副本匹配缓存环境构建当前源码，而非直接继续使用旧副本产物。无正式密钥时只产生unsigned和明确测试签名副本，不使用debug密钥冒充正式签名。

### 4.3 A06先实现隔离协议

现入口为 `ChatService.kt`、`feature/agent/AgentScheduler.kt`、`AgentTaskStore.kt`；`server/agent-runtime/README.md` 明确该Python库不是公网API或产品级云Agent。不要另起未协调执行器。

下一步先新建A06合同及隔离fake executor夹具（尚无现成A06 suite可直接报通过），固定唯一权威、账户/owner、任务快照、grantEpoch、可信停止凭据和递增fence；覆盖双host争抢、旧host晚提交、写后退出、移交丢ACK、撤权/自然失效/设备确认/暂停并存。随后再做账户、vault、transport和设备适配，不默认接真实key。

### 4.4 真实模型/OCR与数学质量

入口：`ProfessionalMathGuidance`、`TaskPlanner`、`MathVisualRuntimeTest`、`DocumentServiceLiveTest`、`MathWorkInputs/MathWorkCandidates`。精确单方法命令及夹具/凭据前置见 `P2-PROFESSIONAL-MATH-20261022/SOL_BLOCKERS.md`，不能直接把其中旧serial当可用设备。

先制定公开或获授权材料的评估集、权威答案和逐符号标签，锁定实际provider/model/reasoning及角色传输范围，再运行；无真实服务时继续整理真值和可复现错例，真实结果保持NOT_RUN。正确但超出证书充分条件的题目不能直接判错。

### 4.5 扩展/费用/跨环境

- OAuth/新价格/新transport/写操作verifier：从 `A04-OAUTH-A05-COST-20261022/SOL_BLOCKERS.md` 的方法和合同接续。当前本地费率目录注明2026-11-21 UTC到期；届时须重新核对来源后才能用于新请求，否则保持unknown，不改旧金额。
- Linux复验：从 `docs/parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md` 的专属daemon/两终端命令接续，使用新run-dir与匹配源码/镜像，不启动用户全局Docker代替隔离环境。
- MSOffice/WPS/真机：另立环境合同、确认用户Office实例和数据归属，使用测试副本与新输出；不接管现有用户实例来补验收。

## 5. 建议执行顺序与回滚边界

1. 先完成合同17证据/源码差异收尾；整理A07候选并集成当前Release，形成可复核的当前候选基线。
2. P0优先实现A06隔离移交协议；准备真实Agent质量评估材料，并按实际使用需求补扩展/费用适配。
3. 在具体授权服务上验证真实Provider与OCR，再按真实错例推进专业数学；同步开展MSOffice/WPS与Mate50分环境验收。
4. 对冻结最终候选完成适用全项目门禁、正式签名及发布准备；独立审查没有执行者时维持NOT_EVALUATED，继续遵守禁止子代理。

所有代码批次重新建CHANGE_CONTRACT。回滚仅撤对应增量/停相应入口，保留材料原件/不可变版本、来源撤销/墓碑、费用账本、receipt、UNKNOWN和新schema数据；不reset/clean/stash、不将旧库或旧授权覆盖回来。本清单不授权自动合并、生产部署或外发。

百度网盘、邮箱、日历专用连接器继续后置；自动切主模型/推理强度和Token/费用硬预算不属于待实现目标。本轮不计算完成百分比。
