# Flutter 主界面 + Kotlin 功能层实施方案

日期：2026-10-09。用户已选定本路线。本文件是交给 Gemini 的实施合同，尚未创建 Flutter 工程、修改客户端或验证混合 APK。

## 1. 目标与范围

在现有 Android APK 内，用 Flutter 重做主要用户界面，保留已实现的 Kotlin 功能。目标设备仍为华为 Mate 50 / HarmonyOS 4.2 的 Android APK 路线，不转向 HarmonyOS NEXT。模拟器基本功能测试是当前迁移起点，不等于全部能力已验收。

交付重点：聊天阅读与输入、键盘和滚动、四主入口、真实任务状态、模型与提供商配置、材料核对、记忆编辑、成果交付。已有教学模式、长期记忆、审批、档案隔离、文件服务、备份和诊断语义必须保持。

视觉沿用 `docs/ui-design/white-pink-v1/` 中已采纳白粉图册和粉色页芽。图册是视觉参考，功能及状态以现有代码为准。本方案不要求重新生成图片。

## 2. 技术选择

| 层 | 选择 | 责任 |
|---|---|---|
| Android 宿主 | 现有 `client/` Gradle 工程 | Application、依赖注入、权限、服务、数据库、系统入口 |
| 主界面 | 新增 Flutter module：`client/flutter_ui/` | 页面、导航、交互、布局、动画、无障碍 |
| 状态绑定 | Riverpod | 界面临时状态、订阅桥接事件、请求生命周期 |
| 路由 | go_router | 四主入口各自返回栈、详情和配置路由 |
| 命令协议 | Pigeon 生成 Dart/Kotlin DTO 和 HostApi | 类型化命令、返回结果、错误；生成代码不手改 |
| 事件协议 | EventChannel + 明确版本化事件结构 | 会话、任务、材料、记忆变化及恢复快照 |
| 内容显示 | Markdown 渲染器 + 数学渲染器适配层 | 原文、流式未闭合内容、公式、代码、表格 |

依赖候选：`flutter_riverpod`、`go_router`、`pigeon`、`flutter_markdown_plus`，数学渲染候选 `flutter_math_fork`。这些不是已验收依赖组合，尤其 Markdown 与 LaTeX 需要自定义解析集成。Gemini 在阶段 0 核验维护状态、许可证、SDK 最低要求和公式覆盖，锁定实际通过验证的版本、Flutter stable SDK 和 `pubspec.lock`，不要随意追最新版。主业务不增加第二套 Dart 模型 API 客户端或数据库。

官方 add-to-app 支持在原生宿主中嵌入 Flutter module。优先使用源码 module 集成；若宿主 AGP/Kotlin 与 Flutter 工具链无法协调，可评估固定构建的 AAR 集成，但必须证明宿主完整 APK 可构建，记录生成命令与刷新流程。不得为迁移任意降级全部现有依赖。当前 `client/settings.gradle.kts` 使用 `FAIL_ON_PROJECT_REPOS`，仓库设置须按选定 Flutter 版本官方流程核验。

## 3. 宿主与代码布局

建议新增布局（下面均为计划路径，不是已存在接口）：

```text
client/flutter_ui/
  lib/app/                 # 主题、路由、启动、生命周期
  lib/bridge/              # 生成 DTO、HostGateway、事件解码
  lib/design_system/       # tokens、统一组件、页芽状态
  lib/features/{chat,materials,memory,artifacts,settings}/
  pigeons/study_host_api.dart
  test/                    # 状态与 Widget 测试
  integration_test/        # 经宿主启动的真实桥接验证
client/app/src/main/java/me/rerere/rikkahub/flutter/
  StudyFlutterHostActivity.kt
  StudyBridgeRegistrar.kt
  StudyHostFacade.kt
  adapters/                # 从现有 service/store 映射，不复制业务
  generated/               # Pigeon 输出
docs/flutter-migration/    # 方案、提示词、验收合同
qa/flutter-migration/      # 实施时产生的构建记录和证据
```

Flutter 宿主 Activity 独立于现有 `RouteActivity : ComponentActivity`；按真实服务绑定要求选择 FlutterActivity 或 FlutterFragmentActivity，不把 FlutterFragment 强行插入非 FragmentActivity。阶段 0 必须查清原 Activity 中服务绑定、权限、Intent、导航及安全恢复职责，并转移必要宿主能力。

初期用 Debug 可选入口启动 Flutter；旧 Compose 是迁移回退入口。最终四主页面和日常设置由 Flutter 主导，不能长期只包一层首页。特殊系统保存/分享及迁移期未重写设置可调用明确标识的原生页面，剩余项在验收表列出。最终主入口切换在全部迁移门禁通过后实施，保留调试回退机制。

