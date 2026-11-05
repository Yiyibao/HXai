# HXai / MathStudyAgent：grok 后续开发与验收计划

编制日期：2026-11-04（Asia/Taipei）。版本：1.0。新批次前缀：`G48`，避免与既有 `G47` 任务编号冲突。

本文供用户直接交给 grok 执行。它承接 [原七项任务书](grok-development-tasks-1-7-20261104.md)，以本次重新读取的证据更新执行顺序，不覆盖原任务书、原 Pro 规范、历史失败或授权记录。本次仅编写计划，没有实施下列产品修改或重新运行产品测试。

## 1. 接手事实与目标

### 1.1 当前快照

- 工作目录：`D:\Office\Study\code\MathStudyAgent`。
- 分支：`codex/storage-lifecycle-20261103`；完整 HEAD：`a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`。
- 本次读取时：14 个已跟踪文件有修改、40 个未跟踪条目（条目含目录，不等于文件数）。这些是既有工作，不能清理、覆盖、自动 stash 或全部视为自己的提交。
- 开工存储 check PASS：1 个工作树，约 7.16 GiB 逻辑文件、5.56 GiB 生成物；注册表保留的32条记录均 CLEANED。接手时重新检查，不把此值当实时配额。
- 当前版本资格必须用 `HEAD + tracked/staged diff + relevant untracked SHA256 manifest` 描述。只记录 HEAD 无法绑定这些未提交实现。
- 本计划未重新查询远端；涉及 PR、main、合并基线或线上文件时另查当时状态，不复用旧审查的远端结论。

### 1.2 必须继承的进展

| 范围 | 已有证据 | 本计划如何接续 |
|---|---|---|
| 构建 | G47-01：Pigeon、Flutter analyze/422 tests、Debug/JVM/lint、API26/API34 epoch 定向测试通过 | 不再把工具链写成完全缺失；这些证据早于后续 dirty 改动，补当前同源回归 |
| 真实模型 | G47-02-LIVE 的 sealed 目录有37个 CASE：28 PASS、9 FAIL（工程状态） | 保留原37个案例与原答案；建立新 cohort，不改旧分母或结果 |
| 数学验收修复 | 反例交付、归纳、恒等式边界、有理递推有源码及定向证据 | 先审可见答案与证书绑定，再做原失败场景复验，不重复造同样核验器 |
| 含参三次函数 | G47-11 已补齐 HANDOFF/TEST_RESULTS；JVM4项、API26两方法通过 | 已不再是“只有合同”；尚缺完整 HostAcceptanceRuntimeTest 重跑及真实模型案例复测 |
| MCP | 新增 ExtensionToolGrantStore，工具发现与初始 invoke 检查显式 grant | 继续做普通App授予/撤销、epoch和传输时复核；不能宣称完整权限闭环 |
| 文档撤权 | DOC-REVOKE-DEPLOY 已有授权部署、合成公网验收及 worker/validator 不存在的独立读回记录 | 不盲目再次部署；仅撤权子集上线，其他文档服务能力另作版本核对 |
| 云端 | 本机协作与离线安全协议已有实现；CloudBoundary.HOSTED_KEYS="ABSENT"、跨App关闭 | 完成实际托管与跨主机链，不能只改变状态标签 |

证据入口：`HXai_CHANGE_REVIEW/G47-01-BUILD-BASELINE-20261104/`、`G47-02-LIVE-20261104/`、`G47-05-INDUCTION-20261104/`、`G47-06-IDENTITY-20261104/`、`G47-07-MCP-GRANT-20261104/`、`G47-08-CLOUD-BOUNDARY-20261104/`、`G47-10-RATIONAL-RECURRENCE-20261104/`、`G47-11-CUBIC-MONOTONICITY-20261104/`、`DOC-REVOKE-DEPLOY-20261104/`。上述均相对仓库根目录。

目标是让原 Chat 在受支持范围内可靠完成真实任务，保全权限、数据与恢复语义，并拿到与当前源码绑定的验收证据。增加操作种类、测试数量或文档数量不作为完成标准。

## 2. 执行规则与顺序

