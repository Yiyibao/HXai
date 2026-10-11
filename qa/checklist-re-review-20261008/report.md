本报告为修复前复审批次，最新修复、Skills范围调整和验证结果见 [接续报告](../review-fixes-20261008/report.md)。以下失败与范围原样保留为历史证据。

# 最新清单复审（2026-10-08）

依据 [最新复审清单](../review-synthesis-20261008/re-review-checklist.md)。本轮从14:41至15:03（Asia/Taipei）实际运行专项与选定完整回归，使用独立目录；既有修改和历史失败保留，没有修改生产代码、提交、推送、发布或部署。

**结论：本轮 Android 97通过、2失败、2跳过；不能全面验收关闭。** 两项真实模型失败为现有助手引导与材料首轮生成，根因均待定位；另发现 Skills 未开放边界未被工具工厂强制落实，以及真实测试证据文件覆盖问题。详细见 [发现清单](findings.md)。本地 JVM 647项、Linux workspace20项、服务端26项通过；正常交付10/10通过，Lint 0错误/42警告。

本轮最重要的审查发现：六项修复已有相应专项支持，但专项仍不足以关闭最新清单所有子场景。尤其 A01–A04 的实际入口受控时序与失败后独立进程恢复，A05 的启动/周期入口，以及 A06 的图片丢失/变更负例仍缺证据。完整差距见 [逐项子场景表](scenario-gaps.md)。缺验证不等于已复现生产缺陷，也不能写成已通过。

继续复审批次见 [补充报告](supplement/report.md)：A01六组、A02两个删除入口的阶段断言及8条记录独立重开通过；审批补测仍在错误通知断言失败，A04新增场景未执行；D07工具列表已在设备复现失败。补测不并入上述主批计数，生产 APK 未改变。新增测试源码可供后续定位。

## 当前版本与证据边界

- [工作区原始状态](worktree-status-before.txt)、[原差异](tracked-before.patch)、[来源文件 SHA-256](source-hashes-before.json)、[执行配置](run-metadata.json)。只操作合成 QA 档案，不导出个人聊天或凭据。
- [所有 Debug 和测试 APK 哈希](all-apk-hashes.json)，运行安装对应 [APK 哈希](boundary/runtime-apk-hashes.json)。API34 / x86_64 / 4096 字节页；没有外推 API37、16 KB 或 Mate50。
- [构建与质量日志](build-quality.log)：三 ABI Debug 和测试包构建输入有效，允许增量产物。临时使用仓库占位 Firebase 配置并移除，未验收真实 Firebase。
- JVM 任务先命中缓存，随后在 [实际执行日志](jvm-executed.log) 和 [补充模块日志](jvm-remaining-modules.log) 强制执行。XML 另存于 jvm/。workspace 原四项 Windows 和二十项 Linux 分开记录，不能累加成两个完整 suite。
- [Lint](lint-results.xml) 为当前输入增量检查，0 errors / 42 warnings；[逐项差异](lint-delta.json) 与上一轮完全一致。没有增加 baseline 或 suppression，也没有宣称强制编译诊断已重跑。
- [Web 类型检查](web-typecheck.log) 和 [Web 构建](web-build.log)；仅构建前端，未调用 copy.ts 改写 Android 的静态资源。
- 构建原生库限制、缺 Firebase 的首次失败，以及 pytest 临时目录环境错误完整保留。服务端最后使用明确 QA 临时目录，[26 项合同通过](server-authorized.log)。失败不是由删除断言或改生产代码解决。

## 复审矩阵

