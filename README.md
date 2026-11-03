2026-10-11 全面审查列出的六项问题已按顺序修复：完整恢复的档案列表/当前档案、图片任务/版本/个人模板备份、文档服务持久存储额度、聊天置顶/归档持久化与筛选、渲染器升级后的幂等回执恢复、Nginx 全文件回滚。同时迁移停用接口的备份测试，删除不可达的明文云备份代码和文案。283 项 Flutter、398 项 app JVM、63 项服务端和 19 项模拟器专项通过；实际 Flutter 界面及本地 HTTP/受控 Nginx CLI 冒烟已验证，未调用付费模型。说明见 [完整备份](docs/s5-execution.md)、[聊天操作](docs/project-memory-20261011.md) 和 [文档服务部署顺序与边界](server/document-service/README.md)。服务端修改未上线，须先更新网关再使用新转换客户端；Mate 50 和 Release 包未验收，不作全项目验收结论。

本轮静态检查：Flutter 0 error / 0 warning / 176 info；Android Lint 0 error / 57 warning（未声明警告清零）。最终 ARM64 与 x86_64 APK 的 38 项 Flutter 资源均与新 AAR 一致；[ARM64 Debug APK](client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk) 为本地构建产物，尚未真机验收。

2026-10-11 项目分组与手动记忆已实现：按特殊需求创建项目、整理聊天，支持项目内新聊天、移动、重命名和删除分组保留聊天；记忆支持手动添加偏好/背景并沿用编辑、删除、检索和备份保护。282 项 Flutter、395 项 app JVM、2 项模拟器专项通过，双 ABI Flutter 资源核验一致。见 [使用说明](docs/project-memory-20261011.md) 和 [验证记录与 ARM64 Debug APK](qa/project-memory-20261011/report.md)。Mate 50 真机未验。

2026-10-10 智能 Chat 四阶段升级已按顺序完成并通过自审修复：消息级流式与稳定 Markdown、真实任务状态和安全恢复、成果版本迭代/宽屏预览、自动上下文预算与摘要。218 项 Flutter、736 项 JVM 和 19 项 Android/真实模型验证通过；1 项 Windows 符号链接权限用例跳过，Lint 0 错误/56 既有警告。模拟器已安装验收版并打开。见 [自审报告与 ARM64 APK](qa/intelligent-chat-upgrade-20261010/report.md) 和 [实现/使用说明](docs/intelligent-chat-upgrade-implementation-20261010.md)。Mate 50 性能及 Release 包未验收。

2026-10-10 智能 Chat 连续阅读优化：流式内容按布局帧跟随，图片与键盘变化补定位；上滑阅读历史保持位置，卡片内滚动不影响外层，返回最新按钮随输入框高度布局，会话与档案切换重置状态。183 项 Flutter 测试通过，静态分析 0 error/0 warning（81 info），Android Debug 构建与双 ABI 资产核验通过。见 [报告与 ARM64 APK](qa/chat-reading-20261010/report.md)。本轮未执行真机验收。

2026-10-10 智能 Chat 数学可视化已按顺序实现并完成自审：内置自动数学 Skill、图片/文字题配图、函数与圆锥曲线、参数滑块、状态恢复及真实 PNG 成果。364 项 app JVM、173 项 Flutter 与模拟器绘图专项通过；两道真实图片题分别通过方程核对，椭圆最终复验含一次网络中断后的安全继续。Lint 0 错误/56 警告，Mate 50 真机待验。见 [自审报告与 ARM64 APK](qa/math-visual-20261010/report.md) 和 [使用与实现说明](docs/math-visualization.md)。

2026-10-09 全面审查后的六项问题已修复：档案列表及草稿隔离、旧发送回执、后台上传恢复权限、记忆开关持久化与教学模式切换。本轮 62 项 Flutter、341 app + 202 AI SDK JVM、13 项 Android 合并回归通过，Lint 0 错误/46 警告；修复了备份测试字典污染并保留初次失败证据。见 [修复报告与新 APK](qa/fixes-20261009/report.md) 和 [修复前审查](qa/review-20261009/report.md)。本轮未重新验收真机或真实外部服务。

2026-10-09 核心需求 V1.0 已按 R0–R5 顺序完成开发、逐项自审和修复。MathStudyAgent 是以 ChatGPT 手机端为参考的**个人通用 AI 助手**，开发方向为 **Chat-first，Agent-extend**。通用聊天、动态交互 UI、文件自动调度与实际成果交付、首批文件编辑、Skills 与备份入口已接入真实业务。最终 341 app + 202 AI SDK JVM、48 Flutter、16 模拟器验收通过，含已配置真实模型与 Flutter 系统交付。见 [完成记录与 APK](qa/core-v1/completion.md)、[逐批执行记录](docs/core-v1-execution.md)、[核心需求 V1.0](docs/core-requirements-v1.0.md)。Mate 50、真实 MinerU 联合流程、复杂格式及完整社区脚本仍有明确未验收范围；当前为 Debug，P2 MCP 暂缓。下方数学专属定位和阶段记录保留为历史背景，与其冲突时以 V1.0 为准。

