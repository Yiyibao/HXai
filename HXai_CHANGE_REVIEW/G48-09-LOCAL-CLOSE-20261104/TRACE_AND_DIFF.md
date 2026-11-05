# TRACE_AND_DIFF

HEAD `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1` 不包含这些未提交字节。清单在 `SOURCE_MANIFEST.json`。没有提交、推送或部署。

- `ExtensionRuntimeTest.ordinaryManagementEntryGrantsCallsRevokesAndRejectsTheOldEpoch` 走 `ExtensionFrontend.handle`：`extensions.install`、`extensions.command`、`extensions.grant`、`extensions.revokeTool`，再调用 `ExtensionMcpRuntime.invoke`。
- 未授权、撤销后、旧安装 id、新代次未重授，都不增加 `tools/call`。再授权后同一工具可调用。
- `HostAcceptanceRuntimeTest` 增补区间写法与颠倒区间，以及圆锥交点、一致收敛保持 `COMPLETED_UNVERIFIED`。
- `UncertifiedGoalTest` 锁定这两则题面不被四个现有解析器认领。
