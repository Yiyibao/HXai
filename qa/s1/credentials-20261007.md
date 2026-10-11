# Keystore 凭据配置与备份排除（2026-10-07）

## 实现

包含凭据的设置分区（providers、assistants、自定义模型头/请求体、network、search、MCP、WebDAV、S3、TTS、ASR 和 Web 访问密码）整体加密；DataStore 保存 `credential-v1:<UUID>` 引用。采用 AndroidKeyStore AES-256/GCM、由 Cipher 生成随机 IV、引用作为 AAD；密文记录位于 `noBackupFilesDir/credentials-v1`。读取后在进程中还原现有 Settings，现有聊天调用无需把引用当作密钥使用。

先写入不可变记录并验证可解密，再提交 DataStore。设置写入成功后才发布到内存；更新复用内容相同的旧引用，旧记录不提前覆盖。迁移置于原 V1–V3 之后，失败不返回新 Preferences，不回退空配置。已有引用、缺失记录及认证失败不能作为空字符串继续使用。

设计依据：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[AES/GCM KeyGenParameterSpec 示例](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)。未要求每次使用生物认证或 StrongBox，不声称已确认 Mate 50 的硬件保护等级。

## 备份和 Web

- 本地/WebDAV/S3 共用 BackupManager，导出前去除 API 密钥、私钥、各级自定义头/请求体、接口 URL/路径及其他服务凭据；保留模型和助手 ID。
- 旧 ZIP 恢复暂存时也清理凭据。恢复后需要重新配置连接，Web server 默认关闭。备份页面提供提示。
- Web settings SSE 使用同一清理函数；服务端实际调用仍使用原 Settings。不能把 SSE 的清理副本写回运行设置。
- Android 12+ 云备份和设备迁移均收紧为仅包含 upload，旧 fullBackupContent 已为同一范围；密文 vault 位于 no-backup，普通 DataStore 不随系统备份传递。

## 验证

首轮应用 JVM 回归与 Debug / AndroidTest 构建成功，5 项合成凭据 Android 测试通过：重开读取、无明文 canary、篡改拒绝、缺失/路径非法拒绝、迁移幂等和失败保全。日志 `.local/s1-credentials-build-20261007.log` 与 `.local/s1-credentials-android-20261007.log`。

最终 app 246 项 JVM 回归（0 失败）及 Debug/AndroidTest 构建通过，日志 `.local/s1-credentials-verified-20261007.log`；临时 Firebase 配置已移除。最终 APK 覆盖安装后，7 项凭据仪器测试连续两轮通过，第二轮先 force-stop 再启动新进程；包括实际应用设置文件与备份 ZIP 检查。证据 [JSON](credentials-20261007.json)，重复入口 `scripts/test_s1_credentials.ps1`（使用已构建 APK）。

已有 ChatNetworkFaultTest 16 项通过，日志 `.local/s1-credentials-network-regression-20261007.log`；使用本地 HTTP/SSE 和合成设置，恢复原设置，不作为真实模型请求证据。最终构建前还清理了设置读取异常链，防止解密 JSON 被序列化错误打印。

## 未完成边界

本批不宣告 C01/C07 或完整 S1 通过。尚需真实模型冷启动往返、Keystore 失效的可恢复 UI、备份恢复的跨设备端到端验证、所有导入导出/异常/模型上下文路径审查及 Mate 50 验收。历史明文日志/手工备份不自动清除。为防并发旧快照失去引用，本批保留旧加密记录；后续需在完整引用审查后实现安全回收与资源预算。备份是无凭据的学习数据备份，不是可跨设备恢复密钥的加密备份。