先完整阅读根及相关目录 AGENTS.md、Pro 权威文件、原七项任务书的“必须阅读原文”清单、`BUILD_AND_TEST.md` 和 `docs/storage-lifecycle-v1.md`。历史 Pro 中 Q[x]、schema32 等仅是原版本范围，结合后续正式增量，不把当前能力降回旧版。

执行顺序：

1. **G48-00** 收齐当前来源与同源可构建基线。
2. **G48-01、G48-02** 优先关闭“假成功/正确输入误拒”和 MCP 授权闭环；发现已证实 S0/S1 时先修再推进相关真实调用。
3. **G48-03** 用原失败集驱动 P0 与数学质量修复，完成新37案例评估。
4. **G48-04、G48-05、G48-06** 分别推进 Office、OCR、云端；不依赖外部环境的合同、实现和合成验证可先做。云端属于 P0，具备条件即推进，不必等待全部数学/OCR通过。
5. **G48-07** 横向核费用、数据生命周期和用户界面；相关安全问题可提前插入。
6. **G48-08** 当前候选整包回归与发布准备。正式签名、分发、生产变更不是本计划自动授权的动作。

保留原 ChatService / ConversationSession / GenerationLoop / TaskPlanner / TaskStore / 材料版本库及唯一调度权威；不重造第二套Agent。用户锁模型/推理强度，专用角色独立配置与记账。UNKNOWN 不盲重试，主动暂停/撤权与设备/输入等待独立。文件新版本保留原件，备份不复活删除或授权。不设Token/费用硬预算。

grok 4.7 可按根规则使用子代理；主执行者统一接管 `AgentTaskStore.kt`、`TaskPlanner.kt`、`AgentPlan.kt`、Pigeon及共享AAR，避免并发改同一文件。子代理适合独立模块勘探、实现、反例准备；不等于外部独立审查。设备、Gradle/Flutter共享输出与存储run串行独占，最多两个工作树。

## 3. G48-00：当前增量收尾与构建基线

**交付结果：** 能准确说明当前哪份源码构建了哪份包、哪些回归通过，且没有丢失既有改动。

1. 重读 tracked/staged 完整diff和未跟踪产品源码，按数学验收、MCP、云边界、测试、文档划分来源；记录文件hash与来源批次。`AGENTS.md` 和基线文档保留用户改动，不混作产品修复。
2. 核每个 G47 小批的合同、真实日志、patch、源码绑定与接续。补紧凑索引，不复制整棵QA/源码/产物树。找不到原始证据则写未绑定/NOT_RUN，不能补造过去的运行记录。
3. 核 G47-11 新API26证据的源码范围。重跑受影响当前完整 HostAcceptanceRuntimeTest；再做当前共享链的混合运行，防单方法通过掩盖状态污染。
4. 按当前变化重建需要的 AAR/宿主，验证 Pigeon两端、Flutter analyze/test、受影响JVM与lint、普通Debug冷启与真实Host回调。Pigeon未变无需反复生成后改格式；有变必须生成两端。
5. 将 Release 缺 `org.hxsi.agent` Firebase客户端配置、缺debug engine工件分别处理。核现有配置用途与合法来源；不能伪造配置、跳过Google Services门禁、放宽APK校验或使用debug密钥冒充发行。外部账户配置缺失只阻断相关步骤。
6. 当前候选只保留一套共享Flutter AAR；按源码和产物hash绑定，不从同版本号推定字节相同。提交前按规则收口，但不自动推main、合并或部署。

**验收：** 当前 dirty manifest 对应可构建 Debug；新验收代码及MCP改动的受影响回归有明确结果；旧数据/UNKNOWN保持；Release与引擎校验各自记录 PASS/FAIL/BLOCKED，不能相互代替。

**入口：** `BUILD_AND_TEST.md`、`scripts/storage.init.gradle`、`scripts/verify_flutter_apk.py`、`client/app/build.gradle.kts`、G47-01与G47-11证据。

**回滚：** 只回退本批增量；不删除G47代码或用户配置，不回滚真实授权撤销和数据。

## 4. G48-01：完成判定与可见答复绑定（P0，优先）

**用户故事：** 正确且完整的回答能完成任务；提到正确公式但实际否定、写错结论或漏条件的回答不能借宿主证书显示成功。

