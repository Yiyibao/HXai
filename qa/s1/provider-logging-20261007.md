# 模型请求与聊天运行日志清理（2026-10-07）

沿 S1 凭据与日志门禁推进。现有 OpenAI Chat Completions / Responses、Claude、Google GenerateContent / Interactions 适配器会记录请求正文、回复事件、解析错误正文与异常堆栈；全局 HTTP 拦截器还会记录头、完整 URL 与正文，仅遮蔽 Proxy-Authorization。

## 改动

- 删除上述适配器的正文、事件、图片地址及异常日志；只保留固定的流缓冲失败提示。响应解析与错误传播继续执行。
- 移除 OkHttp HEADERS 日志拦截器，避免 Authorization、x-api-key、自定义头或带凭据的 URL 进入 Logcat。
- 应用请求日志只保存 HTTP 方法、状态码、耗时及固定错误提示。URL 使用固定占位符，头为空，正文为空；不读取或重复序列化请求体。
- 聊天生成层移除工具参数、原始异常、堆栈和附件路径日志；网络重试日志保留次数与延迟，移除 throwable。

## 验证及边界

- AI 模块 197 项 JVM 回归通过；日志 `.local/s1-provider-log-tests-20261007.log`。
- Debug 最终构建与 app 245 项 JVM 回归通过（0 失败）；记录 `.local/s1-provider-log-final-20261007.log`。临时 Firebase 占位配置已移除。
- 本批未配置或读取真实密钥，未执行真实模型请求、模拟器安装或 Mate 50 验收。
- C07 仍未通过：Keystore 凭据引用与旧配置迁移、备份/导出排除、其他模块异常与模型上下文的全链路审查仍待完成。旧版已写入的日志没有在本批自动删除。
