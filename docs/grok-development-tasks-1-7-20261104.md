# HXai / MathStudyAgent：交给 grok 的 1—7 项详细开发任务书

编制日期：2026-11-04，Asia/Taipei。报告版本：1.0。

用途：承接用户要求的七项开发与验证任务，作为 grok 的执行入口。本文是任务书，不是测试通过报告，不替代根 AGENTS.md、Pro 原始需求或正式审查规范。不包含上一轮第 8 项的完整正式发行、生产部署及目标设备总验收；七项开发中必要的构建、设备和安全回归仍必须执行。

## 0. 接手快照、范围与执行规则

### 0.1 本次核对事实

| 项目 | 2026-11-04 本次读取的事实 |
|---|---|
| 工作目录 | `D:\Office\Study\code\MathStudyAgent` |
| 当前分支 | `codex/storage-lifecycle-20261103`，已经是隔离 feature 分支，优先复用 |
| 完整 HEAD | `fac15db2f0be0336c0c7c5bfd2f2a4b2a30dcdbf` |
| 近期代码提交 | `f541032` 预检/epoch/撤权/准入修复；`1523c52` Flutter context 与字体修复；`fac15db` 旧 epoch 与文档撤权原生测试 |
| 已跟踪未提交文件 | `AGENTS.md`、`docs/pro-development-baseline-20261012.md`；不得覆盖或混作自己的产品修复 |
| 未跟踪资料 | `HXai_CHANGE_REVIEW/DOC-PACKAGE-PREFLIGHT-20261104/`、`REVIEW-FIX-20261104/`；`HXai_FULL_REVIEW/` 下本日原审查、R2、R3、REVIEW-FIX 目录 |
| 数据协议基线 | 已有记录：Room33、原生 TaskStore13、网络 schema4、RPC1、云协作 noBackup journal1；开工按实际代码复核，不按旧 Pro 文档降回早期版本 |
| 存储 | 本次开工 check PASS，1 个工作树，约 4.43 GiB 逻辑文件、3.53 GiB 生成物；并非全机磁盘统计 |
| 本次操作 | 只读规划、规则、关键源码定位和 Git 状态，运行存储检查，编写本文；未运行产品测试、模型、设备或服务 |

HEAD 会继续变化。grok 开工必须重新记录 branch、完整 HEAD、status、完整 tracked/staged diff 和相关 untracked 清单。本文不重新声明远端 main、PR 或部署状态；需要远端时重新查询，不能沿用旧审查 SHA。

特别注意：`FULL-AUDIT-20261104-R3` 的快照是 `54b8326` 加当时未提交修复，早于当前三个提交。报告所述“未提交”“无 SDK”“NOT_RUN”等是当时事实；须重新查环境与新回执，既不能抄成当前缺陷，也不能把后续提交当作已完成验收。

### 0.2 必须阅读的原文

1. 根 `AGENTS.md`、涉及目录的 AGENTS.md、`docs/pro-development-baseline-20261012.md`、`BUILD_AND_TEST.md`、`docs/storage-lifecycle-v1.md`。
2. Pro 首次接手文件：`HXai_Pro_Delivery/README.md`、`DELIVERY_MANIFEST.md`、`01_SOURCE_AUDIT.md`、`02_ARCHITECTURE_AND_PLAN.md`、`08_TEST_AND_REVIEW_RESULTS.md`、`09_SECURITY_AND_PRIVACY.md`。
3. Pro 续作：`11_HANDOFF_TO_GPT6_1_SOL.md`、`12_SOL_CONTINUATION_PROMPT.md`，结合包内 `inputs/12_GPT6_1_SOL_CONTINUATION_PROMPT.md`。
4. 产品目标：包内 `inputs/01_SCOPE_DECISIONS.md`、`02_FINAL_PRODUCT_REQUIREMENTS.md`、`03_P0_SMART_CHAT_SPEC.md`、`04_P1_FILES_P2_MATH_SPEC.md`、`13_REQUIREMENT_ACCEPTANCE_SCENARIOS.md`、`14_SOURCE_AND_VERSION_RULES.md`。
5. 每批读取 `HXai_Pro_Delivery/10_REVIEW_SYSTEM/` 的 NEW_FEATURE_REVIEW_STANDARD、PROMPT_NEW_FEATURE_REVIEW、REVIEW_EVIDENCE_AND_SEVERITY、REVIEW_SCOPE_AND_SOURCE_MAP、README；任务/凭据/数据库/跨档案及发布架构变更另读 PROJECT_FULL_REVIEW_STANDARD 和 PROMPT_FULL_PROJECT_AUDIT。
6. 开发路线读 `docs/parallel-development-20261026/PLAN.md`、`docs/remaining-development-20261027.md`，再用本任务书及其引用的后续协议更新旧状态。历史计划中的“尚未实现”不覆盖后来已完成的代码；历史禁子代理说明按当前根规则解释。

### 0.3 不变量与授权范围

