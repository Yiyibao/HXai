# A/B 后续开发计划（2026-10-19）

本轮用户要求重新规划 A/B 并行开发。本计划接替 `../parallel-development-20261014/PLAN.md` 的后续顺序；旧文件及历史交付保留。A 由 Sol 负责平台和集成，B 延续 OMP 的领域开发职责。任务书可分别交给两个独立开发会话；本轮未派发会话、启动子代理或实施产品功能。

## 1. 规划合同与当前基线

- FeatureID：`AB-REPLAN-20261019`，范围仅新增规划、任务书和本轮快照；不改变产品 schema、权限、模型、执行器或用户数据。
- 起点分支：`codex/b-source-chat-integration-20261019`；完整 HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。
- 快照记录 tracked 状态项 177、untracked 状态项 146，无 staged diff；目录状态项不是文件数。完整状态、tracked diff、2742 项文件清单/字节哈希见 [evidence/SNAPSHOT.json](evidence/SNAPSHOT.json) 和 [evidence/SOURCE_BEFORE.json](evidence/SOURCE_BEFORE.json)。快照不是已经复制完成的并行工作树。
- 本轮不查询远端，不判断 main、PR 或部署现状。新会话开始时重新记录 branch/HEAD/status/diff；涉及远端再查询。
- 本地协议已增量至 v1.22；最近交接记录 Room32 / sidecar9 / Pigeon164 / memory payload1 或 2。实施前再核源码，不把历史版本固定为永远最新。
- 本轮可证伪条件：所有既存产品文件保持原字节；计划必须有唯一文件负责人、并行边界、依赖、验收、回滚和下一入口。产品构建/运行测试本轮 `NOT_RUN`，独立及全项目审查 `NOT_EVALUATED`。
- 回滚本次规划仅移除本次新增目录；不触碰既存源码、原 Pro 包、旧证据、数据库和材料。

## 2. 继承成果，避免重复开发

以下为历史交接加本轮静态接线核对，不能冒充本轮重跑通过。

| 范围 | 当前可继承事实 | 下一步缺口 |
|---|---|---|
| 统一 Agent、锁模型、调度、授权、恢复、用量 | 已有任务底座、任务中心/安全控制/恢复回执、账本分组/导出/提醒；整体仍 PARTIAL | 真实复杂任务质量、安全扩展、可信费用、云端权威执行和发布 |
| Memory/历史来源 | v1.19 已保全孤立 v0 墓碑和 payload2；title/suggestion 等已补来源保护 | 其他辅助输入、旧派生物和全备份语料仍需逐入口验证 |
| 数学主 Chat | v1.20 注册 `math_verify`，可信 EXACT_MATH，限定 Q[x]/Q(x) 合同 1–15；代表故事有局部 E4 证据 | 逐题型完整故事、专业数学质量、OCR/手写与专用呈现 |
| 章节主 Chat | v1.21 接索引/章节读取/字面搜索；最终验收前后重查源 | 不能宣称全书完整、印刷页映射或定理证明 |
| 来源与跨材料搜索 | v1.22 接 `read_material_source_location`、`search_material_text`、`search_material_library`；绑定原输入与完整回执 | 资料库生命周期/授权目录/覆盖率、面向用户的跨文件成果故事 |
| XLSX 真重算 | B1b 已有领域代码、本机 UNO/HTTP/Android 局部证据 | 当前 Factory/Registry 尚未注册重算工具；七共享文件候选待 A 三方集成，Linux/Docker另验 |
| DOCX/OMML、PPTX 形状、PDF 组页 | 已有 B1c/B1d/B1e 增量和 `edit_material_file` 路径 | 各格式主 Chat 真实任务、实际渲染/保真和恢复故事尚待完成 |
| Release/目标设备 | 当前 Gradle Flutter 依赖仍只见 debug AAR | Release AAR/构建/启动、Mate50、200%大字/TalkBack需分别取证 |

主要出处：[v1.22交接](../../HXai_CHANGE_REVIEW/A-B-SOURCE-INTEGRATION-20261019/HANDOFF.md)、[v1.21交接](../../HXai_CHANGE_REVIEW/A-B-CHAPTER-INTEGRATION-20261019/HANDOFF.md)、[v1.20交接](../../HXai_CHANGE_REVIEW/A-B-MATH-INTEGRATION-20261019/HANDOFF.md)、[B1b遗留](../../HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/SOL_BLOCKERS.md)、[开发基线](../pro-development-baseline-20261012.md)。B3o 等早期文档所写“主 Chat 未接”已被后续 A 交接更新；不能照旧重复套累计补丁。

