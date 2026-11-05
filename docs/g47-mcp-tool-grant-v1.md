# MCP 逐工具授权 v1

安装启用和服务器返回的 schema 不是授权。主 Chat 只列出 `extension-tool-grants-v1.db` 里当前 installation id 的工具。调用前再查同一记录，没有记录就拒绝，不发传输。

一个工具的授权不覆盖同安装的其他工具。安装 id 变化后旧授权不再匹配。共享 `mcpManager` 仍不进入 Chat 工具列表。