本次静态阅读发现需要优先验证的具体入口（是待复现线索，不预先宣布漏洞等级）：

- `InductionGoal.quoted` 当前用子串包含判断是否写出闭式。
- `CubicParameterGoal.quoted` 当前按参数分格和“严格递增/递减”词检查，需核是否真正绑定临界点、区间和结论。
- 多个宿主识别器对题面措辞/固定变量较敏感；正确等价表述可能落入不同路径。
- `TaskPlanner.accept` 和 `AgentTaskStore.accept*` 新路径必须与普通计划、持久回复、lease、审批和未决调用保持一致。

**实施：**

1. 先给每种完成类型定契约：闲聊回复交付、文件交付、核验假命题/给反例、形式证明、缺输入等待。明确每种依赖哪些持久证据；工具DISPROVED只能支撑匹配的反驳任务，不能证明原假命题。
2. 在当前版本尝试：回复“不是n²”却含n²；正确公式仅作为错误示例/引用；同时出现互相矛盾的结论；三格都有增减词但临界点/区间错；漏定义域；证书证明了另一个对象。保留实得，未触发不要写成已复现。
3. 同时测合法异法、LaTeX/Unicode/空白、变量改名、等价分支合并、同一问题自然改写。冻结原37题之外的变体，禁止只针对题号或固定句子补通关。
4. 优先复用可信结构化谓词与数学解析，形成对象/前提/结论/区间/来源绑定。模型可提出结构化候选，宿主核验；不能只新增关键词或用第二次模型“确认”冒充独立数学证明。
5. 原子提交前重读当前分支回复、task身份、revision、owner/contextEpoch/fence/grant、计划与未决调用。验证旧回包、撤权、暂停、分支切换、进程退出、重复完成不改变不该改变的数据。
6. 不能形式化覆盖的目标保留未核验/需确认，并解释范围；不得把所有难题统一降成普通回复已交付来消除工程失败。

**验收：** 正确对照仍能完成；上述假成功反例被拒；拒绝不损坏原任务；完整HostAcceptanceRuntimeTest及共享Chat回归通过；可见结果与证书对象一致。适用授权/任务架构变更执行全审门禁。

**入口：** `feature/agent/{TaskPlanner,AgentTaskStore,AgentPlan,InductionGoal,RationalRecurrenceGoal,CubicParameterGoal,IdentityCaveatGoal,RefutationDelivery}.kt`、`data/ai/GenerationLoop.kt`、`HostAcceptanceRuntimeTest.kt`，均在 `client/app/src` 对应 main/androidTest 源集。

**回滚：** 保留已有任务与回执；如有新schema必须提供前后兼容和安全停用方案，不清库。

## 5. G48-02：MCP逐工具授权真正闭环（P0支撑）

**用户故事：** 用户在普通App明确授予某安装的某工具范围，任务按批准输入调用；撤销后连正在握手中的请求也不能继续派发新的工具动作。

本次在生产 `feature/agent` 路径搜索，只见 `ExtensionToolGrantStore` 的读取调用，未见前台写入grant调用；store虽保存 `grant_epoch`，`granted` 查询当前只返回工具名，`invoke.checkCurrent` 当前重查安装状态和任务guard。须沿完整前台链确认，不能仅凭新表/单测认定权限功能完成。

1. 先追 `ExtensionFrontend`/实际管理入口→Pigeon或现有桥→store→toolFor→invoke→transport→receipt。已有安装范围及任务批准不能被新表割裂；明确哪个是权威，避免两套授权状态不一致。
2. 实现普通App可用的逐工具授予/查看/撤销，并绑定owner、installation、工具契约/版本、范围、授权代次及适用任务。启用安装、OAuth成功、远端schema仍不是授权。
3. 派发前及网络交互到实际call之间重核当前授权；不能只在invoke入口查一次。旧epoch、契约变化、停用/重启/重装、跨owner、备份恢复不得复活grant。撤权后在途结果按真实UNKNOWN/只读结算规则处理。
4. 持久化迁移默认保守不自动授新权；正确的用户重新授权有可用路径，不能只“全拒绝”当安全完成。
5. 扩展仪器测试不只直接 `store.grant`：增加普通管理入口的授予→调用→撤销→旧批准拒绝→重新授权；核现有 `ExtensionRuntimeTest`、`GeneralMcpChatStoryTest`、`SandboxMcpChatStoryTest` 与进程恢复probe。
6. 合成transport测握手中撤权、schema变更、同名工具、越域参数和超大输出。真实服务只沿已有具体授权验证；外部写工具需独立副作用/回读合同。

