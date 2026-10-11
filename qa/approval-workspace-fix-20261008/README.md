# 审批与 workspace 修复证据

| 验证 | 结果 | 日志 |
|---|---|---|
| 真实远端 400 原因 | `prompt_blocked`：服务端 prompt filter 拦截 | real-provider-rejection.json |
| 真实待审批→拒绝／同意→完成，重复／冲突／迟到提交 | 通过 | real-prompt-filter-fix-final.log |
| 400 schema／prompt filter、401／429、不自动重试、失效审批不替换活动任务 | 5/5 | local-prompt-filter-fix-final.log |
| ai JVM | 200/200，无跳过 | build-unit-tests.log、test-summary.json |
| app JVM | 317/317，无跳过 | build-unit-tests.log、test-summary.json |
| Windows 原 shell 测试 | 4/4，无跳过 | workspace/windows-shell.xml |
| Linux 完整原 workspace 测试 | 20/20，无跳过 | workspace/posix-all.log |

旧读取函数按 OkHttp 内部具体类型判断，诊断拦截器包装后的正文被丢弃。新的 OpenAI 错误读取和固定分类已通过实际本地 HTTP 回归，包括诊断拦截器。

真实 400 在待审批工具生成前发生。服务端未提供触发过滤的具体片段；测试请求现使用普通数学学习澄清措辞。应用不自动改写或重发被过滤的请求。历史那次只保存状态码，不能恢复其正文；本目录的原因来自同一审批场景的新复现。

先前的远端失败、超时和开发阶段失败日志保留。最终验收使用上述 final 日志与 test-summary.json 中的 APK 哈希，不将早期失败记作通过。临时原始拒绝正文和捕获代码已清理。

Windows 没有符号链接权限时，完整 suite 使用 scripts/test_workspace.ps1 在 WSL 的 Linux 文件系统执行。没有改动 workspace 原测试或断言，没有用复制文件伪装链接。
