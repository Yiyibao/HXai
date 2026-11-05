# HXai 全项目审查详细报告

审查日期：2026-11-04。执行者：同一执行体系。独立外部审查：**NOT_EVALUATED**。

裁决：**CONDITIONAL**。

不授予完整 App、Debug/Release 安装包、正式签名、Mate 50、HarmonyOS 4.2、真实 Provider、Docker/LibreOffice/MinerU、生产网关。

## 1. 快照

| 项 | 本轮事实 |
|---|---|
| 分支 | `codex/storage-lifecycle-20261103` |
| HEAD | `54b832613c1b4ab3eefbecedca5367c274b60135` |
| 工作树 | 唯一。18 个已跟踪文件有未提交 diff，约 +252/−53。另有审查目录未跟踪 |
| 用户未提交、本审查未改 | `AGENTS.md`、`docs/pro-development-baseline-20261012.md` |
| 远端默认分支 | `refs/heads/main` = `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` |
| 安全分支 | `pro/hxai-safety-delivery-20261012` = `d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59`，未并入 main |
| 存储 | `project_storage.py check` PASS。1 个工作树。逻辑占用 653554460 字节 |
| Java | 21，在 PATH |
| Flutter / kotlinc / adb / ANDROID_HOME | 不可用或未设置 |
| `client/local.properties` | 本会话早前确认不存在；本轮未重建 SDK |

审查对象是当前 feature 分支加未提交修复，不是 origin/main。main 仍是 2026-10-11 的交付提交。

未执行 `reset`、`clean`、`stash`、推送或合并。未在原 `HXai_Pro_Delivery` 运行 `run_verification.py`，避免覆写原 evidence。

## 2. 方法与未读范围

本轮重新查询远端，重读启动入口、任务 claim/dispatch、凭据恢复、会话详情模型字段、文档撤权、Office 预检、OCR 缓存路径、模型请求准入、Release AAR 坐标和 Room 版本，并重跑两套 Python 合同。

未逐文件重读，保持 `PROVISIONAL`：备份/Keystore 全链、MCP 每个服务器的运行时授权、Flutter 图片组件是否二次下载、教材印刷页绑定、容器调度器。邻近模块结论不外推。

## 3. 源码追踪

| 范围 | 本轮入口 | 状态 | 证据 | 测试 |
|---|---|---|---|---|
| 启动 | `AndroidManifest.xml:119-122` MAIN/LAUNCHER → `StudyFlutterActivity` | WIRED | E1 | 设备 NOT_RUN |
| 全局执行位 | `AgentTaskStore.claim:210-224`。单例 slot；占用或 `UNCERTAIN_SIDE_EFFECT` 拒绝；fence+1；暂停和独立等待不能 claim | WIRED | E1 | Kotlin NOT_RUN。Python 内核 130 PASS，不是 App |
| 派发租约 | `requireDispatchLease:232-239` 要求 RUNNING、未暂停、ACTIVE，且无输入/设备/确认/取消/审批阻塞 | WIRED | E1 | Kotlin NOT_RUN |
| 凭据恢复 | `restoreAuthorization:616-621` 只把 `EXPIRED` 改回 `ACTIVE`。不写 pause、device、input | WIRED | E1 | Kotlin NOT_RUN |
| 命令 epoch | 停止/重试/切分支/教学模式/批准在宿主副作用前 `requireProfile(profileId, expectedEpoch)` | WIRED | E1 | Flutter/设备 NOT_RUN |
| 会话模型 | `getConversationDetails:863-873` 的 `activeModelId/Name` 为 null。注释写明当前选择在 bootstrap | PRESENT | E1 | UI NOT_RUN |
| 模型请求 | `beginProviderRequest:42-44` 无记账上下文即失败，返回类型不再可空 | WIRED | E1 | JVM 单测 NOT_RUN |
| 会话库 | Room `AppDatabase.kt:44` version 33 | PRESENT | E1 | 迁移夹具 NOT_RUN |
| 任务库 | 侧车 `agent-tasks-v1.db`，不是 Room，也不是 Python `hxai_agent` | WIRED | E1 | Python 130 PASS 只证明参考内核 |
| 文档撤权 | `POST /api/v1/identity/revoke`；`revokeIdentity:83-98` 先确认 `revoked=true`，再写禁用配置，再删密文 | WIRED 于源码 | E1 + 服务 E3 | 服务 164 含该合同。Android NOT_RUN |
| Office 预检 | `inspect_package:51-94` 拒绝坏 ZIP、大小写签名目录、`vbaData.xml`、宏内容类型、签名/宏关系、全部外部关系，并核对 CRC | PRESENT | E2 | 含在 164 PASS |
| OCR 缓存 | `OcrTransformer.kt:37-40` 删除 `cacheDir` 旧文件，新文件在 `noBackupFilesDir/ocr-cache-v1.json` | PRESENT | E1 | 设备 NOT_RUN |
| Release AAR | `app/build.gradle.kts:347-348` debug/release 分别依赖对应坐标 | PRESENT | E1 | assemble NOT_RUN |
| 主 Chat MCP | `ChatToolFactory.kt` 仅声明 `mcpManager`，该文件内无其他引用 | DISABLED 于该工厂 | E1 | 其他模块未逐一重读 |