**验收：** 未授权零实际工具dispatch；只授权A不能调用B；撤权/旧epoch/跨owner不可调用；重新明确授权可用；重启持久正确；原任务审批和可信verifier不被绕过。提交前适用全审。

**回滚：** 禁用新入口而保留数据和撤权，禁止重新开放旧共享McpManager绕过检查。

## 6. G48-03：原失败集、真实模型与数学质量（P0/P2）

原37案例的9个工程FAIL，按下表接续，不能把“不成功”全部强改为SUCCEEDED：

| 原案例 | 当前线索/增量 | 下一步与正确结果 |
|---|---|---|
| AGENT_FALSE_COMPLETION | 反例交付已补定向证据 | 验原命题/实际反例/可见回答绑定；核验任务可完成，假命题仍DISPROVED |
| AGENT_MISSING_INPUT | 缺输入场景 | 核评估合同和实际等待状态；应准确等待与续接，不假造输入或强制成功 |
| HS-SEQUENCE | 归纳增量 | 正确闭式、基例、归纳步、量词、可见结论共同复验 |
| HS-SEQUENCE-TELESCOPING-FULL | 有理递推增量 | 对原初值/定义域/闭式与完整要求复验，不外推为一般求和 |
| HS-PARAM-CUBIC-FULL | G47-11三格证书/API26两方法已通过 | 审完整区间/退化边界绑定，重跑整类和真实回答 |
| HS-CONIC-IDENTITY | 恒等式不等于几何证明的边界增量 | 保留限制说明，不把代数恒等核验冒充几何命题 |
| HS-CONIC-PARAM-INTERSECTION | 仍需完整题目对象核验 | 明确参数分支、交点存在性、重根/退化、完整性；支持不了就保留未核验 |
| HS-SEQUENCE-SAMPLING | 题干有“同上”上下文问题 | 原输入保持，检查传入上下文/缺输入机制；若题库有缺陷另版本修订，原分母不改 |
| ANALYSIS-UNIFORM-NOT-DERIVATIVE | 分析命题/条件范围 | 逐条核一致收敛与求导结论的条件/反例；不套局部代数证书 |

**执行方法：**

1. 先封存原CASE/RESPONSE/RECEIPT/USAGE、输入与源码身份；检查“instrument OK”只说明测试驱动完成，不代表37题全通过。
2. 本地可做：离线评分工具回归、失败答复静态分析、合成provider端到端、数学内核反例和变体集、参考依据整理。
3. 真实新cohort沿原Chat、配置快照和账本执行；读取既有授权记录，确认覆盖当前endpoint/公开输入/模型/重跑范围。不因本计划重新索要已授权限，也不把旧一次性授权扩成无限调用。当前旧记录模型为 `gpt-6.1-terra` / MEDIUM，若用户另选则新版本明确记录，不暗换。
4. 不直接重跑 `G47-02-LIVE-20261104/run_live.py`：其run-id、端口、日志路径固定且有AVD清理动作。先做可配置安全驱动，要求唯一新run-id、设备归属、当前APK hash、无历史覆写、每阶段退出检查、临时凭据不入日志、只结束本次创建的进程；不自动删外部设备池快照。旧UNKNOWN先对账，不重放。
5. 先跑针对原9项的合法新任务定向复测，再跑完整固定37题。保留28项旧PASS回归；有授权/环境阻碍则完成全部本地工作并列精确依赖。
6. 数学32题按原reference/obligations逐项 MET/VIOLATED/UNDETERMINED，绑可见答复证据；工程状态、目标覆盖、数学质量、用量分四列。参考或标签确需修正另版本、说明理由，不能迎合代码改预期。
7. 新增冻结变体作为独立泛化集合，不拼进原37项提升比例。独立数学审查缺失仍 NOT_EVALUATED，交可供外部审阅的题目/答复/义务/证据包。