2026-10-08 最新本地补测又发现并修复审批重试队列暂停未落库、技能链接删除边界，以及材料夹具/图片成功判断的测试回归，见 [报告](qa/coverage-completion-20261008-192645/report.md) 和 [逐项矩阵](qa/coverage-completion-20261008-192645/verification-matrix.md)。最终 app JVM 328项、Android核心21项、交付2次（10格式组哈希一致）、服务端28项、采集器6项分别通过；Lint0错误/42警告。真实模型历史根因、缺配置和未验证子场景继续单列，不作全项目验收结论。

此前独立复审三项修复见 [报告](qa/independent-review-fixes-20261008-182645/report.md)：技能资源和路径上限、按方法/UUID拒绝陈旧证据、诊断测试隔离。该批 app JVM327项、Android14项、采集器6项及原64 MB探针分别通过，保留为历史证据。

上一修复轮记录见 [报告](qa/review-fixes-20261008/report.md)：Skills制作/编辑关闭，安装和显式使用保留；审批错误掩盖、证据覆盖和分享测试等待已修正。真实教学及材料路径该轮通过，历史失败根因与外部条件继续单列。

# MathStudyAgent

2026-10-08 最新清单复审：Android **97通过／2失败／2跳过**，两项真实失败为现有助手引导和材料首轮生成，根因待定位；发现 Skills 未开放入口缺口与真实测试证据文件覆盖问题。JVM **647通过**（含 Windows shell4项）、Linux workspace **20通过**、服务端 **26通过**，正常交付 **10/10**；Lint **0错误／42警告**。A01–A06仍有必需子场景未验，图片探针本轮NOT_RUN，不作全面验收关闭。见 [本轮报告](qa/checklist-re-review-20261008/report.md)、[发现清单](qa/checklist-re-review-20261008/findings.md) 和 [覆盖缺口](qa/checklist-re-review-20261008/scenario-gaps.md)。以下旧批次保留为历史证据。

2026-10-08 最新边界修复：三次审查筛选的六项问题已修复，app JVM **322 通过**、文档服务 **26 通过**、选定 Android 回归 **62 通过／2 跳过**；最终 APK 联合专项 **26 通过**。Lint **0 errors、42 warnings**。范围、真实模型和版本边界见 [修复报告](qa/boundary-fixes-20261008/report.md)，后续条件项见 [复审清单](qa/review-synthesis-20261008/re-review-checklist.md)。此前审批 HTTP 400 与 Windows workspace 失败已有 [专项修复复验](qa/approval-workspace-fix-20261008/README.md)；[警告审查](qa/warnings-review-20261008/report.md)及其 [35 项矩阵](qa/warnings-review-20261008/feature-test-matrix.md)保留历史证据。下述批次数字不覆盖最新修复范围，真机、图片模型与增强文档真实解析条件仍待验。

2026-10-08 审查后修复：已修复成果预览跨档案残留、虚构符号和范文、二进制预览、材料状态筛选及交付反馈/目标校验；Lint 从 45 errors 降为 0。317 项 app 单元测试、78 项模拟器测试与真实四模式/审批复验通过，2 项跳过与剩余警告单独说明，见 [修复与复验报告](qa/fixes-20261008/report.md)。修复前失败记录保留，真机与正式交付边界仍适用。

2026-10-07 手机诊断与反馈：设置及崩溃安全模式已加入诊断入口，支持查看设备/版本/最近崩溃、填写复现说明、保存或分享脱敏 ZIP；自动记录不包含聊天、密钥或材料正文，不自动上传。306 项 app JVM、6 项诊断专项与 21 项相关模拟器回归通过，实际系统保存重读及跨 UID 分享哈希校验通过；操作见 [使用说明](docs/diagnostics-feedback.md)，证据见 [验收报告](qa/diagnostics/report.md)。当前仍为 Debug，Mate 50 验收待完成。

2026-10-07 加密备份与长期可靠性：已实现完整 `.msab` 密码备份、校验预览、启动前恢复/回滚、可移植记忆保护及旧删除规则合并；补齐每日请求预算、后台维护、任务压缩和文件持久化。开发验证与已知迁移限制见 [验收记录](qa/s5/report.md) 和 [使用说明](docs/s5-execution.md)。Mate 50 与连续真实使用仍待交付验收。

