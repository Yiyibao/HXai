# R27-03/04/05：单权威云执行与手机协作协议 v4

## v4：R27-05 普通安全按钮持久化（2026-10-30）

来源：`codex/r30-05-durable-controls-20261030`，HEAD `070f3d83c294ba0d4a2197d591055e75cff59ac3` 加保留的未提交成果；精确范围和原始失败见 `HXai_CHANGE_REVIEW/R30-05-DURABLE-CONTROLS-20261030/`。原 v1–v3 保留为历史，不覆盖其 FAIL。

- 任务详情的 `cloud.control pause/cancel/revoke` 现在与原 Stop 共用 `AgentScheduler.cloudSafety → CloudWorkspace.safety/persistControls/flushSafety`。在通用远端读取及旧 pending controls 的网络结算之前，先保存本次安全意图并取消本机作用域；网络失败不丢意图。返回 `STOP_REQUESTED` 不是远端已停止证明，真实状态仍由 `cloud.read` 投影。
- 安全动作只收紧原任务，允许按原 safety-only 合同读取最新远端修订；`resume/supplement/provide_input` 仍校验用户所见修订，不进入自动重放 journal。已 ACK 的同一安全命令不重复修改任务，未 ACK 的命令阻断新派发。
- 离线暂停后实际强停手机，重启先结算原安全命令及原 claim；manualPause 保持，只有明确恢复并重新前台确认才可新派发。取消/撤权在原 NOT_DISPATCHED 停止凭证结算后保持 CANCELED/REVOKED，不写 DOCX、不产生后续模型请求。未知工具效果仍不得据超时重做。
- Room33、TaskStore13、网络 schema4、Pigeon、RPC1 与 noBackup journal version1 均不迁移；只补已有普通 UI 回调到原持久边界。回滚继续要求先结算所有 pending controls，保留原件、成果、撤权和 UNKNOWN；不能让旧的直接 RPC 路径接管未确认安全命令。
- 源码地图：`CloudTaskPanel.control` → `StudyHostFacade.handleFrontendFeature`（owner/contextEpoch）→ `CloudFrontend.dispatch`（前台/解锁）→ 原 `cloudSafety/safety/flushSafety` → 原 `CloudHost.control/device_recover/device_abort`；原 Chat/Planner/工具/Verifier/唯一位不变。

新驱动场景为 `offline_pause/offline_cancel/offline_revoke/offline_pause_restart`；与原 8 个确认/锁屏/停止/档案/claim/receipt 场景在同源两包运行，实际结果只取本批 TEST_RESULTS。PIN 测试正常打开安全键盘并点击真实 PIN，不绕过锁屏；首次冷启等待附真实表面诊断。每个 AVD 只保留一个 ADB 传输；旧 API26 多传输会使 reverse 报 more than one device，不得重启共享 ADB 或操作其他任务的设备来修复测试环境。

本批只授权本机合成范围；真实无线网/跨主机、Mate50、TalkBack 朗读、真实模型质量、普通最终 Release/生产和独立审查另验。跨 App/截图仍 DISABLED。

## v3：R27-05 前台确认与离线停止（2026-10-29）

来源：`codex/r29-05-foreground-binding-20261029`，HEAD `070f3d83c294ba0d4a2197d591055e75cff59ac3` 加继承脏树及本批增量。精确源码、旧失败、产物和逐范围裁决见 `HXai_CHANGE_REVIEW/R29-05-FOREGROUND-BINDING-20261029/`，不把历史 v1/v2 的状态作为当前全产品完成结论。

