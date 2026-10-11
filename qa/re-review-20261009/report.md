# MathStudyAgent 独立复审（2026-10-09）

本轮确认 **5 项 P2 可操作问题**，其中 4 项通过 Flutter 合成桥接探针复现，1 项通过 Android 隔离 HTTP 探针复现。没有确认新的 P0/P1；这不代表已经证明所有授权竞态或隐私边界安全。上一轮修复对原始跨档案列表覆盖、后台自动重传、记忆设置写入和会话 ID 回执保护有效，但输入框生命周期、教学模式回执以及分页并发仍有遗漏。

审查对象是当前整个工作区，包括未提交与新增文件，不限于 Git diff。先读取适用的 `client/AGENTS.md`；检查项目及上级目录未找到额外 AGENTS.md。本轮不涉及 web-ui 修改或深入前端 React 审查。两份历史报告只用于确定线索，以下数字和结论均来自本轮重新读取源码或执行。只新增本目录隔离测试、证据与报告，未修改业务代码，未提交、推送、发布，未向真实模型或文档服务上传材料。新增 HTTP 探针的拦截器完全替代网络响应。

## 按严重程度排序的问题

### R1 · P2 · 撤回上传权限同时阻断已批准任务的工具查询、取消与结果下载

- **位置**：[DocumentServiceTools.kt:68](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceTools.kt:68)，下载判定在 49 行，`remoteEnabled` 定义在 10 行。生成时权限接入：[ChatService.kt:806](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:806)。
- **触发**：已批准任务有 remoteId、结果尚未下载；随后用户说“不要上传，仅本地处理”，工具创建得到 `allowRemote=false`。
- **实际行为**：过滤器删除 `get_material_parse_status` 和 `cancel_material_parse`；保留的 `read_material_parse_blocks` 在未下载时也因 `require(remoteEnabled)` 直接失败。这些操作并不重新上传原文件。即使已批准远端任务完成，也不能通过工具入口取得结果。
- **影响与边界**：助手无法履行查询、取消和读取已批准结果的请求。原生前台同步与后台仓库路径仍可以查询/下载/清理，已下载缓存也可以读；因此不是所有入口失效，也未证明私有文件被重新上传。
- **证据**：`android/DocumentRevocationAuditTest.kt` 与 `qa-device-runtime.log`。探针使用已批准 QUEUED 任务，确认禁上传后提交工具消失、查询/取消工具同时消失、读取工具在发请求前失败；SUCCEEDED 未下载任务经过同一个无状态例外的权限门禁，属于代码确认。仓库级操作仍产生预期 `GET、DELETE、GET`。探针断言的是缺陷存在，成功不表示正确业务验收。
- **建议**：分离“上传原文件”和“访问既有任务”的权限。禁止新的提交/重发原文件，保留归属与服务配置校验后的状态查询、取消、已批准结果下载和清理。

### R2 · P2 · 延迟教学模式回执改写新上下文模式

- **位置**：[chat_page.dart:118](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/chat_page.dart:118)，等待在 121 行、无条件写当前模式在 122 行。
- **最小复现**：会话 A 选择“完整讲解”，延迟 `setStudyMode` 成功回执；切到会话 B，B 模式设为 CHAT；再返回 A 的回执。当前会话仍为 B，但当前模式成为 EXPLAIN。
- **影响**：新会话的界面模式错误；下一次发送会读取这个错误模式并传给原生层。档案/epoch 切换或两次模式选择乱序返回具有相同无校验写入机制，后者尚未单独运行探针。
- **证据**：`race_probe_test.dart` 的 `PROBE late mode acknowledgement...`、`race-probes.log`；当前 `sendMessage` 在 `study_providers.dart:219` 读取当前模式。
- **建议**：捕获 profile、epoch、会话 ID、selectionRevision 和模式请求序号；等待后全部匹配才更新当前 UI。旧会话已完成的持久化更新可以保留，不应改变新会话状态。错误提示也应绑定请求上下文。

### R3 · P2 · 新建聊天后旧发送回执仍清空当前输入框

