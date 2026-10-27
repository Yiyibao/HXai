# API26 与 A04 合流协议 v1（2026-10-22）

适用 codex/api26-a04-integration-20261022 完整未提交工作区；旧HEAD 5d826d5d7d8755ed42db834d6cd6dc52ae914c69 只作骨架定位。真实源码/两包由 HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/SOURCE_FINAL.json、final-APK_INPUTS.json 固定。

继承 a04-durable-mcp-protocol-v2 与 B06-API26-COMPAT-PROTOCOL。B06 两文件候选按 before/candidate/current 原字节核对后精确接入。原候选只覆盖旧任务库；实际 API26 新增反例证明 A04 后加安装库也缺运行时 AutoCloseable，追加显式 java.io.Closeable，复用原 close，不改变任何SQL/状态/授权。两处旧迁移夹具改事务重建旧表，生产 onUpgrade 不变。

源映射：AgentTaskStore→Scheduler/Chat/Verifier与任务读回；McpInstallationStore→SyntheticMcpRuntime.registry→Factory/可信审批/dispatch/独立验收，以及 nativeqa/McpFixtureManagementActivity 的真实前台管理。verify_mcp_persistence 增加可选 --ownership，设备 serial/AVD/API/ABI/boot 在首个副作用前核对；不假造AVD名称。普通App共享MCP仍关闭，QA管理页与DocumentsProvider不进入普通包。

sidecar9/Room33/安装库1/Pigeon164、原 owner/profile/contextEpoch、安装与任务独立 grantEpoch、输入绑定、fence/UNKNOWN、锁模型与未知费用全部保持，无库迁移或新执行器。完整diff与五文件副本重放见 FIXES.patch/PATCH_REPLAY；其余起点索引源保全见SOURCE_PRESERVATION。

本轮API26/x86_64实际新包139方法全部PASS（包含原B06全89方法，不重复计历史通过），四MCP live进程模式PASS；旧四失败反例与修复同例均保留。JVM App502/AI211、Debug AAR、QA/普通Debug/lint、38资源一致性、普通包Manifest/DEX/运行guard分别通过。QA最终小屏320x640管理表面截图为真实运行；当前API26大字/深浅色/TalkBack矩阵未由本批验收。

本机声明范围PASS_SCOPED；完整项目与独立审查NOT_EVALUATED，无子代理、提交/推送/部署。通用第三方MCP/脚本沙箱、真实Provider/OCR、Release/Mate50与生产另验。回滚只逆本批差异，保库/墓碑/receipt/UNKNOWN/原件和版本。