`WIRED` 不是 `PASS`。Python 130 项不是 Android → 真模型 → 远程服务。

## 4. 本轮测试

| 名称 | 退出码 | 结果 |
|---|---|---|
| `PYTHONPATH=server/agent-runtime python -m pytest -q server/agent-runtime/tests` | 0 | 130 passed，20.64s |
| `python -m pytest -q server/document-service/tests` | 0 | 164 passed，1 条 Starlette 弃用警告，10.81s |
| Flutter analyze/test | — | NOT_RUN，flutter 不在 PATH |
| Gradle unit/lint/assemble | — | NOT_RUN，无 SDK/`local.properties` |
| Kotlin 助手 JVM | — | NOT_RUN，kotlinc 不在 PATH |
| 设备 / 容器 / 生产 / 真模型 | — | NOT_RUN，无 adb、无授权 |

一次性测试目录由存储工具回收。没有把历史计数累加进本轮。

## 5. 发现

### R3-1 Pigeon 未由生成器重跑

五个命令的末位 `expectedEpoch` 已在 `study_host_api.dart`、`study_host_api.g.dart`、`StudyHostApi.g.kt` 和 `StudyHostFacade` 对齐。本机不能运行 Pigeon 29.0.7，不能证明生成文件与生成器输出逐字节相同。

严重度 S2，置信度中。不是已证实的跨档案执行。下一条命令：在项目外 Flutter 环境生成两端后 diff。

### R3-2 宿主与设备门禁未跑

任务槽、模型锁、epoch 命令、文档撤权和 OCR 缓存路径有源码。没有 Debug/Release 构建，没有模拟器，没有 Mate 50。不能把源码在场写成这些门禁通过。

严重度是发布门禁，不是已复现的 Release 崩溃。

### R3-3 既有产品缺口，不是本轮回归

- 自然语言目标的最终接受仍可询问模型。工具谓词和 `UNSUPPORTED`/`DISPROVED` 不能单独变成工具成功；这不等于自然语言目标已有确定性验收器。
- 主 Chat 工厂没有使用 `mcpManager`。要接入仍须先有逐服务器授权，不能把 schema 当权限。
- 含外部超链接的 OOXML 会被当前服务端预检拒绝。这是与 Android 对齐后的行为，富样例仍在 164 项内通过。
- 容器停止、Office 像素保真、教材印刷页、生产部署均未在本轮执行。

没有新的已证实 S0。没有新的已证实且未修复的 S1。

## 6. 裁决理由

不能 `PASS_SCOPED`：声明过的 Android、Flutter、Release、设备和生产门禁全部 `NOT_RUN`。局部 Python 通过不能升为 App 或生产通过。

不能 `BLOCK`：本轮没有证实未修复的 S0/S1。已提交 HEAD 上的旧预检缺口仍在工作树修复中，未提交。发布 main 或已签名 Release 是另一棵树/另一层，本裁决不放行。

## 7. 未提交修复的范围

工作树里的产品修复包括 Office 预检、epoch 命令、会话详情模型字段、文档自撤权、无记账拒绝、OCR 缓存位置，以及撤权时先写配置再删密文。这些都未提交。回滚是还原对应文件，不涉及 Room 迁移。

不要把 `AGENTS.md` 和开发基线说明混进该修复提交。

## 8. 下一条

1. 项目外 Flutter 重新生成 Pigeon，并 diff 三个文件。
2. 有 SDK 后跑 `flutter analyze`、`flutter test`，以及宿主 Debug 单测和 lint。
3. 已归属设备上复验双档案抢槽、旧 epoch 停止/批准、文档撤权。
4. 新撤权路由先于依赖它的客户端发布。旧服务没有该路由时，客户端应失败并保留本地凭据。