- 保留原 `ChatService → ConversationSession → GenerationLoop → TaskPlanner/TaskResultVerifier → TaskStore/Room → Flutter`；云端也只有每用户工作域一个权威执行位。不得以新 Agent 框架另建未协调执行器。
- 用户接受任务时锁 provider/model/reasoning/config；不能自动换模型或推理强度。专用 OCR/title/vision 等角色分别配置、授权和计量。
- 没有 Token/费用硬预算。保留安全循环、重试、文件、上下文、CPU/存储限制；usage/cost 分 reported、estimated、unknown，未知不记零。
- 暂停、主动撤权、自然失效、缺输入、设备/前台确认独立持久；换钥/联网/解锁不能清除其他阻碍。
- 每次 dispatch 校验 owner/task/capability/scope/grantEpoch/inputHash/fence；副作用前 journal，结果独立读回；UNKNOWN 不盲重放、不据超时放位。
- 文件原件与不可变版本、墓碑、旧失败、凭据和任务库保全。禁止 reset/clean/自动 stash、降库、清库、自动合并、直接推 main、自动部署。
- 此任务书授权开发、接线、受控本机验证和修复，不新增付费模型、账号、私料外传、跨 App 操作或生产权限。已存在授权可沿原范围继续，先查原记录；仅缺少的具体范围才需补充。密钥经可信配置使用，不要求复制到文档/日志。
- grok 4.7 可依根 AGENTS.md 按 omp 需要使用子代理；其他活动模型仍按根规则执行。子代理不是外部独立审查；共享代码与唯一活动测试运行仍要协调，不并发争写/争设备。

### 0.4 存储与命令执行

每批开工/收尾运行 `python scripts/project_storage.py check` 和 `report`；新增工作树前 `check --new-worktree`，最多两棵。不复制冻结整套源码树，不为复验重建项目内 SDK/Flutter/Gradle/AVD。

本地前台测试/构建/截图必须通过 `project_storage.py run`，明确 disposable/preserve；所有临时输出主动使用子进程的 `HXAI_RUN_DIR`。日志、必要 JSON/失败反例及 hash 在回收前提取到紧凑审查目录，不能把临时目录链接当交付。保留遵守最多一批/24h，日志与提交文件遵守策略。Gradle 使用 `--no-daemon`。Flutter host AAR 仅一套复用集成输出，修改时独占并封存 hash。

服务、守护进程、后台 worker、真实数据库不得使用一次性目录。需要服务的验证先读驱动进程生命周期，确认它确为有界前台测试且全部自有子进程结束；否则先实现独立的持久受控运行目录/关闭与结算方案，不能套 disposable 后留下后台写入者。

以下为已存在的安全入口示例，须先审脚本与环境：

```powershell
Set-Location D:\Office\Study\code\MathStudyAgent
git branch --show-current
git rev-parse HEAD
git status --short
git diff --stat
git diff --cached --stat
python scripts/project_storage.py check
python scripts/project_storage.py report
# 新克隆须安装，遇已有其他 hook 不覆盖：
python scripts/project_storage.py install-hook
```

本文各任务列的测试目标/驱动是“入口索引”，不是允许裸跑旧命令。执行者先用 `--help`/源码核参数、安装/联网/清 nativeqa/force-stop 等副作用，然后经统一入口运行；旧 --run-dir/--out 要显式改到 HXAI_RUN_DIR。输出目录和设备 serial 必须当次核定，不照抄旧端口。

## 1. 当前版本构建、桥接与回归收尾（G47-01）

### 1.1 目标与已有成果

形成当前真实源码可复现的构建基线，验证本日修复在 Flutter、桥接、宿主和服务上成立。已有历史 Debug/Release/AAR、API26/API34 证据，但只属于各自源码。当前有 epoch、会话模型展示、文档撤权、无记账请求拒绝、Office 预检和 OCR noBackup 缓存修复，不重复开发这些功能。

### 1.2 主要入口

- `client/flutter_ui/pigeons/study_host_api.dart`、`lib/bridge/study_host_api.g.dart`；Kotlin `flutter/generated/StudyHostApi.g.kt`、`flutter/StudyHostFacade.kt`。
- `client/app/src/androidTest/java/me/rerere/rikkahub/service/HostEpochAndDocumentRevokeTest.kt`、`FlutterChatBridgeTest.kt`；实际消费者和 Flutter 回调由完整 diff 追踪。
- `client/app/build.gradle.kts`、Flutter pubspec/lock、Gradle wrapper、`scripts/storage.init.gradle`、`scripts/verify_flutter_apk.py`、`BUILD_AND_TEST.md`。
- 产品宿主路径统一前缀 K=`client/app/src/main/java/me/rerere/rikkahub/`；下文 K/ 均指此绝对根下源码。

### 1.3 开发步骤

1. 盘点项目外 Flutter/Dart/JDK/SDK/Gradle/AVD，记录实际版本与可用性；以当前 pubspec/lock 为准，不靠升级依赖消除错误。当前 pubspec 声明 Dart ^3.13.5、Pigeon ^29.0.7。
2. 从 Pigeon 源运行项目生成器（Flutter 模块 cwd，`dart run pigeon --input pigeons/study_host_api.dart`），核源/两端 diff 与调用者；保持既有类型 ID/字段兼容。生成器造成协议变化时补版本/兼容测试，不能只手工对齐。
3. 对本日修改建立受影响矩阵：旧 epoch 的 stop/retry/branch/mode/approve 拒绝；档案切换/迟到回包拒绝；文档撤权确认/失败保凭据；会话实时选择不冒充任务锁；无记账请求物理出站为零；OCR 新缓存不进入备份。
4. 经统一存储入口验证 Gradle build 重定向。未生效就修路径/脚本并保失败；不得用绕过 wrapper、删未知旧目录或抬配额解决。
5. 完成 Flutter analyze/test、app/ai/search 受影响 JVM 与 lint；构建对应 Debug/Release AAR、QA/test APK 和普通包。Web 构建若跳过须说明哪些字节复用、为何不受影响；不能宣称完整前端新建通过。
6. 当前同源 QA 包在已归属 API26/API34 复验本日测试及受影响 Chat/授权/来源/恢复。强停探针独立串行，不把 prepare Probe 直接加入普通批量 suite。
7. 核普通包无 QA 管理/测试 Provider；读回 Manifest/资源/engine/AOT/安装 SHA，与实际 AAR 和源码清单绑定。unsigned Release 与临时测试签名分开记录。
8. 迁移本批必需旧驱动的固定 .local SDK/AVD 路径到共享工具链参数；清单列出尚未迁移的历史脚本，不要求为无关旧脚本扩大本批范围。