每个进程初期最多一个主 FlutterEngine。先完成稳定启动，再测量是否需要缓存/预热；保留现有 RikkaHubApp 初始化和崩溃安全模式。桥接注册一次，Activity attach/detach 时更新系统能力引用，不持有泄漏 Activity 的长生命周期闭包。引擎释放时注销监听和协程。不得让页面销毁自动取消 Kotlin 业务任务。

## 4. 现有代码接入地图

以下路径以 `client/app/src/main/java/me/rerere/rikkahub/` 为根。它们是需复用或分析的真实源文件，不代表已提供 Flutter API。

| 能力 | 现有位置 | 桥接约束 |
|---|---|---|
| 启动与路由 | `RikkaHubApp.kt`、`RouteActivity.kt`、`di/` | 复用依赖容器、启动恢复、安全入口和外部 Intent |
| 聊天生成 | `service/ChatService.kt`、`ConversationSessionManager.kt`、`data/repository/ConversationRepository.kt` | 沿用会话、分支、队列、取消、重试、持久化；分析 Service 绑定后接入 |
| 档案与教学 | `feature/studychat/LearnerProfile.kt`、`StudyMode.kt`、`StudyLearningLoop.kt`、`StudyCapabilities.kt` | 不把教学规则或能力判断复制到 Dart |
| 自动记忆 | `feature/memory/LearningMemoryStore.kt`、`MemoryState.kt`、`LearningMemoryPage.kt`、`ui/StudyMemoryDetailPage.kt` | 复用来源、版本冲突、删除抑制和事务 |
| 材料与成果 | `feature/material/MaterialFiles.kt`、`MaterialReader.kt`、`MaterialWriter.kt`、`DocumentServiceRepository.kt`、`MaterialPage.kt`、`ui/StudyArtifactPreviewPage.kt` | 复用所有权、SHA256、状态、真实生成和交付 |
| 模型配置 | `feature/studychat/ui/StudyProviderPages.kt`、`StudyModelPickerSheet.kt`、`data/datastore/CredentialVault.kt` | 复用凭据保护、真实测试结果和能力标签 |
| 备份与诊断 | `data/sync/EncryptedBackup.kt`、`BackupManager.kt`、`feature/diagnostics/`、`ui/activity/SafeModeActivity.kt` | 复用恢复前校验、回滚及脱敏导出 |
| Skills | `data/files/SkillManager.kt`、`ui/pages/extensions/skills/` | 安装、查看、显式使用；制作/编辑仍关闭 |

部分逻辑现在位于 Compose 页面或 ViewModel，必须先提取为 Kotlin 可测试用例/Facade，旧界面也调用同一实现。不要直接从桥接调用 Composable，不要移动整套业务进 Dart。提取前保存当前工作区差异清单，保留已有大量修改；不可 reset、clean 或覆盖现有功能。

## 5. 桥接合同

### 5.1 命令与数据

计划 HostApi 能力分组：bootstrap/profiles、conversations/chat、materials、memory、artifacts、providers/settings、backup/diagnostics/skills。真实方法签名由阶段 0 逐项映射后冻结；接口禁止以任意字符串执行任意功能。

每条命令包含 `apiVersion`、`requestId`、必要的 `profileId`、`contextEpoch`、业务对象 ID 和需要时的 `expectedRevision`。返回 success/failure 及原生确认的数据/任务 ID。错误包含稳定 code、用户提示、retryable、requestId，不透出密钥和原始服务响应。建议错误码：`STALE_CONTEXT`、`NOT_FOUND`、`REVISION_CONFLICT`、`CAPABILITY_UNAVAILABLE`、`PERMISSION_DENIED`、`CANCELLED`、`INTEGRITY_FAILED`、`HOST_UNAVAILABLE`。

档案 epoch 是新增 UI 上下文隔离令牌；不是替代现有所有权校验。Kotlin 每次按真实数据校验对象归属；前端提供的 profileId、路径和能力标签不能作为授权依据。跨档案长任务继续绑定原始归属，只向正确档案投递。切换时清空旧详情、草稿可见性、事件订阅和缓存，并对旧异步返回执行 epoch 检查。

消息 DTO 保留 nodeId、messageId、分支选择、role、parts、revision、真实时间和真实生成状态；parts 区分文本、图片、文件、推理、工具调用、工具结果等，不把所有内容拼成字符串。历史分页使用稳定 cursor，禁止每个 token 传输全部历史。

