# 两条并行开发线：Sol / OMP

用户于 2026-10-14 明确要求两大项并行开发，本助手负责一项，OMP 负责一项。本文件是任务分配和协作约定，不是已开始执行或已通过验收的声明。两条线均为开发职责，不启动审查子代理；独立审查保持 NOT_EVALUATED。

## 共同起点

- 仓库：`D:/Office/Study/code/MathStudyAgent`。
- 分配时分支：`codex/multi-artifact-evidence-20261013`。
- HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。
- 实际基线包含完整未提交工作树、产品 untracked 源码、根/client AGENTS、Pro 包和最新增量；仅 checkout HEAD 会丢失已经完成的 TaskStore/Planner 等成果。
- 当前状态见 `docs/project-progress-20261014.md`；最新原生协议 v1.8、Room32、sidecar7；历史报告不替代接手时的新快照。
- 本轮只新增分工文件；未启动 OMP、未创建子代理、未修改产品源码、未使用真实模型、未部署。

## 两大项

| 开发线 | 负责人 | 用户最终得到什么 | 首批范围 |
|---|---|---|---|
| A：统一 Agent 与平台能力 | 本助手 / Sol | 可规划、协作、恢复、验收和观察的统一任务系统；真实用量、扩展授权、云/手机协作及最终 App 集成 | P0 计划/补充要求/验收路径收尾与可复验质量入口；任务/账本只读查询和真实 UI；先本机验证，不默认运行付费 endpoint |
| B：专业文件与数学能力 | OMP | 可保原件、可追来源、实际可打开/核验的 Office/PDF 成果；教材证据与严格数学工具 | 真实材料局部编辑→新版本→实际引擎处理→独立回读/预览；优先 XLSX 真重算和复杂 DOCX/PPT 保真切片；数学精确核验接入作为随后批次 |

完整任务书分别为 `SOL_TASK.md` 和 `OMP_TASK.md`。每线按可验收的小批次推进，不将整个大项包装为一次未经验证的修改。

## 文件归属

路径以下以 `client/app/src/main/java/me/rerere/rikkahub/` 为 Kotlin 根。

| 路径 / 状态真源 | 唯一直接修改负责人 | 另一方使用方式 |
|---|---|---|
| `feature/agent/*`（任务、授权、规划、验证、账本） | Sol | OMP 复用现有 API；新增 verifier 以接口需求和最小补丁交付，不能另造 Scheduler |
| `service/ChatService.kt`、`ConversationSession.kt`、`data/ai/GenerationLoop.kt` | Sol | OMP 通过工具适配器接入 |
| `data/ai/tools/ChatToolFactory.kt`、可信 registry/dispatch | Sol | OMP 提供工具构造函数、schema、风险/capability/verifier 建议和注册补丁；描述不授予权限 |
| `client/ai/*`、模型角色/Provider/请求账本、统计页 | Sol | OMP 不增加隐蔽主体推理或专用模型路由 |
| `flutter/StudyHostFacade.kt`、Pigeon 源及双端生成文件、`study_providers.dart` | Sol | OMP 独立实现材料/数学 host facade，提交需要的桥接签名；Sol 接线并生成两端 |
| Flutter `chat_page.dart`、`agent_task_panel.dart`、全局任务/用量页 | Sol | OMP 使用现有组件和 DTO，不更改任务状态真源 |
| `feature/material/*`、材料检索/版本、文档转换适配器 | OMP | Sol 保持既有 owner/hash/版本 API；涉及兼容变更先登记接口差异 |
| `server/document-service/*`、Office/MinerU worker 与受限引擎 | OMP | Sol 的云 Agent 不复用文档 job 冒充通用任务，不改此服务调度库 |
| 新建 `feature/math/*`、教材/数学 verifier、相关工具与独立数学服务模块 | OMP | Sol 统一接 registry/dispatch/验收接口 |
| Flutter文件/成果/文档任务/数学页面及专业图形组件 | OMP | 共享 `interactive_surface.dart`、全局路由/DI/依赖变更只提供补丁需求，由 Sol 集成 |
| Memory/Backup/凭据/跨域删除与恢复、搜索公共网络策略 | Sol | OMP 提供材料索引/缓存删除 hook 和材料/数学恢复夹具，不能清全局数据 |
| Gradle/pubspec/DI/导航共享配置、原生验证脚本、Release/AAR/最终集成 | Sol | OMP 提供精确依赖和构建请求；专属 Office/数学验证脚本可独立新增 |
| 原 Pro 包、历史证据和旧交付目录 | 双方只读保留 | 新证据使用各批独立目录 |
| 共享开发基线、总进度、协议总表、根 AGENTS | Sol 归并 | OMP 使用独立增量文档，不覆盖共享汇总 |