### 1.4 验收与反证

| 用例 | 必须观察到的结果 |
|---|---|
| stale epoch 与正常当前 epoch 对照 | 旧命令零副作用；合法命令仍可达且有真实回执 |
| 撤权网络失败/旧服务/取消确认/切档案 | 不谎称远端已撤权；适用失败保本地凭据，取消不出请求；服务成功才按原安全顺序收紧本地状态 |
| 无记账/正常记账请求 | 无上下文不发物理请求；正常主链不被误拒 |
| 源码/AAR/APK 被替换 | 绑定检查拒绝，不能因版本号相同通过 |
| build/test 失败与成功 | 都有真实退出码、源码/输入/输出 hash；临时输出回收或明确 BLOCKED |

完成：当前声明范围构建和回归通过，或缺环境项有精确命令与依赖且不被写成 PASS；必要 FAIL 未解则阻断相关功能。输出 SOURCE_FINAL、BUILD_BINDING、受影响回归矩阵及普通包隔离证据。此任务不授最终正式发行资格。

## 2. 真实 Agent 任务质量闭环（G47-02，P0）

### 2.1 已有成果与待解决问题

规划、工具执行、最终计划修复、持久回复交付均有实现。历史 32 数学题+5 Agent 公开任务真实评估记录过工程完成失败，后续有 wire、最终计划、response_delivery 等增量；不能把旧 cohort 改标为修复后结果，也不能由几个局部成功推导完整质量通过。

`response_delivery` 只证明受限的当前分支持久可见回复已交付，数学仍 NOT_EVALUATED；文件、正式证明、来源和外部副作用不能借此完成。自然语言目标的模型语义评估有误判可能，不应把“独立文件读回”与“同模型语义判断”混写为相同强度证据。

### 2.2 入口与依据

- K/`service/ChatService.kt`、`service/ConversationSession.kt`、`data/ai/GenerationLoop.kt`，仓库中的 `ConversationRepository`。
- K/`feature/agent/TaskPlanner.kt`、`FinalPlanRepair.kt`、`ResponseCompletion.kt`、`TaskResultVerifier.kt`、`AgentTaskStore.kt`。
- `LiveQualityEvaluationTest`、`QualityEvidenceFilesTest`、`ResponseCompletion*Test/ProcessProbe`，`scripts/prepare_r27_live_quality.py`、`import_r27_quality_answers.py`、`verify_quality_evidence_process.py`。
- `docs/r27-final-plan-repair-contract1.md`、`r29-response-delivery-office-recovery-v1.md`、`r29-planner-narrative-v1.md`、`quality-06-09-contract2.md`。

### 2.3 开发步骤

1. 找回原固定公开评估的授权范围、题干/目标、配置锁、封存回答、失败任务、请求账本与 UNKNOWN；按当前源码重新核所有记录。不得续跑旧 UNKNOWN、复制其任务为“重试成功”。
2. 建立任务级质量矩阵，至少包含：零工具闲聊、两讲义 Word、同任务追加新版本、PPT 逻辑页修改、Excel 数值/公式、源文件变化、漏目标、工具不支持、需用户输入、失败重规划、断流与重复命令。
3. 为每个故事先写可观察结果、必要工具/来源/证明要求、允许的未完成状态；题干、验收和评分修订在执行前封印。新增题/角色/材料先核授权。
4. 用当前原 Chat 主链执行新的有界评估，锁 provider/endpoint/model/reasoning/config/角色；封存实际可见答复、计划、call/receipt、完整目标、产物、usage、版本与时间。先答案封存后评分，不给模型喂参考/证书。
5. 对“文件正确而任务未完成”定位：工具输出类型、Document producer 绑定、父版本、持久回复提交时机、语义输入、最终计划和 UI 状态。对“任务成功但漏目标”定位谓词缺失、来源/旧分支、过宽 response_delivery；最小修复，不直接强写 SUCCEEDED。
6. 用同一失败输入先旧版反例、后修复，保留正常闲聊/合法补充/正确文件对照。计划修复不得获得新派发位、隐删未决调用、丢弃拒绝数学历史或增加权限。
7. 完成固定 cohort 的修复后评估；若只授权局部回归，明确新 cohort 分母与未覆盖项，不声称完整通过。质量阈值在运行前确定并注明是新合同选择；不得看结果后降门槛。

### 2.4 必测反例与交付

