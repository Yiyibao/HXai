# 原生请求用量只读协议 v1

2026-10-14，A-USAGE-LEDGER-20261014。适用于 codex/agent-usage-ledger-20261014 的未提交增量，以本批 SOURCE_DELTA/SOURCE_AFTER、TEST_RESULTS 和 patch 固定具体字节。沿用原生任务协议 v1.8、sidecar7/Room32；本增量没有任务/数据库状态迁移。

## 入口与权限

Chat 的 AgentTaskPanel 提供“查看请求用量”，按需打开 AgentUsageView；经新 Pigeon getAgentUsage(profileId,taskId?,from?,until?,cursor?,limit,expectedEpoch) 到 StudyHostFacade。查询前后 requireProfile，默认复用唯一 AgentScheduler/AgentTaskStore。taskId 指定时再次验证其 owner；不指定时只查当前档案的全部任务，包括专用角色任务，不查询其他档案。

接口只读 SQLite usage JOIN tasks/calls，不读取 vault，不运行恢复或模型请求。无输入/正文/目标/结果 JSON/凭据引用/原文件路径进入输出。UI 检查响应 owner/task/time scope、查询代次和当前 profile/contextEpoch；筛选切换或档案变化后的迟到回包丢弃。

## 明细与汇总

AgentUsageEntryDto 为每次物理请求记录 request/task/conversation/role、实际请求 provider/model/reasoning、时间、call状态、provenance和 nullable input/output/cached/total Token。缺角色模型信息保持未知，不从任务主模型或当前设置补造。

AgentUsageMetricDto 的 reported/estimated 是各自**已知小计**，unknownRequests 是该字段无法可靠取得的请求数；不能将小计当完整总量。报告0和估算0保持0，unknown行的历史0、非法负值或缺字段不作已知计数。没有请求时小计为空、未知条数0；有未知请求时显示未知条数。费用当前缺供应商费用数据，UI明确未知，无硬费用预算。

页汇总覆盖完整过滤集，不是当前显示的30条。时间过滤是毫秒区间 [from,until)，两个边界同时提供且 from>=0/until>from；周期按设备时区的自然日、周一开始的自然周、自然月。createdAt缺失的记录不归入周期，单独给出未归期条数，“全部”仍可查看。

## 有界分页

limit 1–100。首读固定过滤范围所属账本的最高 rowid；后续游标绑定 owner/task/time scope、最高 rowid 和最后已读位置，以 rowid降序 keyset读取。插入新请求不会让旧分页重复/漏项；刷新开始新的范围。游标是位置，不是授权，所有查询仍参数化 owner 条件；错范围/畸形/越界游标拒绝。

每页在 Store 的同一事务读取汇总与明细。供应商迟到回执可能在后续页/刷新更新现有值，本协议不承诺跨页账单值永远冻结；不修改 usage/calls/task以换取一致性。

## 桥接与兼容

新增三种 DTO 和一条 Host API，由项目现有 Pigeon 29 生成 Kotlin/Dart 两端，Flutter AAR与宿主同步构建。同APK必须包含对应生成两端；不支持混搭旧Flutter缓存与新宿主 codec。旧任务/材料/授权/模型字段与语义不变。回滚恢复本批桥接两端及widget增量，不删账本或重建数据库。

## 验证与限制

本批合同、正常/反证/真实页面/原始日志与适用结果见 HXai_CHANGE_REVIEW/A-USAGE-LEDGER-20261014。跨owner/profile/epoch、旧unknown0、reported0、部分字段、估算、缺时间、半开边界、页间插入、错游标、UNKNOWN位保持、实际native→Pigeon→Dart、320px/200%深浅色为本批门禁。

本批不等于完整费用系统：价格/币种/实际账单来源、按model/provider的分组汇总、导出、异常消耗通知、独立全局任务中心入口仍需后续开发；真实模型、云设备、Office/数学、Release/Mate50/生产无本批放行。独立审查与完整全项目审查保持NOT_EVALUATED。

## v1.1：跨会话任务中心和独立用量入口（2026-10-14）

上述 v1 是原批次事实；A-TASK-CENTER-20261014 新增侧栏 `/tasks` 和 `/usage`，没有当前会话/已接受任务也可查看当前档案账本。目录按当前 owner 跨会话浏览主任务和辅助请求；其他档案需要通过既有切换重新获取 bootstrap/epoch，不跨 owner 聚合私有记录。执行位仍属原工作域唯一 Scheduler。

