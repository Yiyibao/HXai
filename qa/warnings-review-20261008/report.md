# MathStudyAgent 警告修复、完整复审与新问题处理

日期：2026-10-08，Asia/Taipei。审查对象为 `D:\Office\Study\code\MathStudyAgent` 的当前工作区，包含用户原有未提交修改。已读取适用 AGENTS.md；初始状态见 [initial-status.txt](initial-status.txt)。没有提交、推送、发布或部署。

**本轮警告修复与完整复审已完成。Lint 从 0 errors／340 warnings／3 hints 降为 0 errors／42 warnings／0 hints。上一轮 7 项缺陷没有在本轮专项复验中回归；新发现的共享 workspace 外部入口和公共日志隐私问题已修复。真实审批链路仍失败：实际服务返回 HTTP 400，不能列为通过或声称全部功能已验收。**

## 结果与可信范围

| 检查 | 本轮最终结果 | 证据 |
|---|---|---|
| Android Lint | **0 errors、42 warnings、0 hints**；减少 298 warnings 和 3 hints | [修复前 XML](before-lint.xml)、[最终 XML](after-lint.xml)、[最终文本](lint-results-final.txt)、[最后复验日志](final-lint-verified.log) |
| Kotlin/AndroidTest 完整编译 | 61 条剩余诊断：app 生产 41、ai 兼容 6、仪器测试 API 14；编译成功 | [强制完整编译](final-compiler-full.log)、[最后修改重编译](final-privacy-build-tests-lint.log)、[逐条记录](remaining-compiler-warnings.md) |
| Debug 与测试 APK | 构建、覆盖安装成功，最终应用仍保留原有配置和数据 | [构建/单元测试/Lint](final-privacy-build-tests-lint.log)、[应用安装](final-app-install.log)、[最终测试安装](privacy-verified-install.log)、[APK 哈希](final-apk-hashes.json) |
| 全模块 JVM | **656 项：651 通过、5 失败、0 跳过**；5 项为 Windows workspace 环境条件失败，不能记为通过 | [强制全量日志](final-build-tests-lint-second.log)、[模块汇总](all-module-jvm-results.json)、[原始 XML](jvm-full/)、[最后 app XML](jvm-final/app/) |
| app JVM | **317 项全部通过**；最后生产修改后重新执行 | [最后构建日志](final-privacy-build-tests-lint.log)、[XML](jvm-final/app/) |
| Android 最新逐例结果 | **87 通过、1 失败、2 跳过**；首次失败与后续复验均保留，seed/verify 分进程分别计数 | [完整逐例记录](test-summary.json)、[主批次](emulator-results.json)、[追加批次](additional-emulator-results.json)、[运行日志](emulator-run.log) |
| 上一轮 7 项缺陷 | **11 项专项断言通过**；最终隐私修复版 APK 再次通过同一组断言 | [完整复审](ProjectReviewAuditTest.log)、[最终 APK 复验](ProjectReviewAuditTest-post-privacy.log) |
| 两套五格式交付 | **10/10** 同次原件、系统另存、VIEW/SEND 跨 UID 接收回执 SHA-256 一致 | [精确匹配结果](exact-delivery-hashes.json)、[原件与交付文件](delivery/)、[材料交付](MaterialDeliveryTest.log)、[成果交付](ProjectReviewDeliveryTest.log) |
| 文档服务本地合同/隔离 | **20 项通过，1 条第三方测试适配弃用警告** | [项目运行环境日志](document-service-project-runtime.log)、[版本](document-service-runtime-versions.txt) |
| Web 编译 | 类型检查和构建成功；9 条 source-map 定位诊断消除；4 个超过 500 kB 的独立 chunk 保留 | [类型检查](web-typecheck-after.log)、[构建](web-build-after.log)、[修复文件](web-directive-fixes.json)、[剩余逐项说明](remaining-web-build-warnings.json) |
| 原生库打包检查 | 最终 Debug APK 各变体合计 **56/56** native entries 的 ELF load segments 满足 16 KB 对齐 | [逐库记录](packaged-native-alignment.json)、[最终检查](native-audit-final.log)；模拟器为 4 KB，未验证 16 KB runtime |

