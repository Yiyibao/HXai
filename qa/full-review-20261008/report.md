# MathStudyAgent 当前工作区完整审查与模拟器验证

本报告保留修复前发现与原始失败证据。后续修复及最终验证见 [修复报告](../fixes-20261008/report.md)，请以该记录判断当前失败项是否已解决。

审查日期：2026-10-08（Asia/Taipei）。审查对象是当前工作区，包含用户原有的大量未提交实现，不是 Git HEAD 或旧验收报告。新增内容仅为审查测试、复验脚本及证据，未修改生产功能、提交或发布。

**结论：核心聊天、学习记忆、教学闭环、文件读写、加密备份与诊断已有可运行实现；当前版本不能认定为全功能验收通过。成果预览切换学习者后残留上一档案正文，材料核对及预览有虚假内容，Lint 门禁失败，若干外部服务能力仍有验证边界。**

## 环境与证据规则

- Android 14 / API 34 / x86_64，`emulator-5554`，1080×2340，4 KB 内存页；设备时区 GMT。本文时间采用 Asia/Taipei，设备内部合成来源时间使用设备时区。
- 包名 `org.mathstudy.agent.debug`，版本 `0.1.0-dev`；重新构建、覆盖安装当前 Debug APK 和测试 APK，保留原有配置。
- 本轮使用专属 QA 档案、合成问题与材料；真实模型使用设备已有的加密配置。测试结束恢复原档案与设置。QA 缓存和系统另存样例保留作证据。
- 将 JVM、Android 仪器测试、实际页面操作、真实远程模型、系统文件交付分别记录。假服务测试不能证明远程提供商可用；JUnit `skipped` 不算通过。
- 本轮新运行证据在本目录；不引用旧报告作为新通过证据。导出缓存时核对修改时间，只收集本轮产生的指定测试文件，避免混入旧样例。

## 本轮检查汇总

| 检查 | 结果 | 证据 |
|---|---|---|
| app / ai / search JVM | 317 / 198 / 16 项，均无失败；ai、search 强制重新执行 | [核心构建](build-core.log)、[强制复跑](jvm-rerun.log)、[原始 XML](jvm/) |
| speech / common / document JVM | 43 / 1 / 1 项，均无失败 | [补充检查日志](lint-online.log)、[原始 XML](jvm/) |
| JVM 合计 | 576 项，0 失败／错误；部分上游模块包含示例测试，数量不代表端到端覆盖 | [结构化汇总](test-summary.json) |
| 服务端本地合同／隔离测试 | 20 项通过 | [日志](server-tests.log)、[XML](server-tests.xml) |
| Android 仪器测试最新逐例状态 | 71 通过、12 失败、2 跳过；保留不同测试方法及 seed／verify 阶段 | [逐例及所有复跑记录](test-summary.json) |
| Debug 构建／安装 | x86_64、arm64、universal、测试 APK 构建成功，模拟器安装成功 | [核心构建](build-core.log)、[最终测试构建](audit-long-build.log) |
| Android Lint | **失败：45 errors、340 warnings、3 hints** | [文本报告](lint-results-debug.txt)、[XML](lint-results-debug.xml) |
| 差异空白检查 | 未发现空白错误；存在既有 CRLF 提示 | [日志](diff-check.log) |
| 五格式系统交付 | MD、DOCX、XLSX、PPTX、PDF 实际打开／另存／分享；另存与接收字节 SHA-256 均匹配 | [仪器日志](ProjectReviewDeliveryTest.log)、[接收回执](delivery-receipts.txt)、[另存文件哈希](saved-file-hashes.json) |

首次离线检查缺少公开依赖；补充联网运行后，语音、公共、文档模块测试完成，Lint 产生完整报告。首次服务端测试因沙箱临时目录不可用而未执行，换用本机隔离临时目录后 20 项通过。环境问题与产品问题分开统计。

12 个最新失败中，6 个为本轮正确性断言复现的产品缺陷，5 个为旧测试仍依赖过时入口／文案，1 个为原真实模型测试超时。相同功能的当前页面替代测试与独立四模式真实测试已通过，但不覆盖或抹除旧失败。2 个跳过分别为未开启的富文本专用样例与需分进程执行的记忆持久化占位项；后者另有 seed／force-stop／verify 通过证据。另存缺少应用侧目标校验是第 7 项静态确认的问题，正常交付实测通过。

