# HXai / MathStudyAgent 开发与审查规则

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

1. 编码前写可证伪的 CHANGE_CONTRACT：FeatureID、用户故事、base/head、真实入口→持久层→UI、schema、owner/profile/epoch、权限、模型快照、副作用、成功/失败/恢复、兼容/回滚。
2. 阅读完整 diff 及调用者/消费者；按变更类型选适用门禁，不适用项说明理由。先读测试与构建脚本，识别 --fix、安装、联网、生产与签名操作。
3. 已确定缺陷先保留旧版失败反例，再最小修复、正常/边界/故障与相关旧回归；保存 cwd、版本、退出码、日志、源码/输入/产物哈希。未知副作用、撤权竞态、丢 ACK、跨 owner、迁移和真实进程退出按适用范围验证。
4. 按新增功能审查提示词进行对抗复审，至少尝试“假成功/错恢复”和“正确输入被拒/旧数据损坏”两类反证。自己复审明确为同一执行者，不虚构独立审查。
5. 新功能审查输出 `HXai_CHANGE_REVIEW/<feature_id>/`：CHANGE_CONTRACT、TRACE_AND_DIFF、TEST_RESULTS.json、原始 logs、ADVERSARIAL_REVIEW、实际 patch/diff、VERDICT_AND_ROLLBACK、HANDOFF。
6. 全审输出 `HXai_FULL_REVIEW/`：SNAPSHOT、SOURCE_TRACE_MATRIX、FINDINGS、TEST_RUNS.json/原始 logs、FIXES.patch 或 PR、REVIEW_VERDICT、SOL_NEXT_ACTIONS。
7. 实现批次按续作提示词交付 SOL_IMPLEMENTATION、SOL_TEST_EVIDENCE、SOL_CHANGE_REVIEW、SOL_ACCEPTANCE_MATRIX、SOL_BLOCKERS、HANDOFF_STATUS 和可审阅 diff/PR；每个遗留有文件/方法/依赖/下一条命令/验收/回滚。

实现状态 ABSENT/PRESENT/WIRED/DISABLED/PARTIAL、证据 E0—E6、测试 PASS/FAIL/SKIP/NOT_RUN/BLOCKED 三轴分开。裁决仅 BLOCK/CONDITIONAL/PASS_SCOPED/NOT_EVALUATED 并写适用范围。S0/S1 未解或必要验收 FAIL 阻断相关功能；局部测试不能授予完整 App、Release、Mate50 或生产 PASS。不能累加历史计数当完成率。缺环境记录 NOT_RUN 和精确接续命令，继续独立可做工作。
