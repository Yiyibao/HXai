# 发现

严重度与置信度分开。未把静态缺口写成已发生的漏洞利用。

## F1 服务端 Office 预检放过大小写签名目录和 vbaData

| 项 | 内容 |
|---|---|
| ID | F1 |
| 需求 | P1 ZIP 宏/签名拒绝 |
| base/head | 审查 HEAD `54b832613c1b4ab3eefbecedca5367c274b60135`；修复只在工作树 |
| 位置 | `server/document-service/office_worker/package.py:inspect_package`；对照 `OfficePackage.kt:22-24` |
| 触发 | 合成 docx 含 `_XMLSIGNATURES/sig.xml` 或 `word/vbaData.xml`，并带合法主文档与 `[Content_Types].xml` |
| 预期/实得 | Android 拒绝。HEAD 服务端 `inspect_package` 返回空字体列表，即接受。工作树改为 `casefold` 并拒绝 `vbadata.xml` 后抛 `active_or_external_office_content` |
| 复现 | `python -m pytest -q server/document-service/tests/test_office.py::test_active_expanding_and_external_packages_rejected`。修复前 `extra2`、`extra5` `DID NOT RAISE`；修复后 9 通过，含既有富样例摘要检查 |
| 状态 | HEAD：PRESENT 缺口。工作树：已修，未提交 |
| 证据/结果 | E2；修复前 FAIL，修复后 PASS |
| 严重度/置信度 | S2 / 高（接受行为）。不升 S0/S1：`office_worker/engine.py:91-92` 静态设置 `MacroExecutionMode=NEVER_EXECUTE`，本轮未运行容器，不能声称宏已执行或未执行 |
| 残余 | 服务端仍允许超链接型外部关系，Android 拒绝一切外部关系；服务端此函数无 CRC/实际膨胀校验。容器、LibreOffice、生产未复验 |
| 回滚 | 还原上述两文件 |

## F2 停止、重试、批准不携带 contextEpoch

| 项 | 内容 |
|---|---|
| ID | F2 |
| 位置 | `study_providers.dart:490-546`；`StudyHostFacade.stopGeneration:1213`、`approveToolCall:1264`、`retryMessage:1222`。`requireProfile:350-352` 只有传入 epoch 才比较 |
| 实得 | 发送校验 epoch。停止/批准只要求 `profileId` 等于当前档案。本轮只看到 `switchProfile:722` 递增 epoch，且同时切换档案 |
| 预期 | 旧 epoch 的异步命令应丢弃 |
| 状态 | WIRED 但 epoch 不完整 |
| 证据 | E1；设备 NOT_RUN |
| 严重度/置信度 | S2 / 中。当前 epoch 变化与档案切换绑定，旧 profileId 会被拒绝，未证实跨档案停止。若以后 epoch 单独递增，这几个命令没有第二道门 |
| 回滚 | 不改 |

## F3 会话详情里的当前模型不是任务锁

| 项 | 内容 |
|---|---|
| ID | F3 |
| 位置 | `StudyHostFacade.getConversationDetails:854-866` 用 `getCurrentChatModel()`。任务 DTO 另走 `modelIdentity`（`getAgentTaskDetail` 附近已读到快照字段） |
| 实得 | 聊天输入条读的是 bootstrap 的 `activeModelName`，文案为当前模型（`chat_composer.dart:36`）。任务列表文案区分未验收与已验收 |
| 状态 | PRESENT |
| 严重度/置信度 | S3 / 中。执行路径读的是接受时快照，不是这个详情字段。字段名仍可能被后续 UI 误当成任务锁 |
| 回滚 | 不改 |

## F4 Android 不能撤销文档服务身份

| 项 | 内容 |
|---|---|
| ID | F4 |
| 位置 | `DocumentServiceRepository` 本轮检索无 revoke。服务端撤权在 `api.admin` / `Store.revoke`，当前服务测试 17 项通过 |
| 实得 | 身份撤销不在已读的 App 上传/取消/下载链里 |
| 状态 | 服务 WIRED；App 身份撤销 ABSENT |
| 严重度/置信度 | S2 / 中。未把“App 已断开”写成已证实的服务端继续执行；本轮没有看到 App 声称本地删除即服务端撤权 |
| 回滚 | 不改 |

## F5 提供者请求在缺少记账上下文时仍会发出

| 项 | 内容 |
|---|---|
| ID | F5 |
| 位置 | `ChatCompletionsAPI.kt:104-106`：`beginProviderRequest` 可返回 null，随后仍 `newCall` |
| 实得 | 已读的聊天、OCR、翻译、记忆、标题、压缩、规划路径都包了 `AgentExecution.accounting` 或 `ModelRequestRunner.run`。未找到一条当前产品调用在无记账上下文下达模型 |
| 状态 | 库层 PRESENT 的失败开放；当前主链未证实绕过 |
| 严重度/置信度 | S2 潜伏 / 中。不写成已发生的未授权模型调用 |
| 回滚 | 不改。改成无记账即拒绝会改变所有提供者，本轮没有失败用例，不做 |

## F6 冻结交付包在本机 Windows 上的运行时失败不是当前树回归

| 项 | 内容 |
|---|---|
| ID | F6 |
| 位置 | `HXai_Pro_Delivery/07_CODE/repo/server/agent-runtime/hxai_agent/runner.py:106` `os.open(folder, os.O_RDONLY)` |
| 实得 | 冻结包 8 失败：目录 fd `PermissionError`，以及符号链接 `WinError 1314` 特权不足。当前树 `server/agent-runtime/tests` 130 通过，同一存储运行器 |
| 状态 | 冻结包 FAIL_ENVIRONMENT；当前树 PASS |
| 严重度 | 不计入当前 HEAD 缺陷。未改冻结包 |
| 结果 | 冻结文档撤权 15 PASS。原始负对照店 2 FAIL（预期），修复店 2 PASS |

## F7 OCR 正文进入应用缓存

| 项 | 内容 |
|---|---|
| ID | F7 |
| 位置 | 勘探指出 `OcrTransformer` 缓存 `<image_file_ocr>` 到 `cacheDir/ocr_cache.json`。本审查者只复读了失败路径不记录正文（`OcrTransformer.kt:165-181`），没有打开缓存文件 |
| 实得 | 没有把识别正文写进已读的 Log。缓存若存在，是应用私有目录中的短期副本，不是日志外传 |
| 状态 | PRESENT / 缓存格式未复读 |
| 严重度/置信度 | S2 / 低。不写成已发生的日志泄漏 |
| 回滚 | 不改 |

## 未证实

没有读到跨身份数据外流、密钥泄漏或不可恢复数据损毁的当前复现。未构建 Release，不能写成已复现的 Release 崩溃，也不能写成 Release 通过。