**验收：** 每个FAIL都有根因、预期行为、修复或诚实限制、新证据及下一步；固定37个案例完整报告，满足正负场景合同，无未解释必要失败。不能用一律降级未核验宣称质量目标完成。

**入口：** `LiveQualityEvaluationTest`、`scripts/{prepare_r27_live_quality,import_r27_quality_answers,score_b_math_evaluation,verify_agent_evaluation_process}.py`、`docs/quality-06-09-contract2.md`、`docs/b27-math-obligation-scoring-contract1.md`。

## 7. G48-04：文件与Office实际交付（P1）

1. 复用 `MaterialEditor`、`MaterialReader`、`MaterialFiles`、`MaterialSourceLocationRead` 与 `DocumentServiceRepository`，核文件原件、parent/sourceHash/expectedText/locator/owner链，不重写第二套资料库。
2. 重现原WPS三类失败：DOCX公式/PDF映射与版式、XLSX导出严重缩小、PPT可见fallback丢失。锁定同SHA输入、引擎版本与字体，逐项定位writer/兼容表示/导出设置/引擎限制。可修则修；确不支持则真实UI说明，原需求仍记未达。
3. 分引擎/格式验证：DOCX可编辑OMML与非目标内容；PPT按r:id逻辑顺序核全部页和隐藏设置；XLSX核公式/常量/日期/跨表/图表与实际重算；PDF核真实页与视觉。文本抽取和XML结构通过不能代替渲染。
4. 在普通App跑创建→读取→局部新版本编辑→转换→预览/另存→重开，独立读回产物；错误定位/源变化/撤权/进程退出保原件且不产生假“已验证”。
5. 线上服务先只读核实际版本/capability/路由/receipt。已部署的撤权子集不代表新request lookup、重算、配额或schema已上线；UNKNOWN按原requestKey/remoteId查询，不重新enqueue。
6. 延续Office预检边界，覆盖UTF16 DTD/实体XInclude/正常UTF16、ZIP重复/路径/CRC、宏/签名/外链。合法超链接被拒的产品行为明确；需要改策略另立合同，不能直接放开外部解析。

**验收：** 每个引擎、格式分别有结构/语义/重算/视觉/原件保全结论；历史FAIL有同输入对照；真实App流程绑定当前包。历史撤权停止证据只授其合成任务，不推为任意容器都已验证。

**入口：** `scripts/verify_b_microsoft_office.py`、`verify_b_wps.ps1`、`verify_b_linux_office.py`、`office_roundtrip_contract.py`、`verify_office_artifact.py`。先审副作用，只使用自有新文档/进程；不关闭用户现有Office/WPS。

**回滚：** 还原本批编辑/导出策略，保留全部原件与新版本；服务候选单独留回滚包，不自动部署。

## 8. G48-05：OCR到批改与可编辑Word（P1/P2）

1. 保留固定五页、原93字符真值与已有WinRT/MinerU分数；旧LiveTest、固定评分集和MathWriting样本身份分开。MathWriting许可及未识别结果不能被新样本覆盖。
2. 先完成语料schema/许可记录/标注规则/错误分类；按页覆盖、正文CER、公式符号与结构、关键歧义漏报分别评估。在看新结果前声明门槛，不能仅选“平均分提高”掩盖关系符/负号/上下标错。
3. 新增合法真实多页手写及人工真值，按作者/文档隔离调参与保留评估集。缺用户材料时先用许可明确的公开样本与独立保留集；不伪造学生样本，不擅自扩远端OCR传输范围。
4. 有界比较既有明确配置引擎、语言及预处理，保留原baseline。专用角色/服务/用量可见，不能用人工抄答案或静默换模型提高机器成绩。
5. 跑原图/PDF→实际覆盖→候选→逐歧义确认→同一Chat批改/首错→可编辑Word→预览/另存；保持原识别、修订、来源版本与真实页定位。人工修订质量和机器识别质量分开。
6. 非白页无文本、隐藏公式/图片/表格、源删除/变更、取消/进程退出、旧确认、档案切换、缓存清理都做反例；教材印刷页只有映射证据才展示。