- 工具 HTTP200/已写文件，但缺用户要求章节/错误来源/父 hash：不能成功。
- 正确无工具回答、正确反驳或需要用户输入：分别呈现交付/输入等待/未核验范围，不因无工具全被拒。
- 写后进程退出、最终回复提交前后退出、晚到旧分支/旧档案回包：不复制成果、不覆盖新分支、不假交付。
- 语义请求在途撤权/源变化/goal revision 变化：旧答复不发布成功；物理请求账本保留，未知费用不记零。
- PNG 等二进制附件只提供可核 metadata；无视觉核验不能满足“图形正确”要求。

交付 EVALUATION_MANIFEST、CASE_RESULTS、原始失败与修复对照、稳定封存回答/账本摘要、成果独立读回和谓词覆盖。逐题分开工程完成、目标覆盖、数学/视觉正确性、费用来源。完成不是“所有任务必须成功”：应完成合同所需正/负行为及质量门槛，未达门槛保持失败并列后续。

## 3. 云端与手机协作的实际环境验收及缺口修复（G47-03，P0）

### 3.1 已有成果与边界

独立网络权威、CloudExecutor/监督进程、AES-GCM file-key vault、严格 TLS、期限/换钥验证、原文件分块传输、文档角色、设备前台确认和离线安全 journal 已实现。TLS 不是待从零开发；file-key backend 不是硬件 KMS。跨 App/截图工具仍 DISABLED。

先核 `docs/r27-cloud-device-protocol-v1.md` 全部 v1—v4 和来源。本任务补实际跨主机网络、部署配置与恢复验收；是否引入硬件 KMS 取决于实际托管合同，不以无 KMS 判定所有本机实现无效。

### 3.2 主要入口

- K/`feature/agent/` 中 `AgentScheduler`、`CloudWorkspace`、`CloudFrontend`、`CloudNativeEvidence`、`HostedConfiguration/HostedUsage`；Flutter `cloud_workspace_page.dart`、`cloud_task_panel.dart`。
- `server/agent-runtime/hxai_agent/{authority,service,cloud,hosting,vault,worker,provider,documents,domain}.py`；`NetworkAuthority/NetworkStore/CloudHost/CloudExecutor/ProcessSupervisor/TaskVault`。
- `scripts/verify_r27_authority.py`、`verify_r27_cloud.py`、`verify_r27_android.py`、`verify_r29_tls.py`、`verify_r29_validation.py`、`verify_r29_documents.py`。

### 3.3 开发步骤

1. 冻结唯一权威配置与客户端/worker 版本，盘点真实主机、证书、进程用户、数据库/vault/日志目录与维护授权。无跨主机授权时继续本机协议与部署配置开发，实际跨主机项记 NOT_RUN。
2. 在授权隔离环境验证 worker URL/CA/hostname/有效期；测试错 CA、错 hostname、过期证书、代理、重定向、HTTP 降级均拒绝。不得禁证书校验或改防火墙绕过环境问题。
3. 服务器密钥文件权限、AAD、轮换与恢复按实际 OS 验证；如需 KMS adapter，另立合同、合成密钥测试和迁移方案，不导出真实密钥、不把密文数据库复制进证据。
4. 手机真实离线/进程退出后云端继续非设备步骤；设备动作进入等待。联网/解锁后按优先排队、前台明确确认执行，不能抢占独立运行任务。
5. 在跨主机链路注入配对/准入、provider、claim、receipt、control、文件分块的断连/丢 ACK。读原 request/command，不新号盲重发；区分“未收到 ACK”“远端未知”“真实停止”。
6. 复验 v4 普通暂停/取消/撤权先持久化、迟到整份 claim 映射不覆盖 controls；手机重启先结算旧控制/lease。未确认停止不得显示已停止或释放唯一执行位。
7. 撤权/expiry/续期/换钥与 pause/input/device blockers 做组合验证；旧 READY 确认不升级到新 grantEpoch；旧 executor/fence/实例晚提交拒绝。
8. 真实材料上传/下载重复分块、hash/size/owner/任务范围、取消、引入库及重复导入验收；无所选来源权限不读取同 owner 的其他任务材料。

### 3.4 最小场景矩阵

| 组 | 场景 | 关键断言 |
|---|---|---|
| 执行权威 | 手机与 worker 并发、权威重启、旧实例晚回 | 唯一位/fence 保持，实际进程停止证明优先于租约时间 |
| 安全控制 | offline_pause/cancel/revoke/pause_restart | 意图持久化、先阻新派发、ACK 与停止分别显示 |
| 手机接管 | lock_pending/lock_claim/stale_confirmation/stop/profile | 锁屏拒绝；新确认绑定原 call/input/epoch/revision，换档案不得误执行 |
| 恢复 | claim_restart/receipt_restart/服务退出 | 不重复 DOCX、工具、模型请求或账本；UNKNOWN 原样保留 |
| 材料与隐私 | 错 hash/越 owner/未选文件/重复导入 | 只访问授权固定版本、原件不变、无重复成果 |

交付拓扑与授权清单、当前协议/迁移说明、进程退出和网络故障证据、客户端/worker 同源绑定、最小运维/停用/恢复说明。可选跨 App 的启用不作为本任务默认动作；如另获授权，必须独立合同补全可停止和临时截图生命周期，不顺手开启。

## 4. Office 兼容、服务回执及版本读回收尾（G47-04，P1）

### 4.1 已有实测与待修问题

MS Office/LibreOffice 有限定范围证据，WPS 已实际调用，不是 NOT_RUN。`quality-06-09-contract2.md` 记录 WPS：DOCX OMML/正文结构保留但 PDF 数学映射/版式问题；XLSX 部分读回通过但 PDF 严重缩小；PPT 保逻辑页/隐藏设置但丢 VISIBLE FALLBACK。这些原 FAIL 必须保留。