- **位置**：[chat_page.dart:108](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/chat_page.dart:108)，清理在 109–110 行；新聊天只修改会话选择与模式，见 [conversation_drawer.dart:25](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/conversation_drawer.dart:25)。
- **最小复现**：输入 `same draft` 并发送，延迟成功回执；在同档案/epoch 中新建聊天（会话 ID 仍为 null，但 selectionRevision 已递增）；返回旧成功。provider 正确忽略回执导航，Widget 却将输入框清空。
- **影响**：新聊天仍显示发送期间的输入，且旧发送使发送按钮继续锁定；相同文字的新草稿或复用附件可被旧完成清除。成功探针在新聊天明确重新输入同文草稿后确认被清空；失败探针在新聊天输入不同草稿后确认仍出现旧错误“发送失败，草稿已保留”。两者均已复现。
- **证据**：`PROBE send receipt after new chat...`。探针同时断言当前会话保持 null、输入已被清空。未声称原消息没有提交：原消息的提交是合法持久化结果。
- **建议**：为输入框绑定会话选择世代，选择历史会话或新聊天时隔离/清理输入并使旧 `_sendRevision` 失效；完成、错误、finally 都按同一个完整上下文校验。不能仅在 provider 中阻止会话 ID 导航。

### R4 · P2 · A→B→A 后旧附件选择回执重新加入已清理的输入框

- **位置**：[chat_page.dart:718](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/chat_page.dart:718)，回执仅校验 profileId 的 719 行；草稿清理只监听档案，见 130 行。
- **最小复现**：A 开始附件选择并延迟桥接返回；切到 B/epoch2，再切回 A/epoch3；旧回执返回 `/qa/A-private.txt`，该附件又进入新输入框。另一个探针确认仅变更 epoch 时草稿与附件保留。
- **影响与边界**：用户已放弃的附件重新出现，后续可能误送到 A 的新会话。不是已证明 A 附件进入 B，也不是已证明跨档案上传成功。原生 `pickAttachments` 在选择及导入前后校验 epoch（StudyHostFacade.kt:279–290），因此“选择器一直打开直到切档案”的真实流程通常会被原生拒绝；本复现针对原生已经完成校验、桥接成功消息延迟到达 Flutter 的时间窗。
- **证据**：`PROBE attachment picker returning after A B A...`、`PROBE same profile epoch change...`。epoch 单独变化的探针证明 Flutter 状态防护缺口，未单独证明普通设置 UI 可直接触发这种单字段变化。
- **建议**：选择请求捕获 epoch 和输入框/附件请求世代，返回时全部匹配才加入附件；epoch 变更也应使旧草稿/附件上下文失效。过期错误不应干扰当前输入框。

### R5 · P2 · 分页请求可以作废正在进行的刷新并保留旧首页

- **位置**：[study_providers.dart:127](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/providers/study_providers.dart:127)，分页递增共用 `_revision` 在 131 行，捕获旧列表在 132 行；刷新接受判定在 116 行。
- **最小复现**：旧首页加载完毕 → 发起刷新并延迟新首页 → 点击加载更多，分页拿旧列表长度作 offset 并递增 revision → 分页返回 → 新首页返回。最终列表仍为旧首页+下一页，新的首页结果被丢弃。
- **影响**：刷新成功却不生效；新建/删除/更新会话可能继续缺失或残留。分页与刷新都可能由真实 UI/发送后的自动刷新发起。这是同档案列表一致性问题，不是 F1 原始跨档案覆盖再次出现。
- **证据**：`PROBE pagination started during refresh...` 精确断言最终为 `old-first-page,next-page`，没有 `new-first-page`。重复分页的单一序号可以避免同一页双追加，但不能解决不同类型请求的依赖关系。
- **建议**：刷新时禁止或排队分页；分页绑定已提交首页的数据版本，只接受该版本的下一页。刷新成功后原子替换首页并废弃旧分页，不能让较晚开始的分页取消刷新。

上述“本轮问题”指本轮发现或重新确认，不表示全部由上一轮补丁新引入。无可用独立修复前提交快照可证明每项引入时间，因此没有把历史存在的工具权限过滤误称为已证明的新回归。

## F1–F6 逐项判定

