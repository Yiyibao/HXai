# MathStudyAgent 全面审查（2026-10-10）

审查对象为 `D:/Office/Study/code/MathStudyAgent` 的当前工作区，包括尚未提交的源码。以通用助手、Chat-first 的当前需求为准，不把 README 的历史批次验收等同于本次验证。没有改动业务代码、调用真实模型、上传用户材料或操作远端部署。

结论：确认 **5 项可行动问题：3 项 P1、2 项 P2**。既有测试通过，但新增界面探针复现了详情数据残留和预览错误，因此不能据此宣布全项目无缺陷。P1 应优先修复；未发现可在本次证据范围内确认的 P0。

## 1. [P1] “使用记忆”关闭后仍向聊天模型发送已有记忆

位置：[ChatService.kt:842](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:842)，[LearningMemoryStore.kt:209](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/memory/LearningMemoryStore.kt:209)，[memories_page.dart:102](/D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/memories_page.dart:102)。

界面把开关标为“使用记忆”；关闭操作只设置档案的 `memoryEnabled=false`。这个标志控制后台抽取和入队，却没有控制 `handleMessageComplete` 的记忆检索：它仍无条件调用 `learningMemories.context(...)`。该方法也没有读取启用状态，后续 GenerationLoop 只要收到非空记忆就追加到系统提示。因此已有 BACKGROUND/PREFERENCE 等记录仍能在关闭后的新聊天中发送给模型。

复现条件：当前档案已有可检索记忆 → 关闭“使用记忆” → 发起新会话。根据真实调用链，已有记忆仍会被注入；这是源码调用链确认，本轮没有使用真实外部模型抓取请求。现有 toggle 回归覆盖持久化，没有证明关闭后的请求不含记忆。

建议：明确区分“自动抽取”和“使用已有记忆”的语义。如果保留当前“使用记忆”产品行为，应在请求构造前按会话所属档案检查开关，关闭时不检索、不注入；补充捕获模型请求的集成回归。删除抑制机制仍需保留。

## 2. [P1] 解析上传回执丢失后无法取消，批任务取消后还可能恢复上传

位置：[DocumentServiceRepository.kt:236](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceRepository.kt:236)，[DocumentServiceRepository.kt:135](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceRepository.kt:135)，[StudyHostFacade.kt:330](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt:330)。

提交前已保存本地任务 ID，但远端接受请求、客户端未收到响应时，任务会保持 `SUBMITTING/remoteId=null`。此时单任务取消直接抛出“提交尚未确认，请先重试提交再取消”，没有持久化取消意图。批取消只处理 `remoteId!=null` 的子任务，遗漏这类未确认任务。

批任务的本地 `cancelled=true` 也不能阻止首批恢复：`syncChatDocuments` 在检查批取消状态之前，先对绑定的 `SUBMITTING` 首批执行 `resumeSubmission`。因此用户已取消的批任务仍可能重新发送原文件；后面的 `advanceBatch` 才会拒绝续批。幂等键可防止重复排队，但不能阻止再次上传文件或让服务器停止已接受任务。

证据：[contract-probes.log](contract-probes.log) 使用真实 Store 确认“已接受但客户端可无回执”的队列状态，并验证服务端已经具备按 request key 取消的基础能力；客户端取消缺口及批恢复顺序由源码确认。Office 转换仓储已经使用持久化 `cancelRequested` 和 request-key 取消，可作为解析任务的实现参考。

建议：解析任务也保存取消意图；为已知本地幂等键提供取消/查询接口，无需重新上传即可取消；批取消覆盖所有子任务；恢复循环在任何网络上传前检查整个批次取消状态。补充丢失回执、离线取消、重启恢复三种回归。

## 3. [P1] 详情页以路由快照兜底，绕过当前档案及删除后的显示边界

位置：[file_detail_page.dart:21](/D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/file_detail_page.dart:21)，[memory_detail_page.dart:95](/D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/memory_detail_page.dart:95)，[doc_task_detail_page.dart:50](/D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/doc_task_detail_page.dart:50)。编辑记忆和符号核对页也直接持有路由 DTO。

这些页面在当前档案列表没有找到目标时，会使用 `route extra` 中的旧对象继续渲染。列表已经完成隔离，不代表详情页也隔离：旧档案 DTO 留在页面或恢复路径中时，当前档案查不到它反而触发旧数据兜底。删除或恢复后同样会重新显示已经不存在的内容。

