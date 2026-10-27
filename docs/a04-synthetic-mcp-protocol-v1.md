# A04 合成 MCP 合同 v1（2026-10-21）

来源：Pro inputs/02–03 的扩展/授权目标、A_TASK A04，以及正式 NEW_FEATURE/PROJECT_FULL 标准 v1.1。本增量保留原文门槛。适用完整工作区 codex/a04-synthetic-mcp-20261021；HEAD 5d826d5d7d8755ed42db834d6cd6dc52ae914c69 为共同旧基线，源码版本由本批 SOURCE_FINAL.json 固定。

这是 A04 第一切片：单个合成、固定内容版本、只读 MCP 经主 Chat 接线；只有 agentNativeTest=true 构建且 applicationId=org.hxsi.agent.nativeqa，并由可信测试代码显式安装，才暴露 read_synthetic_mcp_fixture。普通 App 仍关闭共享 MCP。尚无第三方扩展安装/配置 UI、持久服务授权、OAuth、脚本沙箱或依赖安装；不能据此宣传通用 MCP 已可用。

协议采用 [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports) 的无 session、JSON 响应子集，按 [lifecycle](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle) 执行 initialize→notifications/initialized→tools/list→tools/call。仅固定 read_fixture；SSE、服务器请求、session、分页、重定向或未知协议均保守拒绝，不作兼容性或通用客户端声明。描述/schema/annotations/instructions 不成为权限，也不作为动态工具提示传入模型。

权限依然由原生可信 Registry 和现有任务审批建立。mcp.synthetic.read 的风险为 EXTERNAL，未加入 automaticCapabilities；scope 为 owner，完整输入 hash 同时绑定固定 record_id/expected_sha256/installation_id。AgentToolDispatch.checkInput 核 owner、工具、实际 JSON 输入，原 store 核 task/fence/capability/scope/epoch/inputHash/IN_FLIGHT。只读回验允许核已 VERIFIED 的同一调用，普通 checkToolDispatch 仍拒绝 VERIFIED 的再次 dispatch。进程重启丢失 QA 安装状态，但保留任务、批准和 UNKNOWN；不自动安装、重放或释放执行位。

每个物理请求前后核安装实例、任务租约/授权、协程取消。HTTP 使用独立客户端、NO_PROXY、无用户 header/cookie、无重定向/自动重试；地址只能是安装时固定的 127.0.0.1 端口。请求固定四次，无发现循环，响应每次上限 32 KiB，单请求 callTimeout 5s，严格 UTF8，校验 JSON-RPC version/id/result、工具身份和内容形状。此接口不接受私人材料、文件路径、账号凭据、任意网络目标或脚本。

初次成功不等于任务成功。输出必须匹配可信安装的内容 SHA256，独立重新进行完整协议并比较全部回执；Planner 在所有步骤（含未直接命名的步骤）和语义验收请求前后复验。返回文字含指令仍只是 untrusted_data。真实模型如何理解这些数据未由合成故事评估。

映射：Factory.createTools→SyntheticMcpRuntime.toolFor/read→AgentToolDispatch.checkInput→AgentTaskStore.checkToolDispatch/checkToolReadback→TaskResultVerifier.verify→TaskPlanner.accept。原 Chat/Session/GenerationLoop、审批 UI、Room33/sidecar9/Pigeon164 继续使用；无迁移、无新 DTO。

验证与状态以 HXai_CHANGE_REVIEW/A04-SYNTHETIC-MCP-20261021 为准；独立及完整全项目审查 NOT_EVALUATED。扩展授权的适用全项目不变量用已有和新增定向原生反证检验，不声称全库全部审完。回滚只撤回本批增量，保任务/回执/UNKNOWN/文件；不得裸 checkout 旧 HEAD 覆盖合流成果。