**验收：** 固定基线与新保留集分别报告；关键符号不确定时触发确认；误确认不因旧回包复活；Word独立结构/渲染验收。没有足够真值只授工作流，不授手写质量。

**入口：** `MathWorkInputs/MathWorkCandidates/MathTranscription`、`MaterialPdfOcrDraft`、`OcrTransformer`；`scripts/verify_b_windows_ocr.py`、`verify_b_ocr_corpus.py`、`verify_b_pdf_ocr_draft.py`。

## 9. G48-06：云托管与跨主机持续执行（P0）

1. 先读 `docs/r27-cloud-device-protocol-v1.md` 全部增量、现有authority/worker/vault实现和CloudBoundary，画清“现有本机任务密钥引用”与“真正服务器托管用户Provider密钥”的边界。不要因已有云页面认定托管完成，也不要推翻现有唯一权威。
2. 在原架构补显式按服务/任务授权、加密存储与引用、最小worker读取、过期/轮换/撤销、日志脱敏及退出清理。威胁模型决定系统密钥/KMS方案；模型、扩展、任务正文和receipt不能拿到明文密钥。
3. 先做合成跨进程/回环协议及故障：错CA/主机名/过期证书、网络中断、双端争权、lease/receipt丢ACK、旧executor发布、未知收费请求、暂停/撤权持久。恢复验证新key只恢复原范围，不清其他blocker。
4. 准备可审阅部署候选、服务用户/权限/证书/网络/备份回滚方案；现有明确授权可按范围执行，新的主机/托管/私料/生产权限不足时只阻断实际外部动作，继续实现与隔离验证。
5. 在已授权真实两主机完成：手机退出→服务器继续已托管步骤→手机恢复读回同task成果；设备专属步骤等待→安全释放执行位→其他任务可运行→前台确认→原任务优先排队且不抢占；保持原fence和UNKNOWN。
6. 本批保持可选跨App/远传截图关闭。若用户后来明确启动该能力，另立前台确认、可见停止、任务级传输及保留合同，不能随云功能默认启用。

**验收：** 托管配置真实存在且可撤、未授权无外发；两端不同时执行；原模型快照与账本一致；断网/重启/丢ACK无重复副作用；新key不复活主动撤权。具备实现前不把HOSTED_KEYS改成可用。

**入口：** `CloudFrontend`、实际云authority/worker/vault、`scripts/verify_r27_authority.py`、`verify_r27_cloud.py`、`verify_r29_tls.py`、`verify_r29_validation.py`、`verify_r27_android.py`。

**回滚：** 停止新托管准入并安全结算在途任务，保留UNKNOWN/撤权/成果；不能降fence、清数据库或恢复已撤销凭据。

## 10. G48-07：费用、长期数据与普通界面

- **费用：** 逐物理请求核requestId/task/model/role/config，覆盖stream/非stream、辅助角色、重试、云导入；reported/estimated/unknown分开、不漏计不重复。价格信息从当时官方依据核对；没有真实账单只授合成协议。日周月时区、未知覆盖、导出、异常提醒通过；不加财务硬上限。
- **长期数据：** 聊天/教材删除→摘要/记忆/OCR缓存/索引→备份/恢复→迟到结果；旧备份不能复活授权/删除/原执行队列。用旧库、损坏密文、缺key、跨owner、路径变更、真实进程退出验证保全，不清库当修复。
- **网络：** 从WebImageService/WebPageReader追至Flutter最终loader/预览/缓存，核DNS/IPv4/IPv6/重定向/大小/凭据及二次下载。测试用合成DNS/transport，不探测真实内网；合法用户授权的模型内网endpoint与公共网页策略分开。
- **界面：** 普通包从Chat到任务/文件/OCR/扩展/云控制均是真实命令；SUCCEEDED、COMPLETED_UNVERIFIED、WAITING_INPUT、STOP_REQUESTED有准确区别。实际验证小屏、200%字体、深浅色、长文件名、键盘、TalkBack与档案切换，不以静态参考图代替。

**验收：** 每条有真实入口→存储→消费者→UI证据；回归原使用路径，新增授权表/云凭据/源页语义均覆盖删除、备份和晚回包。