- `cloud.executeDevice` 新派发同时携带所见 `callId/inputHash/grantEpoch/taskRevision`；宿主重新读 pending 后逐项核对。授权续期/换钥、暂停再恢复或动作身份改变后，旧确认只能拒绝并提示刷新，不能签最新授权冒充用户已确认。Flutter 确认框显示动作与任务修订；同版 AAR/宿主配套，旧客户端缺字段不得新派发。
- 原 `deviceLease/deviceReceipt/deviceStopReceipt` 仍先按原 task/instance/journal 读回结算，不消费新确认字段；结算不授第二次执行。已确认未派发的动作仍须新的前台确认。
- 手机 Stop/原安全命令先取消实际 scope，并在任何网络读取前将 safety-only `pause/cancel/revoke` 意图写入原 noBackup controls；未确认命令在后续读回/控制/派发前先结算。远端不可达时不能假称云端已暂停或已停止，但本机不会丢失停止意图或继续新派发。
- 已发送命令保留原 payload/command，丢 ACK 不换号。实际 HTTP409 拒绝后才允许下一次读回绑定新修订/新 command，仅重试同一安全意图，不重放工具或收费请求。controls 由同一同步锁下的专用 journal writer 更新，迟到 claim/receipt 的整份映射不得覆盖 Stop。
- Room33、TaskStore13、网络 schema4、Pigeon 与 RPC protocolVersion1 保持；noBackup version1 controls 增加未取得远端修订的 action/可选 command，旧 value/ACK 保留。回滚前先用理解新记录的版本结算 pending controls，不能让不理解离线 Stop 的旧执行器直接接管。
- 源码地图增量：`CloudTaskPanel.execute` → `CloudFrontend.dispatch/execute/settleDevice` → `CloudWorkspace.safety/flushSafety/persistControls/delegation` → 原 `CloudHost.device_confirm/device_claim/device_finish/device_abort`。服务端现有权限/唯一位、原 Scheduler/工具/Verifier 不另建替代实现。

复验用本批冻结 QA 两包与专属 OWNERSHIP，新的 `--run-dir`；`verify_r27_android.py --scenarios stale_confirmation offline_claim lock_pending lock_claim stop_claim profile_claim claim_restart receipt_restart`。驱动只归档/清 nativeqa、自有 loopback fault gateway/Provider，真实锁屏 PIN/进程退出；`offline_claim` 是已提交 claim 后实际断连接与 RPC 不可达，不冒充系统飞行模式/跨主机网络。跨 App/截图仍 DISABLED；真实模型、Mate50、TalkBack 朗读、正式发行/生产与独立审查分别未由本批验收。

## v2：R29 本机接续（2026-10-29）

来源：`codex/r29-tls-hosting-device-20261029`，HEAD仍为`070f3d83c294ba0d4a2197d591055e75cff59ac3`加继承及本批未提交源码；精确字节、命令、原失败和适用裁决取`HXai_CHANGE_REVIEW/R29-TLS-HOSTING-DEVICE-20261029/`。下文v1描述保留为历史，不替代本批证据。

