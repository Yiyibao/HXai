# A04 通用 MCP / Skills 协议增量 v1（2026-10-22）

产品依据为 Pro inputs/02 的 E、inputs/03 工具契约、A_TASK A04；正式门禁继续取 Pro 10_REVIEW_SYSTEM 原文。本协议不改写原规范。本轮独立和完整项目审查均未执行；开发、定向验证和对抗反例由同一执行者完成。准确源码、原始失败和裁决取 `HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/`。

## 使用入口与实现范围

设置 → **MCP 与 Skill 计算服务**，Skills 页也提供同一入口。输入安装标识、精确 HTTPS MCP 地址和可选的服务专用 Bearer，确认连接发现；选择确认可独立复读的工具，生成 schema 与逐参数范围配置，确认安装后仍处于 DISABLED，再显式启用。每次实际调用仍进入原统一 Chat 的任务审批，不在管理页绕过任务执行器。更新先停用；编辑后重新安装/授权，不能热替换运行中的工具。

通用工具名为 `call_authorized_mcp`，输入为 installation_id、tool、arguments（严格 JSON 对象的字符串）。服务身份、工具 schema、固定脚本 SHA 与依赖镜像 SHA 均由前台确认后 pin；第三方 instructions/description/annotations 不进入可信策略。服务 name/version 是声明 pin，不是密码学身份；TLS 由系统信任库验证固定连接对端。

支持 Streamable HTTP 的 JSON 和有界 SSE 响应、会话标识、工具分页以及明确 pin 的 2025-03-26/2025-06-18/2025-11-25。会话仅在一次调用或独立回读内存在；不自动恢复、重连或重放 POST；不发送 GET 重放历史事件。拒绝重定向、继承代理/cookie/拦截器、服务器反向 sampling/roots/elicitation 请求；资源链接和图片不自动下载。普通 App 仅 HTTPS，隔离 nativeqa 额外允许 127.0.0.1 HTTP。

本版支持用户确认的只读工具一致性复读，以及自有隔离 Python Skill 的独立回执合同。只读复读证明返回数据一致，不证明第三方事实真实、任意远端写入完成或数学定理成立。外部发送/删除/付款等尚无专属读回适配器，不能选作可重复只读工具。OAuth 浏览器授权、旧 SSE transport、stdio 和不支持的 JSON Schema 关键字明确拒绝，不偷偷降级或绕过认证；需要 OAuth 的服务显示 NEEDS_AUTH。手动 Bearer 只用于用户确认的精确 endpoint，不自动挑战升级 scope 或刷新权限。

## 权限、数据与恢复

新增 noBackup SQLite `extensions-v1.db` schema1，多 owner/多 slot；原 `mcp-installations-v1.db` 的 QA 合同不变。持久 owner/slot/id/revision/grant_epoch/state/config/credential-reference、管理事件和独立 credential_cleanup 队列。Keystore AES-GCM 使用既有 CredentialVault；token 不进模型、目录 DTO、事件、备份或输出。撤权/卸载先持久化失效和墓碑，再清除该安装的加密凭据；待清除引用独立保留，不能恢复可调度权限。

enable/disable/revoke/uninstall/install 均 CAS 真实 owner+slot+id+revision，并递增 revision/grant_epoch、旋转 installation_id。REVOKED/UNINSTALLED 不能直接 enable；重新安装也默认停用。损坏库和未来库拒绝并保全，不删除重建。它不改 Room33、TaskStore sidecar9、Pigeon164、记忆 payload 或主模型/推理快照。前端复用已有受保护 MethodChannel，新增 `extensions.*` payload version1；没有修改 Pigeon，故无需生成新的两端文件。

管理命令仅前台、带 owner/contextEpoch，宿主再次检查；模型没有管理工具。Flutter 根据真实快照更新，切档案清空旧页面/凭据编辑器并丢弃旧异步回执，恢复前台或刷新时重新读取。无乐观伪造的已启用/已撤权状态。

可信注册表固定 `extension.invoke / EXTERNAL / EXTENSION_READBACK`。所有调用都需输入绑定任务审批；工具 metadata 不能降低风险。Factory 发现 → GenerationLoop → AgentToolDispatch → TaskStore IN_FLIGHT → MCP → Room 持久结果 → TaskResultVerifier → Planner 语义发送前/回包后再次复查 → 原完整目标验收。每个物理请求前后复核 owner/task/capability/scope/任务 grantEpoch/fence/inputHash，加安装 identity/revision/安装 grantEpoch。管理重装不解除任务暂停、撤权、缺输入、设备等待或 UNKNOWN。