2026-10-07 自建文档服务首批：香港源站已部署单任务 CPU Basic，腾讯云提供 HTTPS 入口；Android 已接入独立档案配置、指定页任务、校验下载和原页核对。真实五页合成扫描 PDF 约36秒通过，完整实施与剩余验收见[文档服务实施记录](docs/document-service-execution.md)。Office 增强与复杂生成后续推进，Mate 50 和正式签名仍待验证。

2026-10-07 文件能力增量：已接入档案私有材料库、真实文件工具及成果保存/分享入口；追加 Word 图片与常见公式、Excel 名称与数值类型、长内容分页及 PDF 跨页表格。逐格式交付与模型视觉专项的结果见 [追加验收](qa/material/completion-20261007.md)，基础测试历史见 [原报告](qa/material/report.md)，支持合同见 [执行说明](docs/material-execution.md)。复杂格式、图片生成服务及 Mate50 真机仍有明确边界，Skills 后置，不代表完整 v1 已验收。

面向一至两名数学师范学习者的手机学习 Agent，与 MathStudy 备考平台配合使用。

- 立项日期：2026-10-02
- 状态：聊天、学习记忆、教学闭环、材料与五格式成果、增强文档服务首批、加密备份及诊断已实现。当前工作区功能与逐项验证见 [最新矩阵](qa/warnings-review-20261008/feature-test-matrix.md) 与 [本轮复审](qa/warnings-review-20261008/report.md)；[首次完整审查](qa/full-review-20261008/report.md) 保留历史失败证据。Skills 后置，Mate 50、签名发布及部分外部服务仍待验收，不代表完整 v1 已交付。
- 目标设备：华为 Mate 50（CET-AL00），HarmonyOS 4.2.0；2026-10-02 已由用户截图确认，按 Android APK 路线规划

## 已确认的范围

1. 优先做好 Agent 聊天系统：模型 API 直连、流式问答、数学公式、连续追问，以及逐步提示和完整讲解。
2. 自动提取长期学习记忆：学习背景、讨论进度、重复困难、错因及复习事项；按使用者隔离，本地持久化，可查看、编辑、删除、备份和恢复。
3. 支持上传文件与图片，让 Agent 调用模型及必要工具读取、分析和处理 Word、Excel、PPT、PDF 等文件；同样支持按用户需求制作、生成文件与图片，并提供可打开、保存和分享的真实输出。范围不限于讲义阅读或学习笔记。各格式与图片生成能力须逐项实现和验证，不假设所有模型原生支持全部文件。
4. Skills 制作与优化暂时后置，待实际学习中积累需求后逐步完善；当前不以制作三个内置 Skills 或建立导入体系作为文件能力的前置任务。
5. 允许材料发送给模型 API 或解析服务；计划使用 ChatGPT 中转，协议、图片/文件输入和工具调用能力待验证。密钥不得写入仓库或记忆。
6. 视频暂列后续：优先评估字幕转录、关键画面及带时间戳的片段问答，不承诺首版支持整段视频理解。

## 与 MathStudy 的分工

MathStudy 负责计划、日历、正式学习记录、错题、复习队列和备考进度。本项目负责日常教学聊天、材料处理、自动记忆和学习成果整理，避免重复建设备考平台。

首期采用复制、分享及文件导入导出配合使用；后续再评估 MathStudy API。正式学习数据以 MathStudy 为准；保存错题、修改计划和同步记录需要使用者明确操作。聊天记忆不能自行提高正式掌握度。

## 技术方向（待真机运行与接口验证）

- 固定客户端基线：RikkaHub 2.5.6，复用 Android 聊天、多模态输入、公式显示、Skills 和工具调用；保留并核对上游许可证。
- 教学规则来源：本机 MathStudy，路径 `D:/Office/Study/code/MathStudy`。迁移时解除其知识点编码、输出 Schema 和服务依赖，不直接照搬。
- 记忆设计参考：Lyra、Hermes Agent、oh-my-pi；选择性适配机制，不整套拼接运行时。
- 记忆设计：原始记录、增量抽取、去重与冲突处理、相关记忆检索、定期整理；保留来源和时间。明确区分独立完成、提示后完成、仅阅读等证据。
- 删除记忆需考虑历史再抽取，避免已删除内容自动复活。
- Skills 可从文件或 GitHub 安装、查看并显式启用；制作和编辑功能暂时关闭，不自动生成技能草稿。记忆记录个体学习情况。
- 页面采用“聊天 / 材料 / 记忆 / 成果”四主入口；原创折页书签伙伴、状态动效、成功反馈及减少动画策略已实现。全部动画状态与真机表现仍需验收，参考页面图由用户在网页端手动生成。
- 这是手机运行 Agent 并联网调用模型的方案，不等于手机本地运行大模型。

