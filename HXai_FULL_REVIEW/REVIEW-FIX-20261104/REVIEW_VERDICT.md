# 复审裁决

**CONDITIONAL**

范围：`REVIEW-FIX-20261104` 工作树修复，基线仍是未提交的 `54b832613c1b4ab3eefbecedca5367c274b60135`。独立审查 `NOT_EVALUATED`。

## 已修并复读

| 项 | 复审 |
|---|---|
| Office 预检 | 与 Android 对齐：大小写签名、`vbaData.xml`、宏内容类型、签名/宏关系、全部外部关系、CRC/坏 ZIP。文档服务单测 164 通过，含富样例 |
| contextEpoch | 停止、重试、切分支、教学模式、工具批准在 Pigeon 源、生成两端和宿主都带 epoch，并在副作用前 `requireProfile` |
| 会话模型 | `ConversationDetailDto` 不再填实时模型。当前选择仍只在 bootstrap |
| 文档撤权 | `POST /api/v1/identity/revoke` 幂等且不回身份。本地凭据只在 `revoked=true` 后删除。复审补了确认框，避免误触 |
| 模型准入 | 无记账上下文直接拒绝，不再返回 null 后继续请求 |
| OCR 缓存 | 新文件在 `noBackupFilesDir`；旧 `cacheDir` 文件删除，不迁移正文 |

## 复审新发现

撤权按钮最初没有确认。已补对话框；取消或档案/epoch 变化不发请求。

未再发现可在本环境安全修复的 S0/S1。

## 未跑

Flutter 不在 PATH，Pigeon 生成文件是按现有通道手工对齐，不是 `pigeon` 重新生成。Gradle、设备、Release、容器、生产未跑。因此不能给 App 或生产 `PASS_SCOPED`。

F6 仍不是当前产品缺陷，未改冻结历史包。