模拟器为 Android 14/API 34/x86_64、`emulator-5554`、4 KB 页，构建标识见 [设备记录](emulator-build.txt)。设备内部合成来源使用设备时区；报告和宿主日志采用 Asia/Taipei。Debug 不能替代 Mate 50/HarmonyOS、Android 新版本、正式签名或真机迁移验收。

完整批次与最后隐私修复版 APK 分别记录哈希：[完整复审 APK](full-review-apk-hashes.json)、[最终 APK](final-apk-hashes.json)。最后改动只涉及公共日志脱敏与 Web 无效指令；最终 APK 又复验隐私、图库、滑块、外部入口、诊断、导航及 11 项缺陷断言。真实模型记录对应各自明确的一次执行，不冒称在所有 APK 上重复通过。

## 警告分类、去向与修改

每一条初始 Lint 项目都保留原类型、文件及位置，并记录最终去向：[逐条去向](before-warning-destinations.json)、[按类型前后数量](warning-types.md)。42 条剩余项逐条包含影响、具体保留原因、已做检查和后续条件：[可读记录](remaining-warnings.md)、[结构化记录](remaining-warnings.json)。61 条编译器警告也有独立逐项清单：[可读记录](remaining-compiler-warnings.md)、[结构化记录](remaining-compiler-warnings.json)、[早期诊断去向](first-compiler-warning-destinations.json)。

主要修复及验证见 [修改明细](review-changes.md)：通知旧系统兼容、静态 Context 生命周期、图片保存及流关闭、复数资源、原始类型状态、受控滑块、新剪贴板 API、局部弃用 API、空安全/类型、资源/图标与 Gradle DSL。没有增加 baseline、批量 suppression、降低严重级别、删除已支持功能或升级整套依赖。

剩余 Lint 为 27 项精确依赖版本提示、3 项依赖 native 二进制对齐提示、4 项物理 Left/Right 对齐合同、2 项拖拽 scope 的 Modifier 工厂风格、2 项必须保留 commit Boolean 的 KTX 建议，以及私有 CursorWindow、Gradle 版本、真实 `us-central1` 标识误报、存储 free-space 指标各 1 项。版本提示不是漏洞审计结论；没有声称依赖已完成安全审计。

3 条 Aligned16KB 警告来自依赖 AAR。实际 APK 打包的是本地同名对齐库，全部 ELF 检查通过，且项目 `useLegacyPackaging=true` 使用压缩/提取方式。此证据纠正“必然打包不对齐库”的推断，但不消除 Lint 的依赖告警，也不能证明 16 KB 设备 JNI 行为通过。

编译器统计边界：初始增量构建大量 UP-TO-DATE，不能据此声称编译器警告为零。早期 app 编译记录 80 条，另一次其他模块/中间失败编译记录 39 条，它们不是同一次未修改全量基线，**不能简单相加再宣称 119→61**。本轮在部分修复后取得同口径的成功强制全量样本 71 条，继续滑块/剪贴板迁移后成功强制全量为 61 条；后续 ContextUtil 重新编译仍保留同一 AppOps 诊断。14 条测试 API 弃用包含新滑块回归测试；保留 v2 调度器迁移风险，不改断言制造通过。

## 完整复审与新问题

当前支持功能、正常/失败/取消路径、档案隔离、进程恢复、文件交付及未验证边界统一列在 **[35 项完整功能与测试矩阵](feature-test-matrix.md)**。