## 11. G48-08：整包候选与交付门禁

1. 以最终源码manifest构建普通Debug与unsigned Release，核实际AAR资源/engine/AOT及QA入口隔离。缺正式签名不妨碍完成unsigned验证；不得默认debug签名分发。
2. 以相同源码组合跑相关JVM/Flutter/服务合同、API26/API34混合场景、进程恢复和普通App主故事。只因新改动/失败/未解决问题重复测试，避免无意义整轮构建。
3. 有目标设备条件时实际验证Mate50/HarmonyOS4.2启动、权限、文件选择、锁屏/后台、网络恢复和TalkBack。缺设备明确NOT_RUN，不能用模拟器代授。
4. 按正式全项目规范形成当前候选全审：授权、任务/凭据/数据库架构和跨档案数据是必审范围；同执行体系自审写明，外部独立审查缺失记NOT_EVALUATED。
5. 发布准备和实际发布分开。产出候选说明、支持矩阵、已知限制、数据升级/回滚、精确接续。最终签名、分发、PR合并、线上部署仅在已有对应授权范围内执行。

**总门槛：** 不存在已证实且未解的适用S0/S1；必要验收无未解决FAIL；所有真实依赖可达；当前源码与证据绑定；未验收域明确不放行。每个模块分别裁决，不用局部PASS给完整App背书。

## 12. 安全命令入口与环境依赖

接手先执行下列只读/容量命令，之后按合同实施：

```powershell
Set-Location D:\Office\Study\code\MathStudyAgent
git branch --show-current
git rev-parse HEAD
git status --short
git diff --stat
git diff --cached --stat
python scripts/project_storage.py check
python scripts/project_storage.py report
```

新克隆安装本地 `install-hook`，不覆盖已有hook。每批收尾重复check/report。新增工作树前 `check --new-worktree`。

G48-01定向JVM入口示例（先核工具链和当前测试类；这不是已执行结果）：

```powershell
$storageInit = (Resolve-Path scripts/storage.init.gradle).Path
python scripts/project_storage.py run --feature G48-01-ACCEPTANCE --kind test --disposable --reserve-mib 2048 -- cmd /d /c client\gradlew.bat -p client -I $storageInit :app:testDebugUnitTest --tests me.rerere.rikkahub.feature.agent.InductionGoalTest --tests me.rerere.rikkahub.feature.agent.RefutationDeliveryTest --tests me.rerere.rikkahub.feature.agent.IdentityCaveatGoalTest --tests me.rerere.rikkahub.feature.math.RationalLinearRecurrenceTest --tests me.rerere.rikkahub.feature.math.CubicParameterMonotonicityTest --offline --no-daemon --console=plain
```

该入口只跑测试且会回收临时build；正式取证需在同一个受控前台驱动返回前提取必要XML摘要、失败原文和hash到本批审查目录，不把已删除路径写成交付证据。输出多于预留容量时按实际需求在既有6GiB单次上限内规划，不能提高策略上限。

其他驱动先读源码与 `--help` 核参数、副作用和路径：不得直接照抄旧 `--run-dir`、旧serial、旧冻结包路径。普通工具测试/构建/截图均由 `project_storage.py run` 入口，明确 disposable/preserve；子驱动在启动后读取 `HXAI_RUN_DIR`，不能在父shell未设置时提前展开为空路径。SDK/Flutter/Gradle/AVD放项目外共享位置。

服务/AVD/长期worker不能把真实状态放进一次性目录；测试若启动自有辅助进程，必须证明退出前已关闭并读回，否则标BLOCKED待结算，不超时自动清。活动运行仅一个，保留最多一批24小时。