另有真实 LibreOffice 上传后缺 rendererVersion 的未决意图；现在已有保存远端 ID、原 requestKey 只读查询与回执不完整 blocker。客户端代码不代表旧部署服务已升级，更不代表历史远端任务已核对。

### 4.2 入口与依据

- K/`feature/material/MaterialEditor.kt`、`SpreadsheetEditRegions`、`MaterialReader/MaterialFiles/MaterialVersions`、`OfficeConversionRepository.kt`、`DocumentServiceRepository.kt`。
- `server/document-service/` 实际 api/store、office 路由、dispatcher/worker/office_worker 及 package preflight；按当前真实布局定位。
- `scripts/verify_b_wps.ps1`、`verify_b_microsoft_office.py/.ps1`、`office_roundtrip_contract.py`、`verify_office_artifact.py`、`verify_b_linux_office.py`。
- `docs/quality-06-09-contract2.md`、`r29-response-delivery-office-recovery-v1.md`，本日 DOC-PACKAGE-PREFLIGHT 与 REVIEW-FIX 记录。

### 4.3 开发步骤

1. 按原输入 SHA 与软件版本复现 WPS 三类失败，分别定位为 writer、兼容表示、导出设置、字体还是产品明确不支持；对支持范围内故障修实现。若确属引擎限制，用户界面和支持矩阵如实拒绝/提示，不能仅文档改口即判原需求完成。
2. DOCX 核可编辑 OMML、段落表格、非目标区域及 PDF 实际视觉；PPT 用逻辑 r:id 核全部页/隐藏标记/mc:AlternateContent 的显示语义；Excel 核公式、常量、类型、日期、跨表/图表、实际重算和页面可读性。
3. 保留既有单 binary64 ULP 缓存等价的狭窄边界；不能扩成泛用容差，更不能忽略公式/日期/非公式数据变化。PPT 内容丢失和非法 Unicode 不得靠文本清洗后判通过。
4. 执行同源普通/QA App 的创建→读→新版本编辑→转换→预览/另存→重开流程，绑定 owner/source/parent/expectedText/locator/hash；失败不修改原件或产生“已验证”版本。
5. 对旧服务先核 capability/route/receipt 真实版本。授权部署前准备版本兼容与回滚，实际部署仍需在既有明确授权内；客户端查原 requestKey/remoteId，只读恢复，不二次 enqueue。
6. 验证撤权在慢上传、enqueue、claim、发布前，以及 worker 进程退出、丢 ACK、cancel ACK 但未停；任务终态不等于 worker_stopped，旧任务不能伪造历史停止证据。
7. 补本日 Office package preflight 的 Android/服务一致性：坏 ZIP/CRC、大小写签名目录、宏内容/关系、外部关系、UTF16 DTD/实体 XInclude、正常 UTF16 对照。当前“拒全部外部关系”会拒绝合法超链接，应明确产品行为；若改变策略另立安全合同，不能为富文档直接放开外部解析。

### 4.4 验收与交付

每个引擎/文件类型分开裁决：结构、可编辑性、语义值、重算、可视版式、来源、新旧版本保全。真实打开/保存/重开/导出，独立 XML/数值/PDF 渲染读回；文本抽取不替代视觉。只使用自有新文档对象，不关闭用户 Office/WPS，不接管已有实例。

交付逐引擎支持矩阵、WPS 原失败/修复对照、同 SHA 测试输入清单、紧凑结构/视觉证据、历史请求核对结果及仍未决项。找不到旧请求或回执缺失不能算失败零副作用，更不能重新上传填证据。

## 5. 专业数学真实质量与参考裁决（G47-05，P2）

### 5.1 目标、现状与入口

专业数学已超出最初 Q[x]，合同1—17、部分有理式/微积分误差界、证明规则与批改工作流已有实现；并非通用证明器。32 题分离包、逐义务评分和真实回答已有记录，但同一执行者打分不等于独立数学裁决。

入口：K/`feature/math/ProofDerivation.kt`、`ProfessionalMath.kt`、`ProfessionalMathGuidance.kt` 及实际 ProfessionalSolution/Review；`feature/agent/TaskResultVerifier` 与 math 工具；`server/agent-runtime/hxai_agent/math_verify.py`；`scripts/score_b_math_evaluation.py`、`import_r27_quality_answers.py`、`verify_b_rational_math.py`。依据 `b27-math-obligation-scoring-contract1.md`、`r27-quality-06-09-protocol-v1.md`、`quality-06-09-contract2.md` 及对应数学合同。

### 5.2 开发步骤

1. 封存原32题、参考、义务、原答案与版本，先查是否依赖缺失上下文；例如 HS-SEQUENCE-SAMPLING 不能默改题干去消除未定。
2. 按江苏高中、高数、数学分析、实变/测度、证明与反例、正确异法/首错分层；每层列已支持核验能力、需要模型推理的部分和缺口，不用继续增加 operation 替代整题质量。
3. 核参考来源/许可/定理条件与义务；可做独立符号或 Fraction/Sturm 等复算，但由 grok 或其子代理完成的复核仍属同执行体系。外部专业裁决缺失维持 NOT_EVALUATED，并交可交审材料。
4. 在任务2同一原 Chat/锁模型/账本路径封存修复后真实回答。盲题不带参考、证书或评分答案；参考修订和新增题另版本化，不修改原分母。
5. 逐义务给 MET/VIOLATED/UNDETERMINED，定位可见答复中的精确原文/Unicode区间，写条件、量词、定义域、边界、关键等价与定理依赖；工程状态和数学结论独立。
6. 从实际错误补最小能力：条件遗漏、错误参数分支、证书规则、首错前缀或来源绑定。新增规则先定形式域/前提/结论/依赖消除，再正例、错误前提、合法异法、未知前缀及资源边界测试。
7. 教材引用绑定真实文件版本、物理页与页内依据；印刷页只有实际映射才展示。图形另核定义域/奇点/参数范围，数值采样不能授证明。