| 本轮问题 | 处理与最终验证 | 首次证据 |
|---|---|---|
| 共享 workspace DocumentsProvider 仍可被外部解析，缺少学习档案隔离 | Manifest 禁用此此前未开放入口，保留代码/文件；最终不可解析断言通过 | [原失败](workspace-boundary-first-failure.log)、[最终通过](WarningReviewBoundaryTest-post-privacy.log) |
| 复制内容与完整 URL 写入系统日志，异常可能再次泄漏内容 | 只记录固定事件和异常类名，保留正常复制与错误提示；合成标记不进入 helper/System.err，URL 异常分支确实执行 | [原失败](context-privacy-first-failure.log)、[首次修复后通过](context-privacy-final.log)、[强化失败分支最终通过](context-privacy-final-verified.log) |
| 图库保存可能留下空行/流未关闭/未完成写入却记录成功 | 关闭流后发布 pending 行，检查编码/插入/发布，失败删除行；两种输出与同一原件一致、缺失源不留行 | [首次新增回归通过](gallery-first.log)、[最终 APK 再验](WarningFixGalleryTest-post-privacy.log)；原问题来自静态核查，不虚构修复前仪器失败 |
| OAuth 回调关闭服务过早导致响应截断 | 给响应排空保留 grace period；两项 OAuth 单元测试重新通过 | [原 XML](first-jvm/oauth/)、[最终 XML](jvm-full/oauth/) |
| 真实审批组合测试超时且原测试缺阶段证据 | 增加阶段记录、明确合成 QA 档案/会话绑定、终态失败即时断言；保留原断言，不改为跳过或放宽 | [原超时](StudyRealModelTest.log)、[一次定位复验失败](StudyRealModelTest-diagnostic-final.log) |

最后一项的定位复验显示 GUIDED/HINT/CHECK/EXPLAIN 均完成，审批请求的第一条网络调用被服务 HTTP 400 拒绝；不是客户端工具执行后的重复请求。见 [只含状态/时长的网络证据](approval-http-metadata.json)、[异常类型](approval-error-type-metadata.txt)。未导出端点、凭据、请求/响应正文或全量个人诊断。HTTP 400 的具体拒绝原因尚未确定，不能笼统认定“上游故障”，也不能认定审批功能已经修复。

已有本地审批状态机、重复/迟到审批及不确定工具恢复测试通过，不能代替本次真实审批失败。下一步需要该已配置链路的**脱敏服务错误 code/param 和工具合同**，或专用合成 QA 服务环境，以核对服务是否接受当前 ask_user 请求；未擅自修改模型/连接配置，未为失败工具加入自动重试，也未第三次重复发送该审批场景。

## 真实链路与文件证据

- [独立真实四模式](ProjectReviewTeachingTest.log)通过；[合成回答](fixtures/review-real-teaching.md)。审批定位复验的四模式阶段也完成，其 [部分真实记录](fixtures/s1-real-teaching.md)明确不能作为审批通过证据。
- [四轮教学闭环与记忆抽取](LearningLoopRealModelTest.log)通过；[合成记录](fixtures/s3-real-teaching.md)。
- [30 条正反来源与新会话](LearningMemoryRealModelTest.log)通过：20/20 正例来源被覆盖，当前 10 条负例无错误事实；[结构化合成结果](fixtures/s2-real-memory.json)。此结论不外推为所有输入均正确。
- [真实文件/视觉链路](MaterialRealModelTest.log)通过：五格式工具生成、清晰图片数字 7、扫描 PDF 7391、Word 内嵌图 8642，以及包含原图/显式公式的 DOCX 重建；[完整合成记录](fixtures/material-real-model.md)。复杂版式/手写/OCR 正确性不在通过范围。
- 同一记录中的图片生成独立探针明确返回 **FAILED：未配置图片生成模型，没有生成图片**。这是条件支持/未验证，虽然该测试验证了失败反馈且整体通过，不能因此将真实图片生成标为成功。
- 文档服务 [health=ok](document-health.log)，本地 20 项和结果页面通过；[专用合成凭据检查](document-service-precondition.txt)为 False。因此本轮真实提交/轮询/取消/下载解析链路 **未验证**。health 不等于解析器就绪，不读取个人服务凭据替代 QA token。