动态证据：新增探针分别把 A 档案文件传入 B 档案页面，以及把已删除记忆传入当前空记忆列表。两个安全断言均失败，页面仍显示 `A-private-file.txt` 和 `deleted-private-content`。这证明显示缺陷；本轮没有证明能绕过 Kotlin 的所有权检查读写其他档案文件，不能把它扩大为后端跨档案读写漏洞。

建议：路由只传 ID，并绑定来源 profile/epoch；以当前档案查询结果为唯一显示依据，加载中显示占位，不存在/已删除时显示不可用；编辑器在档案/epoch 变化时撤销旧内容和操作。需要测试返回栈、恢复备份及迟到响应。

## 4. [P2] 服务端允许的大解析块无法通过工具分页读取

位置：[DocumentServiceTools.kt:67](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceTools.kt:67)，[api/output.py:76](/D:/Office/Study/code/MathStudyAgent/server/document-service/api/output.py:76)。

服务端及客户端文档校验约束总正文不超过 1,000,000 字符，却没有约束单块必须低于 24,000 字符。`read_material_parse_blocks` 只按整块计入 24,000 字符预算；首块超限时返回错误，没有字符分页参数。即使把 count 降为 1，仍无法读取这个块，也没有拿到用于继续读取该块的游标。

证据：[probe_server_contract.py](probe_server_contract.py) 构造单个 24,001 字符的 table_body，实际 `validate_output` 接受并发布成功；客户端筛选条件必然排除该块。大表格或长识别段落会出现“解析和下载成功，但助手读不到正文”的断链。本轮为合同边界验证，没有假称跑过真实 MinerU 大表格解析。

建议：增加块内字符 offset/长度和稳定游标，或在服务端规范化时拆分大块并保留来源关联；同时计算 LaTeX/元数据的响应大小。不能只允许模型跳过该块，因为这会丢失全文处理要求。

## 5. [P2] 成果预览把处理状态当成文档正文

位置：[StudyHostFacade.kt:941](/D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt:941)，[artifact_preview_page.dart:105](/D:/Office/Study/code/MathStudyAgent/client/flutter_ui/lib/pages/artifact_preview_page.dart:105)。

`getArtifacts` 对所有格式设置 `previewContent=MaterialFile.detail`。detail 是“操作已完成”“需核对排版”等状态或限制说明，并非文件内容；前端只要它非空就以 Markdown 正文渲染，同时跳过“此格式请使用系统应用查看完整内容”的提示。结果是页面名为成果预览，却没有预览实际成果，用户容易误以为看到的文字就是文件内容。

动态证据：第三个探针传入成功 DOCX 的真实 DTO 结构和处理状态文本，期望出现系统打开提示，实际不存在。结合后端赋值确认这是生产数据路径，不只是构造夹具问题。

建议：将 `statusDetail` 与 `previewContent` 分离。能安全提取/渲染的格式提供实际内容并注明范围；其他格式清楚显示状态和系统打开入口，不把状态占用正文区域。

## 本次测试和审查范围

| 验证 | 本次结果 | 证据 |
|---|---|---|
| 12 个模块 JVM 单元测试，强制重新执行 | 736 通过，1 跳过，0 失败 | [模块统计](jvm-summary.json)、[主模块日志](../review-20261010-jvm-fresh.log)、[其他模块日志](../review-20261010-jvm-other.log) |
| Flutter 完整既有测试 | 218 通过 | [日志](../review-20261010-flutter.log) |
| 文档服务 pytest | 46 通过，1 条依赖弃用提示 | [日志](../review-20261010-server.log) |
| Android Lint Debug | 0 错误、56 警告 | [本次日志](../review-20261010-gradle.log)、[Lint 报告](/D:/Office/Study/code/MathStudyAgent/client/app/build/reports/lint-results-debug.html) |
| Flutter analyze | 0 error、0 warning、150 info；因 info 返回退出码 1 | [日志](../review-20261010-analyze.log) |
| 新增详情/预览审查探针 | 3 个安全断言失败，均复现问题 | [代码](repro_details.dart)、[失败日志](detail-probes.log) |
| 服务端边界探针 | 成功运行，确认取消基础能力和大块合同不匹配 | [代码](probe_server_contract.py)、[日志](contract-probes.log) |

