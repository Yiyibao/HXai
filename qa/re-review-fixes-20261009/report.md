# 独立复审问题修复与确认（2026-10-09）

[独立复审报告](D:/Office/Study/code/MathStudyAgent/qa/re-review-20261009/report.md) 的 **R1–R5 已全部修复，修复后回归验证通过**。原来的缺陷复现保留在原 QA 目录；新增的正式回归测试断言正确行为，不再把“缺陷出现”当作测试成功。

保留既有工作区改动，未提交、推送或发布，未调用真实模型或线上文档服务。文档权限设备测试由 HTTP 拦截器完全替代网络，使用合成文件。

## 修复闭环

| 问题 | 实现 | 修复确认 |
|---|---|---|
| R1 撤回上传权限误阻断既有任务 | 只按上传权限过滤 `parse_material_enhanced`；查询、取消保留。读取未下载结果要求已有 remoteId，交由仓库执行归属、配置、摘要校验；不会恢复未确认提交。 | 新设备测试实际查询、下载 SUCCEEDED 任务、校验 ParsedDocument、清理远端、再次读缓存及调用取消。下载/取消/清理不增加原文件上传次数；错档案读取在发请求前拒绝。重建仓库后 SUBMITTING 后台恢复仍零新增 POST，显式批准恢复保持原幂等键。 |
| R2 延迟模式回执污染新上下文 | 模式请求绑定 profile、epoch、会话 ID、选择世代和请求序号；成功、失败都只作用于当前匹配上下文。 | 延迟 A 的模式成功、选择 B/CHAT、返回旧成功后，B 仍为 CHAT。原教学模式与 bootstrap 回归继续通过。 |
| R3 旧发送收尾清理新聊天草稿 | 会话选择有独立可观察的世代，即使 null→null 新聊天也递增；档案、epoch、选择变化均清理旧输入上下文并使旧发送状态失效。 | 新聊天重新输入相同文字后，旧成功不清除；新聊天输入不同草稿后，旧失败不弹出错误；当前正常成功仍选中目标会话并清空已发送输入。 |
| R4 旧附件选择回执重新出现 | 附件请求绑定 profile、epoch、选择世代和附件请求序号；过期成功/错误都丢弃。epoch 变化也清理草稿、附件。 | A→B→A 延迟附件不加入新输入框；同档案 epoch 变化清理旧草稿/附件；延迟选择失败在新 epoch 中不显示。正常选择和直接切档案清除的既有 Widget 回归继续通过。 |
| R5 分页作废刷新 | 刷新期间忽略分页；重复在途分页不发第二次请求；刷新/上下文变化作废旧分页结果和错误。分页末页状态随列表上下文重置，抽屉不再用累计按钮次数决定能否继续加载。 | 刷新→分页、新分页→刷新、重复分页、旧分页失败→epoch 变化均通过；新首页不被旧分页覆盖，忽略请求也不会累加抽屉分页阈值。 |

业务实现位置：

- [DocumentServiceTools.kt:49](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceTools.kt:49)
- [chat_page.dart:120](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/chat_page.dart:120)、输入上下文清理在 139 行、附件请求在 728 行附近。
- [study_providers.dart:124](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/providers/study_providers.dart:124)、会话选择世代在 170 行。
- [conversation_drawer.dart](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/conversation_drawer.dart)

正式回归：[re_review_regression_test.dart](D:/Office/Study/code/MathStudyAgent/client/flutter_ui/test/re_review_regression_test.dart)、[DocumentPermissionRegressionTest.kt](D:/Office/Study/code/MathStudyAgent/client/app/src/androidTest/java/me/rerere/rikkahub/service/DocumentPermissionRegressionTest.kt)。本目录 `fix.patch` 以独立复审时保存的源码为基线，仅列本轮四个业务文件的修改；`source-evidence/` 与 `source-hashes.json` 保存最终源码行号和摘要。

## 实际验证

