# 审批与 workspace 验证

## 真实审批

设备保留的模型连接由加密配置加载。真实测试使用合成问题和临时学习者／会话，完成后恢复设置并清理会话。

```powershell
.local/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am instrument -w -r -e class 'me.rerere.rikkahub.service.StudyRealModelTest#realApprovalDecisionsAndBoundaries' -e realModel true org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner
```

断言包括 `WAITING_TOOL`、拒绝／同意后的 `COMPLETED`、工具结果已保存、相同及冲突的重复提交保留首次决定、完成后的迟到审批不改变消息。同意 `ask_user` 时按模型生成的问题 ID 提交答案。

`ChatNetworkFaultTest#staleApprovalCannotReplaceAnActiveGenerationJob` 验证不存在或已失效的工具审批不会替换正在生成的任务。`badToolSchemaIsClassifiedWithoutRetryOrLeakingResponse` 验证真实本地 HTTP 400 在服务层及两个 OpenAI API 实现中保留状态码和 schema 分类，不自动重试、不导出原始错误正文。

诊断拦截器包装了响应正文，旧读取函数仅接受 OkHttp 内部 `RealResponseBody` 类型，因而丢弃实际链路的拒绝正文。现在读取通用 `ResponseBody`，HTTP 错误和流内 `response.failed` 保留固定枚举分类；未知原因仍为 UNKNOWN，不推断为网络错误。分类不包含远端自由文本、请求正文、URL 或凭据。

本轮已再次复现同类真实 HTTP 400。服务端返回 `code=prompt_blocked`、`type=invalid_request_error`，安全的拒绝消息为 `Request contains content blocked by prompt filter`。拒绝发生在生成待审批工具之前；不是审批保存接口或工具 schema 报错。

应用现在将 `prompt_blocked` 明确分类为 CONTENT_POLICY 并保留 400；测试改用普通数学学习澄清请求，避免原夹具“必须／禁止”等强制测试措辞。不会自动改写用户消息或重试服务端过滤拒绝。原历史日志仅保存状态码，无法直接追溯当时的正文；上述原因来自本轮相同审批场景的新复现。临时私有正文和捕获代码均已删除，只保留脱敏原因元数据。

## Windows 与 POSIX workspace

workspace 命令使用 POSIX 语法。Windows 自动发现 Git for Windows 的 `sh.exe`，也可通过 `WORKSPACE_POSIX_SHELL` 指定实际 shell。Windows 使用临时脚本传递命令，保留 `awk` 等命令内部的引号，并将 stdin 留给命令。

严格 rootfs 测试要求实际符号链接。无符号链接权限的 Windows 不能满足该文件系统契约，因此完整原测试在 Linux 文件系统运行，不能用复制文件代替链接。

Linux 主机运行：

```sh
cd client
./gradlew :workspace:testDebugUnitTest
```

Windows 运行：

```powershell
scripts/test_workspace.ps1 -WslDistribution Ubuntu -Offline
```

WSL 需要 Java 21。对于没有 Java 的精简发行版，可以传入与该发行版 libc 匹配的 Linux JRE `.tar.gz`：

```powershell
scripts/test_workspace.ps1 -WslDistribution docker-desktop -LinuxJreArchive .local/workspace-posix/jre.tar.gz -Offline
```

脚本先在 Windows 执行 4 个原 shell 测试，再将同一批编译后的测试类和依赖映射到 WSL，执行完整 workspace 测试。临时目录位于 Linux `/dev/shm`，退出时清理。不修改原断言、不跳过完整测试、不开启 Windows 开发者模式。

本轮结果：Windows 4/4，Linux 完整 suite 20/20。原来失败的 5 项均已实际通过，其中符号链接断言在 Linux 通过。普通 Windows Gradle 全量测试仍需要 Windows 符号链接权限；没有该权限时使用上述跨平台脚本。

详细日志位于 `qa/approval-workspace-fix-20261008/`；早期失败日志也保留。
