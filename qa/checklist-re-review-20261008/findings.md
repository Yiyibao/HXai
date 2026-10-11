# 本轮复审发现

## RR01：现有助手配置的真实引导请求进入 FAILED（P1，未定位）

2026-10-08 14:53:11 +08:00 开始的 ProjectReviewTeachingTest，首个 GUIDED 请求在约29秒后进入 FAILED，断言期望 COMPLETED。该方法随即退出，HINT/CHECK/EXPLAIN 在这次独立执行中均 NOT_RUN。原始日志见 [ProjectReviewTeachingTest.log](ProjectReviewTeachingTest.log)，只含合成输入的产物见 fixtures/review-real-teaching.md（若成功收集）。

生产诊断中网络与正文读取记录为 COMPLETED，随后 CHAT 两次 FAILED；没有 HTTP_ERROR/status。见 [失败时间窗口诊断](failure-diagnostics.json)。这不足以断言 HTTP400、prompt filter、网络中断、工具错误或解析错误。根因未定位；本轮不自动改写、不自动重发、不捕获或导出完整服务响应。

另一个 StudyRealModelTest 已通过四种模式和审批。两类用例配置不同：前者使用现有 original.assistantId；后者创建基于 StudyTutorDefaults.systemPrompt、只配置 AskUser 的合成助手。通过结果只能支持该合成助手配置，不能抵消现有助手配置的失败。代码差异是下一步定位线索，不是已证明根因。

## RR02：六项修复的“整项验收关闭”证据不足（P2，覆盖缺口）

现有专项可支持对应修复方向，但 A01–A06 仍缺最新清单部分必需子场景；逐项见 [scenario-gaps.md](scenario-gaps.md)。尤其 JVM new session 初始化不等于 Android 独立进程重启；另一会话引用不等于队列/同消息其他分支；手动清理函数不等于启动/周期调度入口；原图正例不等于图片丢失/变更负例。此项是验证缺口，不声称生产缺陷已经重新复现。

## RR03：历史分享窗口失败仍未定位（观察项）

本轮 MaterialPageTest、两套五格式跨 UID 与系统另存测试通过，10/10 精确源文件与目标/VIEW/SEND 哈希一致。保留 R3 首次窗口 package 断言失败及原样复跑记录，见 [原三次审查汇总](../review-synthesis-20261008/report.md)。没有代码修复与确定根因证据，不能把本次通过改记成“偶发问题已修复”。

## RR04：真实四模式与审批复用证据文件，前一方法内容被覆盖（P2，测试证据问题）

StudyRealModelTest 两个方法均进入 runScenarios，创建新的 StringBuilder，却使用同一 cache/s1-real-teaching.md 路径并在 stage 中 writeText。第一方法包含四模式及审批，第二方法只含审批，后运行者覆盖前一方法的详细答案。当前收集文件因此仅保留审批内容；四模式通过仍有 instrumentation 阶段与断言记录，但缺可供逐轮人工核对的独立答案产物。对应源文件 StudyRealModelTest.kt 第46–48行。

独立 ProjectReviewTeachingTest 使用另一文件保留了真实失败，不能补回第一方法丢失的答案。后续应按方法/运行ID区分证据文件；本轮没有为补证据自动重发已完成的远端请求。

## RR05：Skills 未开放边界没有在工具工厂强制落实（P2，设备工具列表复现）

最新清单 D07 把 Skills 列为未开放，但 ChatToolFactory.kt 第63–69行在 assistant.enabledSkills 非空时仍调用 createSkillTools，没有能力开关或 profile 授权判断。SkillsTools.kt 第22–27行对已启用且存在的技能创建 use_skill，并读取全局 SkillManager 返回的技能目录。SkillManager.getSkillsDir/listSkills 没有接收 profileId。现有导入/旧助手配置只要保留 enabledSkills，就存在进入该分支的条件；AssistantExtensionsPage 也保留设置该字段的代码。

补充设备测试已用合成已启用技能调用实际工具工厂，确认返回列表含 use_skill，合同断言失败，见 [补测日志](supplement/skills-boundary.log)。尚未覆盖导入 UI 完整流程。不声称已发生跨档案泄漏，也不声称 use_skill 可以读取技能目录之外文件（SkillPaths 有路径保护）。需要在工具工厂落实未开放开关，或者明确开放合同并补档案授权验收；仅隐藏导航入口不足以支持“始终禁用”。

本轮 [SkillsToolsTest XML](jvm/app/TEST-me.rerere.rikkahub.data.ai.tools.SkillsToolsTest.xml) 两项实际执行通过，证实低层 use_skill 创建与合成文件读取可用；它不证明上层未开放开关存在。

## RR06：真实材料首轮生成进入 FAILED（P1，未定位）

2026-10-08 15:01:14 +08:00 开始的 MaterialRealModelTest，在首个MD附件分析/生成round约43秒后进入FAILED，断言期望COMPLETED。原日志 [MaterialRealModelTest.log](MaterialRealModelTest.log) 与 [失败诊断](failure-diagnostics.json) 保留：材料本地导入COMPLETED，网络COMPLETED，RESPONSE_BODY为CANCELLED，随后CHAT两次FAILED；未记录HTTP_ERROR/status。不能据此直接判定用户取消、HTTP过滤或网络原因。

round在追加答案之前断言失败，finally保存的本轮 material-real-model.md 为空；之后DOCX/XLSX/PPTX/PDF模型生成、图片识别、扫描PDF视觉、Word重建、图片生成探针均NOT_RUN。上轮完整通过及Word产物不参与本轮计数。后续需补失败原因的安全分类、按现有配置受控定位，不自动重发或改写原请求。


## RR07：审批故障补测缺少预期错误通知（待定位）

补测首个 approve 的内存和数据库仍为 Pending，但错误列表没有目标会话，断言失败；直接 join 对应任务后仍失败。不能据此确认故障注入成功或认定产品缺陷，后续 deny/answer 与附件新增场景均 NOT_RUN。部分重开只核对了此前记录的 A01/A02；详见 [补充报告](supplement/report.md)。