| 检查 | 最终结果 | 证据 |
|---|---|---|
| Flutter analyze | 无问题 | `flutter-analyze.log` |
| Flutter 全部测试 | **74 通过**，含本轮 12 项回归/对照 | `flutter-tests-final.log` |
| app JVM 强制执行 | **341 通过**，0 失败/错误/跳过 | `android-build.log`、`jvm-summary.json`、`jvm-results/app/` |
| AI JVM 强制执行 | **202 通过**，0 失败/错误/跳过 | 同上、`jvm-results/ai/` |
| Android Lint 强制执行 | **0 错误、46 警告**，与复审前一致 | `android-build.log`、`lint-results-debug.txt` |
| 当前源码 Flutter AAR | 构建成功 | `flutter-aar-final.log` |
| 最终 Debug APK、测试 APK | 构建成功，刷新本地 AAR 依赖重新打包 | `android-final-package.log` |
| 最终包组合设备回归 | **23 通过**，0 失败/跳过 | `android-runtime.log` |
| 真实 Flutter→桥接→文件交付 | **1 通过**，打开、另存、分享成功，目标内容摘要一致 | `flutter-device-delivery.log` |
| 进程停止→重启恢复 | seed/verify 各 **1 通过**，合成会话结束清理 | `process-seed.log`、`process-verify.log` |
| APK/AAR Flutter assets | 两种 ABI 各 **32 项一致，0 差异** | `build-manifest-x86_64.json`、`build-manifest-arm64.json` |

组合设备回归覆盖新文档权限测试、旧提交恢复、Flutter 会话桥接、记忆开关、审批/删除边界、Skills 路径与诊断。三项备份测试与后续全文搜索在同一 instrumentation 进程中执行成功，未再次污染分词字典。检查了每项 instrumentation status、numtests 和最终 OK；不是依据 adb 退出码宣布成功。JVM 日志确认两个测试任务实际执行，XML 时间戳为本轮，未把 UP-TO-DATE 当作重跑。

较早的设备组合日志保存为 `android-runtime-initial-package.log`；最终结论使用刷新 AAR 后重新安装的最终包结果。中间 Flutter 夹具的 Future 编码与未稳定 bootstrap 时序已纠正，最终完整执行通过；中间失败或中断日志不作为验收成功证据。

## F1–F6 复核结论

- **F1 有效**：跨档案和乱序列表防护保留，新增 R5 分页遗漏已补齐。
- **F2 有效**：直接档案切换、epoch 变化、附件延迟返回及新聊天输入生命周期均已覆盖修复。
- **F3 有效**：后台未确认上传仍不重传；已批准任务查询/下载/取消/清理保留，新增 R1 已修复。
- **F4 有效**：会话 ID 保护保留，旧发送成功/失败的输入框收尾也已隔离。
- **F5 有效**：桥接持久化与异档案拒绝设备回归再次通过；此前独立复审核实的冷启动持久化结论没有受到本轮改动影响。
- **F6 有效**：档案/epoch 模式初始化防护保留，手动模式请求的旧回执也已隔离。

## 产物与确认范围

- [ARM64 Debug APK](D:/Office/Study/code/MathStudyAgent/client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)，SHA-256：`18ad464a5bef93667e0c6079470912a4d51e18f2ebbdeaeea6bd5be4ac161df4`。
- [x86_64 Debug APK](D:/Office/Study/code/MathStudyAgent/client/app/build/outputs/apk/debug/app-x86_64-debug.apk)，SHA-256：`f99cd1d4d4d0e704e8858cde9697592eaf430d36ea79f56937e3b2634f387cb5`。

上述五项问题已在对应复现场景及追加回归范围内确认修好。验证范围是 Debug、本地 x86_64 模拟器、合成桥接/HTTP，并包含实际 Flutter 文件按钮联调；没有重新验收 Mate 50/HarmonyOS、ARM64 实机、真实模型/生产文档服务或正式签名。既有 46 项 Lint 警告及前轮 Windows workspace 符号链接权限条件不是本轮五项修复的未完成项。完整产品的所有业务场景不能仅由这些测试推定已验收。
