# A/B后续开发拆分（2026-10-24）

用户要求将最新剩余工作拆成A/B线。本计划及两份任务书接续 `docs/remaining-development-20261024.md`，更新旧20261019计划的当前进度；旧任务书、原Pro包与历史证据保留。**A负责平台、主Chat与最终集成，B负责文件/OCR/数学领域；两线均负责开发和必要验证，不另设审查线，不启动子代理。** 本轮仅规划，没有创建会话、工作树、执行产品代码或调用账号。

## 规划合同与起点

- FeatureID：`AB-SPLIT-20261024`，仅文档和本轮证据，无产品schema、授权、模型或状态机变更。
- 起点分支：`codex/p2-calculus-ocr-20261022`；完整HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。tracked diff仍为195文件，实际完整状态/diff见本目录 `evidence/`。
- 当前代码大量在未提交/untracked工作树，不能裸checkout旧HEAD后声称已继承当前成果。开工重新冻结完整产品源码与必要输入，检查秘密和私有材料，不将整个证据库盲目纳入产品。
- 当前已知Room33、TaskStore10；合同17旧文档仍有TaskStore9，需要A记录继承增量、B更新相应领域说明，不能据此降级库。
- 规划完成条件：每项剩余工作有唯一主责、交接接口、依赖及验收；已有成果不重复开发；两线不同时改共享真源或共用可写构建/设备资源。
- 本轮产品行为测试NOT_RUN，独立及完整项目审查NOT_EVALUATED。规划校验通过不授予产品PASS。
- 回滚规划仅撤本次文档增量，不动原件、任务库、墓碑、receipt、UNKNOWN或旧证据。

## 两线职责与任务清单

新任务ID使用A24/B24，避免与历史A01–A07/B01–B06的已完成批次混淆。下表顺序为建议依赖顺序，不是固定工期。

| A：统一Agent、云设备与最终App | B：文件、OCR与专业数学 |
|---|---|
| **A24-01** 平台来源核对与Release候选集成：合同17中7份A所属差异、schema地图、旧A07候选三方集成、当前包构建/冷启 | **B24-01** 合同17领域收尾：1份数学测试差异、数值/候选确认/真实表面证据、批次汇总/补丁/回滚 |
| **A24-02** A06云设备协议：先fake移交，再账户鉴权/vault/remote executor/设备工具适配 | **B24-02** 真实OCR与扫描文件故事：逐页逐符号真值、真实MinerU/视觉识别、歧义确认到Word与来源 |
| **A24-03** 真实模型Agent质量：复杂目标、多步工具、同任务追加、错误恢复、完整目标验收 | **B24-03** 数学质量：高中/高数/分析/实变题库、盲评、正确异法与首错，从实际错例补能力 |
| **A24-04** MCP/Skills/OAuth受支持范围扩展与可信费用覆盖维护；按实际目标补协议和验证器 | **B24-04** 跨Office与领域兼容：MSOffice/WPS、公式/图表/版式、最终API26/目标设备领域用例 |
| **A24-05** 最终合流、普通Debug/Release、Mate50系统级与TalkBack、适用全项目门禁/签名发布准备 | **B24-05** 最终领域交付与支持矩阵，给A提供同版本输入/产物/独立读回/领域回归及遗留 |

直接任务书：[A_TASK.md](A_TASK.md)、[B_TASK.md](B_TASK.md)。A/B是职责划分；后续若由同一助手执行可串行交替，不要求创建两个会话，也不构成派发授权。

## 立即开始的第一批与合流顺序

| 阶段 | A工作 | B工作 | 门禁/依赖 |
|---|---|---|---|
| G0 完整基线 | 冻结当前平台/共享接口与完整产品快照 | 确认领域源码/输入/现有证据hash | 两线从同一完整快照开始；分支名可用 `codex/a-platform-20261024`、`codex/b-domain-20261024`，本轮未创建 |
| G1 收尾与Release | 核合同17的A所属差异；三方提取A07候选并做适用构建 | 收尾合同17领域证据与数学测试差异，交冻结领域清单 | B冻结→A归并共享接线→同一源码与APK复验交叉影响。Release可以先准备，领域未冻结的包不能当最终候选 |
| G2 核心缺口 | A06 fake协议和本机故障注入；准备真实Provider评估 | 准备OCR真值、权威数学题库及Office夹具 | 无真实账号时可推进合同/夹具/fake，不把等待授权当成通过或停止所有工作 |
| G3 获授权的真实质量 | 运行固定配置的真实Agent任务；按需补扩展/费用 | 真实OCR、数学盲评、MSOffice/WPS；按错例修领域 | A持有Provider实际调用/账本，B持有领域真值/质量判据。分开报告工程链路与解题/识别质量 |
| G4 最终资格 | 汇总同版本包，系统级API26/Mate50/后台/停止/授权/无障碍与发布门禁 | 在同版本包核文件/数学/OCR/来源与设备领域故事 | B域验收不能授完整App通过；A编译通过也不能代替B领域质量。正式签名/发布/生产按授权范围另执行 |