新增只读 `getAgentTaskCatalog(profileId,kind,cursor,limit,expectedEpoch)` 和
`getAgentTaskDetail(profileId,taskId,cursor,limit,expectedEpoch)`，以及六个 Catalog DTO。
Pigeon 29 同源生成双端并同步构建 AAR/宿主；codec 编号发生变化，旧 AAR 不可与新宿主混用。
目录游标绑定 owner/filter/rowid 上界，调用分页游标绑定 owner/task；limit 1–100。
计划最多投影 64 步，单结果解析上限 256 KiB、合计 1 MiB，成果引用最多 100 个。
截断/未核验/缺失/外档案成果标 partial，完整原回执保存在库中。

任务状态、独立等待、目标/计划修订及 fence 来自持久真源。辅助请求目标隐藏正文；
调用详情只返回状态/指纹/能力/授权 epoch，不返回参数、结果正文、路径或 vault 引用。
友好模型名称来自接受配置，配置不可读时显示已记录 ID，不能读取当前设置补造。
成果只解析已核验回执中的 owned/existing/output 文件引用；所列 SHA 是保存的元数据，
实际预览复用既有独立回读核验入口，不把历史任务成功变成当前文件重新验收。

会话不存在时仍能查询独立任务，关联会话按钮禁用。可访问会话通过真实宿主回包核对 owner/id 后打开；
执行命令继续使用会话当前任务面板，不在历史详情伪造暂停/继续动作。
页面拒绝错归属及旧 profile/epoch/筛选/路由异步结果，分页遇任务修订变化重新读第一页。
bootstrap 加载或错误时隐藏内容；查询不 claim/resume/reconcile，不发模型请求，不改变授权或 UNKNOWN。

本增量无 Room32/sidecar7 迁移、无费用硬预算。准确源码和实测层见
`HXai_CHANGE_REVIEW/A-TASK-CENTER-20261014/TEST_RESULTS.json`、`SOURCE_AFTER.json`、`FIXES_FINAL.patch`。
可信费用、导出、分组、异常提醒及任务中心直接控制历史任务仍为后续范围；完整 A/P0、
真实模型、Release/Mate50/TalkBack/生产及独立审查不由本批放行。

## v1.2：按实际提供商、模型与角色汇总（2026-10-14）

A-USAGE-GROUPS-20261014 在原请求用量入口增加“逐请求 / 按提供商 / 按模型 / 按角色”。
本节是 v1/v1.1 的增量，不改写其历史待办。分组覆盖完整当前档案或指定任务的过滤窗口，
不是仅汇总已加载的明细。模型分组键为 (provider_id, model_id)，同名模型跨提供商分开。
角色取物理 usage.role；身份缺失/空值归未知，不借用任务主模型或当前设置。
失败、UNKNOWN、重试和辅助请求均计入；报告、估算与逐字段未知仍遵守 v1，费用保持未知。

新链：`AgentUsageView` → `getAgentUsageGroups(profileId,taskId?,from?,until?,groupBy,cursor?,limit,expectedEpoch)`
→ `StudyHostFacade` 前后 requireProfile → 唯一 `AgentScheduler.readUsageGroups`
→ `AgentTaskStore.readUsageGroups` 同一事务 → `AgentUsageGroups.read` SQL GROUP BY。
`AgentUsageLedger.summaryColumns/readMetrics` 为旧逐请求汇总和新分组共享字段语义。
新 `AgentUsageGroupDto` / `AgentUsageGroupsPageDto` 返回实际 nullable 身份、完整 requestCount/
groupCount、未归期数、已知小计/未知数与分页位置；不返回输入、结果正文、vault引用或路径。

首读固定 owner/task 所属 usage 的最高 rowid，后续页排除新插入请求；按身份 BINARY 顺序
keyset 分页，不按可变化的小计排序。g1 游标绑定 JSON 编码 owner/task/from/until/groupBy
的 SHA256、上界及 Base64URL JSON 身份键；跨范围、畸形、丢失键拒绝并要求刷新。
limit 1–100、身份每维最多 4096 字符、游标最多 128000 字符是资源边界，无 Token/费用预算。
SQLite SUM 溢出报错，不降为零。半开毫秒日期/未归期沿用 v1；迟到回执可能改变后续页小计，
不承诺跨页财务快照恒定。页面标明完整窗口请求数/分组数与已显示分组数。

