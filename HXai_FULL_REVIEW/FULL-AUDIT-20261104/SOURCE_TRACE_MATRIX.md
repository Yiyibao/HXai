# 源码追踪矩阵

坐标分开。`WIRED` 不是 `PASS`。行号是本轮读取时的当前树，不是 2026-10-12 地图。

| 范围 | 当前入口 | 状态 | 证据 | 本轮测试 |
|---|---|---|---|---|
| 启动 | `AndroidManifest.xml:119-122` → `StudyFlutterActivity` | WIRED | E1 | NOT_RUN（无 SDK/设备） |
| Flutter 发送 | `chat_page.dart:76-89` → `study_providers.dart:436-466` 带 profile/epoch → `StudyHostFacade.sendMessage:1164` 校验 epoch | WIRED | E1 | NOT_RUN |
| 教学模式 | `chat_page.dart:1219-1223` 为 CHAT/GUIDED/HINT/CHECK/EXPLAIN；未见普通/Agent 开关 | PRESENT | E1 | NOT_RUN |
| 任务槽 | `AgentScheduler` 单库 `agent-tasks-v1.db`；`AgentTaskStore.claim:210-224` 单例 slot，fence+1，占用或 `UNCERTAIN_SIDE_EFFECT` 拒绝 | WIRED | E1 | Kotlin NOT_RUN；Python 当前内核 130 PASS |
| 模型锁 | `AgentScheduler.accept:111-125` 快照；`accounting:57-75` 拒绝与锁定角色不一致的模型/推理 | WIRED | E1 | Kotlin NOT_RUN |
| 凭据恢复 | `restoreAuthorization:616-621` 只把 `EXPIRED` 改回 `ACTIVE`；不写 pause/device/input。`installCredential:691-695` 不把暂停清成可派发 | WIRED | E1 | Python 合同在 130 项内 PASS；Kotlin NOT_RUN |
| 停止 | `stopGeneration:1213-1219` → `ChatService.stopGeneration:2123-2132` 暂停队列并 `agentScheduler.stop` | WIRED | E1 | NOT_RUN。停止/批准未带 epoch，见发现 F2 |
| 会话库 | Room `AppDatabase.kt:44` version 33 | PRESENT | E1 | 迁移夹具 NOT_RUN |
| Python 任务内核 | `server/agent-runtime`；App 不调用该包 | PRESENT，非 App WIRED | E3-local | 130 PASS |
| 文档撤权 | 当前 `server/document-service` enqueue/claim/finish 围栏 | WIRED 于服务，不是云聊天 | E3-local | 17 PASS；全目录 160 PASS |
| Android 文档身份撤权 | `DocumentServiceRepository` 未发现 identity revoke | ABSENT | E1 | NOT_RUN |
| Office 预检 | Android `OfficePackage.kt:22-24` 折叠大小写并拒绝 `vbaData.xml`。服务端 HEAD 不拒绝；工作树已补 | Android WIRED；服务端工作树 PARTIAL→本轮修复 | E1+E2 | 修复前 2 FAIL，修复后该组 9 PASS |
| Excel 重算 | 服务 README 与 `engine.py:91-92` 将宏执行设为 `NEVER_EXECUTE`。`calcPr` 不等于重算这一条本轮未重读编辑器全文 | PARTIAL / PROVISIONAL | E1 仅引擎标志 | 容器与 LibreOffice NOT_RUN |
| 数学 | Kotlin `MathVerification` / `TaskResultVerifier.kt:60-71`：`UNSUPPORTED`/`DISPROVED` 不作为工具验收成功。Python Q[x] 在当前内核测试内 | PRESENT；App 执行 NOT_RUN | E1 + Python E3 | Kotlin/Flutter NOT_RUN。无题库，不报准确率 |
| 任务 UI 文案 | `agent_tasks_page.dart:16-18` 区分 `SUCCEEDED` 与 `COMPLETED_UNVERIFIED` | PRESENT | E1 | Flutter NOT_RUN |
| 网页 | `PublicWebTransport.kt:38-44` 每跳解析并钉住已判定地址；`WebImageService` 使用同一传输 | PRESENT | E1 | 实网 SSRF NOT_RUN，且未做 |
| OCR 日志 | `OcrTransformer.kt:165-181` 失败路径不回写识别正文 | PRESENT | E1 | 原生 NOT_RUN |
| 跨 App | `CloudFrontend.kt:57` `crossAppEnabled` 固定 false；远程文档工具默认参数 false | PRESENT | E1 | 设备 NOT_RUN |
| Release AAR | `app/build.gradle.kts:347-348` debug/release 分别依赖对应 AAR；缺发布密钥不回退 debug 签名 | PRESENT | E1 | assemble NOT_RUN |

## 旁路补记

同一执行体系的只读勘探在初稿之后返回。下列条目未由本审查者逐行复读，不能升成独立 E1。它们不改变裁决。

| 范围 | 勘探结论 | 状态 | 本审查者 |
|---|---|---|---|
| 主 Chat MCP | `ChatToolFactory.createTools` 不调用 `mcpManager`；工具名必须在 `TrustedToolRegistry` | DISABLED 于主生成集 | 未复读 |
| Skill | 仅 `enabledSkills` 进入；数学内建技能豁免实时复查 | PARTIAL | 未复读 |
| 网页/图片 | 两者都用 `PublicWebTransport`；Flutter 是否仍有 `AsyncImage` 二次下载 | PRESENT / PROVISIONAL | 传输类已读；二次下载未复读 |
| OCR 缓存 | 无原始日志；识别文本在 `cacheDir/ocr_cache.json`，约 64 条、3 天 | PRESENT | 失败路径已读；缓存文件未打开 |
| 备份 | API 密钥在 `noBackupFilesDir` + Keystore；备份规则只含 `file/upload/` | PRESENT | 未复读 |
| 跨 App | `crossAppEnabled=false`；非允许工具在云执行处拒绝 | PRESENT | `cloud.state` 已读 |
| Release AAR | release 坐标不是 debug AAR；`app/libs` 不存在 | PRESENT | Gradle 段已读；AAR 字节未打开 |

Python `hxai_agent.store` 是参考内核，不是手机进程。不能把 130 项 pytest 写成 App 已接入。