| 依赖缺失 | 仍可继续的工作 | 外部动作前需要的事实 |
|---|---|---|
| Firebase对应配置/debug engine | 代码、单测、已可构建variant、配置诊断 | 合法配置/共享SDK工件来源，不能伪造 |
| 真实模型新调用范围不明确 | 原回答分析、合成provider、评分、反例修复 | 原授权是否覆盖当前新cohort；只补具体缺失范围 |
| WPS环境 | writer/兼容结构、MSOffice/LibreOffice已可用路径 | 独占自有实例与固定输入；不操作用户实例 |
| 手写真值不足 | 公开语料合同、标注工具、覆盖/确认链 | 许可、脱敏、人工真值与引擎传输范围 |
| 云主机/托管授权不足 | 实现、合成TLS/多进程故障、可审阅部署包 | 主机、证书、凭据托管与生产动作的明确范围 |
| Mate50/外部审查缺失 | API26/API34/unsigned候选、审查材料 | 目标设备实际运行、外部审查独立证据 |

## 13. 每批必须交付

实现前写可证伪CHANGE_CONTRACT；已确定缺陷保留旧失败→最小修复→正常/边界/故障→相关旧回归。完整diff追调用者与消费者。对抗复审至少包含假成功/错恢复、正确输入误拒/旧数据损坏两类。

在 `HXai_CHANGE_REVIEW/G48-XX-<短名>-<执行日>/` 输出：

- CHANGE_CONTRACT、TRACE_AND_DIFF、TEST_RESULTS.json、受控原始logs、可审阅patch、ADVERSARIAL_REVIEW、VERDICT_AND_ROLLBACK、HANDOFF。
- SOL_IMPLEMENTATION、SOL_TEST_EVIDENCE、SOL_CHANGE_REVIEW、SOL_ACCEPTANCE_MATRIX、SOL_BLOCKERS、HANDOFF_STATUS。可以引用同批紧凑源文件，不复制重复大型证据。
- 测试记录cwd、命令、工具版本、退出码、时间、源码/输入/产物hash、日志完整性与清理结果；不记密钥/私料正文。
- 触发全审的变更另在 `HXai_FULL_REVIEW/<batch>/` 提交规定的快照、来源矩阵、发现、运行、修复、裁决和接续。

统一汇总字段：`需求ID | 用户故事 | 实现状态 | 源码manifest | 证据层 | 测试结果 | 独立审查 | 适用范围 | 遗留依赖 | 下一条精确命令 | 回滚`。

实现仅ABSENT/PRESENT/WIRED/DISABLED/PARTIAL；证据E0—E6；测试PASS/FAIL/SKIP/NOT_RUN/BLOCKED；裁决BLOCK/CONDITIONAL/PASS_SCOPED/NOT_EVALUATED。不要给虚构完成百分比，不累计历次测试数。不要因为受限环境只能验证一部分就停止全部可独立推进的工作。

## 14. 可直接复制给grok的启动指令

> 请在 `D:\Office\Study\code\MathStudyAgent` 按 `docs/grok-next-development-plan-20261104.md` 执行后续开发。先读根AGENTS.md及其Pro权威文件，重新记录branch/完整HEAD/dirty manifest和存储状态，优先复用当前隔离feature分支，保留全部已有未提交工作。当前已不是原七项任务书的fac15db快照；G47-11已有JVM和API26定向证据，原37个真实案例仍为28工程PASS、9FAIL，不能改旧结果。
>
> 从G48-00开始，优先完成G48-01答案与证书绑定、G48-02普通App逐工具授权及撤权全链，再完成G48-03新cohort和原失败集修复；Office、OCR、云端按依赖推进，最后核长期数据/费用/UI及当前候选整包。先复现具体假成功或误拒再修实现，不能只靠关键词、给难题降级或更改评分消除失败。保留唯一调度权威、锁模型、UNKNOWN、原件/不可变版本和全部授权边界。
>
> grok 4.7可按根规则使用子代理，但共享文件由主执行者收口、设备与测试run串行独占，子代理不算独立审查。遵守共享工具链、最多两工作树、唯一活动run、HXAI_RUN_DIR与回收规则。既有明确授权可沿原范围继续，本指令不扩大真实模型/私料/账号/托管/生产权限；缺外部条件时继续所有独立可做的实现与隔离验证，给出精确待办。
>
> 每个小批交付合同、反例/修复/回归、当前源码绑定、完整可审阅diff、规范审查资料及六类SOL文档。最后按本计划逐项给实际裁决、遗留、下一命令和回滚；正式签名、合并、分发、部署不可自动执行，不以文档完成代替产品完成。
