# CHANGE_CONTRACT

FeatureID: G47-01-BUILD-BASELINE-20261104

用户故事：当前 HEAD 的 Flutter/Pigeon/Debug 宿主包可以复现构建，旧 epoch 命令在 API26/API34 上零副作用拒绝，合法当前 epoch 仍有回执。

base: fac15db2f0be0336c0c7c5bfd2f2a4b2a30dcdbf
head: 本批提交后的 HEAD。开工时工作区还有用户未提交的 AGENTS.md 与 docs/pro-development-baseline-20261012.md，未纳入本批。

入口：Pigeon `study_host_api.dart` → 两端生成文件；Gradle wrapper + 绝对路径 `scripts/storage.init.gradle`；Debug APK `org.hxsi.agent.debug`；仪器测试 `HostEpochAndDocumentRevokeTest`。

schema/owner/profile/epoch：未改协议。测试只验证旧 epoch 在副作用前拒绝。

模型/授权：无新模型、无生产部署、无付费调用。

副作用：重新生成 Pigeon（无 diff）；构建 Debug/androidTest APK；在外部 AVD 安装并跑测试；退出后删除模拟器 `default_boot` 快照。

成功：Pigeon 无 diff；Flutter analyze/test 通过；Debug 组装、app/ai/search 单测和 lint 通过；两台模拟器 epoch 测试通过。

失败：Release 包因 `google-services.json` 没有 `org.hxsi.agent` 不能组装。Debug 引擎绑定因 SDK 没有 `android-x64/flutter.jar` 不能完成。相对 `-I` 路径到不了门禁。

兼容/回滚：产品行为无新增代码路径。回滚本批文档与 `storage.init.gradle` 注释即可。不要删除用户的 Firebase 配置，也不要用 debug 密钥签 Release。