两个新 DTO 与 API 由现有 Pigeon 29 同源生成双端，codec 编号变化；AAR 和宿主必须同步重建。
Room32/sidecar7 无迁移，无调度、授权、暂停、模型锁、receipt 或 UNKNOWN 状态改变。
旧 owner/epoch/筛选/分组回包丢弃，错误重试保留当前位置；不读取当前模型填补历史身份。

实际源码、失败负对照、运行证据、截图、补丁与六类 SOL 在
`HXai_CHANGE_REVIEW/A-USAGE-GROUPS-20261014/`；该批 TEST_RESULTS 与 SOURCE_AFTER 固定适用字节。
独立外部与完整全项目审查 NOT_EVALUATED；价格/币种/账单来源、导出、异常消耗提醒、
百万级性能、真实模型质量、云设备、Release/Mate50/TalkBack/生产仍需后续。

## v1.3：完整范围的不可变 JSON 用量快照（2026-10-14）

A-USAGE-EXPORT-20261014 为 v1.2 增加用户主动“导出 JSON 快照”。导出当前档案或任务、
半开周期和 requests/provider/model/role 的完整范围，不仅是已加载的30条。新 API
`exportAgentUsage(profileId,taskId?,from?,until?,groupBy,requestId,expectedEpoch)` 由原生可信
当前 owner/epoch 和 Activity 前台状态检查；不创建 Agent 任务，不 claim 或改暂停、授权、
模型锁、UNKNOWN、全局执行位。原 Scheduler/Store 同事务只读捕获数据；Room32/sidecar7不变。

`AgentUsageSnapshot.capture/encode/decode` 定义 schemaVersion=1 的 hxai_usage_snapshot：
外层 sourceSha256 绑定 data 的紧凑严格UTF8编码，实际文件另有独立SHA256/size。
data 包含 owner/task/request/time/groupBy/capturedAt、summary、records、recordCount和cost。
Long 字段均为十进制字符串，nullable未知为 null；reported 0保持字符串"0"，费用固定
status=unknown/amount=null/currency=null。模型身份来自实际usage；不导出正文、vault或输入hash。
最多10000记录/分组、8MiB输出、每文本字段4096 UTF16单位是存储资源限制，无Token/费用预算。
超限整体失败，不能用部分结果交付。JSON只承诺文本快照，不声明Office保真或实际财务账单。

`AgentUsageExport.export` 将 owner+用户request UUID派生新artifact UUID，requestKey固定原范围。
沿用 MaterialFiles.meta.json 的持久PENDING/RUNNING意图，在写前保存预期文件hash/size；
临时文件关闭、sync、独立读回后原子落盘，最终文件严格UTF8/JSON/摘要/scope/count全部核验
后才登记SUCCEEDED并返回14字段AgentUsageExportDto。sourceId=null表示独立报告，不修改旧材料。
旧request只核验原文件，不重新捕获迟到用量、不重写。落盘但未登记receipt可凭原意图回读
恢复；只剩意图/残缺part时同request拒绝重写，用户明确新request才创建新快照。损坏元数据、
孤儿目标和hash不符保留并拒绝。旧owner/epoch/筛选回包丢弃；成果预览和用户选择另存复用
现有ProfileResourceRoute/getArtifactPreview/saveArtifact链，生成不自动外发。

Pigeon29双端新增DTO末尾类型159，旧128–158映射保留；新API仍要求AAR/宿主同源重建。
准确源码字节、失败负对照、真实安装UI/输出和进程中断证据以
`HXai_CHANGE_REVIEW/A-USAGE-EXPORT-20261014/TEST_RESULTS.json`、`SOURCE_DELTA.json`及交接为准。
独立外部和完整全项目审查NOT_EVALUATED；可信费用、异常提醒、规模压力、真实模型质量、
云设备、Release/Mate50/TalkBack/生产待接续，历史v1/v1.1/v1.2待办保留其原批次含义。

本节恢复证据指新进程持原requestId重复API调用；页面State中的上次requestId目前不跨UI
进程自动恢复。重启后主动导出创建新request并保留旧成果/意图，上次请求的手动恢复入口
仍需接线，不以API级恢复测试冒充已完成该界面。
# v1.4 增量：跨页面/进程导出意图查询与手动核验（2026-10-14）