P0优先于一般P2算法扩展；B可独立准备领域真值/Office验收，不以增加算法数量占用A06关键集成窗口。已证实S0/S1按归属优先修复。

## 唯一文件负责人

K=`client/app/src/main/java/me/rerere/rikkahub/`，F=`client/flutter_ui/`。

| 路径或状态真源 | 主责 | 另一线交付方式 |
|---|---|---|
| `K/feature/agent/*`、ChatService、ConversationSession、GenerationLoop、ToolFactory、Registry/Dispatch/Planner/Verifier | A | B交最小接口/schema/输入输出/独立验证候选，不直接另造任务权威 |
| Provider/`client/ai/*`、凭据/授权/用量费用、Memory/Backup、公共网络安全 | A | B交真实错例、来源/删除hook或角色输入契约 |
| `server/agent-runtime/*`及后续云Agent适配、设备执行器与跨App边界 | A | 与document-service分开；B只提供领域远程调用/停止/结果契约 |
| StudyHostFacade、Pigeon源与双端生成、共享providers、主Chat/任务/用量/扩展页 | A | B交领域facade/widget和最小接线候选 |
| `K/feature/material/*`、`K/feature/math/*`、`server/document-service/*`及对应领域测试/专属验证脚本 | B | A按冻结合同集成；发现领域错误给B反例，在合流窗口串行处理 |
| 文件库/文档任务/数学专用页面与专用组件 | B | 主Chat、共享交互组件、路由和依赖变更交A归并 |
| Gradle、pubspec、DI、路由、共享验证脚本、AAR/Maven与最终APK | A | B说明最小依赖/测试需求，不整份覆盖或共写build输出 |
| 总需求矩阵、schema总地图、最终集成文档与构建说明 | A | B交领域增量；领域协议由B维护 |
| Pro原包、旧批次与原始失败日志 | 只读继承 | 各自建立新run目录；收尾补充文件不得覆盖旧失败/原日志 |

合同17已知8份差异的具体分配：A负责 `ExtensionFrontend.kt`、`ExtensionMcpRuntime.kt`、`ExtensionMcpTransport.kt`、`ExtensionOAuth.kt`，以及 `AgentTaskRuntimeTest.kt`、`ExtensionRuntimeTest.kt`、`TaskResumeReceiptRuntimeTest.kt`；B负责 `CertifiedCalculusRuntimeTest.kt`。基准见 `qa/remaining-development-20261024/SOURCE_COMPARISON.json`。这些差异有后续A04/A05冻结出处，不预先定性为缺陷。

## 交接接口与资源独占

每次B→A交接包括：FeatureID/需求ID、完整基线与前后字节SHA、领域文件清单、工具schema/capability/risk、owner/source版本/locator、成功/失败/UNSUPPORTED语义、取消/UNKNOWN/恢复、独立读回方法、正常/边界/故障夹具、最小共享patch、真实测试与回滚。A核原blob和精确锚点再接线，拒绝整套旧累计补丁。

真实并行须独立目录/分支、build目录、可写缓存、Flutter AAR仓库、服务端口/daemon和设备。设备serial/AVD每次核归属，不能沿用历史5554/5588/5592默认有权使用。Pigeon生成、共享APK、安装/force-stop/清QA数据由持有人独占。产品仍保持同一用户工作域一个独立任务执行位。

## 每批共同交付及最终约束

遵守根/client AGENTS与Pro权威原文。编码前CHANGE_CONTRACT；保旧版FAIL、最小修复、正常/边界/故障及相关回归；至少验证“假成功/错恢复”和“正确输入误拒/旧数据损坏”两类反证。每批输出六类SOL、合同/完整diff、TEST_RESULTS/原始logs、源码/输入/产物hash、实际patch、裁决/回滚和可执行接续。

实现状态、证据E0–E6、测试结果分开；局部PASS_SCOPED不能授完整App/真机/Release/生产通过。触发架构/数据库/授权/跨档案或发布门禁时由相应执行者完成适用验证，不设审查子代理，不把同一执行者复验称作独立审查。独立审查未执行则NOT_EVALUATED。

云托管、真实收费模型、真实账号、私有材料新增外传、跨App和生产依明确授权；模型/推理手动锁定，不加费用硬预算。百度网盘/邮箱/日历专用连接器后置。回滚保原件/版本/墓碑/新库/费用账本/receipt/UNKNOWN，不reset/clean/stash、自动合并或部署。