|ID|本轮判定|依据与保留范围|
|---|---|---|
|A01|部分验证，未整项关闭|JVM 受控挂起与旧元数据拒绝，Android Room 正文/标题/FTS；六组受控 session 时序与独立重开已补；真实流与编辑 UI 全入口仍待补。|
|A02|部分验证，未整项关闭|session 删除/代次/撤销和 Room 不复活；历史页/聊天页入口、取消失效及独立重开已补；其他会话隔离待补。|
|A03|部分验证，未整项关闭|三种决定 JVM 失败重试；Android 回答 SQLite 故障、重试和迟到冲突；真实拒绝/同意 SQLite 与失败后重启待补。|
|A04|部分验证，未整项关闭|SQLite 失败消息/字节保留、另一会话引用、无引用清理；分支/队列/私有材料库及失败后重启待补。|
|A05|部分验证，未整项关闭|服务端写 request/insert/commit 故障、重试、孤儿/宽限期/活动任务/根外拒绝及 Event 并发；启动/周期入口待补。|
|A06|公式断言专项通过；整条验收失败|合法外括号、错误值和伪结构及本地生产解析通过；真实材料首轮FAILED，Word视觉重建及图片探针NOT_RUN；图片丢失/变更负例未运行。|
|B01|已执行主要场景，部分未验证|切档正文及按钮清空、DOCX 预览、缺失/损坏/越权；受控异步读取竞态未验证。|
|B02|已执行主要场景，部分未验证|非公式不虚构、修正重开、来源校验和筛选；“稍后”完整路径未验证。|
|B03|正常交付通过，故障交付未完全验证|两套五格式 10/10 精确 SOURCE / VIEW / SEND / 目标 SHA-256；途中切档/取消/重读故障 UI 待补。|
|B04|部分验证|诊断/错误/TTS 脱敏与共享 provider 禁用边界；复制和 URL 全分支敏感标记审计未完成。|
|B05|本地专项已执行|API34 图库写入清理、滑块、OAuth；旧设备留 D03。|
|B06|本地错误专项通过，真实模型另列|包装400 schema/filter、401/429、拒绝不改写；真实 Pending/审批结果按实际日志记录，与 A03 分开。|
|B07|通过|原 Windows 4/4、Linux 20/20；真实符号链接，无 skip，未开放学习聊天 Shell。|
|C01|本地构建/门禁通过|当前输入构建、强制 JVM 执行、Lint 0/42、Web 类型/构建、服务端26项；缓存与强制执行口径单列。|
|C02|本地通过；现有助手真实引导失败|19项网络合同、编辑/分支；runtime seed PID4741→force-stop→verify PID4827；合成AskUser助手四模式/审批通过，但现有助手GUIDED失败，后续三个模式NOT_RUN。|
|C03|选定本地/真实样例通过，未整项关闭|独立 memory seed/verify，页面草稿并发；30源逐条核对20/20正例保留、10/10负例排除，四轮闭环中表现仍为候选；其他清单子场景见差距表。|
|C04|核心专项已执行，部分 UI 故障待补|凭据、备份、迁移约束与诊断 ZIP；未映射到明确断言的取消/回滚不得整项关闭。|
|C05|主要页面/材料通过，部分视觉未验证|导航、模型持久化、分页、材料合同和当前截图；1.3倍字体四入口验收通过并恢复1.0；专用富文本夹具跳过。|
|O01|当次交付通过；历史根因仍未定位|本轮原测试通过和哈希回执，保留 R3 首次 package 断言失败；不能写成问题已修复。|
|O02|当前摘要已对齐|README/验收矩阵链接本轮97/2/2与未关闭项，保留历史批次及原始失败。|
|D01|条件支持；本轮探针NOT_RUN|材料在首轮提前失败，未到探针；不能沿用上一轮“缺配置”冒充本轮实际探针结果。|
|D02|未验证|专用合成身份与真实 worker 整条解析合同，本轮未执行。|
|D03|未验证|Mate50/HarmonyOS、旧通知/图库、API37、16 KB 实机条件缺失。|
|D04|未验证|正式 Release、双真机迁移和明确时长后台可靠性。|
|D05|未验证|复杂 Office/图表/中文手写/模糊图，需先确认支持合同。|
|D06|未验证|按真实提供商配置补扩展、迁移版本与专用富文本视觉。|
|D07|FAIL：未开放 Skills 进入工具列表|共享provider外部拒绝通过，Web能力开关关闭；Skills非空配置仍加入use_skill，不能写成禁用边界全部通过。|

## 五种最终状态

|状态|最终内容|
|---|---|
|修复项已关闭|本轮未新增A整项关闭；F01–F06上一轮修复事实与本轮专项支持保留，完整清单子场景仍需补证据。|
|已通过能力|JVM647/647（含Windows shell4）、Linux原workspace20/20、服务端26/26；Android97通过；正常文件交付10/10；本地网络/审批/附件、独立恢复、真实教学闭环与30源记忆；1.3倍字体导航。|
|剩余缺陷|RR01现有助手GUIDED真实失败、RR06材料首轮真实失败（均待定位）；RR05 Skills禁用入口缺口（代码路径发现）；RR04真实测试答案文件覆盖；既有42项Lint警告。|
|跳过与未验证|Android2项默认占位跳过（富文本视觉、默认记忆恢复）；后者另有实际独立seed/verify通过，两者分别保留不覆盖。失败后的三个模式、其余真实材料/Word/扫描视觉及图片探针NOT_RUN；A/B/C子场景缺口与D平台条件另列。|
|条件支持|图片生成、增强worker真实解析、目标平台/签名/双设备与复杂材料。|

## 最终证据与真实产物核查

[机器可读结果](test-summary.json)逐条保留101个Android终态，不累加历史通过；[边界批次](boundary/runtime-results.json)、[追加批次](additional-results.json)、[字体结果](large-font-result.json)和[精确交付哈希](exact-delivery-hashes.json)对应同一APK版本。seed/verify分进程计为不同执行；默认占位skip保留，不改记PASS。

[30来源逐条审计](memory-source-audit.json)：按新会话USER节点与生产reconcile插入顺序关联，逐一校验Unicode长度（这是明确标注的来源顺序推断，不导出HMAC密钥）。正例1–20均各有匹配产物，负例21–30均未入库；第14、15、16项分别为READ_ONLY、WITH_HINT、INDEPENDENT_ATTEMPT，均CANDIDATE。其余背景、偏好、困难、进度和复习意愿只保留作者实际陈述，没有把“学过/读完”改成掌握。

[四轮对话](fixtures/s3-real-teaching.md)与[对应记忆](fixtures/s3-real-records.json)人工对照：漏乘内层导数→一级提示→正确重答；独立尝试只核对提交结果；最后区分阅读与待迁移练习。四条来源对应表现候选，没有把模型确认直接升级为正式掌握。

[真实材料失败日志](MaterialRealModelTest.log)停在首个MD生成round；[当前材料记录](fixtures/material-real-model.md)为空，不能借历史Word输出补成通过。[图片探针](image-probe.json)明确NOT_RUN；真实Word输出因mtime早于本轮被采集器拒绝。当前本地Word正例仍有MaterialFidelityTest生产解析断言。

[失败诊断](failure-diagnostics.json)只保存失败QA时间窗口的枚举、时长和状态。引导请求正文读取COMPLETED后CHAT失败；材料请求正文读取CANCELLED后CHAT失败，两者均未记录HTTP_ERROR/status。没有据此猜测提供商错误，也没有自动重试。O01原分享窗口根因同样未定位。

视觉抽查了当前跨档案禁用和DOCX预览截图：前者无旧正文且三个交付按钮禁用；后者显示实际文本而非ZIP乱码。放大字体结果仅证明四入口/模式选择当前可操作，不代替全部页面视觉验收。截图与测试夹具分别在screenshots/、fixtures/保存。
