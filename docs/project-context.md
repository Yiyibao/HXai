# 项目上下文与决策依据

日期：2026-10-02。用途：保存本项目规划所需的已确认背景、历史纠正和证据入口。本文不保存密钥，也不把其他项目的授权迁入本项目。

2026-10-06 补记：用户要求制定 MathStudyAgent 动效参考专项、调整已有方案，并把页面提示词写入方案，供其手动在网页端 ImageGen 生成。新增内容是设计与规划授权，不代表页面已生成、业务实现已开始或 S0 已完成。

## 1. 本轮读取范围

- 本项目 [README](../README.md)。
- Codex 对话 **查看 Lyra 项目**：`01a0f699-9ea8-7932-b397-d3c3ff64a7b3`，分页读取至最早一轮，共 19 轮；核对用户消息、最终回答及有关材料的读取记录。
- Codex 对话 **Compile portable user memory**：`01a0f144-acd8-7840-8bfd-14cabfb1be98`，读取与学习背景和协作偏好有关的记忆摘要。
- 本机学习地图与复习队列：`D:/Office/Study/LearningNotes/learning-map.md`、`review-queue.md`；目前仍为空白初始化状态。
- Codex 本地记忆库：只读检索 MathStudy、Lyra、数学师范及来源对话 ID，未找到匹配记录。不能据此声称所有渠道都没有记忆。
- MathStudy 的数学师范方案入口、教学规则和教学提示词；邻接项目只读，没有修改或调用其模型服务。
- RikkaHub 固定版本的关键源码、Lyra 本机源码，以及 Hermes、oh-my-pi 和 Android 官方资料。
- 2026-10-02 后续补记：用户提供“关于手机”截图，确认 HUAWEI Mate 50、型号代码 CET-AL00、HarmonyOS 4.2.0；未展示补丁/完整构建号。截图未作为安装或运行通过证据。

对话记录中的助手建议是历史分析；只有用户表达、当前文件和已核对源码可以作为对应事实的依据。早期范围与后来表达冲突时，采用后来的用户决定。

## 2. 已确认的产品决定

| 决定 | 依据与含义 |
|---|---|
| 手机独立运行 | 用户选择 Android 独立版；日常使用不依赖个人电脑在线，手机联网调用模型 |
| 设备为华为 Mate 50 | 用户截图已确认 CET-AL00、HarmonyOS 4.2.0；当前按 Android APK 路线规划，具体客户端安装和运行待真机验证 |
| 供一至两人使用 | 给其他数学师范学习者使用，不做公共平台；不能把开发者的个人记忆直接设为学习者档案 |
| 先做好教学聊天 | 用户明确优先 Agent 聊天，暂时不做地区考试配置、试讲说课和复杂备考功能 |
| 自动提取记忆进入首版 | 用户指出它是最重要的需求之一；不能仅做会话保存或模型主动调用记忆工具 |
| 长期记忆保存在 Agent | 本地持久化、按使用者隔离；材料和相关上下文允许发送给模型或解析服务 |
| 处理材料并生成文件 | 图片、PDF、Word 是核心；模型内容生成与应用实际文件生成必须一起设计 |
| 使用 ChatGPT 中转 | 用户配置接口；协议、可用模型和多模态、工具能力待实测 |
| 与 MathStudy 配合 | MathStudy 管计划、日历、正式记录、错题、复习和进度；Agent 管聊天、记忆、材料和成果整理 |
| 保留 Skills 能力 | 从 GitHub 导入教学 Skills，逐步迁移 MathStudy 教学方法；脚本需求须匹配真实工具 |
| S0 开始执行 | 2026-10-02 用户授权“开始执行s0”；据此获取固定上游、本地依赖与验证工具并建立合同；未授权发布或创建远端，业务实现仍受 S0 门禁约束 |
| 动效与页面专项 | 2026-10-06 用户要求参考 Coucou 调整方案并附页面提示词；采用原创伙伴的设计提案，用户手动出图；未要求本轮生成图片或编写动效代码 |

## 3. 历史纠正与不能迁入的假设

1. 早期曾建议以 Lyra + React Native 做通用开发 Agent；后来用户明确数学学习范围，底座候选收敛为 RikkaHub。两套技术路线不同时实施。
2. 早期曾把自动抽取后置；用户纠正后，自动抽取必须在首个可用学习 Agent 中交付。
3. 视频一度出现在较完整功能表中；最终已后置，不以“上传视频”代替真实理解能力。
4. 已有个人记忆及 MathStudy 方案涉及 2027 常州初中数学教招；本项目面向他人，用户随后明确暂不考虑这些配置。此背景只作材料来源，不设为所有使用者默认事实。
5. MathStudy 已有教学提示词不是独立 Skills 成品，其中的知识编码、Schema 和服务端约束需要解除或适配。
6. MathStudy 的上线、模型配置、账号和阶段审查授权仅对原项目有效；本项目不能继承其密钥、接口能力结论或生产操作权限。
7. 学习记录为空，不代表学习者没有知识，只代表目前没有可读取的掌握证据。