## 3. 两条线的职责

| 开发线 | 交付目标 | 唯一责任 |
|---|---|---|
| **A：统一 Agent、共享接线与 App 交付（Sol）** | 用户在 Chat 提交、追加、暂停/恢复任务，看到真实成果、证据、用量和失败；扩展/云设备复用统一任务权威 | 调度/授权/模型/验收、共享桥接、主 Chat、跨线集成、最终构建与发布门禁 |
| **B：专业文件、教材与数学（OMP/独立B开发会话）** | 真实可编辑文件、可验证重算/渲染、可追溯教材证据与限定数学能力 | 领域算法、材料/版本/索引、文档引擎、领域 UI、独立读回及格式/数学夹具 |

两线都负责开发、编译、定向验证和故障修复；均不开子代理。不另设审查线，不把同一执行者复验称为独立审查。

## 4. 按轮次推进

轮次表示依赖顺序，不承诺固定天数。每个小批次完成验收即交接；B 不必等 A 完成整条线才交付。

| 轮次 | A 同期工作 | B 同期工作 | 合流门禁 |
|---|---|---|---|
| **G0：完整基线** | 固定产品源码、规则、Pro 输入及必要 untracked 的安全清单；建立共享接口基线 | 核对自己的领域最终哈希、旧候选和构建环境 | 两边从相同完整产品快照开始；仅有旧 HEAD 不准开工 |
| **第1轮：优先落地 XLSX** | **A01** 三方集成 B1b 七共享文件，补 Chat→审批→异步续接→真实 XLSX/witness→全目标验收/UI | **B01** 冻结现有重算合同，补引擎可用性、输出保真/异常夹具；优先实际受限 Linux/Docker，缺环境继续本机可做项 | **G1** 正常工作簿成功；错 witness/改源/撤权/丢ACK不假成功，旧PDF正常 |
| **第2轮：数据保全与 Office 产品故事** | **A02** 辅助入口/旧派生物/备份删除恢复；**A03** 分格式接 B 成果并补同任务追加、版本回滚和成果预览 | **B02** DOCX可编辑OMML和复杂结构；**B03** PPT逻辑页/隐藏页/母版保全、PDF原页组装与扫描覆盖分开验收 | **G2** 已授权两份讲义→真实可编辑Word+来源+预览；追加要求产生新版本、不重复副作用。各格式逐批验收 |
| **第3轮：安全扩展与专业能力** | **A04** 最小 owner-scoped MCP/Skill 只读切片及可信授权，脚本沙箱另立合同；**A05** 可信费用来源和真实任务质量入口 | **B04** 资料库目录/标签/覆盖率/删除hook；**B05** 现有数学合同代表题型+来源/OCR歧义/首错前缀，再按收益扩新合同 | **G3** 不可信描述无法授予权限；正确输入仍可用；数学未知/反证不作成功，费用unknown不当0 |
| **第4轮：云设备与可交付包** | **A06** 先本机远端权威/fence/vault协议，再授权环境；**A07** Release AAR、正式构建/启动、目标设备与可访问性 | **B06** 配合worker取消/进程终止、跨软件保真、低API/目标设备领域兼容，交最终支持矩阵 | **G4** 独立等待/停止证明/未知副作用保全；Debug/Release/真机分别验收。全部必要门禁前不得发布 |

Release 依赖检查和真实模型验收入口可提前准备；不能用准备状态授予发布/真实模型质量 PASS。真实模型使用已授权配置和合成材料；没有收费调用授权则保持 NOT_RUN 并继续本机工作。新的已证实 S0/S1 优先于一般功能，按文件归属修复。

## 5. 文件与接口归属

K = `client/app/src/main/java/me/rerere/rikkahub/`，F = `client/flutter_ui/`。