2026-10-07 用户修正文件能力范围：Word、Excel、PPT、PDF 和图片的输入处理，以及按需求生成文件与图片均纳入后续实施；2026-10-06 的 PDF + Markdown 优先、Word 后置为历史安排，不再作为输出范围上限。具体格式支持与生成路径逐项验证。当前没有可连接的 Mate 50，允许构建、模拟器及接口验证后推进开发，目标真机兼容性保留为交付门禁，具体开发条件见详细方案。

## 当前待确认

- RikkaHub 基线 APK 在 Mate 50 / HarmonyOS 4.2.0 上的安装、启动、文件访问、公式与联网表现；系统版本已确认，完整构建号在真机验收时补记。
- 中转接口的协议、可用模型及流式、工具调用、图片、文件输入支持情况。
- Word、Excel、PPT、PDF 与图片的输入保真、模型处理方式、生成输出及手机打开/分享验证；可编辑公式等细节按实际格式能力确认。
- 加密备份、删除约束合并及隔离目标上下文恢复已通过开发验证；两台真实设备迁移仍待验收。成果预览的档案切换缺陷及复验状态见当前审查与修复记录。

## 当前边界

S1 开发已完成：独立应用标识、教学默认助手、档案隔离、四种教学模式、四主入口与静态伙伴、聊天中断/队列/工具恢复、历史分支保全及加密凭据恢复均已实现。462 项 JVM 测试与模拟器、真实 Responses 验证见 [S1 完成报告](qa/s1/s1-completion-20261007.md)。S2 已实现有来源的自动记忆、增量任务、纠正删除、删除抑制、跨会话检索和管理页面，498 项 JVM、模拟器与真实模型证据见 [S2 完成报告](qa/s2/s2-completion-20261007.md)。本轮 S3 按用户五项要求实现教学闭环，287 项 app JVM、7 项模拟器回归与四轮真实模型教学/抽取检查见 [S3 完成报告](qa/s3/s3-completion-20261007.md)。材料私有库、真实文件工具、五格式成果及系统交付已实现；Skills 优化后置。当前缺陷、格式边界及未验收能力见完整审查。Web 服务和全局文件浏览因档案边界未满足而关闭。目标 Mate 50、跨设备迁移和签名发布仍待交付验证，未部署或发布。本地单仓库的 `client/` 保存固定上游源码，未创建 GitHub Fork/远端。暂不建设复杂工程工具、多 Agent、终端、手机自动化或地区考试配置。

## 详细方案入口

- [自建文档服务方案](docs/document-service-plan.md)：本地与服务端分工、MinerU 隔离运行、身份与任务合同及实施批次；首批部署结果与边界见[实施记录](docs/document-service-execution.md)。
- [详细实现方案](docs/implementation-plan.md)：技术路线、模块与数据设计、自动记忆、材料处理、文档导出和 S0–S6 实施批次。
- [动效与页面设计专项](docs/motion-design-reference.md)：原创伙伴、四主入口、状态映射、动效参数、Compose 接入与阶段安排。
- [网页 ImageGen 页面提示词](docs/imagegen-page-prompts.md)：统一母提示词、10 个页面/状态稿、原创角色与动效关键帧、键盘/深色/大字及修图提示。
- [S2 执行说明](docs/s2-execution.md)：记忆存储、抽取、来源、删除和检索实现及边界。
- [S2 完成报告](qa/s2/s2-completion-20261007.md)：真实 30 组样例、逐事实指标、页面与重启证据。
- [S1 执行记录](docs/s1-execution.md)：独立客户端首批改动、构建与模拟器证据，以及未完成项。
- [S0 执行记录](docs/s0-execution.md)：已执行检查、构建结果、实际证据与待补条件。
- [数据合同 v1](docs/data-contract-v1.md)：所属身份、来源版本、候选格式与原子提交要求。
- [S0 验证操作](qa/s0/README.md)：本地、真机与真实中转的运行方式。
- [验收矩阵](docs/acceptance-matrix.md)：每阶段必需检查、条件项与完成标准。
- [项目上下文与决策依据](docs/project-context.md)：已读取记忆和“查看 Lyra 项目”对话中的最终决定，以及固定上游源码证据。

## 上游参考

- RikkaHub：https://github.com/rikkahub/rikkahub
- Lyra：https://github.com/kittors/Lyra
- Hermes Agent：https://github.com/NousResearch/hermes-agent
- oh-my-pi：https://github.com/can1357/oh-my-pi
