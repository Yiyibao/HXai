# 完整审查失败项修复与复验

日期：2026-10-08，当前工作区。基于 [修复前完整审查](../full-review-20261008/report.md)，直接修复生产实现与过时测试；保留用户原有修改，没有提交或发布。

**最终结果：7 项已确认的功能问题已修复；Lint 0 errors；317 项 app JVM 测试通过；模拟器最新逐例 78 通过、0 失败、2 跳过。真实模型四模式与审批链路复验通过。**

## 功能修复

| 问题 | 修复 | 实际复验 |
|---|---|---|
| P1 成果预览泄漏上一档案正文 | 预览文本、图片和操作状态按档案及成果绑定；档案切换立即清空，旧读取任务取消；失去访问权禁用操作 | `artifactPreviewClearsContentAfterLearnerSwitch` 通过 |
| P1 固定公式冒充识别结果 | 删除固定公式和默认候选；显示当前材料实际提取片段及覆盖限制；人工修正与待核对状态持久化，并绑定原文件 SHA-256 | 无公式材料不产生虚构公式；确认按钮初始禁用；修正重开保留；错误来源哈希拒绝；稍后处理状态保留，均通过 |
| P1 缺失或损坏成果显示范文 | 删除默认数学范本；显示加载、缺失、失败等真实状态 | 缺失成果提示明确、无范文、操作禁用，通过 |
| P2 二进制成果乱码 | 复用有界格式读取器提取 Office/PDF 文本；严格 UTF-8 读取文本；图片按图片显示，Markdown 仍使用公式渲染 | DOCX 显示真实正文；底层五格式读写与保真回归通过 |
| P2 材料筛选状态不匹配 | 筛选使用真实 RUNNING/SUCCEEDED 合同；成果登记按生成状态判断，徽标显示中文状态 | 已解析筛选保留成功材料，通过 |
| P2 打开/分享失败声称成功 | 异常明确报告失败；成功调起仅说明已打开选择器，不声称接收方成功；状态横幅按实际任务状态显示 | 损坏文件打开显示失败；五格式实际打开与分享通过 |
| P2 另存缺少目标校验 | 材料列表与成果预览共用 `MaterialFiles.saveVerified`；关闭输出流后重开目标、计算 SHA-256、匹配原文件后才提示成功；取消与档案变化不另存错误文件 | 两套五格式系统 DocumentsUI 与跨 UID 回执测试通过，应用和测试侧均检查目标文件 |

主要生产文件：[成果预览](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt)、[材料页面](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialPage.kt)、[材料存储及交付](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialFiles.kt)、[文件卡片](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyFileCard.kt)、[核对弹层](../../client/app/src/main/java/me/rerere/rikkahub/feature/studychat/ui/StudySymbolCheckSheet.kt)。新增元数据字段有默认值，旧材料仍可读取；人工修正不被表述为自动识别或数学正确性认证。

## Lint 与构建

- **Lint 从 45 errors 降为 0 errors**，没有关闭检查、修改严重级别或添加 baseline。保留 **340 warnings、3 hints**。
- 37 处 Compose 资源读取改为 `LocalResources.current`，3 处语言格式读取使用可观察的配置，Activity 使用 `LocalActivity`。
- 补齐缺失资源的英文回退，保留已有翻译；修正本机 SDK properties 路径转义。
- Debug 应用与测试 APK 构建成功，**317 项 app JVM 测试 0 失败/错误**。其他模块前轮 259 项通过，本轮未因仅修改 app 而重复运行，不把它们算成本轮新执行。
- [构建、单元测试及 Lint 日志](build-tests-lint.log)、[Lint XML](lint-results-debug.xml)、[最终测试 APK 构建](test-build-final.log)。构建使用项目已有的合成 Firebase 配置，结束后清理，未加入真实凭据。

## 模拟器复验

环境：Android 14 / API 34 / x86_64，`emulator-5554`，保留原有配置，使用合成 QA 档案、问题和文件。

- [缺陷专项 11 项](ProjectReviewAuditTest.log)全部通过。
- 原有导航、记忆管理及材料测试已改为当前实际页面入口，保留来源回看、纠正删除、分页尾部、分享、系统另存和档案隔离断言。
- [分页最终复验](MaterialPaginationTest-final.log)、[原五格式交付最终复验](MaterialDeliveryTest-final.log)、[成果预览五格式交付最终复验](ProjectReviewDeliveryTest-final.log)。
- [其他逐类回归与进程重启](emulator-run.log)、[原始批次结果](emulator-results.json)、[按最新执行去重的逐例状态](test-summary.json)、[独立目标文件哈希](saved-file-hashes.json)、[跨 UID 回执](delivery-receipts.txt)、[截图](screenshots/)。
- 最终 78 项通过、0 失败。2 项跳过是未启用的专用富文本样例与记忆重启测试的默认占位模式；后者另有分进程 seed/force-stop/verify 两阶段通过证据。未把跳过项算作通过。
- 最新每种格式的系统另存文件均与最后一次对应回执 SHA-256 匹配，5/5 通过。不同复跑使用不同 Office 样例或 ZIP 时间戳，不能把早期文件与最后一次原件混比；[全部合成文件原始哈希](all-saved-file-hashes.json)保留为独立记录。
- 第一批复验的分页测试误选横向表格滚动区；改为垂直滚动区后通过。交付测试在系统窗口关闭期间遇到 Espresso 布局重入；增加系统窗口空闲等待后原断言通过。失败的原始日志仍保留，最终日志另存，不删除或伪装为成功。

## 外部服务与剩余边界

原真实模型测试复验 **通过，耗时约 56 秒**，包括四种教学模式、真实审批拒绝、重复提交及完成后的迟到审批不改变消息，见 [日志](real-model-final.log)与[合成教学记录](fixtures/s1-real-teaching.md)。历史 HTTP 524 是外部服务超时；复跑成功不能证明服务永不超时。本轮没有擅自更改用户连接配置、放宽成功断言或加入可能重复执行工具的自动重试。

340 个 Lint 警告仍包括上游弃用、静态分析建议和 `libtermux.so` 的 16 KB 对齐风险。Mate 50/HarmonyOS、16 KB 设备、正式签名、长期后台及复杂格式仍需单独验收。图片生成与增强文档服务的完整链路仍取决于对应配置与服务，不能从本轮界面修复推定通过。

README 已校正材料、成果、备份及伙伴实现状态与旧阶段说明的矛盾。当前提交范围没有模型协议或服务端逻辑变更。

复验 APK：`client/app/build/outputs/apk/debug/app-x86_64-debug.apk`，SHA-256 `0A62F221FC225BB99A47066C0B323FB4CDAD02E0309536B7EB8C998482E903B1`。当前为 Debug，已覆盖安装至模拟器。