- TLS监听地址与worker连接身份分开：`service --worker-url/--worker-ca-file`，worker严格验证CA、证书有效期及主机名；不接受通配监听地址充当TLS身份，不继承代理、不跟重定向、不隐式降为HTTP。HTTP仍仅显式loopback QA。权威重启后持续观察原OS进程代次及实际退出，不用心跳超时放位。
- 托管期限为`UNTIL`（明确未来期限、最多30天）或明确`UNTIL_REVOKED`（expiresAt=null），旧无mode的定时许可仍按UNTIL读取。持续授权不扩展目标、所选文件、原模型或文档角色。期限变更递增grantEpoch并使旧READY设备批准失效；可在凭据EXPIRED时更新期限，但不恢复凭据、暂停、缺输入或设备确认；REVOKED不允许续期。
- ACTIVE/EXPIRED密钥更新均经独立、加密且锁模型的验证子任务。已知成功回执后实际进程退出、尚未安装密钥时，明确同范围恢复重用该物理回执，不再计费请求。活进程及UNKNOWN不得因换密钥或期限而释放。不同密钥或陈旧范围只能在实际停止且无不确定副作用后撤销旧验证并接受明确新验证。撤权后的迟到拒绝回执允许结算，不发布验证成功或安装密钥。ACK另带真实validationState；未入队的不再声称VALIDATION_QUEUED。
- 原生资料选择新增原文件二进制版本上传，name/hash/extension/size/uploadId绑定owner、设备及原件；分块最多512KiB、每文件30MiB，精确重复ACK可读回，冲突不覆盖。云端文本读取也仅限当前任务明确所选来源，不能读取同owner其他任务文件。
- 文档角色单独明确授权并加密固定endpoint/credential，不随当前设置或主模型换钥而改指。可信注册工具`render_office_pdf/recalculate_xlsx/parse_document`复用原文档API、原Store/dispatcher/worker及独立输出核验，不用文本抽取冒充渲染或重算。
- RPC protocolVersion仍为1（增量命令hosting.update/source.chunk及产物分块）；NetworkStore schema3→4精确增加net_document_calls，保留旧任务、ciphertext、模型、UNKNOWN及唯一执行位；拒绝未来库。Python本机schema2、Room33、原生TaskStore13和Pigeon不变。文档服务jobs新增worker_stopped，旧终态默认0，不能补造历史停止证据；executionReceiptVersion/requestLookupVersion均为1。
- 文档上传先PREPARED/UPLOADING，丢ACK按原request key查询并验证完整原意图，绝不盲重传；取消ACK、任务终态及“查不到请求”均不等于executor停止。独立原进程退出证明及原校验器、真实下载hash/size/parent绑定满足后才形成receipt。丢上传ACK的已验证结果仍要求明确恢复；已确认异步任务可按原接受范围继续，所有独立阻碍保留。
- 原生导入真实PDF/XLSX/MD/TXT，分块校验SHA、绑定原件父版本并使用原MaterialReader；重复导入复用版本ID，原件不覆盖。模型只见有界投影与截断标记，完整文件和manifest保留；文档角色用量unknown、费用null，不记零。
- 锁屏/停止/档案切换/实际进程退出矩阵使用专属模拟器、真实安全PIN与真实claim/receipt ACK故障。档案切换造成的持久暂停必须明确恢复；测试不得绕过keyguard、预先切换profile或自动清暂停。可选跨App/截图工具继续DISABLED。

复验：`python scripts/verify_r29_tls.py --run-dir <新目录>`、`python scripts/verify_r29_validation.py --run-dir <新目录>`、`python scripts/verify_r29_documents.py --run-dir <新目录>`；Android用本批OWNERSHIP文件和同源码冻结APK运行`verify_r27_android.py`。不得重用旧产物SHA或清用户模拟器。IPv6额外用`--ipv6`，当前Windows ::1连接实际WinError10013，不降TLS或改防火墙绕过。真实跨主机需要另获授权的隔离主机/证书；真实模型、MinerU解析质量、目标设备、生产与独立审查均不由本机Office/TLS证据代授。

## v1 历史基线

适用：`codex/r27-cloud-device-20261028`，base/HEAD `070f3d83c294ba0d4a2197d591055e75cff59ac3` 加未提交候选；精确字节取 `HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/SOURCE_FINAL.json`，不是该 HEAD 单独已有的能力。原 Pro 原文、历史失败与原生协议保留。

## 两种入口，不是两套并行调度器

- 本机原 `ChatService/ConversationSession/GenerationLoop/TaskPlanner/TaskResultVerifier/MaterialFiles` 保留。未配置网络权威时沿用原 TaskStore13/Room33。
- 普通 App：设置 → 云端协作与手机执行，绑定整个用户工作域的唯一权威；所有原生执行也须取得其 admission。断网不回退为另一个独立执行位。
- 任务中心 → 任务详情 → 云端协作，逐任务明确传输目标、锁定配置凭据、已有核验步骤、所选文本与可选上下文。许可24小时；长期记忆、原图、未选文件不自动导出。
- 委派映射在物理发送前持久化，丢 ACK 用同一 command/intent 读回。停用/换权威保留映射，不能授权本机重跑委派任务。
- 服务器 `CloudExecutor` 是独立受监督进程，只通过认证 RPC 操作同一个权威 TaskStore，不持另一套任务库。原生与服务器共享 `agent_semantics_v6.json` 的规划/验收语义；设备步骤仍运行原 Chat 工具和原 verifier。