## 4. 上游核对结果

设备补充依据：华为官方文档适用版本列明 HarmonyOS 4.2，并说明通过文件管理安装外部来源应用及安装包的流程。由此支持当前 APK 规划路线，不构成 RikkaHub 真机验收通过。[外部来源安装](https://consumer.huawei.com/cn/support/content/zh-cn00766399/)、[安装包安装流程](https://consumer.huawei.com/cn/support/content/zh-cn01089223/)

### 4.1 RikkaHub 基线

2026-10-02 查询发布页及 GitHub API，最新非预发布版本为 **2.5.6**，发布于 2026-10-01。标签解析为提交 **447bb7e89710d31f1204d7a2973baa19fdbd5b28**。这是后续验证的候选固定基线，本轮没有创建 Fork 或下载 APK。[发布版本](https://github.com/rikkahub/rikkahub/releases/tag/2.5.6)、[固定提交](https://github.com/rikkahub/rikkahub/commit/447bb7e89710d31f1204d7a2973baa19fdbd5b28)

| 核对项 | 结论 | 源码或文档 |
|---|---|---|
| 客户端与持久化 | 原生 Android；Kotlin、Compose、Room 等技术栈已有基础 | [README](https://github.com/rikkahub/rikkahub/blob/2.5.6/README.md) |
| 生成和工具循环 | 已有 ChatService、GenerationHandler、输入变换和工具审批链路；具体改造点仍须在固定版本验证 | [生成链路](https://github.com/rikkahub/rikkahub/blob/2.5.6/docs/references/chat-generation-pipeline.md) |
| 记忆 | memory_tool 提供增删改；不能把这一工具视为已经完成本项目的自动学习记忆验收 | [MemoryTools.kt](https://github.com/rikkahub/rikkahub/blob/2.5.6/app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt) |
| Skills | 目录注入名称与描述；use_skill 加载正文或技能内引用文件 | [SkillsTools.kt](https://github.com/rikkahub/rikkahub/blob/2.5.6/app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt) |
| 材料输入 | 文档变换器已有 PDF、DOCX 等解析入口；PDF 文本附带页面编号 | [文档变换器](https://github.com/rikkahub/rikkahub/blob/2.5.6/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt)、[PDF 解析](https://github.com/rikkahub/rikkahub/blob/2.5.6/document/src/main/java/me/rerere/document/PdfParser.kt) |
| 构建 | 固定版本 minSdk 26；使用 SDK 37.2、arm64-v8a/x86_64 及 Google Services/Firebase 插件；没有核验本机工具链 | [构建文件](https://github.com/rikkahub/rikkahub/blob/2.5.6/app/build.gradle.kts) |

README 标明 AGPL-3.0。实施时保留上游许可证、版权和来源，打包前核对包含组件及分发所需材料，不把少量用户使用当作忽略许可证的理由。本文不作许可证义务的完整法律解释。

### 4.2 记忆参考

- **Lyra**：本机源码提交 `658727ecee9ceb646869c784b2f0d20fad4bb088`。明确记录与自动整理分开；抽取会重写自动摘要，保留明确记录；自动技能先进入待确认区。采用设计原则，不移植 Node/Electron 运行时。[记忆抽取](https://github.com/kittors/Lyra/blob/658727ecee9ceb646869c784b2f0d20fad4bb088/packages/core/src/runtime/memory-extract.ts)、[调度](https://github.com/kittors/Lyra/blob/658727ecee9ceb646869c784b2f0d20fad4bb088/packages/core/src/runtime/memory-pass.ts)
- **Hermes**：参考短画像与历史会话检索的分工；原始历史应能按需找回，不能只有摘要。[记忆文档](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory.md)
- **oh-my-pi**：参考会话增量抽取、跨会话整理及独立维护明确经验的分层。当前文档有多种后端，手机首版不引入整套框架。[记忆文档](https://github.com/can1357/oh-my-pi/blob/main/docs/memory.md)

Hermes、oh-my-pi 的链接是可变分支；后续如复制实现或提示词，先锁定具体提交并登记来源。本轮主要借鉴机制。

### 4.3 MathStudy 教学来源

- `D:/Office/Study/code/MathStudy/packages/tracks/math-edu/1.1.0/prompts/math-edu-tutor.md`
- `D:/Office/Study/code/MathStudy/packages/tracks/math-edu/1.1.0/prompts/math-edu-extract.md`
- `D:/Office/Study/code/MathStudy/packages/tracks/math-edu/1.1.0/prompts/proof-check.md`
- `D:/Office/Study/code/MathStudy/packages/tutor-prompts/v1.0.0/ocr-normalize.md`

可保留：一次处理一个卡点、定义与反例、第一处实质缺口、区分错误事实与根因假设、识别不确定性。不能照搬：固定 MTE 编码、TutorTurnOutput V2、默认禁止大学方法、服务端答案保存和正式掌握度更新。

注意：1.1.0 目录内部分提示词仍标记 1.0.0。迁移时按实际内容核对，记录文件哈希和来源，不能只按目录名宣称提示词已更新。

## 5. 接续时优先读取

先读 [详细方案](implementation-plan.md)、[验收矩阵](acceptance-matrix.md) 和本文件，再读 README。进行页面或动效工作时补读 [专项](motion-design-reference.md) 与 [ImageGen 提示词](imagegen-page-prompts.md)。方案中的建议参数与路线条件不等于已实测能力；后续阶段必须记录真实结果，更新最早未通过项。

## 6. 动效参考与页面方案记忆（2026-10-06）

本节合并原动效补记与用户本次提供的对话摘要。归档时已读取指定项目 `D:/Office/Study/code/MathStudyAgent` 的 README、本文件、详细实现方案、动效专项、ImageGen 提示词和验收矩阵，并补读 S0 执行记录核对阶段状态。下列内容保存设计决定与约束，不复制专项中的完整参数和提示词；本次未重新核验外部仓库。

### 6.1 参考机制与资产边界

用户希望参考 [Coucou](https://github.com/Louis-CFM/coucou) 的 UI 与动画设计，固定参考提交为 `30edad4c5c0b6c3d16a2092a35cddeb9018a0016`。重点是用角色行为表达真实任务状态、轻微弹性、小状态胶囊展开为内容卡片，以及材料接收和操作完成反馈。此前源码与许可核对入口保留在 [动效专项第 2 节](motion-design-reference.md#2-coucou-参考依据与使用方式)。

依据用户摘要及现有专项，源码采用 MIT，但 Mochi 角色外观、表情及角色动画，以及声音、图标、品牌和媒体另有保留权利；不能把源码许可理解为全部素材均可复用。MathStudyAgent 只借鉴通用机制，独立设计角色、动作、图标和声音。此前方案轮未移植 Coucou 代码或素材；若后续选用通用源码片段，仍须记录固定来源、修改范围并保留许可。

### 6.2 产品与视觉决定

产品路线不变：Mate 50 / HarmonyOS 4.2.0、Android APK、RikkaHub 固定基线（2.5.6 / `447bb7e89710d31f1204d7a2973baa19fdbd5b28`）、Kotlin / Compose。核心是教学聊天、自动学习记忆、材料处理和真实学习成果；正式计划、日历、错题和掌握度继续由 MathStudy 管理，不因视觉专项改变分工。

视觉方向为温暖纸面、玉绿强调色和清晰数学排版；原创折页书签伙伴的工作代号为“页芽”。名称、形象、色板、动效参数和专项工作量均属设计提案，尚未由生成图或真机效果定稿。四个主入口为“聊天、材料、记忆、成果”，设置和 Skills 为次级入口。

### 6.3 真实状态、阅读与运行约束

- 动效必须绑定真实运行状态。记忆来源校验、去重及提交事务成功后才能提示已保存；导出文件校验和文件/版本登记成功后才能提示导出完成。成果草稿不能冒充已导出的文件。
- 零候选不庆祝；部分材料解析显示实际范围，待核对符号保留问题与操作。失败、取消、中断分别准确呈现；分享取消保留本地文件，不声称接收方已收到。完成动画不代表数学正确、独立完成或正式掌握。
- 并发以当前聊天优先；事件核验档案、任务、来源/成果版本和唯一事件键。迟到事件不能覆盖终态，档案切换不能串人或泄露旧档案反馈；返回页面、转屏和进程恢复不能重复庆祝或再次触发保存、导出。
- 阅读、公式、输入和取消优先；动画可打断，不阻塞操作，流式正文和公式不反复入场。提供减少动画并尊重系统关闭动画设置；关闭后保留完整状态和操作。
- 后台、锁屏及不可见页面停止装饰循环，回前台读取真实状态。首版聚焦应用内部反馈；悬浮宠物、系统顶部岛、服饰和大量音效后置，v1 不新增音效资产。

### 6.4 用户手动出图与后续接续

用户明确要求将页面提示词写入方案，自己在网页端 ImageGen 手动生成。已准备 [统一母提示词及页面提示词](imagegen-page-prompts.md)：P01–P10 共 10 个页面/状态稿、G00 原创角色、FX-A 动效关键帧，以及键盘、深色、大字体和修图提示。建议顺序为 **G00 角色 → P02 聊天 → P03 材料 → P05 记忆 → P08 成果**，再补其余页面和状态。

静态图只用于视觉参考，不能证明实际动画、性能、功能、记忆提交或文件导出通过。选稿后记录页面 ID、版本、保留与修正元素；实施及验收继续读取专项和验收矩阵。未选定图稿不新增 S0 门禁，也不能据此跳过现有门禁。

### 6.5 方案轮交付与本次归档边界

用户摘要列出的此前方案轮文件已在指定项目中核对存在：

| 文件 | 已有内容与版本 |
|---|---|
| [motion-design-reference.md](motion-design-reference.md) | v1.0；参考依据、原创伙伴、状态映射、动效参数和实施约束 |
| [imagegen-page-prompts.md](imagegen-page-prompts.md) | v1.0；用户网页手动出图的母提示词、页面稿和补充提示 |
| [implementation-plan.md](implementation-plan.md) | 草案 v0.3；合入页面与动效专项 |
| [acceptance-matrix.md](acceptance-matrix.md) | v0.3；新增 UX01–UX12，当前均为 NOT_RUN |
| 本文件 | 已有产品决定与简短动效补记；本次在原位置合并扩充 |
| [README.md](../README.md) | 已有四主入口、动效专项和页面提示词入口 |

此前方案轮完成的是方案与文档调整，未生成图片、未实现动效、未完成真机验收。本次仅更新本文件作项目记忆归档，未启动业务开发、图片生成、模型调用或发布，也未改动其他方案或执行记录。后续阶段状态以项目最新执行记录为准；这份记忆不改变 S0 门禁、不宣布功能完成。

## 7. 本次核对的差异与历史口径（2026-10-06）

用户摘要与当前动效专项、提示词、v0.3 实现方案及验收矩阵基本一致，未发现产品路线、资产边界、生成顺序或完成范围的实质冲突。以下是已有文档中的历史状态差异，保留原决定并说明读取口径：

- 本文件第 4.1 节记录立项规划时“候选固定基线”“本轮没有创建 Fork 或下载 APK”“没有核验本机工具链”；README 的技术方向仍称“主 Fork 候选”。后续 [S0 执行记录](s0-execution.md) 已记录固定源码和 arm64 APK 获取、工具链安装、本地校验与前端构建通过。前者是历史规划快照，不能读成当前尚未获取；当前采用单仓库固定上游快照，未创建 GitHub Fork/远端，Android 完整构建与真机/中转门禁仍未通过。此处不覆盖早期记录，也不因归档修改阶段状态。
- 验收矩阵中的“当前 NOT_RUN”等概括不能覆盖 S0 执行记录的逐项状态：后者已有 BLOCKED、FAIL（修复后复验中）及局部本地 PASS。接续工作以最新逐项执行证据为准；局部本地通过不等于完整 S0 或真机通过。
- 动效专项建议先生成 P02/P03/P05/P08，未在该句列出 G00；ImageGen 提示词第 1 节明确先生成 G00，再按上述页面顺序生成。两者为简写与完整流程的关系，本次按用户摘要归档完整顺序，不视为路线冲突。

## 8. Android 接续决定（2026-10-06）

用户讨论 iPhone/iPad 路线及无 Mac 的条件后，最终明确“继续安卓开发吧”；未启动 iOS 迁移。首版导出选定 PDF + Markdown，Word 导出后置，DOCX 材料输入不变。当前无可连接 Mate 50，开发采用固定基线构建、模拟器基础运行和实际中转验证；目标 Mate 50 安装、无 GMS、锁屏及后台恢复等保留为交付门禁，不把模拟器结果记成目标真机通过。完整门禁与证据以 v0.4 方案、验收矩阵和最新 S0 执行记录为准。

用户提供中转配置为 Responses 协议并提出 gpt6.1 sol、medium；实际服务接受的模型 ID 及能力尚待核验。旧 gpt-5.5 配置不作为最终模型选择。只保留非敏感配置事实，不将密钥或认证头值写入记忆、源码或日志。同机/各自手机使用方式尚未回答。

2026-10-06 执行补记：固定基线完整 APK 构建、无 GMS AOSP 模拟器安装/冷启动/重开、真实 Responses 桌面文本/流式/工具及合成候选来源拒绝通过，按 v0.4 开发门禁进入 S1。首批独立客户端 org.mathstudy.agent（Debug 后缀 .debug）、0.1.0-dev，默认数学教学助手和上游更新入口关闭已构建并在模拟器核对。Mate 50 交付门禁、四主入口、LearnerProfile、Keystore 和自动学习记忆仍未完成，细节以 S0/S1 执行记录及证据文件为准。
