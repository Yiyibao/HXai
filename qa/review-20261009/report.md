# 当前工作区项目审查（2026-10-09）

本次发现 **6 项需要修复的问题**：3 项 P1、3 项 P2。四项 Flutter 行为由独立探针复现，两项由完整调用链确认。现有测试通过不能作为档案隔离及权限撤回场景已经验收的依据。没有修改业务代码、提交、发布或调用真实模型/线上文档服务。

审查对象为 `D:/Office/Study/code/MathStudyAgent` 当前工作区，包括未提交和新增代码；不是单个提交的差异审查。历史 QA 与源码候选副本只作背景，未当作当前运行结果。P1 表示优先修复的数据边界/权限问题；P2 表示功能或状态一致性问题。未发现足以确认的 P0。

## 发现与修复建议

### F1 · P1 · 旧档案的延迟刷新可以覆盖新档案列表

位置：[study_providers.dart:257](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/providers/study_providers.dart:257)，同类代码在 105、284、326 行。

`MaterialsNotifier.refresh()` 保存请求时的 profileId，等待桥接返回后直接写 `state`。用户在等待期间切换档案时，Notifier 会重新 build，但显式刷新仍可在稍后写入旧结果。材料、记忆、成果和会话的手动刷新都采用这一模式。页面直接渲染结果，未按当前档案再次过滤。

复现：A 列表初次加载 → 延迟 A 的第二次读取 → 切到 B 并加载 B 列表 → 返回 A 的响应。当前档案保持 B，但列表变成 `A-private.txt`。本次探针已确认；记忆和成果的同类路径为代码审查结论。原生写操作仍有归属检查，本次没有证明 B 可修改 A 的持久化文件。

修复：每次刷新捕获 profileId、contextEpoch 和刷新序号；await 后校验 `ref.mounted`、当前档案、epoch 和序号，再写入状态。对过期结果直接丢弃。测试要覆盖旧成功、旧错误和多次刷新乱序返回。

### F2 · P1 · 切换档案后仍保留上一档案未发送草稿

位置：[chat_page.dart:24](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/chat_page.dart:24)，附件状态在 31 行，发送读取在 96 行；设置页由根导航 push 覆盖聊天页，见 [router.dart:45](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/router.dart:45)。

聊天页的 TextEditingController 与待发送附件都属于 Widget State，没有绑定档案，也没有监听档案变更。打开设置切换档案后返回聊天，原 State 仍然存在。文字发送时重新读取当前档案，因此 A 的未发送文字可以被用户在 B 中误发。

复现：A 输入 `A private unsent draft` → 保留同一 ChatPage 并切换 provider 到 B → 输入框仍显示 A 草稿。widget 探针已确认文字残留。附件残留由同一 State 生命周期确认；其原生归属检查会拒绝跨档案附件，不能把附件残留描述为已证明跨档案发送成功。

修复：按档案保存草稿，或在身份切换时清理输入、附件和待重试请求；Widget key 应包含档案身份。保留草稿的产品行为需要明确显示所属档案。

### F3 · P1 · 后台提交恢复绕过用户后续“不要上传”限制

位置：[DocumentServiceRepository.kt:267](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceRepository.kt:267)，[DocumentSyncWorker.kt:13](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentSyncWorker.kt:13)。

首次提交先保存 SUBMITTING 意图，再发送文件。若请求未得到确认，`remoteId` 保持 null。后台 Worker 对每个已配置档案调用 `syncPending()`，后者直接 `resumeSubmission()` 再次 POST 原文件。配置一致性校验会检查服务地址和凭据，但没有聊天身份、当前分支或最新用户限制。

触发：创建未确认提交 → 用户随后在聊天中明确“不要上传，仅本地处理” → 网络恢复并触发后台任务。前台 `StudyHostFacade.syncChatDocuments()` 在恢复上传前调用 `DocumentDispatchPolicy.allowsRemote()`，后台路径没有同等检查，因此存在确定的权限检查缺口。本次没有向线上服务上传文件，结论来自调用链。

修复：为提交意图保存可验证的授权/会话来源，并在每次重发前重新确认权限和分支；撤回上传权限时持久化禁止重发状态。仍应允许只下载已批准且已完成的结果及必要的远端清理。增加 Worker 级别“撤回后不 POST”测试。

### F4 · P2 · 旧发送回执可以改写新上下文的当前会话

位置：[study_providers.dart:196](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/providers/study_providers.dart:196)。

`sendMessage()` 在 await 前捕获档案、epoch 和会话，返回后却未检查这些身份是否仍一致，直接设置 `activeConversationIdProvider` 并刷新。同样会影响发送期间打开其他会话或点击“新聊天”的情况。

复现：A 发消息并延迟确认 → 切到 B 且会话设为 null → 返回 A 成功回执。B 仍为活动档案，但当前会话 ID 变成 `A-conversation`。本次探针已确认；真实原生详情查询会拒绝这个错误的档案/会话组合，所以主要后果是聊天跳转、加载错误和状态污染，不是已证明读取 A 正文。

修复：成功回执只在发送时捕获的档案、epoch、会话仍匹配时更新活动 UI；对已经提交到旧上下文的消息保留持久化结果，不强制导航到该会话。

### F5 · P2 · Flutter 自动记忆开关返回成功但没有生效

位置：[StudyHostFacade.kt:699](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt:699)，[PreferencesStore.kt:510](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:510)。

