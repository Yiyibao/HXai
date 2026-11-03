# R29 规划说明兼容增量 v1

2026-10-30，专用分支 codex/r27-quality-06-09-20261028，base b0ed6acc7c2a03df0a28e80f6c07d82a44e35d45 加既有29路径候选。本增量没有集成进 D:/Office/Study/code/MathStudyAgent 根目录。

实际公开 HS-CONIC-IDENTITY 的 V6 规划返回 math_verify 步骤及 tool:null 的说明步骤，原严格 String 解码在派发前失败。原 receipt、COORDINATOR_005.json 及原源码哈希保存在根目录 HXai_CHANGE_REVIEW/R29-QUALITY-06-09-20261029/resume-20261030 和 dedicated-planner-fix。

初始 TaskPlanner.ensure → PlanProposal.decode → 原每次请求工具容量核验 → 原 TaskStore.publishPlan/AgentPlan.validate/TrustedToolRegistry。只有字段恰为 id/tool/purpose、显式 JSON null tool、合法唯一 ID 和有界非空说明的行，才移入 explanation。它不会成为工具位或完成证据；任何 acceptance 引用该行均拒绝。未知字符串工具、缺少工具字段、数值工具、额外字段、重复 ID、超长说明保持拒绝。所有实际工具步骤与原验收谓词保留，缺少所需工具位仍拒绝。最终修复规划维持原严格解码。

无 schema/Pigeon/ToolPolicy/授权迁移；不改既有任务、模型快照、UNKNOWN 或执行位。13 个原封存案例和 1 个 UNKNOWN 属于旧候选，不能用其真实调用结果授予本修复资格。新修复只取本轮源码对应编译、JVM 及本机合成模型的定向原 Chat 测试；独立审查 NOT_EVALUATED。禁止在保留真实凭据和 UNKNOWN 的 emulator-5576 安装、清库、重放或解锁。回滚仅逆本五路径增量，保留原证据与任务。