| 项目 | 判定 | 当前证据与限制 |
|---|---|---|
| F1 异步列表跨档案覆盖 | **修复有效**（原始隔离缺陷） | 四类共用 Ref 生命周期、profile、epoch、revision 防护；既有旧成功/四类旧失败/同档案乱序测试重跑；新增 A→B→A 对照通过。初次 build 延迟覆盖显式刷新这一疑点被探针排除。新发现 R5 属于分页依赖遗漏。 |
| F2 草稿和附件残留 | **修复不完整** | 直接 A→B 草稿与已选附件清除测试通过；附件延迟回执 A→B→A 与 epoch 独立变化仍有 R4，切会话/新聊天的输入框世代还有 R3。 |
| F3 后台上传恢复权限 | **修复有效**（后台原始问题） | `syncPending` 跳过 remoteId=null；新仓库实例读取持久化 SUBMITTING 后仍零追加 POST。显式恢复沿用原键，已确认任务后台 GET 与清理正常。前台 `syncChatDocuments` 重传前有当前分支限制检查；这一完整撤回聊天→前台重传的设备链路未单独自动化复现，不能宣称所有 TOCTOU 已证明安全。既有任务工具操作另有 R1。 |
| F4 旧发送回执改写当前会话 | **修复不完整**（UI 收尾） | profile/epoch/会话 ID/selectionRevision 防护阻止旧回执改写会话 ID，既有测试通过；但同一次回执仍能清理新聊天输入框，见 R3。导航修复有效不等于整个发送完成流程隔离。 |
| F5 自动记忆开关持久化 | **修复有效** | 桥接调用专用 `setLearnerMemoryEnabled`，设备测试核对 settingsFlow、原始 DataStore 流和 bootstrap，拒绝异档案操作；Flutter 失败结果会抛错。额外冷启动核验见设备验证记录；启用检查和提交时再次检查已读取。关闭时不会自动撤销已发出的模型请求，只阻止后续保存，本轮未调用真实模型证明网络取消行为。 |
| F6 教学模式切换 | **修复不完整** | profile/epoch 绑定、匹配 bootstrap/default CHAT、同档案刷新保留手动选择的测试通过；历史会话选择和新聊天都有模式赋值。但手动 `setStudyMode` 延迟回执仍改写当前模式，见 R2。 |

## 本轮实际执行与证据

| 检查 | 实际结果 | 本目录证据 |
|---|---|---|
| Flutter analyze | 无问题 | `flutter-analyze.log` |
| 原有 Flutter 测试 | 62 通过 | `flutter-tests.log` |
| 新增 Flutter 探针 | 8 通过：2 个正确行为对照、6 个缺陷行为探针（R3、R4 各两个） | `race-probes.log`、`race_probe_test.dart` |
| JVM app / ai | 341 / 202 通过，0 跳过 | `jvm-rerun.log`、`jvm-summary.json`、`jvm-results/` |
| JVM common / document / oauth / search / speech | 1 / 1 / 2 / 16 / 43 通过 | 同上 |
| JVM workspace | 19 通过、1 环境失败 | 同上，ExampleUnitTest XML 保存完整失败堆栈 |
| Python 文档服务 | 29 通过，1 弃用警告 | `server-tests.log` |
| Android Debug 构建 | APK/测试 APK 构建任务成功；增量编译有效 | `gradle.log` |
| Android Lint 强制重跑 | 0 错误、46 警告 | `lint-rerun.log`、`lint-results-debug.txt` |
| Flutter Debug AAR | 从当前源码重新生成成功 | `flutter-aar.log` |
| AAR/APK Flutter 资源 | ARM64、x86_64 各 32 项摘要相同，0 差异 | `build-manifest-arm64.json`、`build-manifest-x86_64.json` |
| Android 组合回归 | 22 通过，0 失败/跳过 | `android-runtime.log` |
| 进程恢复 | seed/verify 各 1 通过，PID 2859→2949 | `process-seed.log`、`process-verify.log` |
| 新增文档权限/持久化设备探针 | 1 通过，含 R1 缺陷断言与后台/清理正确行为对照 | `qa-device-runtime.log`、`android/DocumentRevocationAuditTest.kt` |
| 记忆开关冷启动 | seed/verify 各 1 通过；PID 3266→3360，新进程设置及 bootstrap 均保持关闭，结束恢复原值 | `memory-cold-seed.log`、`memory-cold-verify.log` |

JVM 合计 **626 项，625 通过，1 失败**。第一次组合 Gradle 使用末尾 `--rerun` 时，测试仍显示 UP-TO-DATE，未据此算作执行；随后对每个测试任务分别紧接 `--rerun`，日志显示 8 个测试任务执行，XML 时间戳均属于本轮。增量构建不是测试实际执行的替代证据。

workspace 失败为 Windows 创建符号链接时“客户端没有所需的特权”，落在 `rootfsInstallerDownloadsAndExtractsTarGz`；不据此判定 Android 同样失败，也不把该模块写成全通过。Python FastAPI/Starlette 的 httpx 弃用警告非本轮功能失败。

