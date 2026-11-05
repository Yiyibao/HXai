# 裁决

**CONDITIONAL**

审查对象：工作树 `codex/storage-lifecycle-20261103`，提交 `54b832613c1b4ab3eefbecedca5367c274b60135`，加上未提交修复。远端 `main` 仍是 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。安全分支仍是 `d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59`，未并入 main。

独立审查：**NOT_EVALUATED**。本轮是同一执行体系重读和重跑，不是外部审查。

不授予：App、Release、Mate50、HarmonyOS、真实模型、Docker/LibreOffice/MinerU、生产。

## 本轮重跑

| 命令 | 结果 |
|---|---|
| `PYTHONPATH=server/agent-runtime python -m pytest -q server/agent-runtime/tests` | 130 通过，19.85s |
| `python -m pytest -q server/document-service/tests` | 164 通过，9.03s |
| Flutter / Gradle / 设备 / 容器 / 生产 | NOT_RUN。无 Flutter、SDK、adb |

未在原包执行 `run_verification.py`。存储预检 PASS，1 个工作树。

## 本轮重读

- 任务槽仍是 `agent-tasks-v1.db` 单例。`claim` 在槽占用、`UNCERTAIN_SIDE_EFFECT`、暂停或独立等待时拒绝，fence 递增。
- 停止、重试、切分支、教学模式、工具批准的 Pigeon 源、生成两端和宿主都带 `expectedEpoch`，副作用前再次 `requireProfile`。
- 会话详情的 `activeModelId` / `activeModelName` 现为 null。当前选择只在 bootstrap。
- 无记账上下文的 `beginProviderRequest` 直接失败，不再返回 null 后发请求。
- 文档身份撤权只在服务端 `revoked=true` 后改本地配置。本轮发现并改了顺序：先写禁用配置，再删凭据，避免写失败后配置仍指向已删密文。
- OCR 新缓存在 `noBackupFilesDir/ocr-cache-v1.json`，旧 `cacheDir` 文件删除且不迁移。
- Office 预检拒绝大小写签名目录、`vbaData.xml`、宏内容类型、签名/宏关系、全部外部关系和坏 ZIP。富样例仍在 164 项内通过。含超链接的用户文件现在也会被拒绝，这是与 Android 对齐，不是放行。

## 仍阻断放行

Pigeon 生成文件是手工对齐，不是 `pigeon` 重跑。Kotlin 宿主未编译。设备、Release、容器和生产未跑。这些未跑项不能写成通过。
