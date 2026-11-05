# HXai / MathStudyAgent 开发与审查规则

## 2026-11-03 用户新增：grok 4.7 允许子代理

用户明确决定，覆盖 2026-10-12「禁止开启任何子代理」：活动模型为 grok 4.7（含 `xai-oauth/grok-4.7` 及其思考档）时，允许按 omp 需要开启子代理。数量不由本仓库另设上限或固定配额，随 omp 当次任务需要开启，直至 omp 自身并发上限。其他模型仍遵守 2026-10-12 禁令，除非用户另行决定。

子代理可用于勘探、开发、接线、定向验证和修复。开启子代理不构成外部独立审查；未做独立审查仍记 `NOT_EVALUATED`，不得把同一执行体系的子代理复审写成独立通过。Pro 产品约束、安全不变量、验收门槛、存储生命周期和最多两个工作树不因本决定降低。历史批次与规划文档中的「未开/禁止子代理」是该批事实，不回写，也不再约束 grok 4.7 会话。


## 2026-11-03 用户新增：存储与统一回收（后续执行必须遵守）

用户要求阻止测试、构建、工作树重复占用。以下规定优先于历史文档中要求无限保存纯测试数据的执行方式；原始 Pro 规范包不修改，真实产品安全与验收门槛不降低。

1. 每批开工、收尾运行 `python scripts/project_storage.py check` 和 `report`；新增工作树前运行 `check --new-worktree`。优先复用当前隔离 feature 分支，最多两个工作树，禁止每轮复制整个项目或冻结完整源码树。
2. 使用唯一策略 `.hxai-storage-policy.json`：单工作树 12 GiB、全部工作树 16 GiB、生成物 8 GiB、单次运行 6 GiB、磁盘至少空闲 10 GiB；超限阻断开工/提交，不得临时提高限制掩盖失败。统计为逻辑文件大小，跳过链接并列出。
3. 新增本地前台测试/构建/截图通过 `scripts/project_storage.py run`，明确 `--disposable` 或 `--preserve`。临时输出必须使用 `HXAI_RUN_DIR`；默认完成即回收，可显式保留最多 24 小时、最多一批。一次只允许一个活动运行。禁止守护进程、后台子任务或真实服务使用该一次性目录；Gradle 使用 `--no-daemon`。
4. SDK、Flutter、Gradle 工具链放在项目外共享位置，不向工作树复制。AVD 放在项目外统一测试设备池，最多 API26/API34 两个，默认禁用快照；外部池数量与缓存须人工核查，当前脚本不宣称管理全机磁盘。Flutter host AAR 只保留一套可复用集成输出，不按测试批次复制。
5. 保留紧凑合同、失败反例文字、测试结果、哈希与接续信息；不长期堆积 APK、截图、完整 QA 树、重复 build、模拟器快照与原始海量日志。运行日志上限 8 MiB并标记是否截断，回执最多32条。提交日志上限256 KiB、普通文件16 MiB，生成物和运行数据库禁止入库；必要源码夹具例外必须精确列入策略并说明出处。
6. 自动回收仅限本工具登记、标记匹配、已结束、明确可丢弃的 `.local/hxai-runs/<id>`。先持久记录回收意图、隔离、删除、再读回确认；失败保持待结算。活动/中断不明运行不按超时释放；未知目录、Git 跟踪文件、链接/junction、原始规范、凭据、用户材料、真实任务数据库不能自动删除。
7. 启用本地 `install-hook` 提交门禁（每次重新克隆需安装；不覆盖已有 hook）。构建 wrapper 已接入容量预检；外部 Gradle、Flutter 和历史脚本不得绕过统一入口。旧脚本的固定 SDK/AVD 路径须先迁移到共享位置，不能为运行旧脚本重新复制工具链。
8. 收尾必须核对本批一次性输出已回收；保留或 BLOCKED 时明确目录、原因和下一步。当前机制是运行前/结束时门禁，不是操作系统实时磁盘配额，不能声称能拦截人为绕过的任意命令。详见 `docs/storage-lifecycle-v1.md`。

用户于 2026-10-12 明确要求：以 `HXai_Pro_Delivery/` 为后续开发基准，严格遵守 Pro 的实现、全项目审查和新增功能审查提示词。本文件使该要求在后续会话中持续生效。用户后续明确决定及系统/开发者指令优先。

## 权威文件：必须读取原文

本文件是执行入口，不替代 Pro 原文，不降低其门槛。

开发前需求定义最终产品目标；Pro 交付定义已做成果及证据，不能用其局部实现缩小需求。例如本机确定性任务协议仍须继续实现真实模型规划，Q[x] 核验器仍须继续扩展专业数学能力。用户 2026-10-12 再次提供的 Downloads/02、03 已逐字节核对，与包内 inputs/02、03 完全一致。文档中的执行措辞按当前用户请求范围使用；阅读/理解需求本身不启动部署、外发或全部功能实施。