## 目前支持的功能及逐功能结果

| 功能 | 当前实现与边界 | 本轮模拟器／真实服务结果 |
|---|---|---|
| 四主入口 | 聊天、材料、记忆、成果，以及对应子页面 | **通过**，正常及 1.3 倍字体下当前标签与导航实际点击验证 |
| 服务提供商与模型配置 | OpenAI／Responses、Google、Claude 底层协议；配置、连接检查、模型管理及模型选择持久化 | **当前页面通过**：本机 HTTP 模型列表请求、未保存草稿保护、模型选择失败不关闭；未逐个调用所有远程厂商 |
| 四种教学模式 | 引导、提示、步骤检查、完整讲解，模式持久化及分支保留 | **通过**：四轮真实模型均 COMPLETED；定积分原函数及结果核对正确 |
| Markdown／数学回答 | Markdown、LaTeX、表格等富文本渲染器，中文教学输出 | 真实回答内容通过；本轮不声称所有复杂公式和长公式显示均验收 |
| 流式聊天、连续追问与停止 | 部分内容保留、手动重试、停止状态、持久化队列 | **通过**：故障测试 16 项，含停止、超时、静默 EOF、连接重置、401／429、飞行模式、Wi-Fi／蜂窝切换 |
| 工具与审批恢复 | 检查点、不确定执行不自动重放、恢复后继续审批、重复消息控制 | **假服务与本机实测通过**；真实审批组本轮遇到超时，不能据此宣布真实审批链路全通过 |
| 聊天编辑与分支 | 编辑后保全旧路径、切换分支、教学模式继承 | **通过**：3 项当前聊天合同仪器测试 |
| 进程结束后的聊天恢复 | 保留消息 ID、部分回答、队列、已完成／不确定工具状态 | **通过**：seed／force-stop／verify 分属 PID 5302、5390 |
| 学习者档案隔离 | 聊天、记忆、材料按固定档案归属；有效档案切换 | **部分失败**：仓储、聊天及记忆隔离通过；成果预览切换档案后仍显示旧正文，专项复现失败 |
| API 凭据保护 | Android Keystore、AES-GCM，异常恢复及旧配置迁移 | **通过**：10 项仪器测试，含篡改、缺密钥、迁移失败、冷加载及备份排除凭据 |
| 自动长期记忆 | 背景、偏好、进度、困难、表现、复习建议；增量抽取、来源校验与冲突处理 | **通过**：30 条真实正反样例；20/20 正例来源被覆盖，负例未产生错误事实（本次样例范围） |
| 记忆跨会话使用 | 相关记忆检索、附件不作为本人表现、模型断言不作为用户事实 | **通过**：真实新会话调用与本机持久化测试 |
| 记忆管理页面 | 来源回看、搜索、人工更正、删除确认与删除抑制 | **当前页面通过**：回看真实合成会话、更正、取消／确认删除、档案隔离；并发版本冲突与草稿恢复也通过 |
| 记忆持久化与后台可靠性 | Room、租约、重试、每日 20 次预算、任务压缩、周期维护 | **仓储通过**：重启、预算、租约与压缩；未验证整夜锁屏及厂商后台限制 |
| 教学闭环 | 第一处错误→分级提示→重答→独立尝试→待复习建议 | **通过**：四轮真实链式求导及实际记忆抽取；WITH_HINT／INDEPENDENT_ATTEMPT／READ_ONLY 区分正确 |
| 材料导入与档案私有库 | DOCX／XLSX／PPTX／PDF／UTF-8 文本／PNG／JPEG／WebP；30 MiB 单文件限制、状态、哈希、取消与失败 | **底层通过**：4 项文件仪器测试；原文件损坏及跨档案读取明确失败 |
| Word 输入／输出 | 基础正文与表格、常见 OMML、包内图片；基本可编辑输出 | **本机格式与保真通过**；复杂版式、复杂公式及全部 Word 特性未保证 |
| Excel 输入／输出 | 工作表名称／顺序、坐标、数值／布尔类型、公式与缓存值；基本单表输出 | **本机格式与保真通过**；不执行公式，不承诺完整样式／图表保真 |
| PPT 输入／输出 | 逐页文字读取、基本可编辑文字幻灯片 | **本机读写和实际交付通过**；图片、图表、动画与备注未完整支持 |
| PDF 输入／输出 | 文本范围、指定物理页渲染、扫描候选；分页文字 PDF 与跨页表格 | **本机读写、页范围、表格尾部通过**；不是专业数学排版或全自动正确 OCR |
| 材料文本预览／分页工具 | 有界提取与范围反馈、工具分页至 24000 字符、页面分页 | **通过**：短文本实际预览及长文本滚动至末尾标记；[最终日志](long-and-isolation-final.log) |
| 材料列表筛选与状态徽标 | “处理中／已解析／已登记”筛选和状态反馈 | **失败**：UI 使用的状态名与仓储合同不一致 |
| 材料关键符号核对 | 核对弹层、候选项、手动输入 | **失败**：实际入口把固定示例当成当前材料已识别公式；确认结果只更新页面字符串 |
| 文件成果生成 | 工具创建真实 MD／PDF／DOCX／XLSX／PPTX；成功校验、幂等键、档案归属 | **本机生成及五格式交付通过**；真实 Agent 生成五格式通过，首轮 HTTP 524 失败保留，完整追加复验耗时约 297 秒 |
| 成果预览 | 当前文件详情及打开／另存／分享 | **部分失败**：档案切换残留旧正文、DOCX 显示 ZIP 乱码、缺失文件显示默认数学范本；异常状态反馈不真实 |
| 图片视觉理解 | 图片原图、扫描 PDF 指定页、Word 内嵌图进入已配置视觉模型 | **通过**：实际读出图片数字 7、扫描 PDF 的 7391、Word 图片的 8642，并重建带原图与公式的 DOCX；只适用于本次清晰合成样例 |
| 图片生成 | 配置图片生成模型后，实际最终图像解码、尺寸校验与 PNG 落盘 | **条件能力**：无本轮真实生成成功证据；不能标为已验收 |
| 自建增强文档服务 | HTTPS、独立档案凭据、指定 1–5 页、提交／轮询／取消／下载校验、结果范围及原页回看 | **结果页面通过、服务端 20 项通过、公开 health=ok**；当前模拟器无专用合成服务凭据，本轮未重新提交真实解析任务 |
| 加密 `.msab` 备份／恢复 | 密码验证、完整快照、预览、启动前恢复、回滚、可移植记忆指纹、删除约束合并 | **通过**：3 项可靠性＋1 项密码页面测试；跨设备恢复为本机隔离目标上下文模拟，不等于两台真机迁移 |
| 诊断与反馈 | 脱敏事件、最近崩溃记录、反馈说明、ZIP 保存／分享、清除确认 | **通过**：4 项数据／网络测试＋2 项真实页面与交付测试；未制造系统 ANR 或真实进程崩溃 |
| 页芽伙伴与反馈 | 状态映射、减少动画、前台／可见性策略、成功反馈消费记录 | 当前导航与伙伴显示可见；**反馈跨进程不重播通过**；未逐状态测量全部动画 |
| 上游扩展能力 | 搜索、TTS／ASR、翻译、JS、图表、助手高级设置、提示注入、导入等代码仍在 | **保留／受配置限制**：相关 JVM 协议测试通过，但未逐个实测所有远程语音／搜索／翻译服务，不列为全部端到端验收通过 |
| 明确未开放项 | Web 服务、共享 workspace／Shell／MCP 工具、旧全局文件浏览；Skills 优化后置，视频与 MathStudy 自动同步后续 | 不作为当前学习 Agent 已支持功能；配置入口存在不等于聊天工具实际开放 |