参数 schema 为有界、明确支持的 object/array/string/number/integer/boolean/null、enum/const、上下限和必填字段；未知验证关键字、外部引用、额外字段失败关闭。scopes 使用 JSON Pointer，支持 exact、directory、origin。例如 `/path` 的 directory `/course` 允许 `/course/a`，拒绝 `/course-other`、`..`、反斜线及百分号编码；origin 精确包含 scheme/host/port。没有手机文件映射、隐式目录读取或自动附件上传。对于不受本机控制的第三方服务，范围约束控制发出的参数和数据，不能证明对方服务器遵守路径或只读语义。

撤权阻止后续 admission 与迟到结果发布；已发出的远端请求不能假装撤回。网络中断、进程死亡或 ACK 丢失保留原任务 UNKNOWN/全局位，不自动重放。请求次数可在原任务详情查看；远端未上报的费用仍 unknown，不记零，也不设费用硬预算。

## 独立 Python Skill 服务

`server/skill-sandbox/sandbox_service.py` 是独立工具适配器，不是云 Chat 或第二个 Agent scheduler。一个显式配置实例绑定一份批准的 script SHA 和不可变 image SHA；镜像中的预装依赖构成批准集合。更新代码或依赖须停旧实例、显式确认新 SHA、前台重装和授权；没有 pip/依赖下载管理工具，也不在宿主执行导入 Skill 脚本。

脚本 stdin JSON → stdout JSON，输入最多32KiB、代码256KiB、合并输出64KiB、15秒。每个调用启动独立 Linux Docker 容器：非 root65534、network none、根只读、单个只读 `/payload` 挂载、cap-drop ALL/no-new-privileges、32 PID、1 CPU、128MiB内存且无Swap、16MiB临时目录、文件/描述符限额、不挂凭据/任务库/宿主socket、不继承宿主环境。脚本可输出数据，不可签发可信 receipt；服务独立解析严格 JSON 并计算输入/代码/输出 hash。

MCP 线协议的 run_python schema 如实包含 run_id；模型侧 schema 隐去该保留字段，宿主绑定 owner/task/callId/完整输入生成 run_id，拒绝模型传入。read_result 只读取同一 run_id 的持久结果，不重执行 Python。初始化与 receipt 都必须匹配前台确认的 image SHA。

服务端 journal schema2：IN_FLIGHT 先提交，再启动容器；服务真实死亡、create/start ACK 丢失均保留 UNKNOWN，不自动恢复执行。schema1迁移保留原任务/回执/UNKNOWN并标 legacy-unassigned，不猜新 owner。HTTP 凭据标识服务账户，`X-HXai-Profile` 由宿主生成档案摘要；结果查询与幂等键同时校验 profile，不能跨档案读回或复用同一 run_id。脚本无法获得 header/token 或 journal。

服务默认只绑定 loopback，拒绝浏览器 Origin，需强服务专用 token、最多8连接及请求超时。HTTP/1.1 所有成功响应均给明确 Content-Length，初始化/通知/工具目录可在同一连接连续完成；不依靠客户端自动重试修复连接关闭竞态。远程使用须在用户明确环境内配置 TLS 反向代理；本轮仅本机合成 QA，没有部署生产或托管真实账号。代码原件、独立任务成果和 UNKNOWN 均保留；卸载手机连接不等于删除独立部署服务的审计/成果。

## 源码地图和复验

`ExtensionsPage/FrontendFeatures` → `StudyHostFacade.handleFrontendFeature/extensions.*` → `ExtensionFrontend` → `ExtensionInstallationStore`。

`ChatToolFactory` → `ExtensionMcpRuntime` → `ExtensionMcpTransport`；`TrustedToolRegistry/AgentToolDispatch/AgentTaskStore` 绑定原任务；`TaskResultVerifier/TaskPlanner` 负责完整验收及迟到结果失效。`SkillsTools.checkEnabled` 在加载前和发布前重查用户停用/卸载，加载说明不等于运行脚本。

复验先读 BUILD_AND_TEST 的本批命令与脚本。真实进程 Probe 不可当普通 JUnit 批跑；使用专属模拟器、全新 run-id/run-dir。Linux 驱动只接受已有专属发行版/私有 daemon 的身份，不安装依赖、不使用默认 Docker socket。测试/源码/APK/输入/产物 hash 与实测状态以本批 TEST_RESULTS 为准；不累加历史计数或授完整 App/Release/目标设备/生产通过。

本轮核对的协议依据：[MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)。本版明确支持子集，不宣称完整OAuth或所有transport一致性资格。