发送/重试/审批/导入等可产生副作用的命令，在 Kotlin 以 requestId 实现幂等并返回已有任务/消息结果；UI 连点禁用只是体验措施，不是幂等保证。若旧功能无此机制，新增适配层必须证明去重与恢复语义，明确保留期限。

### 5.2 流事件与恢复

事件建议字段：`apiVersion, subscriptionId, profileId, contextEpoch, conversationId?, operationId?, seq, revision, type, payload`。seq 以 subscription 为范围递增；消息 revision 以消息为范围递增。丢弃重复/陈旧事件；检测缺口后请求原子快照与对应 watermark，再接增量，不能先读快照再订阅留下竞态窗口。

初版优先合并发送“正在变化消息的完整快照”，每 30–50 ms 最多一次，而非逐 token 事件；其他历史仅分页传输。需要 delta 时带 baseRevision，无法匹配即重取。完成、失败、审批等边界事件不得被节流合并丢弃。切回页面、进程恢复、服务重绑定后总是以 Kotlin 持久化状态重新核对。

UI 取消订阅仅释放观察者；点击停止才调用原生取消生成。stop 命令需等待原生状态确认，不能仅停止转圈。桥接事件发布遵守所用通道线程要求；IO/公式相关数据处理和文件工作不阻塞主线程。

### 5.3 文件、配置与恢复

文件经原生 SAF/ContentResolver/FileProvider 处理。Dart 使用 opaque ID 和分页/限额预览，不接受任意本地绝对路径，不经通道搬运整份大型 PDF 或 base64 文件。系统取消与成功分开返回；保存完成以现有重读/hash 校验为准。原页缩略图通过限额字节或短期可读取资源适配，需明确释放和档案切换清理。

凭据读取仅返回 masked 值和 configured 状态；编辑提交的新密钥短期经过通道后交 Kotlin 保护保存，不写 Dart 日志、状态恢复、分析或持久化缓存。测试连接必须走真实 Kotlin 请求。备份密码同样不持久化到 UI；进入恢复前冻结任务并沿用原生恢复/回滚，成功后重启宿主或失效全部上下文再 bootstrap。

## 6. 视觉与交互实施合同

主色：白 `#FFFFFF`，淡粉操作 `#F8D5DE`，浅粉背景 `#FFF1F5`，深玫瑰 `#925568`。深色正文建议 `#27232A`、次级文字 `#655D66`。这是设计起点，实测对比度后调整文字/边界色；不能用浅粉当正文文字。

基础间距 4/8/12/16/24/32 dp，页面边距 16–20 dp；字号正文 16、次级 14、页面标题 22，使用系统中文字体和真实 text scaling。按钮/列表点击区至少 48 dp；宽度由约束决定，不使用模拟器固定坐标。组件圆角分级 12/16/24 dp，避免每段信息都套卡片。保留深色模式，形成单独可读 tokens，不直接反色。

底部四入口：聊天、材料、记忆、成果；详情和配置隐藏底部入口并给返回导航。设置与档案放在主界面上方合理位置。四入口保留各自浏览状态；切换档案时隔离这些状态。页芽只在首页/空态及小型任务状态位置出现，不重复占据阅读区域，不掩盖真实错误。

| 页面 | 实现要点 |
|---|---|
| 聊天首页/教学聊天 | 初始欢迎简洁；教学模式易发现但不常驻大面板；用户消息淡粉气泡，助手以舒适阅读版式显示；工具状态可展开 |
| 输入框 | 附件、可增长文本、模型选择器、发送/停止层级清楚；模型框沿用已采纳位置，窄屏/大字可换行；保留每个会话草稿；不直接清掉发送失败的内容 |
| 历史/分支 | 历史搜索/切换有入口；分支显式展示；重新生成不能删旧分支；切模型保留会话上下文 |
| 模型/提供商 | 模型按提供商分组；配置、管理模型和真实连接测试可达；能力来自原生结果；未配置有明确修复入口 |
| 材料列表/原页核对 | 搜索筛选、状态、导入和重试；图片缩放与页码；原文/解析内容核对；符号修正反馈与来源版本一致 |
| 记忆列表/详情 | 来源、更新时间和证据语义清晰；编辑与删除；版本冲突保留草稿并引导重载；候选不冒充正式掌握度 |
| 成果列表/预览 | 格式、状态、来源、打开/另存/分享；数学、表格及二进制预览按真实能力呈现，不伪造文本预览 |
| 设置/错误恢复 | 提供商、档案、记忆、Skills、备份、诊断和现有关键配置入口；错误就近反馈；审批明确允许/拒绝/等待状态 |

