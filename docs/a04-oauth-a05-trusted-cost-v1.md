# A04 OAuth / A05 可信费用协议增量 v1

2026-10-22，继承 Pro inputs/02 E/H、inputs/03、正式 10_REVIEW_SYSTEM v1.1 与 A04 通用服务 v1。精确源码、命令、负对照、patch 和裁决在 `HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/`；共享旧 HEAD 不代替 SOURCE_FINAL。未启用子代理；独立审查与完整全项目审查 NOT_EVALUATED。

## OAuth 连接

设置 → MCP 与 Skill 计算服务 → 填安装标识、HTTPS MCP 地址 → 展开 OAuth 浏览器连接。输入服务提供的 issuer、已注册的公开客户端 ID、所需 scope。发现后逐项确认 resource/issuer/授权端点/兑换端点/回调与 scope，再进入系统浏览器。回到 App 刷新，点击“完成连接”。首次产生无工具的停用草稿；继续用已授权凭据发现服务、确认逐工具 schema/参数范围、保存并显式启用。每次 Chat 外发仍需原任务审批。更新已有安装需先停用。

支持 HTTP MCP 的公开客户端 authorization_code + PKCE S256/resource/state；RFC9728 的 WWW-Authenticate 或同源 well-known 资源发现、RFC8414/OIDC 发行者发现与精确 issuer/resource 校验。client ID 由用户预注册，回调地址在确认框展示（按实际 applicationId 区分普通/QA）。不在手机保管 client_secret。元数据中的跨源资源发现入口保守拒绝；授权/兑换端点可以由元数据声明，但必须在前台明确确认，且只使用 HTTPS、无重定向/自动重试的专用客户端。该客户端不继承模型/浏览器 Cookie、代理、Key 或拦截器。兼容性是明确子集，非全 MCP OAuth 认证声明。

OAuth journal schema1 位于 noBackup；verifier/code 在 CredentialVault 独立密文引用内。OS 回调只能保存匹配的单次 state，不联网、不启用安装、不创建任务许可；重复参数、错 redirect/issuer、过期、已撤权/更新安装拒绝。前台完成绑定 owner/contextEpoch/安装 id+revision。兑换前持久 EXCHANGING；丢 ACK/进程死亡仍保持未知，不能自动兑换第二次。重新授权或清理须用户显式动作。成功只保存 access token 与 issuer/resource/scope/expiry 元信息，仍 DISABLED；新安装 id/revision/grantEpoch 使旧任务审批失效。返回扩大或缩小的 scope 拒绝；缺少有效期拒绝。refresh token 不保留；到期前台重新连接，绝不自动增权或恢复暂停/撤权/输入/设备等待。撤权/卸载清理安装 token 与待授权密文，保留审计/任务/成果/UNKNOWN。

## 费用来源及计算

所有物理模型请求继续复用原 accounting。TaskStore schema9→10 只加 usage_costs 表：request_id、admission 时冻结的 basis、完成后不可冲突覆盖的 receipt。旧请求不补造历史价格，旧任务/slot/fence/grant/receipt/模型快照保留。Room33、原 Pigeon 类型 ID128–164 不变；四种 Usage DTO 各追加 nullable costJson 并生成 Kotlin/Dart 两端。旧客户端不应消费新桥/新 schema10；降级保留新库并停用执行器，不清库。

已实现两种可信来源：

- OpenRouter Chat Completions：只接受真实最终 HTTPS 响应地址 `https://openrouter.ai/api/v1/chat/completions` 的 usage.cost，且 admission 锁定的 base 是同一官方服务。流式最后 usage 和非流式都支持。金额与 USD 来源独立于 Token counters，不使用 upstream_inference_cost 代替用户扣费。自定义兼容服务即使返回同字段也保持未知。依据：[Usage Accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting)、[USD credits](https://openrouter.ai/support)。这是该请求报告金额，不包括充值手续费/税/其他账户活动，不冒充完整账单。
- OpenAI 官方标准文本 GPT‑4.1 / gpt-4.1-2025-04-14：2026-10-22 核对[官方模型价格](https://developers.openai.com/api/docs/models/gpt-4.1)，每百万输入/缓存输入/输出为 USD 2/0.5/8。admission 固定价格版本、源 URL、核对时间及到期时间，计算 receipt 带 basis SHA。缓存是输入子集，按 `(input-cached)*2 + cached*0.5 + output*8` / 1,000,000，金额始终 **estimated**。缺输入/输出/缓存、负数/不一致 counters、不支持模型或域名、非标准 tier、batch/内置收费工具/音视频/图片/文件/预测输出等不套用该价格。价格快照仅适用 2026-10-22 UTC 至 2026-11-21 UTC 前的 admission；到期新请求未知，旧 receipt 不重新定价。更新价格须再次核公开官方来源并版本化代码。

其他供应商、OpenRouter Responses、MCP 服务本身收费、历史/失败/在途未知请求，无对应可靠来源时费用保持 unknown；不默认免费。未知 Token 不推出未知金额，已报告金额也不补造 Token。Decimal 字符串与 BigDecimal 运算，校验数字长度/精度/指数，避免浮点误差和非有限值；有效明确的零金额可显示零。

## UI、统计与导出

任务详情/任务中心的实际用量账本同时显示费用。逐请求可展开来源；任务、档案、提供商、模型、角色及设备时区日/周/月沿原查询/分页范围聚合。reported 和 estimated、币种、unknownRequests 分开；已知小计不是完整账单，不混币或将未知记零。每个分页水位和 owner 过滤在同一 Store 事务中同时用于 Token 和费用。

JSON 快照保持原 envelope schema1，并增加 costSchemaVersion1、每记录 cost、范围 cost.totals/unknownRequests。金额和整数均字符串；unknown amount/currency 为 null，混合小计取 totals，不伪造单一总金额。旧已签发快照原字节与旧验证合同仍可回读；新快照绑定原清单/hash/sourceSha，导出幂等重用当时金额。没有模型切换、Token/费用硬预算或新的推理执行器。

## 源码地图与验证范围

`ExtensionFrontend → ExtensionOAuth/CallbackActivity → ExtensionInstallationStore → ExtensionMcpRuntime`；`ChatCompletionsAPI/Decoder → TokenUsage → AgentExecution.accounting → AgentTaskStore/AgentCostLedger → Ledger/Groups/Snapshot → StudyHostFacade/Pigeon → AgentUsageView`。测试使用自有 loopback、合成凭据、独占模拟器；不调用真实账号/付费模型。真实外部 OAuth 服务、TLS 部署、目标 Mate50/Release/生产及完整独立审查不由本机验证授予通过。