初次 Flutter 探针包含一个被排除的错误假设和一个动画等待超时；原日志保存为 `race-probes-initial.log`，之后把前者改为正确防护对照、把后者改为有限 pump，再重新执行成功。QA Android 源码第一次仅加到 Java sourceSet，实际类未进 APK，运行报 ClassNotFound；保留 `qa-device-runtime-class-not-loaded.log`，改用 Kotlin sourceSet 后实际探针 `numtests=1`、status=0、OK(1)。adb 的进程退出码不能替代 instrumentation 的结果核对。

新增 Android 测试通过 `qa-source.init.gradle` 临时扩展 androidTest sourceSet，测试源码仍完全位于本 QA 目录。宿主业务 APK未修改；QA 构建的测试 APK包含额外测试，不是发布产物。验证结束已不带 init script 重新构建标准测试 APK，见 `restore-standard-test-apk.log`；测试构建及设备安装只用于本轮验证。

## 扩展覆盖与验收限制

- **聊天、分支、重复请求与审批**：设备组合中实际执行会话提交/重复提交与 facade 重建、交互表面旧分支拒绝、审批持久化失败后重试、删除/撤销不复活旧队列、旧生成元数据不覆盖人工标题。进程恢复保留队列 ID 和部分文本，将 RUNNING 生成标为 INTERRUPTED、暂停队列，不自动重跑不确定工具。没有用真实模型验收多工具长会话。
- **材料与成果归属**：读取桥接归属检查、材料摘要校验、文档任务配置绑定和成果导出重读；设备 `realArtifactInToolResultIsExposedAndSaveIsVerified` 与损坏解析缓存不续聊通过。未重新完整跑 Office 排版保真、系统文件选择器真实延迟回执或所有格式编辑。
- **Skills 边界**：读取 canonical 目录/文件解析、限长导入、目录链接与同级别名保护；设备三项边界测试覆盖外部软链、卸载/替换链接、超限 zip 保留已有安装。没有执行社区 Skill 脚本，不能以“加载 Skill”证明完整流程可运行。
- **记忆删除与再抽取**：检查删除时 epoch、来源/事实/主题 suppression、旧任务 CANCELLED、提交 revision 校验和恢复合并；相应 JVM 与备份设备测试通过。未调用真实抽取模型测试长语义文本、重新明确记住的语义质量或跨模型差异。
- **备份恢复与全文搜索字典**：本轮 22 项同一 instrumentation 进程中先执行三项 BackupReliabilityTest，再执行会话全文搜索测试 `staleGeneratedMetadataDoesNotReplaceNewMessagesOrManualTitle`，全部通过；测试 finally 在删除临时目录前恢复应用字典。证明当前正常与已覆盖异常退出路径不再污染后续搜索；未证明进程硬杀/VM abort 时 finally 一定执行。未做真实两设备迁移或完整应用恢复过程中的电源故障验收。
- **桥接及诊断**：检查 Pigeon 命令失败传播、归属与 epoch 拒绝、请求恢复以及持久化错误处理；设备六项 DiagnosticsTest 和 JVM ErrorPrivacy 测试通过。生成桥接的通用异常包装仍含 exception.toString/cause/stacktrace，属于需进一步审计的错误展示边界；未证明这些信息进入脱敏诊断导出或外发，因此不列成已确认泄漏。前台恢复权限检查使用会话快照，校验后等待仓库锁时发生撤回/分支变化的窄竞态未做原子性证明。
- **源码与产物一致性**：重新生成 AAR，并逐字节摘要核对 APK 内所有 32 项 Flutter assets，涵盖 Debug kernel；两种 ABI APK 摘要与此前记录相同。源码摘要另存 `source-hashes.json` 和带行号 `source-evidence/`。这证明本轮所检 Dart 输入对应嵌入资源，不等于 release AOT、签名或所有 native `.so` 的独立可复现构建证明；Kotlin 通过当前输入的增量 Gradle 构建核对。

验证环境为本机 Windows、本地 Android x86_64 模拟器、Debug 包与合成桥接/HTTP。未验收 Mate 50/HarmonyOS 真机、ARM64 实机运行、真实模型/MinerU/Docker 联合流程、正式签名、依赖漏洞数据库或真实服务可靠性。未经运行或仅静态检查的场景均不能算完整业务验收。

修复建议以 R1–R5 为本轮可行动清单；优先补齐请求上下文与输入框世代，再处理上传权限和既有任务访问权限的分离、分页版本依赖。测试通过不能关闭尚未覆盖的竞态。
