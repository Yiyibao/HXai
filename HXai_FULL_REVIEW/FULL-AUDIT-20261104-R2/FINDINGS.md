# 本轮发现

上一轮 F1–F5、F7 的修复仍在工作树，本轮重读未发现回退。F6 仍不是当前产品缺陷。

## R2-1 撤权后先删凭据再写配置

| 项 | 内容 |
|---|---|
| 位置 | `DocumentServiceRepository.revokeIdentity` |
| 实得 | 服务端已确认后，原先先 `vault.discard` 再写禁用配置。写配置失败时，配置仍引用已删除密文 |
| 修复 | 先写入 `enabled=false` 且空引用，再删除密文。写失败则凭据仍在，可重试 |
| 证据 | 静态。Android 单测 NOT_RUN |
| 严重度 | S2 / 高。未造成跨身份外流 |
| 状态 | 本轮已改，未提交 |

## R2-2 Pigeon 未由生成器重跑

| 项 | 内容 |
|---|---|
| 位置 | `study_host_api.dart`、`study_host_api.g.dart`、`StudyHostApi.g.kt` |
| 实得 | 五个命令的末位 epoch 在三处一致。本机没有 Flutter，不能证明与 Pigeon 29.0.7 生成结果逐字节相同 |
| 严重度 | S2 过程风险 / 中 |
| 状态 | 未改。下一条是项目外 `flutter` 生成后 diff |

## 未升为缺陷

- 含外部超链接的 OOXML 现在被服务端拒绝。Android 原本就拒绝。富样例测试仍通过。
- 停止命令不持有 `commandMutex`。epoch 和档案检查仍在副作用前执行。未证实跨档案停止。
- 自然语言目标的模型判断、MCP 未进主 Chat、容器未跑、生产未部署，都是既有缺口，不是本轮回归。
