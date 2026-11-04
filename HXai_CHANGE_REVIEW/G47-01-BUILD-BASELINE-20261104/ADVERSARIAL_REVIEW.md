# ADVERSARIAL_REVIEW

同一执行者复审，不是外部独立审查。

假成功：相对 `-I scripts/storage.init.gradle` 配合 `-p client` 退出码 1，但失败原因是脚本路径被解析到 `client/scripts/`，不是门禁生效。绝对路径且没有 `HXAI_RUN_DIR` 才出现预期的 “Run through scripts/project_storage.py run first”。Debug 资源 38 项一致不能冒充引擎绑定通过，因为 `android-x64/flutter.jar` 不存在。

正确输入被拒：API26 与 API34 的 `HostEpochAndDocumentRevokeTest` 各 2 项通过。旧 epoch 在停止/批准前拒绝；当前 epoch 通过且不启动生成任务。文档撤权在服务未确认时保留凭据。没有发现本批把合法当前 epoch 误拒。

未覆盖：`FlutterChatBridgeTest` 未在本批设备上重跑。Release 包未生成。
