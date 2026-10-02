# 项目上下文与决策依据

日期：2026-10-02。用途：保存本项目规划所需的已确认背景、历史纠正和证据入口。本文不保存密钥，也不把其他项目的授权迁入本项目。

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

先读 [详细方案](implementation-plan.md)、[验收矩阵](acceptance-matrix.md) 和本文件，再读 README。方案中的建议参数与路线条件不等于已实测能力；后续阶段必须记录真实结果，更新最早未通过项。