### 5.3 验收与反证

- 有效局部证书但与原题对象/前提无关：不能算整题已证。
- 非零多项式在有限采样点相同：不能误判恒等。
- UNSUPPORTED/无效证书：不能判学生答案错误或命题为假。
- 正确非标准推导、重复合法前提、多分支正确解：不能因格式不同误拒。
- 手写前缀不明、定理条件缺失、实变中的量词/测度前提不足：明确未定/需确认，不能报确定首错。

交付题型/义务覆盖矩阵、参考来源与独立性说明、固定分母评分、错例→修复→回归映射、工具能力边界及待外部裁决包。未建立独立参考前不报权威准确率；不把工程 SUCCEEDED 当数学正确。

## 6. OCR 到确认、批改与可编辑 Word（G47-06，P1/P2）

### 6.1 现状与目标

固定公开五页已实际运行：WinRT zh-Hans-CN CER=41/93≈44.086%，MinerU=46/93≈49.462%。后续本机裁切未改善基线；MathWriting human 单式合成页五个样本，WinRT 未识别出文字。这些不是 Android 默认 OCR、真实学生多页解答或所有引擎质量结论。

目标是来源可核、歧义明确、用户确认可靠的整条工作流，并提高有代表性语料上的识别质量；不能靠取消确认、人工抄参考后标识别成功实现“通过”。

### 6.2 入口与依据

- K/`feature/material/MathWorkInputs.kt`、`MathWorkCandidates.kt`、`MathTranscription.kt`、`MaterialPdfOcrDraft`、`DocumentServiceRepository.kt`；K/`data/ai/transformers/OcrTransformer.kt`。
- `LiveOcrQualityTest`、`scripts/verify_b_windows_ocr.py`、`b_windows_ocr.ps1`、`verify_b_ocr_corpus.py`、`verify_b_pdf_ocr_draft.py`、相关 scripts/tests。
- `docs/b24-ocr-evaluation-contract1.md`、`r27-quality-06-09-protocol-v1.md`、`quality-06-09-contract2.md`。

### 6.3 开发步骤

1. 保留各五页夹具身份：旧 LiveTest、固定评分语料、MathWriting 合成页分别命名/hash/许可，不换题或换真值。MathWriting CC BY-NC-SA 边界按原记录维护，不装入产品/未经许可分发。
2. 错误分类到公式上下标、分式/根号、关系符、希腊字母、正文、表格、页序、空白/覆盖、引擎空输出；选取在执行前明确的质量指标和门槛。原93字符样本不足以概括真实手写质量。
3. 比较当前明确配置引擎/语言/预处理的有界候选；新增远程引擎、传输、模型角色先核授权，不暗换主模型或 OCR 接收服务。记录真实 engine/parser/renderer 版本和任务 receipt。
4. 补有合法来源的真实多页手写、脱敏和逐符号人工真值；缺材料时先完成语料合同/标注格式/来源确认开发，真实质量项保 BLOCKED，不伪造学生样本。
5. 完成真实 PDF/图片→覆盖检查→候选→用户逐歧义确认→同一 Chat 首错/解题→可编辑 Word→预览/另存。新引擎候选与原图绑定，用户修订保留原识别与 revision。
6. UI 明示未覆盖页/区、低置信符号、字面 LaTeX与真正 OMML可编辑公式的区别；多页顺序按真实物理页，原页图不等于识别文本完整。
7. 验证源删除/变更、档案切换、取消/进程退出、缓存清理、旧确认/旧回包均不能复活；noBackup 缓存、日志与删除传播连同任务7专项核查。

### 6.4 必测及交付

非白页无文字不得当空白；空 text 隐藏公式/图片/表格不得骗覆盖；正常 Unicode/组合字符/换行保真，格式空白/XML非法字符拒绝且不损旧记录；使用与 App 一致的非空 latex 优先评分语义。用户确认了转录不等于数学通过；人工修订成绩与机器成绩分别报告。

交付 OCR 错误分层、原样本与新样本分开评分、许可和标注来源、候选/修订/确认审计、来源绑定 Word 的独立读回。OCR、数学、Word结构/版式、任务完成四项分开裁决；低质量 FAIL 保留并给下一具体修复，不靠更新标签掩盖。

## 7. 扩展、费用、长期数据与网络边界专项（G47-07，支撑）

### 7.1 目标与边界

已有通用扩展管理、受支持 MCP transport、独立脚本沙箱、OAuth、可信费用子集、记忆/加密备份。不能简单删停用条件让旧共享 McpManager 裸进 Chat。真实服务兼容、数据生命周期和费用覆盖分别验收；百度网盘、邮箱、日历专用连接器继续后置。

### 7.2 入口