新文档和证据命名：A 使用 `A-*` / `SOL-*`，B 使用 `B-*` / `OMP-*`；回归脚本使用新的 run-id。不能在对方运行时覆写其 evidence 或 build 输出。

## 并行工作区和验证约定

1. 编码前各自按根 AGENTS 记录 branch/完整 HEAD/status/diff 并写 CHANGE_CONTRACT。使用独立 feature branch/工作树，建议 `codex/agent-platform-20261014`、`codex/document-math-20261014`；同一目录不能同时切分支。
2. 新工作树必须来自当前完整产品快照，先核对 tracked 差异、所有必要 untracked 源码及规则/Pro 输入。快照清单、原始字节 SHA 和复制前后差异固定后再编码；禁止从裸 HEAD 开始假称继承全部成果。
3. 本次分工不执行自动提交、stash、reset、clean 或覆盖。确需生成供两线共同使用的 Git 快照时，由 Sol 先建立可审阅且不含秘密/缓存的基线方案；不能使用 `git add .` 批量带入私有材料。
4. 如暂时共用原目录，只允许按上述独占文件归属开发；切分支、生成 Pigeon、改共享配置、构建、安装/清 nativeqa/杀进程均串行协调。原目录状态与原件保持，不将已运行命令当可安全并行的命令。
5. 优先各工作树单独 build/cache。模拟器 instrumentation/install/force-stop 和共享 Flutter AAR/Gradle 输出同时只由一方操作；测试前保存被测输入与 APK hash，不能在对方安装后继续声称测的是自己的版本。
6. OMP 交付其开发增量和共享文件最小补丁；Sol 对当前 blob/锚点、完整 diff、API 兼容与真实入口核对后集成。冲突使用基线/OMP/Sol 三方比较，不以复制整文件覆盖解决。
7. 两线集成后才执行跨线用例：Chat 输入→任务计划→材料工具→真实文件/版本→来源/内容/结构证据→语义验收→实际成果 UI，和限定范围数学工具→scope/状态→任务完成门禁。

## 集成契约

优先复用当前工具/材料/TaskDto 和 receipt schema，不预先改全局 schema。

- 输入身份由可信宿主给出 owner/profile/task/lease；模型输入不能选择其他 owner。
- 材料引用用现有 `materialId/version/sourceId/parent/hash/locator`；新增字段必须注明旧值缺失时的保守兼容。
- 文件输出独立回读，标内容/结构/渲染/重算/覆盖各自状态。没有实际引擎运行就不能返回已渲染/已重算。
- 数学工具输出明确对象、域、验证范围、VERIFIED/DISPROVED/UNSUPPORTED 与证据。局部 Q[x] VERIFIED 不代表一般证明完成。
- 可信 registry 决定工具 capability/risk/verifier；结果包不含 `authorized=true`、`taskSucceeded=true` 等自授状态。
- 数据源发生变化时失效旧证据；文件仅产出不可变新版本，不回写旧版本正文。
- 模型/角色/请求由已有 AgentExecution 和账本管理；没有第二条独立模型执行器或自动换模型策略。

## 统一交付门禁

两线每批均遵守 Pro NEW_FEATURE_REVIEW_STANDARD 和 PROMPT_NEW_FEATURE_REVIEW；schema/权限/跨档案/发布架构变化追加全项目适用门禁。执行者自己的对抗复验明确标同一执行者，不冒称独立审查。

每批交付合同、入口/消费者与完整 diff、源码/输入/产物 hash、旧版 FAIL 与修复结果、正常/边界/故障日志、至少两类反证、回滚、六类 SOL 材料及精确下一步。实现状态、E0–E6、PASS/FAIL/NOT_RUN 分开；未解决相关 S0/S1 阻断该功能，缺环境继续完成可做实现。

真实收费模型、私有资料新增外传、云托管/生产/跨 App 按明确授权范围执行。百度网盘/邮箱/日历专用连接器后置；用户锁模型/推理、无费用硬预算等冻结决定保持。

本分配未派发到一个已确认的 OMP 会话；`OMP_TASK.md` 是可直接交给 OMP 的执行任务书。不得把名为“检查 OMP 修复完成情况”的旧 Codex 检查聊天误认成 OMP 开发者。