- 实现与接续：`HXai_Pro_Delivery/12_SOL_CONTINUATION_PROMPT.md`、`11_HANDOFF_TO_GPT6_1_SOL.md`；根目录新版续作指令结合 `inputs/12_GPT6_1_SOL_CONTINUATION_PROMPT.md` 使用。
- 每批功能、修复、重构：`HXai_Pro_Delivery/10_REVIEW_SYSTEM/NEW_FEATURE_REVIEW_STANDARD.md` 和 `PROMPT_NEW_FEATURE_REVIEW.md`。
- 全面审查、发布候选、任务/凭据/数据库架构变更、跨档案数据问题：`HXai_Pro_Delivery/10_REVIEW_SYSTEM/PROJECT_FULL_REVIEW_STANDARD.md` 和 `PROMPT_FULL_PROJECT_AUDIT.md`。
- 证据与源码定位：同目录 `REVIEW_EVIDENCE_AND_SEVERITY.md`、`REVIEW_SCOPE_AND_SOURCE_MAP.md`、`README.md`。
- 产品范围：`HXai_Pro_Delivery/inputs/01_SCOPE_DECISIONS.md`、`02_FINAL_PRODUCT_REQUIREMENTS.md`、`03_P0_SMART_CHAT_SPEC.md`、`04_P1_FILES_P2_MATH_SPEC.md`、`13_REQUIREMENT_ACCEPTANCE_SCENARIOS.md`、`14_SOURCE_AND_VERSION_RULES.md`。

首次接手还须阅读交付包 `README.md`、`DELIVERY_MANIFEST.md`、`01_SOURCE_AUDIT.md`、`02_ARCHITECTURE_AND_PLAN.md`、`08_TEST_AND_REVIEW_RESULTS.md`、`09_SECURITY_AND_PRIVACY.md`，以及本仓库 `docs/pro-development-baseline-20261012.md`。开始具体任务后按类型补读 03—07 和相关代码。`inputs/05`、`inputs/06` 是 Pro 编写任务书，不能作为正式审查规范的替代品。

## 来源与工作区保护