JVM 跳过项是 workspace Windows 环境的符号链接权限用例。测试统计包括 common/document/web/material3 的占位测试，不能据总数推断这些模块已具备完整行为覆盖。新增失败探针只保留于本报告目录，临时放入 Flutter test 目录的文件已移除；业务源码和既有测试未修改。

静态审查覆盖 Flutter 状态/桥接/详情/聊天、Kotlin 生成与上下文预算、档案授权、记忆提取和删除抑制、材料持久化与编辑、文档任务及自动续聊、备份恢复入口、服务端身份/队列/发布/清理/容器调度以及测试脚本。Web UI 是当前关闭功能，本次未运行 React 构建；上游资源、UI 细节和所有提供商协议没有逐行穷尽审计。

没有重新构建或安装 APK，没有执行 Android instrumentation、Mate 50 真机、签名 Release、跨设备迁移、真实模型或真实 MinerU/LibreOffice 联合验收。已有相关历史报告只能作为背景，不能计入本次通过。依赖在线漏洞库也未检查；本报告是当前源码、离线测试和定向复现的审查结果。

## 修复顺序

1. 修复取消意图和取消后恢复上传，确保用户取消能够撤销任务。
2. 修复记忆开关的请求语义及详情页档案/删除边界。
3. 补齐解析块内部分页，保证已成功解析内容能实际交付给模型。
4. 拆分成果状态与实际预览，补充针对这五项问题的行为回归。

修复后先复跑定向回归，再在模拟器验证丢回执取消、批取消进程恢复、关闭记忆后的模型请求、详情返回栈及真实成果打开。真机和 Release 门禁另行完成。

## 五项修复验收（追加记录；以上为修复前审查）

五项已完成源码、调用方、桥接合同和回归更新：

- 记忆检索和模型生成入口均按会话所属档案的开关门控；关闭不注入已有记忆，重新启用可恢复检索，删除过滤独立生效。
- 取消意图先持久化；丢回执按 request key 取消，远端确认前保持 CANCELLING；批取消先覆盖全部子任务，恢复与 HTTP enqueue 不得越过取消意图。
- 详情路由只携带档案/epoch/资源 ID，从当前有效列表解析资源；删除、档案切换、恢复 epoch 或应用恢复查询期间撤销旧详情、编辑草稿和操作。
- `read_material_parse_blocks` 清理旧 offset 合同，改用 cursor/count/next_cursor；正文、公式和元数据均可分片，整条序列化响应不超过 24,000 UTF-16 单元。
- 成果处理状态与正文拆分；只为当前选择的成果校验文件并提取实际内容，同时显示读取范围或不可提取说明，不用处理状态伪装正文。

| 修复后验证 | 结果 |
|---|---|
| Flutter 完整测试 | 255 通过 |
| app JVM 单元测试 | 387 通过，0 失败、0 跳过 |
| 文档服务 pytest | 48 通过；1 条 Starlette 依赖弃用提示 |
| Android 定向 instrumentation | 19 个不同用例最终通过；独立进程记忆持久化场景缺少 seed 而跳过 1 项 |
| 实际运行 smoke | 捕获关闭/开启记忆的模型 HTTP 请求；真实 PDF 上传后断开回执、取消持久化与仓储重建恢复；长解析块工具逐页重组；原生操作返回后详情撤销；真实 DOCX 成果正文渲染 |
| 服务端实际 HTTP smoke | request-key 取消返回 CANCELLED，重开 Store 保持取消，未授权请求返回 401 |
| Flutter AAR / Android Debug APK | 构建成功，APK 内 32 个 Flutter 资产校验一致 |
| Flutter analyze --no-fatal-infos | 0 error、0 warning、161 info |

首轮设备验证中，记忆测试夹具使用非 UUID 档案，以及详情测试把输入框提示当页面渲染依据，导致三项失败；修正测试夹具与导航判定后，这三项重新运行均通过。未通过延长超时或删除安全断言规避问题。

实际页面截图：[成果正文](review-fixed-artifact-body.png)、[删除后详情](review-fixed-detail-deleted.png)、[换档案后详情](review-fixed-detail-profile.png)。模拟器已在取证后关闭。

发布顺序：先发布新增的 `POST /api/v1/parse-requests/{request_key}/cancel` 服务端接口，再更新客户端。本轮没有部署线上服务，没有执行真实外部模型、真实 OCR/MinerU、Mate 50 或签名 Release 验收；恢复 epoch 的 Flutter 回归不等于真实备份文件恢复验收。
