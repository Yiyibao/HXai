# HANDOFF

启用安装和工具 schema 不再让 MCP 工具进入主 Chat。`ExtensionToolAdmission.visible` 只返回已写入 `extension-tool-grants-v1.db` 的工具名。`invoke` 在发传输前拒绝未授权工具。

JVM `ExtensionToolAdmissionTest` 2 通过。扩展仪器测试已改为显式 `grant`，本轮没有重跑。`mcpManager` 仍未塞进 `ChatToolFactory`。