## 数据、认证与版本

| 数据 | 版本/边界 |
|---|---|
| Android Room/原生 sidecar/Pigeon | Room33 / TaskStore13 / 原桥接 schema 不变 |
| Python 本机参考 | TaskStore schema2，拒绝网络库，不自动降级 |
| 网络权威 | NetworkStore schema3；原5表加精确8个 net_* 表；v2→v3保留旧任务/模型/usage，未来库拒绝 |
| RPC | `/v1/pair` 与 `/v1/rpc`，protocolVersion1；无模型可调用的管理员注册/停止布尔/SQL/verifier接口 |
| Android 委派 | noBackup `cloud-workspace-v1.json`，密钥只存 CredentialVault 引用；原生凭据 AES-GCM + Android Keystore |
| 服务器 vault | AES-GCM file-key backend；AAD绑定 owner/task/model/credentialRevision，包装密钥不入数据库/DTO；不是硬件 KMS |
| 任务许可 | owner、设备 bearer scope/epoch、task/grantEpoch/inputHash、原锁配置、executor instance/fence逐派发复核 |

HTTPS 为正常入口。HTTP只允许显式本机 loopback QA，普通包拒绝 HTTP；不继承代理、不跟重定向、不自动重试可能收费的请求。外部托管、TLS证书/主机名、账号注册与运维授权必须独立落实；本机验证不是生产资格。

## 结果与停止

Provider请求先IN_FLIGHT、稳定requestId与未知usage，再实际物理出站。连接中断/丢回执保UNKNOWN与唯一执行位，不盲重发。Worker完成工具后，原Runner或原生TaskResultVerifier独立读回；全计划receipt与验收谓词、绑定evidenceHash的完整目标评价均满足才SUCCEEDED。Provider正文和HTTP200不构成完成证明。

已完成的云Markdown可独立下载校验SHA并保存资料库；重复导入不复制成果；成功回答按原分支写回原会话，不覆盖已切换分支。云用量按authority/task/physicalRequest命名空间导入原账本，reported/estimated/unknown区分，未知费用不记零，不设置预算上限。

真实停止依据服务器持有的进程代次/实际wait或原生Job作用域退出与持久journal读回。暂停/撤权先阻止新dispatch，STOP_REQUESTED不等于停止；失联或超时不放位。旧权威必须由本机管理员在全部任务终态、实例/进程停止且无UNKNOWN后单向retire；只有本机开关关闭不足以停用执行域。

## 手机步骤与换钥校准

普通接收端只开放已注册的 `create_material_file/edit_material_file/read_material_file/math_verify`，执行原工具工厂、原计划和原 verifier。前台/解锁/owner/epoch/分支持续检查，停止按钮取消实际结构化scope。先确认输入hash/授权代次，优先排队而不抢占；旧claim ACK不授第二次派发。

可选跨App屏幕与截图工具当前 **DISABLED**：不注册无真实执行器的假工具、不自动截屏/外发/进入记忆或备份。实际锁屏、强制停止后未决动作和目标设备兼容不能仅由后台拒绝例推广通过。

2026-10-28/29换钥反例：原任务授权epoch由1→3，待设备输入仍留epoch1；或READY确认被旧epoch保留。新版本只对未派发WAITING/READY动作保留原输入、更新新epoch、撤销旧READY、要求重新前台确认。manualPause、inputBlocked、主动撤权、未知在途结果不清除；不能据换钥自动派发。