格式支持是有边界的合同，不能从上述样例外推成“所有 Office 文件完整保真”。旧 DOC／XLS／PPT 可保存但解析拒绝，要求转换；完整专业数学排版、自动正确性认证、正式掌握度／计划自动同步不在当前实现中。

## 可复现的产品问题

### 1. P1：成果预览切换档案后继续显示上一学习者正文

打开档案 A 的 Markdown 成果后，通过实际档案切换接口切换到 B。成果已因归属检查不可用，标题退回“成果预览”，但正文仍显示 A 的 `AUDIT_UNIQUE_REAL_CONTENT`。`contentText` 使用无档案／文件键的 `remember`，`LaunchedEffect(file)` 在 `file == null` 时不清空缓存。本轮使用合成材料复现，无需绕过仓储授权。

- 位置：[StudyArtifactPreviewPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt#L55)，55–70。
- 证据：[截图](screenshots/artifact-owner-switch.png)、[最终日志](long-and-isolation-final.log)，`artifactPreviewClearsContentAfterLearnerSwitch`。
- 建议：以档案及成果 ID 为键管理预览状态；切换或失去文件访问权时立即清空旧内容，并取消旧读取任务。优先修复此隐私隔离缺陷。

### 2. P1：核对符号把固定范例冒充材料识别结果

纯文本仅含 `AUDIT_UNIQUE_REAL_CONTENT`，点击“核对符号”后仍显示“在材料中检测到关键公式”与固定 `f'(x)=2\sin(x)\cos(x)` 候选。未读取实际原图、页号或识别结果。确认／稍后处理只修改页面 `status`，没有持久化核对数据。

- 位置：[MaterialPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialPage.kt#L273)，273–290。
- 证据：[截图](screenshots/invented-symbol.png)、[审查测试](ProjectReviewAuditTest.log)，`unrelatedTextDoesNotInventRecognizedFormula`。
- 建议：接入真实来源与候选后开放；未识别到内容时明确空状态，并持久化用户核对结果。不能保留“检测到”表述和固定示例。

### 3. P1：缺失／损坏成果仍显示无关数学范本

访问不存在的成果 ID，会展示“函数定义域与解析推导”完整范文。相同后备内容也用于读取异常。用户无法区分真实成果和示例，可能把无关结论当成自己的处理结果。

- 位置：[StudyArtifactPreviewPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt#L55)，55–95。
- 证据：[截图](screenshots/missing-artifact.png)、`missingArtifactDoesNotShowUnrelatedWorkedExample`。
- 建议：分别显示加载、缺失、校验失败和不支持预览；禁止自动填充教学范文。

### 4. P2：Office／PDF／图片成果用 `readText()` 预览

所有扩展名都读取原始字节并交给 Markdown 渲染。DOCX 的本轮实测为可见 `PK` ZIP 头和乱码，无法阅读真实正文。XLSX／PPTX 同样是 ZIP，PDF／PNG 也不应这样预览。

- 位置：[StudyArtifactPreviewPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt#L60)，60–66。
- 证据：[DOCX 截图](screenshots/docx-preview.png)、`docxPreviewReadsDocumentInsteadOfZipBytes`。
- 建议：文本严格解码；Office 使用已有有界读取器；PDF／图片按专用预览显示或明确交给外部查看器。

### 5. P2：成功解析的材料被“已解析”筛选隐藏

仓储保存 `RUNNING`／`SUCCEEDED`／`FAILED` 等值，而 UI 过滤 `PROCESSING`／`PROCESSED`／`SAVED`。本轮成功提取文件在全部列表可见，点击“已解析”立即消失；徽标也直接暴露 `SUCCEEDED`／`NOT_STARTED`。

- 位置：[MaterialPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialPage.kt#L60)，60–64；[StudyFileCard.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyFileCard.kt#L106)，106–120。
- 证据：[过滤前](screenshots/filter-before.png)、[过滤后](screenshots/filter-after.png)、`parsedMaterialRemainsVisibleWhenFiltered`。
- 建议：复用统一状态枚举与显示映射；成果登记按文件任务成功状态判断。

### 6. P2：成果打开／分享异常被报成已调起系统功能

原文件被追加字节导致校验失败后，点击“打开”仍显示“已请求系统打开外部查看器”。`catch` 分支填写了成功语义的提示，分享分支也有同样问题。横幅又仅检查 `output`，未区分 FAILED／RUNNING。

- 位置：[StudyArtifactPreviewPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt#L215)，215–228、259–272、153。
- 证据：[损坏文件操作截图](screenshots/corrupt-open.png)、`corruptedArtifactOpenReportsFailure`。
- 建议：异常显示真实失败与可操作原因；成功提示只在成功调起后设置，状态横幅使用实际任务状态。

### 7. P2：成果另存提示声称已校验，但没有重读目标 URI

预览页面在 `copyTo()` 完成后直接显示“已成功另存并完成校验”，没有读取目标流或比对哈希。材料列表和诊断路径具备重读校验，预览路径没有继承。

- 位置：[StudyArtifactPreviewPage.kt](../../client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt#L91)，91–103。
- 本轮正常路径的五格式文件已由测试侧独立读取并匹配哈希；这证明本次交付正确，不能证明应用实现了自己的目标校验。
- 建议：复用保存与重读校验函数；校验前不能声称“完成校验”。

## 工程质量与交付问题

- **Lint 门禁失败。** 45 个错误分为 LocalContextGetResourceValueCall 37、NonObservableLocale 3、MissingTranslation 3、ContextCastToActivity 1、PropertyEscape 1。它们包含上游遗留问题与当前修改，未全部证明为运行崩溃，不应通过关闭门禁掩盖。
- **16 KB 页兼容尚未验证。** 3 个 Aligned16KB 警告指向 `libtermux.so`；当前模拟器是 4 KB，不提供 16 KB 设备兼容证据。是否能移除未使用的终端依赖需另行检查。
- **旧验收脚本漂移。** StudyNavigationTest 仍期待“材料库尚未开放”；若干材料测试仍找“查看提取内容与范围”“另存”；记忆测试仍找“学习进展”。当前界面匹配的替代测试分别通过，旧测试应更新并继续保留实际断言。
- **真实远程稳定性存在本轮失败证据。** 初次真实教学／材料测试各出现失败；脱敏诊断记录两次 HTTP 524，耗时约 125 秒。随后四教学模式、教学闭环及记忆测试通过。不能因复跑通过而删除首次失败或宣称不存在中转可靠性问题。
- **材料真实追加复验通过。** [完整仪器日志](material-real-retry.log)在约 297 秒内完成，包含多格式附件读取和实际成果生成、图片视觉、扫描 PDF 指定页工具、扫描内容保存为 Markdown、Word 内嵌图片／OMML 公式读取及 DOCX 重建。[完整合成模型记录](fixtures/material-real-model.md)提供原始回答。图片生成独立探针返回未配置，未产出成功图片。
- **说明文档存在阶段漂移。** README 上半部写材料与成果尚未开放、S5 待推进，下半部又记录这些实现已完成；动态页芽代码也超出旧“静态伙伴”文字。建议依据本轮矩阵重写“实现／通过／待验收”状态。
- **全局文件能力需继续保持边界清晰。** 学习聊天禁用共享 workspace／MCP，设置文件页也返回禁用说明；Manifest 仍注册上游系统 WorkspaceDocumentsProvider，源码查询全部 workspace。当前私有材料库不是该 provider，本轮未构造历史 workspace 的跨档案端到端攻击，不据此断言已发生泄漏；发布前应核对是否删除这一不使用入口。

## 尚未验收的范围

Mate 50 / HarmonyOS 真机、正式签名及 Release、第二台真实设备恢复、16 KB 环境、系统 ANR／原生崩溃、长时间锁屏／后台／低电量、全部第三方 Office 与分享客户端、所有提供商和模型组合、复杂及异常 Office 文档、完整可编辑数学版式、真实图片生成服务、增强解析服务本轮重新提交任务，以及所有上游语音／搜索／翻译端点。

这些项目没有被标为通过。本轮文档服务公开 health=ok 只证明 HTTP 服务可达，不等于 MinerU 解析就绪或任务成功。

## 复验与文件

- [批量模拟器脚本](../../scripts/review_project_20261008.ps1)：逐类运行原有回归，失败也保存结果。
- [证据采集脚本](../../scripts/collect_project_review.py)：指定合成文件、时间校验、原始 XML、逐例状态与系统另存文件哈希。
- `ProjectReviewAuditTest`：材料筛选／符号／预览／异常状态的正确性断言，已知缺陷故意保留失败。
- `ProjectReviewDeliveryTest`：与当前成果导航一致的五格式真实交付。
- `ProjectReviewTeachingTest`：四模式独立真实模型测试；只有显式 `realModel=true` 才调用远程。
- [完整逐例汇总](test-summary.json)、[原始批次结果](emulator-results.json)、[截图目录](screenshots/)、[真实合成记录](fixtures/)。

审查包不含 API 密钥、完整配置、用户聊天数据库或真实个人材料。未删除用户原有修改、未创建发布包、未进行正式部署。