- K/`feature/agent/ExtensionPolicy.kt`、`ExtensionMcpTransport.kt`、`ExtensionMcpRuntime.kt`、`McpInstallationStore.kt`、`TrustedCost.kt`、对应 OAuth/frontend/Skill 沙箱；原 ChatToolFactory 与可信注册表。
- K/`data/datastore/CredentialVault.kt`、`feature/memory/LearningMemoryStore.kt`、`data/sync/EncryptedBackup.kt`、`BackupManager.kt`，原 Provider 物理请求/角色/用量导出链。
- `client/search/src/main/java/me/rerere/search/WebImageService.kt`、`WebPageReader.kt`、K/`data/ai/tools/WebImageTools.kt`，以及实际 Flutter 图片 loader/预览/缓存消费者。
- `ExtensionPolicyTest`、`ExtensionRuntimeTest`、`ExtensionOAuthRuntimeTest`、`TrustedCostTest/RuntimeTest/BaselineTest`、EncryptedBackup/BackupIntegrity/BackupRestoreProcessProbe 等实际测试。
- `docs/a04-general-mcp-skills-protocol-v1.md`、`a04-oauth-a05-trusted-cost-v1.md`、A02 数据保全协议及根基线；旧记录只作定位。

### 7.3 子任务 A：扩展与受限执行

1. 盘点普通 App→前台管理→每服务器/每工具 grant→实际 dispatch→receipt 的当前可达链，列 PRESENT/WIRED/DISABLED；区分旧共享 manager 和新安全 runtime。
2. 以实际用户任务需要选最小 HTTPS/OAuth/MCP 服务做兼容验证；OAuth callback 只认证连接，不赋工具权限；state/PKCE/resource/issuer、撤权/过期/UNKNOWN兑换不得弱化。
3. 远端 schema/Skill 描述不能改变可信风险、verifier、scope。恶意 prompt、工具同名冲突、schema动态变化、跨 owner、旧批准和超大输出均做反例。
4. 读操作先验证；外部写操作新增专属合同、前置审批和独立读回，不用一次列表读取当发信/删除/支付成功证明。沙箱写文件/二进制成果验证路径、限额、owner/source/hash和真实 artifact。

### 7.4 子任务 B：费用与请求台账

1. 逐物理请求绑定稳定 requestId、task/provider/model/role/config 与用量来源；流/非流、后台辅助、重试、云导入和价格回执均不能漏记/重复计。
2. reported/estimated/unknown 的升级按原合同；仅有 HTTP200/文本没有 usage 时不填0。旧金额/旧价格依据不可因新费率静默改写。
3. 按当前实际使用供应商核官方价格/usage/cost schema和生效时间；技术文档以官方一手来源为准，记录抓取/有效时间与覆盖范围。历史目录标注到期日不是当前价格证据。
4. 有已授权账单/调用记录则核同 request/model/时间区间与金额；没有真实账单只授合成协议范围。验证日周月时区边界、同调用去重、未知覆盖、导出和异常提醒，不增费用硬预算。

### 7.5 子任务 C：记忆、备份、凭据和网络

1. 追踪聊天/材料删除→摘要/标题/记忆/OCR缓存/检索→备份/恢复→后台晚回包；墓碑和 owner/lifecycle 保护必须贯通，旧备份不得复活删除、授权或原队列执行。
2. 用合成旧库/备份验证升级、staging seal、损坏密文、缺 key、跨 owner、路径重定位、进程退出；拒绝同时保旧数据，不能 wipe/重建当恢复成功。
3. 实际检查 OCR noBackup 新文件、旧 cache 删除、缓存源 hash/角色/档案、日志无正文或密钥；截图继续不自动进记忆/备份。
4. 沿网页与图片最终消费者复核 DNS、IPv4/IPv6、重定向、代理/cookie/auth、大小与解码限制、UI二次下载。没有缺陷证据先记待验；若复现裸原URL再下载等边界，修为受控 owner-scoped asset 或等价逐次验证流程。
5. SSRF/重绑定/注入测试只用合成 DNS/Mock transport，不探测真实内网或 metadata。用户自定义模型内网 endpoint 的明确授权与公共网页下载策略分开，不误封合法已授权配置。

### 7.6 验收与交付

交付扩展实际兼容矩阵、授权/撤权/未知兑换反例、受限成果合同、费用供应商/字段/时间覆盖矩阵、备份删除传播矩阵、最终图片 loader 调用图和网络反例。没有真实服务/账单/设备的项记录 NOT_RUN 及精确依赖；不能由单一模块单测授长期数据或真实服务整体通过。

## 8. 七项任务的顺序、交付与停止条件

### 8.1 推荐批次

| 阶段 | 工作 | 进入下一阶段的条件 |
|---|---|---|
| G0 | 任务1，恢复共享工具链、Pigeon、存储构建与当前同源基线 | 修复源码可构建；测试/设备/产物绑定；必要失败明确阻断 |
| G1 | 任务2，真实 Agent 最小完整故事；任务7中发现的安全阻断优先修 | 对现有成功/假成功/正确输入误拒取得可靠证据；不重放旧UNKNOWN |
| G2 | 任务3云协作；任务4文件、任务5数学、任务6 OCR按已授权环境交替推进 | 公共接口变化先定合同，同包回归；独立准备可并行，测试run和设备串行独占 |
| G3 | 任务7剩余覆盖，归并七项支持矩阵并重跑受影响当前包回归 | 每项有实际裁决、遗留和回滚；不能宣布第8项正式交付完成 |