原生首次并发凭据写入也修正为 `isDirectory || mkdirs() || isDirectory`；另一个写入者已创建目录不再造成合法持久化误拒。阻塞文件、缺记录、密文损坏仍拒绝，旧不可变凭据引用保留。

## 源码地图

| 链路 | 当前文件/符号 |
|---|---|
| 普通配置/任务面 | Flutter `cloud_workspace_page.dart:CloudWorkspacePage`、`cloud_task_panel.dart:CloudTaskPanel`，settings/router/task_detail接线 |
| 可信桥/传输许可 | `StudyHostFacade.handleFrontendFeature` → `CloudFrontend.handle/dispatch/execute/settleDevice` |
| 唯一执行位/恢复 | `AgentScheduler.run/configureCloud/cloudHandoff`、`CloudWorkspace.reserve/recoverAdmissions/retire/rpcFor`、`AgentTaskStore.networkRetirementEvidence` |
| 原会话/材料/账本 | `ChatService.cloudPrepareSource/cloudContext/cloudVerify/cloudCheckpoint/cloudPublishResult`、`ConversationSession.commitQueuedInput`、`CloudNativeEvidence.result`、`HostedUsage.ingest` |
| 原锁请求体 | `HostedConfiguration.encode`，复用OpenAI/Claude/Google原body builder；不支持的Vertex/Interactions明确拒绝，不改协议 |
| 服务可信边界 | `service.create_app`、`NetworkAuthority._bound/claim/retire_process`、`NetworkStore`、`WorkspaceDomain.retire` |
| 云执行/凭据 | `CloudHost.create/provider_prepare/apply_validation/device_*`、`CloudExecutor.run`、`ProcessSupervisor`、`TaskVault`、`provider.request` |
| 实际进程与表面验证 | `scripts/verify_r27_android.py`、`CloudDeviceProcessProbe`、原network/cloud驱动与pytest；凭据并发见`CredentialVaultTest` |

## 实际证据与复验

当前普通Debug/Release、两种AAR、两种lint及资源/engine/AOT绑定已构建验证；Release原件unsigned，未安装/正式签名。普通Debug在自有API34模拟器真实冷启并通过侧栏进入云配置；QA为同领域源码的单独包。

`continuation-04-05-final/android-06/` 包含真实手机退出后云Markdown完成、当前Settings更改不改物理模型、高推理保持、重复导入/回答不复制、停用不重跑；真实WAITING_DEVICE页面、后台拒绝、前台原生DOCX、退出后桌面独立XML读回。首次截图仅是Launcher的`android-03/*-screen.png`不是云UI证明；真实表面证据用android-06。原FAIL全部保留。

复验仅限已归属的合成模拟器，先按 `EMULATOR_OWNERSHIP.json` 恢复专属AVD（`ANDROID_AVD_HOME`与command均在文件），不得wipe/操作其他设备：

```powershell
# Python回归的cwd为server/agent-runtime；以下三个驱动的cwd为仓库根。
# 在server/agent-runtime中执行：python -m pytest -q
python scripts/verify_r27_authority.py --run-dir .local/r27-authority-NEXT
python scripts/verify_r27_cloud.py --run-dir .local/r27-cloud-NEXT
python scripts/verify_r27_android.py --ownership HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/EMULATOR_OWNERSHIP.json --apk-dir HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/qa-surface-timing --run-dir .local/r27-android-NEXT
```
Android驱动会核AVD/冻结两QA包/安装SHA、归档后仅清nativeqa、启动并终止自有服务与adb reverse。用新的目录名；源码变化须先重建冻结QA包，不能沿用旧SHA。Python全量回归以`server/agent-runtime`为cwd运行 `python -m pytest -q`。

裁决仅本机合成范围 **PASS_SCOPED**。真实Provider质量、TLS远端托管/硬件KMS、Mate50/API26新云路径、TalkBack真实朗读、锁屏/全部停止故障、跨App接管、完整项目及独立审查、正式发行/生产分别未验收，不授完整R27产品通过。