聊天滚动：在底部且用户未拖动时跟随；用户上翻立即停止自动跟随并出现“回到最新”按钮。加载早期消息保持首个可见消息及偏移；图片/公式改变高度也不得跳到末尾。新会话、键盘弹起、长内容选择复制、返回上一页均验证。

键盘：按 viewInsets/safe area 布局，输入框始终可见；IME 组合输入期间不误发送，Enter 默认换行，显式发送；Android 返回优先关闭键盘/面板再退页面。系统文件选择回来保持草稿、附件和原档案归属。

数学：支持行内/块级 LaTeX、分数、矩阵、aligned、多行长公式、中文混排、美元金额转义；流式未闭合表达式临时按可读原文呈现，解析失败显示原文与复制动作，不丢消息、不把公式交给模型重写。长公式和表格局部横向滚动。原始 Markdown 可复制；数学渲染包支持边界由实际样例记录。

动画：按压反馈 100–140 ms，页面/面板 200–280 ms，短距离淡入位移；优先 Flutter 内置动画，避免每 token 重播。遵守系统减少动画和关闭动画设置，自动关闭循环伙伴、骨架闪烁和持续动画。默认不添加声音；成功反馈只在 Kotlin 确认成功后一次触发。

## 7. 分阶段实施与完成门禁

| 阶段 | 输出 | 完成条件 |
|---|---|---|
| F0 源码与工具链审计 | 工作区保护清单、能力映射、版本锁定、桥接合同、全部现有入口盘点 | 可构建宿主 + Flutter module；证明原生服务绑定、快照订阅、SAF 往返可行 |
| F1 宿主与设计系统 | Flutter 可选入口、主题、四入口、统一组件、生命周期桥接 | 真实 bootstrap/档案数据可读；冷启动、后台回来、旋转、返回和安全恢复通过 |
| F2 聊天完整闭环 | 真实聊天、数学、模型配置、附件、审批、历史分支与队列 | 从发送到持久化再恢复通过；20 条以上长消息不抢滚动；32 个公式/Markdown 固定案例 |
| F3 其他主页面 | 材料、原页核对、记忆、成果真实接入 | 档案隔离、编辑冲突、删除抑制、校验交付及五格式范围回归 |
| F4 设置与恢复 | 提供商、档案、备份、诊断、Skills 和必要设置完整可达 | 凭据编辑/真实连接、备份恢复、权限失败、崩溃恢复与系统 Intent 回归 |
| F5 主入口与交付 | 默认 Flutter 主入口、完整 APK、演示录像、证据表、已知限制 | 本目录 acceptance.md 达标；真机条件项明列；旧业务无未解释回归 |

阶段之间按证据推进；发现失败先修复，不把未实现功能藏起来冒充完成。无需每步重新询问已授权的常规实现选择；缺失 SDK 可按现有权限安装配置，真实 API 资料不足则验证不依赖它的部分并记录具体阻碍。不能以“截图好看”结束 F2，也不能只完成样板宣称全面迁移。

## 8. 验证与交付

详细场景见 `acceptance.md`。Flutter format/analyze/test、桥接 Kotlin 测试、现有相关 JVM/Android 回归和完整宿主 assembleDebug/lint 均需记录。纯 Flutter 测试无法证明原生桥接：应在宿主 APK 上以 Android instrumentation 或实际设备自动化验证 Flutter 到 Kotlin 的真实往返，选择工具并记录为何可用。

迁移前后用同一模拟器/数据/模式对照。Profile 构建采集帧耗时、冷启动、内存和通道频率；Debug 录像用于交互证据，不能作为性能结论。性能目标与测量规则见验收表。

交付 APK 路径、版本与 SHA256、Git diff 清单（不自动提交/推送）、构建命令、锁定工具链、页码/场景对应截图录像、验证报告和未完成项。真实模型/真机缺条件明确 NOT_RUN，不能将 mock 成功算为生产功能通过。

## 9. 来源与依据

本方案的分层、协议与阶段是针对本仓库的工程设计。工具能力参考以下官方/维护者资料；依赖版本仍须在 F0 实测锁定。

- Flutter add-to-app：https://docs.flutter.dev/add-to-app
- Android module 集成：https://docs.flutter.dev/add-to-app/android/project-setup
- Android 插件和宿主配置：https://docs.flutter.dev/add-to-app/android/plugin-setup
- 平台通道与 Pigeon：https://docs.flutter.dev/platform-integration/platform-channels
- go_router：https://pub.dev/packages/go_router
- Riverpod：https://pub.dev/packages/flutter_riverpod
- Markdown 渲染器：https://pub.dev/packages/flutter_markdown_plus