不设凭空工期和完成百分比。环境不足应继续独立可做的代码/离线合同/反例工作；外部前置只阻断依赖它的子项。任何已证实 S0/S1 先修，不因任务编号靠后延误。

### 8.2 每个实现小批必须交付

在 `HXai_CHANGE_REVIEW/G47-0N-具体子批-日期/` 新目录输出：

- CHANGE_CONTRACT：FeatureID、用户故事、base/head、入口→持久层→UI、schema/owner/profile/epoch、模型/授权、副作用、成功/失败/恢复、兼容和回滚。
- TRACE_AND_DIFF、完整可审阅 binary-safe patch/diff、源码/输入/测试/输出 hash；改 shared 文件先核 before/candidate/current，不套旧累计补丁。
- TEST_RESULTS.json、受控原始日志和紧凑失败反例；每项记录 cwd、实际命令/工具版本、退出码、时间、源码身份、日志是否截断。
- ADVERSARIAL_REVIEW：至少“假成功/错恢复”和“正确输入被拒/旧数据损坏”两类；同执行者/子代理复审如实标注。
- VERDICT_AND_ROLLBACK、HANDOFF，以及 SOL_IMPLEMENTATION、SOL_TEST_EVIDENCE、SOL_CHANGE_REVIEW、SOL_ACCEPTANCE_MATRIX、SOL_BLOCKERS、HANDOFF_STATUS。
- 触发全审的批次在独立 `HXai_FULL_REVIEW/<batch>/` 输出快照、来源矩阵、发现、TEST_RUNS、修复patch、裁决和下一步；不覆盖本日旧审查。

若旧 FAIL 已在历史包有足够反例，引用 hash/路径即可，不复制整包；新增失败必须真实保留。为测试而更改参考、断言或夹具要说明独立理由，不能以迎合实现消除 FAIL。

### 8.3 汇总矩阵必备字段

`任务/子项 | 需求ID | 实现状态 | 当前HEAD/dirty manifest | 证据E0—E6 | 测试结果 | 外部独立审查 | 实际覆盖 | 遗留/依赖 | 下一条精确命令 | 回滚`。

实现状态仅 ABSENT/PRESENT/WIRED/DISABLED/PARTIAL；测试 PASS/FAIL/SKIP/NOT_RUN/BLOCKED；裁决 BLOCK/CONDITIONAL/PASS_SCOPED/NOT_EVALUATED，范围逐项写明。未做外部独立审查保留 NOT_EVALUATED。必要 FAIL/S0/S1 未解阻断相关功能，不能用相邻历史通过覆盖。

每个未完成项必须到“具体文件/方法、已做什么、缺什么、下一条能执行的命令、需要的工具/授权/材料、可观察验收、回滚”。缺真实配置时可交带明确占位符的命令模板并注明何处取值，不能编造 host/serial/密钥或通用绕账本 API 调用。

### 8.4 可直接交给 grok 的启动指令

> 请完整阅读本任务书及根 AGENTS.md 指向的权威文件，重新记录真实工作区和存储状态，优先复用当前隔离 feature 分支。从 G47-01 开始按依赖完成1—7项开发、接线、构建、定向验证与故障修复。继承已实现的原 Chat/唯一调度权威、模型锁、授权、文件版本和数学合同，不重建第二套执行器；保留旧 FAIL/UNKNOWN 和用户未提交文件。每个小批先写可证伪合同，真实失败→最小修复→正常/边界/故障与相关回归，输出规范审查资料、六类 SOL 和可审阅 diff。可沿已存在明确授权推进真实评估，新的账号/付费/私料/生产范围不得由本文推定。缺前置如实记录并继续独立可做部分，不以文档更新代替实现，也不把同执行体系子代理当外部独立审查。遵守唯一活动测试运行、共享外部工具链、最多两工作树和统一回收规则。最终按七项逐条交付实现/证据/测试三轴矩阵及精确接续；正式发行与生产不由本任务书放行。

## 9. 来源索引与报告维护

本任务书将旧 R26/R27 编号映射到新的七项交付范围，不改写原批次身份与原始规范。

| 本文任务 | 主要规划映射 | 最新状态依据 |
|---|---|---|
| 1 | A26-R01/R06、R27-01/02的当前版本接续 | 本日提交 f541032/1523c52/fac15db；存储合同与本日审查差异 |
| 2 | A26-R05、R27-06 | final-plan/response-delivery/quality 合同及工作树合流记录 |
| 3 | A26-R02/R03/R04、R27-03/04/05 | 云协作协议v4，包含v2 TLS/托管与v3/v4确认/离线安全 |
| 4 | B26-R03、R27-09，兼原Office恢复 | 质量合同2、Office只读恢复协议、本日预检修复 |
| 5 | B26-R01、R27-07 | 数学合同1—17、32题评分合同、真实质量协议与后续增量 |
| 6 | B26-R02、R27-08 | 公开五页/MathWriting实际结果及OCR来源/确认合同 |
| 7 | A26-R05维护、R27-10，兼A02数据保全 | 通用扩展/OAuth费用协议、备份/删除保护和最新审查待验范围 |

实施者以实际新证据追加本任务书的后续状态文档，不回写历史批次 PASS/FAIL，也不删除原 Pro 包。若后续源码已经关闭某项，先读回实现与同源证据，再从剩余任务扣除，避免重复开发。