两套交付原件和目标共 20 个文件保存在 [delivery/](delivery/)，匹配 10 条精确 SOURCE 文件名与 VIEW/SEND 回执，不使用旧样例或按扩展名猜配。辅助旧 collector 生成的 latest-file 汇总不是最终交付判断依据，以 [exact-delivery-hashes.json](exact-delivery-hashes.json) 为准。

21 张当前截图在 [screenshots/](screenshots/)；采集只允许显式命名合成文件且检查本轮修改时间。已人工查看档案切换、DOCX 正文截图，见 [视觉核查记录](visual-inspection.json)。没有把旧截图或跳过的专用富文本样例当作本轮通过证据。

## 保留失败、阻碍和后续条件

| 项目 | 已做检查/当前影响 | 后续条件 |
|---|---|---|
| 真实审批链路：失败 | 首轮 120 秒超时；定位复验四模式完成、审批首请求 HTTP 400；尚无待审批状态 | 脱敏服务错误字段和实际工具兼容合同/专用 QA 环境；不能靠改变用户连接或重试制造通过 |
| workspace：5 项失败 | 4 项缺 `/bin/sh`，1 项 Windows 无符号链接权限；保留真实失败，无 skip/assumption | 原样在 POSIX CI/宿主运行；符号链接需 Linux 或用户明确授权的 Windows 特权/开发模式。逐测试位置见 [环境失败记录](workspace-environment-failures.json) |
| Starlette 测试适配：1 warning | FastAPI 0.142.2/Starlette 1.7.0/httpx 0.28.1/pytest 9.1.1，测试 20 项通过；TestClient 的 httpx 路径被弃用，建议 httpx2 | 等项目锁定兼容适配方案，单独迁移测试 transport 并重跑合同/隔离测试；没有将全局 Python 环境当作项目依赖证据 |
| 图片生成/增强文档真实链路 | 未选图片模型；设备无专用合成服务 token；失败反馈/页面/health 不能代替真实成功 | 提供获授权的 QA 模型配置/档案凭据和健康解析服务，再做实际产物验证 |
| 42 Lint、61 compiler、4 Web 大 chunk | 每项文件/位置、影响、具体原因和已查范围已留存；未关闭检查/阈值 | 按逐项清单进行独立兼容迁移；不要整套升级或删除能力清零 |
| 设备与格式 | API 26/旧图库通知、API 37、Mate 50/HarmonyOS、16 KB runtime、正式签名、两台真机迁移、长期锁屏均未验证；复杂 Office/数学排版不完整 | 对应设备、签名/交付条件与专属样例；本轮 Debug/基础合成格式不能外推 |
| 上游扩展与后置能力 | 协议单测覆盖部分搜索/语音；全部远程提供商、翻译/JS/高级设置 UI 未逐一 E2E；Skills/视频/自动同步未开放 | 专属配置与功能开放计划及独立边界验收 |

首次失败均保留，包括缺临时合成 Firebase 配置、复数类别/Tooltip/Gradle DSL 的中间编译与 Lint 失败、workspace/OAuth 失败、外部入口失败、隐私失败和真实审批失败。旧 `incomplete-stale-lint-copy.xml` 明确是失败构建期间误复制的旧报告，**不作为通过证据**；最终结果仅使用后续成功任务的当前 XML。失败记录见各日志及 test-summary.json，后续成功不删除首次失败。

最终清理仅移除本轮临时创建且与仓库示例哈希一致的合成 google-services.json，见 [清理记录](temporary-config-cleanup.txt)；没有修改用户连接凭据。当前变更文件的 [哈希快照](current-workspace-file-hashes.json)、[最终状态](final-status.txt)、[差异检查](diff-check-final.log) 与 [证据哈希清单](evidence-manifest.json) 留存；差异检查通过，仅有既有 CRLF 提示。历史 QA 目录保持原样。
