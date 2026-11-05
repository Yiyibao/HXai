# 裁决

**PASS_SCOPED**

范围：普通 App 的 `extensions.grant` / `extensions.revokeTool` 写入，以及派发前按安装代次和工具契约复核。启用、OAuth 和安装不再被当成授权。旧空契约行不能通过 `current()`。证据是 `ExtensionToolAdmissionTest` 编译并通过，前端命令已进入 `ExtensionFrontend`。不是 API26 管理入口仪器测试，不是真实外部 MCP 服务。独立审查 `NOT_EVALUATED`。

回滚：恢复 `ExtensionToolGrantStore` v1 读写，去掉前端两个命令和 Flutter 按钮。不要重新打开旧共享 McpManager。已撤销的行不要恢复。