1. 每轮重新记录 branch、完整 HEAD、工作树状态、diff；需远端状态时重新查询默认分支、main 和相关 PR/head。不得将交付时的 SHA 或 PR 状态当作永久最新。
2. 本地基线/远端 main、Pro 安全 PR、ZIP 候选代码、历史脏树审计是不同来源。交付时基线 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`；安全修复 `d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59` 仅六文件；其余候选用 `FILES_SHA256.json` 定位。
3. 编码使用隔离 feature 分支或工作树，默认 `codex/` 前缀。禁止 reset/clean/自动 stash、覆盖未提交文件、直接推 main、自动合并或部署。已有授权继续适用，不为普通可逆工作反复询问。
4. 原交付包和历史失败证据保留。复验在副本中运行，另存本轮日志；`run_verification.py` 会覆写 evidence，不能直接在原包执行。`source_base/` 和 `historical_negative_control/` 禁止装入产品。
5. 集成先检查补丁、源码 blob、精确锚点和完整 diff。锚点不匹配先三方核对，禁止跳过检查或强写。Windows CRLF 与 Git blob 的 LF 差异和日志大小写碰撞已有记录，不能据此忽略哈希。
6. 新源码、schema、Pigeon、ToolPolicy、授权和状态机变化后更新适用版本与源码映射；保留原始规范并形成有出处的版本增量，不通过降门槛消除 FAIL。

## 已冻结的产品约束

- P0 智能 Chat > P1 文件系统 > P2 专业数学；身份、授权、数据保全和真实性贯穿全部。
- 所有 Chat 共用真实 Agent 底座；闲聊可以零工具。教学模式是表达策略，不增加普通/Agent 开关。
- 用户手动锁定 provider/model/reasoning；接受任务时固定配置快照，排队、恢复、重试不能后台换模型或推理强度。OCR/title/image 等专用角色单列配置、授权和用量。
- 不设每任务或日周月 Token/费用硬预算；保留循环、重试、文件、CPU/存储和上下文安全限制以及异常消耗提醒。用量分 reported/estimated/unknown，未知不能记为 0。
- 同一用户工作域跨会话/学习档案只有一个独立任务执行位。每会话队列不是全局调度；保持递增 fence，旧 executor 不得发布；未知副作用不能因超时释放或盲重试。
- 保留现有 `ChatService`、`ConversationSession`、`GenerationLoop`、工具与材料版本库，在外层接入持久 TaskStore/Scheduler，不能让两套未协调执行器同时运行。
- 授权、manual pause、device confirmation、input/device blockers 独立持久化。更新验证密钥只恢复原授权范围，不解除暂停、主动撤权、缺输入或前台设备确认。
- 每次 dispatch 验证 owner/task/capability/scope/grantEpoch/inputHash；工具风险与 verifier 来自可信注册表。模型、网页、文档、Skill/MCP 描述不能授予权限。
- 副作用先 journal/IN_FLIGHT；结果独立读回校验后记 receipt；任务完成还须满足验收谓词。模型声称完成、工具返回成功、UI 动画均不构成完成证据。
- 云托管、真实账号/付费模型、私有材料新增外传、生产与危险不可逆动作须在用户明确授权范围内执行。跨 App 默认关闭，等待设备后的屏幕接管须前台确认，持续可停止；截图不默认进入长期记忆或备份。
- 百度网盘、邮箱、日历专用连接器暂缓；保留已有 Memory/Skills/MCP/备份/Compose 能力和授权边界，不能为宣传启用尚无安全契约的工具。

## 文件、数学与界面门禁

- 文件修改保留原件、生成不可变新版本，绑定 owner/sourceHash/expectedText/locator/parent；独立回读真实输出。
- PPT 用 presentation.xml/r:id 逻辑页序；隐藏页、可见页、PDF 页分清。XML 验证与解析使用同一严格解码流；UTF16 DTD、实体编码 XInclude 与正常 UTF16 对照必测。ZIP 路径/重复条目/解压限额/宏/签名/外链另验。
- Excel calcPr/清缓存不等于重算；Office 专业保真、LibreOffice/MinerU 实际渲染必须各自验证，文本抽取不得冒充版式或物理页码。
- 精确核验器当前仅覆盖 Q[x] 恒等、形式导数与表达式等式链。UNSUPPORTED/DISPROVED 不自动算目标成功；数值采样不作证明；首错定位先验证前缀，教材引用绑定真实文件版本/页内依据，符号不清先确认。
- UI 状态取真实 DTO/receipt；当前模型选择不冒充任务模型锁，STOP_REQUESTED 不冒充已停止。Pigeon 修改生成两端；旧 profile/contextEpoch/branch 的异步回执丢弃。无真实命令回调的按钮禁用并说明。
- 原图册与 Pro PNG/HTML 为设计参考，不是 App 运行证据。保留可用页面与 Theme，实际验证小屏、大字、深浅色、长文件名、TalkBack 和档案切换。

## 每批执行流程与交付

执行职责：本助手负责开发、接线、编译、必要的定向验证和故障修复。2026-10-12 曾禁止任何子代理；活动模型为 grok 4.7 时，该禁令由文首 2026-11-03 决定覆盖，数量按 omp 需要开启，不另设仓库配额。其他模型仍禁止子代理。子代理不是外部独立审查，不得以自审或子代理复审代替独立审查；未做独立审查必须如实记录为未评估。保留 Pro 的产品约束、安全不变量和验收要求。

1. 编码前写可证伪的 CHANGE_CONTRACT：FeatureID、用户故事、base/head、真实入口→持久层→UI、schema、owner/profile/epoch、权限、模型快照、副作用、成功/失败/恢复、兼容/回滚。
2. 阅读完整 diff 及调用者/消费者；按变更类型选适用门禁，不适用项说明理由。先读测试与构建脚本，识别 --fix、安装、联网、生产与签名操作。
3. 已确定缺陷先保留旧版失败反例，再最小修复、正常/边界/故障与相关旧回归；保存 cwd、版本、退出码、日志、源码/输入/产物哈希。未知副作用、撤权竞态、丢 ACK、跨 owner、迁移和真实进程退出按适用范围验证。
4. 按新增功能审查提示词进行对抗复审，至少尝试“假成功/错恢复”和“正确输入被拒/旧数据损坏”两类反证。自己复审明确为同一执行者，不虚构独立审查。
5. 新功能审查输出 `HXai_CHANGE_REVIEW/<feature_id>/`：CHANGE_CONTRACT、TRACE_AND_DIFF、TEST_RESULTS.json、原始 logs、ADVERSARIAL_REVIEW、实际 patch/diff、VERDICT_AND_ROLLBACK、HANDOFF。
6. 全审输出 `HXai_FULL_REVIEW/`：SNAPSHOT、SOURCE_TRACE_MATRIX、FINDINGS、TEST_RUNS.json/原始 logs、FIXES.patch 或 PR、REVIEW_VERDICT、SOL_NEXT_ACTIONS。
7. 实现批次按续作提示词交付 SOL_IMPLEMENTATION、SOL_TEST_EVIDENCE、SOL_CHANGE_REVIEW、SOL_ACCEPTANCE_MATRIX、SOL_BLOCKERS、HANDOFF_STATUS 和可审阅 diff/PR；每个遗留有文件/方法/依赖/下一条命令/验收/回滚。

实现状态 ABSENT/PRESENT/WIRED/DISABLED/PARTIAL、证据 E0—E6、测试 PASS/FAIL/SKIP/NOT_RUN/BLOCKED 三轴分开。裁决仅 BLOCK/CONDITIONAL/PASS_SCOPED/NOT_EVALUATED 并写适用范围。S0/S1 未解或必要验收 FAIL 阻断相关功能；局部测试不能授予完整 App、Release、Mate50 或生产 PASS。不能累加历史计数当完成率。缺环境记录 NOT_RUN 和精确接续命令，继续独立可做工作。