桥接把修改后的 learnerProfiles 传给通用 `settingsStore.update()`。其持久化函数不写 LEARNER_PROFILES，随后又明确用原有 `settingsFlow.value.learnerProfiles` 覆盖新值。桥接仍返回 success=true。Flutter 重新 bootstrap 后读到旧开关；原来启用的自动记忆不能通过这个入口关闭。

此结论由设置写入和返回值全链路确认，未运行真机开关测试。现有相关 Android 测试直接使用专用 `setLearnerMemoryEnabled()`，没有覆盖出问题的桥接路径。

修复：调用现有 `settingsStore.setLearnerMemoryEnabled(profileId, enabled)`；返回成功前等待持久化状态可读。Flutter 应检查命令结果并显示失败。补测从 Flutter 桥接关闭、重启后保持关闭，以及关闭期间不再抽取。

### F6 · P2 · 档案切换后教学模式沿用旧档案状态

位置：[study_providers.dart:66](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/providers/study_providers.dart:66)。

`ActiveStudyModeNotifier` 只在 `_initialized` 为 false 时接受 bootstrap 的 studyMode；档案切换刷新 bootstrap 不会重置该标志。设置页切换后将当前会话设为 null，却不重置教学模式，下一条新聊天将携带上一档案的模式。

复现：A bootstrap 为 EXAM → B bootstrap 为 CHAT → provider 仍为 EXAM。本次探针已确认，可能使 B 的普通问答被写成考试模式。

修复：模式状态按 profileId/epoch 绑定；档案切换时采用新档案的默认值，新会话默认 CHAT，选择历史会话时采用该会话持久化模式。

## 本次实际验证

| 检查 | 本次结果 | 证据 |
|---|---|---|
| app JVM，强制重跑 | 341 通过，0 失败/跳过 | jvm-rerun.log、test-summary.json |
| AI SDK JVM，强制重跑 | 202 通过，0 失败/跳过 | 同上 |
| common / document / oauth | 1 / 1 / 2 通过 | aux-jvm.log、test-summary.json |
| search / speech | 16 / 43 通过 | 同上 |
| workspace | 19 通过，1 环境相关失败 | 下文及 aux-jvm.log |
| Flutter analyze | 无问题 | flutter-analyze.log |
| Flutter 原有测试 | 48 通过 | flutter-tests.log |
| 本次缺陷复现探针 | 4 个探针均确认预期缺陷行为 | profile-race-probes.log、profile_race_probe_test.dart |
| Python 文档服务 | 29 通过，1 弃用警告 | server-tests-verified.log |
| Android Lint Debug | 0 错误，46 警告 | gradle.log、lint-results-debug.txt |

JVM 共执行 626 项：625 通过、1 失败。workspace 的 `rootfsInstallerDownloadsAndExtractsTarGz` 在 Windows 创建符号链接时返回“客户端没有所需的特权”，堆栈落在 `RootfsInstaller.createSymlink`。这是本轮机器权限条件导致的失败，不能声称整个模块通过，也不能据此断言 Android rootfs 安装有同样故障。当前产品未向聊天开放 workspace 工具。

初始受限环境阻止了 Gradle/Dart 原生运行库，Python 在受限运行时中也未完成测试；使用本机运行时重跑后取得以上有效结果。未把这些启动问题计为产品失败。Gradle 首次报告 JVM UP-TO-DATE 后，另用 `--rerun` 强制重跑，表内结果来自这次执行。

探针使用合成桥接响应，断言的是“当前确实存在缺陷”，全部通过不表示业务已修复。探针位于 QA 目录，不加入原有业务测试集。

## 审查覆盖与剩余边界

| 领域 | 审查方式与结论边界 |
|---|---|
| Flutter 身份、聊天、列表、教学模式 | 检查 provider、设置导航、草稿和桥接；四项缺陷行为复现 |
| Kotlin 会话、队列、档案授权 | 检查桥接、ChatService 队列持久化与切换边界；检查了切换服务疑点并排除：通用 update 不会提前修改 activeProfileId |
| 材料与文件交付 | 检查归属、摘要验证、系统保存重读、格式编辑限制、模型工具与附件转换；没有重新做外部 Office 软件排版验收 |
| 文档服务 | 检查鉴权、身份隔离、幂等队列、取消/过期、输出校验、后台恢复及容器约束；Python 本地测试通过，未实际启动 Linux Docker/MinerU |
| 记忆与备份 | 检查抽取来源、启用状态、备份容器、恢复日志及路径边界；本次未重跑设备数据库迁移或两台设备恢复 |
| AI SDK、诊断与日志 | 检查 OpenAI 流式终止/错误处理改动及请求日志、诊断拦截路径；AI SDK 单元测试通过，未调用真实模型 |
| Skills 与依赖模块 | 检查目录边界、资源读取/导入和兼容说明；运行辅助模块单测，未执行完整社区脚本 |
| 构建与工程状态 | 离线构建任务和 Lint 完成；当前有大量既有未提交修改。未提交、重置或发布，也未把历史 QA 数字写作当前验证 |

审查是基于风险的全项目架构与核心路径检查，不代表每个源码文件逐行证明无误。Mate 50/HarmonyOS 真机、APK 最新打包与安装、真实模型 + MinerU 联合流程、复杂 Office 保真、两设备迁移、正式签名以及第三方依赖漏洞数据库扫描均未在本轮执行。

建议先修 F1/F2/F3 的数据与授权边界，再修 F4/F5/F6 的状态一致性；把复现探针改为断言正确行为并加入回归。修复后再做 Flutter → Kotlin → 数据库存储/网络的联合设备验收。