来源为 A-USAGE-RECOVERY-20261014 当前feature未提交树，完整HEAD和raw源码hash见该批
BASELINE_SOURCE_INPUTS/SOURCE_DELTA。保留上文v1—v1.3及原Pro规范；Room32/sidecar7不迁移。

新增Pigeon类型AgentUsageExportIntentDto（owner/task/from/until/groupBy/requestId/artifactId/
expected sha256/size/createdAt）与getAgentUsageLastExport、verifyAgentUsageExport。Android和
Dart均从同一Pigeon源生成，AAR/宿主需同步。意图DTO不是成功回执，不含secret/grant。

新增本机noBackup目录agent-usage-export-intents/v1/<owner>/<scopeHash>/<requestId>.json：
schemaVersion1不可变意图，严格UTF8/4KiB/UUID/owner/精确scope/预期artifact/hash/size校验。
副作用前fsync、原子发布并独立读回；原意图不可覆盖。查询只读该精确范围，旧导出缺投影
不推测，未来版本/损坏保留并拒绝。按createdAt和requestId确定该范围最新记录，重核验旧
请求不改变时间。此目录不进入现有备份，不提供跨设备恢复或旧版自动索引。

AgentUsageView在打开/筛选时自动只读查询，另有“查询上次导出”按钮。保存的意图标待核验；
点击“核验上次导出”调用专用API。Native再次验证owner/epoch/前台和原expected SHA，独立
回读现有MaterialFiles及JSON内容/sourceSha256/完整记录范围；只可修复原材料的已验证状态，
没有capture回调、文件创建或盲重写路径。缺失/part/损坏拒绝交付；用户显式新导出另建ID。
界面以generation/查询序号/owner/epoch拒绝迟到结果，receipt还必须与原artifact/hash/size一致。

查询/核验不调用Scheduler执行、模型或网络，不变更授权/模型/任务/UNKNOWN。具体已测范围
和真实进程/界面证据见本批TEST_RESULTS，完整A/P0/Release/真机/生产不由协议声明通过。

实际运行增量：receipt Card含普通状态/时间说明、SelectableText SHA和按钮，关闭默认
semanticContainer合并，分别暴露各文本的无障碍节点。视觉可见不构成无障碍可读证据；
本轮保留Android节点遗漏及Flutter语义负对照，并按混合内容契约复验同源AAR/宿主。

## v1.5 — A-USAGE-HISTORY-20261014：本机跨范围导出浏览

新增 Pigeon AgentUsageExportHistoryPageDto（codec161，owner/entries/nextCursor）和
getAgentUsageExportHistory(profileId,cursor,limit,expectedEpoch)。原类型编号、Room32、sidecar7
及导出意图 schema1 保持；同步生成 Dart/Kotlin，不把该接口当授权或任务执行接口。

AgentUsageExportHistory.page 仅扫描当前 owner 的 noBackup 投影目录，按 createdAt/requestId
降序。每条严格 UTF8/4KiB/schema/路径/原范围/请求唯一性检查；损坏、链接、未来版本或重复
请求使本次查询失败，保留全部原件。未提交 .tmp 只作保留残片，不列为已保存意图。安全扫描
上限 10000 范围目录、10000 已提交记录、20000 目录条目；单页 1–100。超限明确拒绝，不能
宣称完整历史。游标绑定 owner 和整个排序集合全部字段的摘要，新增/修改/删除使旧游标失败，
用户刷新从头读取；游标不授予任何权限。无历史的查询不建目录，不猜测旧导出索引。

AgentUsageView → 浏览历史导出 → AgentUsageExportHistoryView 独立面板，不改变账本筛选。
列表展示原任务/整个档案、分组、精确周期（起含止不含）和请求标识。每页20，核验调用
既有 verifyAgentUsageExport 的原字段及预期 hash，重核验失败不调用 export/capture。
receipt 再校验完整原范围、原 artifact/hash/size、reused、source hash、计数；只有真实
核验通过才开放 ProfileResourceRoute 预览。旧 owner/epoch/异步序号回包丢弃，混合 Card
内容分别暴露语义节点。费用仍 unknown，不增加财务预算、模型调用、授权或调度变化。

本机目录扫描不是数据库迁移或跨设备恢复；大量历史性能、完整 TalkBack、真实设备与
完整项目的裁决以本批交付范围为准。原协议各版本和历史测试证据保留。