| 路径/状态真源 | 直接修改方 | 协作方式 |
|---|---|---|
| `K/feature/agent/*`，`K/service/ChatService.kt`、`ConversationSession.kt`、`K/data/ai/GenerationLoop.kt` | A | B交具体接口需要/验证器候选；不增加第二执行器 |
| `K/data/ai/tools/ChatToolFactory.kt`，registry/dispatch/planner/verifier | A | B交工具工厂/schema/capability/risk/独立读回候选；A核可信合同 |
| `client/ai/*`、模型角色/费用/账本、Memory/Backup/凭据/公共网络策略 | A | B交领域删除hook/恢复和输入夹具，不改全局授权 |
| `K/flutter/StudyHostFacade.kt`、Pigeon源和双端、`F/lib/providers/*`、主Chat/任务/用量页 | A | B交独立领域 facade/widget 与最小接线patch |
| `K/feature/material/*`、`K/feature/math/*`、`server/document-service/*`、领域测试/专属脚本 | B | A在固定版本上接线；需要领域修复时登记给B，合流窗口串行处理 |
| F文件/成果/文档任务/数学专用页面 | B | 共享路由/主Chat/`enhanced_cards.dart`/交互基础组件由A归并 |
| Gradle/pubspec/DI/路由、共享验证脚本、AAR/最终APK、总协议/总进度 | A | B提供精确最小依赖和patch，不整文件覆盖 |
| Pro原包、旧失败/测试证据 | 双方只读 | 本轮另建 A-* / B-* 目录，新 run-id |

XLSX 七共享文件是旧候选，当前源码已到v1.22。不能整套重放其旧 Registry（会回退数学和来源门禁）；按 before/candidate/current 三方逐段归并。领域代码已存在，不重复拷贝安装。

## 6. 真正可以并行的条件

1. 两个独立开发会话，不在同一目录同时切分支。建议新分支 `codex/a-platform-next-20261019`、`codex/b-domain-next-20261019`；名称只是计划，本轮未创建。
2. 开工前从当前完整工作树形成可重建基线：tracked diff + 必要 untracked 内容 + 路径/字节SHA + 所需构建输入。先检查秘密/私有材料；不 `git add .`，不自动stash/reset/clean。可用独立副本或经审阅的本地快照提交/工作树，不能裸checkout旧HEAD冒充最新。
3. 两份目录各有 build/cache/证据目录；`.local`依赖、Flutter AAR/Maven产物、Gradle缓存可发生写冲突，必须核配置或串行。B领域编译不复用A正在改写的产物。
4. A5554 / B5584 仅延续历史资源约定，使用前确认实际设备/AVD归属；无归属则串行。安装、force-stop、清QA数据、Pigeon生成和共享构建输出均由持有人独占。
5. `verify_agent_android.py` 会归档后清空合成 nativeqa 包；只在独占合成模拟器运行，普通App/用户设备不作为隔离替代。每次拉回已安装APK核SHA，绑定源码和数据库。
6. B每批冻结领域十六进制SHA、输入/输出schema及最小共享patch；A集成期间B可推进不改该接口的夹具/文档/下一隔离批次。接口变化另记增量，不边测边换源。
7. 并行开发不改变产品“同用户工作域一个独立任务执行位”的约束。

## 7. 合流与完成判据

合流顺序：B冻结领域合同/产物 → A核源blob和三方diff → A接registry/授权/journal/独立verifier/全目标验收 → 生成适用桥接并显示真实DTO → 两线固定同一源码与APK → 定向故事/故障/相关旧回归 → 归档交接。各工具步骤通过后，仍须验收用户完整目标。

每批遵循根/client AGENTS及 Pro正式新功能门禁；数据库/凭据/任务架构、跨档案和发布触发全项目适用门禁。必须尝试“假成功/错恢复”和“正确输入被拒/旧数据损坏”两类反证。独立审查维持 NOT_EVALUATED，不虚构审查人员。

每个 `HXai_CHANGE_REVIEW/A-*` 或 `B-*` 必含合同、入口/消费者/完整diff、原始logs、TEST_RESULTS、输入/源码/APK/产物SHA、对抗复验、实际patch、裁决/回滚、HANDOFF及六类SOL交接。新源码/schema/Pigeon/授权/状态变化更新版本与来源映射。裁决仅 BLOCK/CONDITIONAL/PASS_SCOPED/NOT_EVALUATED；实现/证据/测试三轴分开。

回滚停对应新入口或逆实际增量，保材料原件/新版本、任务库、receipt、UNKNOWN和墓碑/payload2；不得用旧schema消费者降级、清空未知或恢复被撤授权。缺环境写精确依赖和下一命令，继续独立可做工作。云托管/付费模型/新增私有外传/跨App/生产依明确授权；模型手动锁定、无费用硬预算、后置专用连接器保持原决定。

直接开工任务书：[A_TASK.md](A_TASK.md)、[B_TASK.md](B_TASK.md)。本轮规划检查见 [evidence/PLAN_CHECK.json](evidence/PLAN_CHECK.json)。
