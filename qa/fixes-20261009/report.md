# 2026-10-09 审查问题修复与复验

本轮针对 [项目审查](../review-20261009/report.md) 的 F1–F6 修复当前工作区。保留已有未提交改动，未提交、推送或发布；未调用真实模型或线上文档服务。

## 修复结果

| 问题 | 实现与验证 |
|---|---|
| F1：旧档案异步列表覆盖新档案 | 四类列表共用 `ProfileListNotifier`，显式刷新校验请求 Ref 生命周期、档案、contextEpoch 和序号；会话分页同样校验。测试覆盖旧成功、四类旧错误和同档案乱序刷新。 |
| F2：旧草稿和附件残留 | 聊天页监听活动档案，切换时清理草稿、附件及发送状态。发送完成只在身份、epoch 和发送序号匹配时修改输入；旧错误或 finally 不干扰新发送。文字、附件 Widget 回归通过。 |
| F3：后台未经最新权限校验重发上传 | `syncPending()` 不再恢复 remoteId 为空的 SUBMITTING 意图，保留持久化意图供前台当前聊天权限检查或用户显式操作恢复。已确认任务继续查询、下载和清理。模拟网络恢复后后台零追加 POST，显式恢复沿用原幂等键，随后后台仅 GET。 |
| F4：旧发送回执跳转会话 | 捕获档案、epoch、会话及选择序号，成功回执过期时不修改当前会话；新聊天即使 ID 同为 null 也递增选择序号。测试覆盖切档案、发送期间新建聊天及正常发送仍选中目标会话。 |
| F5：自动记忆开关未持久化 | 桥接使用专用 `setLearnerMemoryEnabled()`，Flutter 检查失败结果并反馈。设备测试验证桥接开关、设置流、原始持久化读取和 bootstrap 一致，拒绝其他档案操作；结束恢复原设置。 |
| F6：教学模式沿用旧档案 | 模式绑定活动档案及 epoch，切换采用匹配的 bootstrap 或 CHAT 默认；同档案刷新保留用户当前手动选择。两项回归通过。 |

生产文件：`client/flutter_ui/lib/providers/study_providers.dart`、`pages/chat_page.dart`、`pages/memories_page.dart`，以及 Android `StudyHostFacade.kt`、`DocumentServiceRepository.kt`。

新增 `client/flutter_ui/test/profile_isolation_test.dart` 的 14 项回归和 Android `FlutterMemoryToggleTest.kt`；扩展 `DocumentSubmissionRecoveryTest.kt`。原审查目录的缺陷探针作为修复前证据保留，不作为修复后验收测试。

## 验证证据

| 验证 | 结果 | 本目录证据 |
|---|---|---|
| Flutter analyze | 无问题 | flutter-analyze.log |
| Flutter 全部测试 | 62 通过，含 14 项新增回归 | flutter-tests.log |
| app JVM，`--rerun` | 341 通过，0 失败/错误/跳过 | android-build.log、jvm-test-summary.json |
| AI SDK JVM，`--rerun` | 202 通过，0 失败/错误/跳过 | 同上 |
| Android Debug APK、测试 APK | 构建成功 | android-build.log |
| Android Lint | 0 错误、46 警告 | lint-results-debug.txt |
| Flutter AAR | 重新生成成功 | flutter-aar.log |
| APK 内 Flutter 资源 | x86_64 / ARM64 各 32 项与新 AAR 一致，无差异 | build-manifest-x86_64.json、build-manifest-arm64.json |
| Android 核心分组回归 | 10 通过 | android-runtime-core.log |
| Android 备份分组回归 | 3 通过 | android-runtime-backup.log |
| 修复夹具后的最终 Android 合并回归 | 13 通过，0 失败/跳过 | android-runtime-final.log |
| 测试夹具再编译及 Lint | 成功 | android-test-isolation-build.log |

首次将备份与会话测试合并时，8 项完成后原生分词库 SIGABRT（`android-runtime.log`、`runtime-crash.log`）。备份测试的隔离 Room 数据库将进程共用的 `jieba_dict` 路径指向临时目录，清理目录后，后续全文搜索读取已删除字典。分组启动新进程时两组均通过。本轮补充 `BackupReliabilityTest.restoreAppDictionary()`，在删除临时目录前恢复应用字典，避免污染后续测试；仅调整测试夹具。重新编译后同进程合并运行全部 13 项通过，其中包含原先崩溃的全文搜索用例，原始失败证据保留。

## 行为边界

后台自动恢复上传的行为有意收紧：未确认的上传等待前台校验/用户恢复，后台不会自动重新传文件。已确认任务的结果同步保留。切换档案会清空未发送草稿和待发送附件。

本轮为 Debug、x86_64 模拟器及合成桥接/HTTP 验证。未重新验收 Mate 50、生产文档服务、真实模型、跨设备恢复或发布签名；历史审查中的 Windows workspace 符号链接权限失败和已有 46 项 Lint 警告未作为本轮六项修复通过来关闭。

## 安装包

[ARM64 Debug APK](../../client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)，SHA-256：`745383c49648e19a89658f13b29586e6511b87c0030b83376b3ee29cf5232214`。

[x86_64 Debug APK](../../client/app/build/outputs/apk/debug/app-x86_64-debug.apk)，SHA-256：`4706132861736e5a59cf037c2777c5aab3c3927eaa2cc348a965ed084106e9a7`。
