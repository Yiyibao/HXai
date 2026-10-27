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

## v1.6 — A-USAGE-ALERTS-20261015：前台请求用量提醒

来源是 codex/agent-usage-ledger-20261014 的完整未提交基线和本批 SOURCE_DELTA/patch；
完整HEAD仍为5d826d5d7d8755ed42db834d6cd6dc52ae914c69。原Pro与v1—v1.5保留。

新增getAgentUsageAlerts(profileId,taskId?,from?,until?,expectedEpoch)及末尾codec162
AgentUsageAlertsDto。双端同源生成，同源AAR/宿主构建；Room32/sidecar7/导出schema1不变。
StudyHostFacade在IO查询前后校验当前档案及epoch；任务指定时在同一Store事务核对owner。
只读usage/calls/tasks，返回范围、读取时刻、计数，不取凭据/输入/正文/结果/路径，不启动任务。

规则v1：调用UNKNOWN或缺失回执记uncertainRequests；VERIFIED且provenance不是reported/
estimated记unreportedCompletedRequests。正常IN_FLIGHT不作失败，reported零与estimated不
作缺失；缺少某一Token字段仍按原协议为未知，不将其计入供应商费用。最近[max(0,now-300000),now]
内同一task实际请求至少20次时记录denseTaskCount与这些任务的denseRequests，包含所有角色。
这是活动密集核对阈值，可能包含正常多步任务，不宣称重试、重复收费或错误循环；过期自然消失。
所有规则应用完整半开from/until范围，不依赖明细分页；无日期仅进入全部范围，另返undatedRequests。

AgentTaskPanel折叠时即可自动显示当前任务提醒，每15秒只读刷新；组件离开/应用非前台时停止，
返回重新读取。AgentUsageView筛选/分组/刷新时自动读取当前范围。结果逐条校验owner/task/范围/
计数及当前epoch和异步代次；错误清旧结果并标未取得提醒，零提醒仍说明费用未知。无重复弹窗、
通知写入或第二执行器，迟到回执和重开从账本重新派生；不设预算、不停任务、不重发请求或改模型。

源码地图：feature/agent/AgentUsageAlerts.kt:AgentUsageAlertReader.read →
AgentTaskStore.readUsageAlerts → AgentScheduler.readUsageAlerts →
flutter/StudyHostFacade.getAgentUsageAlerts → Pigeon162 →
Flutter widgets/agent_usage_alerts.dart；真实入口agent_task_panel.dart/agent_usage_view.dart。
本批测试与适用裁决见HXai_CHANGE_REVIEW/A-USAGE-ALERTS-20261015/TEST_RESULTS.json。
当前只实现前台提醒，后台系统通知、可信费用/价格来源、规模性能、完整TalkBack、
真机/Release/云设备及独立审查不由此协议授予PASS。

## v1.7 — A-USAGE-NOTIFICATIONS-20261015：后台系统核对提示

来源为同一 A 线完整未提交树，HEAD 仍 5d826d5d7d8755ed42db834d6cd6dc52ae914c69；
准确本批字节和测试见 HXai_CHANGE_REVIEW/A-USAGE-NOTIFICATIONS-20261015。旧规范原文保留。

TaskStore 在请求 begin/finish、任务 finish/recover/command 成功后只发送无正文失效信号；
AppModule 的单例 AgentUsageNotificationMonitor 在 AppScope 内按生命周期、当前档案和设置
重读原 usage/calls/tasks/task_control。查询按 BINARY taskId 分页，三规则与 v1.6 相同，
不依赖首30/100条。无第二任务执行器、轮询 Worker、claim、模型调用、预算或授权变化。

默认关闭的 DisplaySetting.enableAgentUsageNotifications 独立于原生成通知开关；旧设置
缺字段时保持false。Flutter AgentUsageView 的“后台用量提醒设置”经既有 openNativePage
ACK → StudyHostFacade allowlist → RouteActivity → 原生通知设置页，提供真实开关及
Android 系统渠道设置。异步代次/owner/epoch防止旧设置回包污染新页；不显示猜测的启用状态。
原 Pigeon162/Room32/sidecar7/导出意图schema1保持，无需生成新桥接；Flutter AAR须同步重建。

通知渠道 agent_usage_review_v1，仅活动档案、已明确启用且系统许可/渠道可用时投递。
主动撤权任务不通知；投递前再查原授权及原异常是否仍存在。通用提示无目标、档案、正文、
模型或数量，私密可见性；点击打开原 Flutter App。前台、切档案、禁用设置或无异常时取消。
Binder 与撤权不能构成同一事务；已提交系统的提示经下一事件取消，不承诺撤回已展示。

独立 noBackup agent-usage-notifications-v1.db schema1 仅存事件hash与attempted_at，
同 owner/task/rule/v1 最多一次尝试；所有新增同类请求不会反复提示，新任务/新规则可以。
事务先保留尝试再向系统提交，不把 notify 返回或 journal 当展示回执。保留后进程中断、
渠道/许可竞态可能少一次提示，不重放歧义尝试；前台完整账本仍可核对。未来schema拒绝
自动降级/重建，旧任务/账本/UNKNOWN不改；权限关闭时不预先消费尝试。

真实源码链：AgentTaskStore.usageChanges/readUsageNotificationCandidates →
AgentUsageNotificationReader → AgentUsageNotificationDispatcher/Journal →
AgentUsageNotificationMonitor/Sink；配置链为 AgentUsageView → openNativePage →
SettingPreferencesNotificationPage → SettingsStore。无任何收费值来源或主模型替换。
通知设置冷启直接进入设置页，不先创建Chat；Navigator的根返回回调真实关闭RouteActivity，
工具栏/系统返回均不产生空栈或无效按钮。具体旧失败和实际返回路径见本批原生实屏测试。
App进程被系统终止/force-stop后不承诺继续提示，重新启动后从原库重读。
各项E3/E4、原始失败、进程退出与未验范围见本批TEST_RESULTS；完整A/P0、百万级性能、
可信费用、Mate50/Release/云设备/完整TalkBack和独立审查不由此增量放行。

## v1.8 — A-NOTIFY-SCALE-20261015：后台提醒分页及索引迁移

来源为当前 A 线完整 feature 工作树，HEAD 为 5d826d5d7d8755ed42db834d6cd6dc52ae914c69。
本批精确源码、原始失败/性能记录及裁决见 HXai_CHANGE_REVIEW/A-NOTIFY-SCALE-20261015/。
保留 v1—v1.7 与原 Pro 规范；此增量不预授予验证通过。

AgentTaskStore sidecar7→8，仅增加 tasks_owner_id(owner,id) 和 usage_task_time(task_id,created_at)
非唯一索引，所有旧表/行/空值/授权/模型快照/计划/回执/执行位/fence 保留。7→8 不重复运行
旧控制表初始化；1—6 仍先执行原兼容迁移。SQLiteOpenHelper 事务异常回滚版本与索引，
未来版本拒绝降级/重建。Room32、Pigeon162、导出意图1、通知 journal1 不变。

NotificationReader.query/read 按 owner 和 BINARY taskId 索引逐页探测原账本，单页最多100候选。
未知/缺回执及已完成但用量未知使用相关 EXISTS；密集窗口读取第20条是否存在，不求全窗口总数。
正常 reported 0、IN_FLIGHT、null日期、所有角色及时间边界与 v1.6/v1.7 相同；撤权任务过滤。
每页不再 GROUP BY 汇总全部剩余账本。TaskStore.readUsageNotificationRules 使用同一
单 SELECT 再检查精确 owner/task、当前授权与当前规则，不进入 writer 事务或请求用量完整汇总。

Dispatcher 每页只保存最多100个 fresh 候选，保留 attempt 后、投递前重新读取当前规则并复核
allowed；每次 scan 最多一条通用提示，投递后继续扫描剩余页以登记尝试。前几页规则已消失时
仍可由后面的有效新规则触发。同任务同规则唯一attempt与歧义投递不重放原则保留。
系统提示可能在第一页复核后发送，不等待全目录扫描；已经交给系统的提示不能事务撤回。
异常中断时仍保留已登记attempt，不把其视为展示receipt；尚未登记的其他任务由下次事件扫描。
跨页不是不可变全集快照，排序早于游标的新请求由下一失效扫描处理。

源码地图：AgentUsageNotificationMonitor → AgentUsageNotificationDispatcher.scan →
AgentTaskStore.readUsageNotificationCandidates/readUsageNotificationRules →
AgentUsageNotificationReader.query/read → 原 tasks/task_control/usage/calls → Journal.reserve → Sink。
实际 schema7 Android 归档迁移、失败回滚、查询计划、百万条合成账本与并发写记录均单列证据；
模拟器性能不授予 Mate50 SLA。回滚先停提醒，用兼容 schema8 的旧查询；保留新库和 journal，
旧 schema7 程序须拒绝新库，禁止降version/删库/重放UNKNOWN。完整A、独立与全项目审查另评估。

## v1.9 — A-TASK-SAFETY-20261015：任务中心受限安全控制

出处为 A 线 feature 完整未提交工作树，HEAD 5d826d5d7d8755ed42db834d6cd6dc52ae914c69。
精确字节、原始失败、实际结果和范围见 HXai_CHANGE_REVIEW/A-TASK-SAFETY-20261015；不由本节预授予通过。

AgentTaskDetailPage → AgentTaskSafetyControls → 同源生成的 taskSafetyCommand(profileId, taskId,
requestId, expectedEpoch, expectedRevision, expectedFence, action) → StudyHostFacade → 唯一
AgentScheduler.command(safetyOnly=true) → 原 AgentTaskStore.command/task_commands 事务。
仅允许 pause/cancel/revoke，固定空 payload；此入口不依赖关联会话存在或当前选中消息。
原 taskCommand、恢复/补充/凭据/设备确认的会话选择限制保持，不能借安全入口启动任务或扩大授权。

宿主检查当前 profile/epoch；Store 同事务验证 owner、revision、fence 与受限 action。
新 pause/cancel 拒绝修改 SUCCEEDED/CANCELED；revoke 只收紧授权，不改终态。
同 request/action/payload/原 revision/fence 已持久时先读取原 ACK，可跨重开/后续修订恢复；
同 request 改意图或边界拒绝。无新增表/状态/授权：sidecar8、Room32、Pigeon DTO codec162保持。
新增 API 必须同源生成 Dart/Kotlin 并同步 Flutter Debug AAR 与宿主。

Scheduler 取消并等待对应原 Job，任务状态仍取真实快照。STOP_REQUESTED 不代表已停止；
未知效果保持 UNKNOWN/slot，不因 cancel/revoke/ACK 释放。模型快照、计划/成果/回执、
输入/设备/审批等待保全，无网络请求、预算或新执行器。撤权后不能复活旧 permit。

UI 按 owner/epoch/task 绑定请求；每种安全动作有独立 pending/忙状态，回执未知复用原 ID/参数，
其他安全动作保持可用。刷新保留控件与 pending 状态，滚出屏幕保持状态；路由/档案变化销毁旧控件，
丢弃旧 ACK。ACK 后重读 DTO，绝不据按钮动画宣称停止、取消或成功。
重新打开页面仍从原库读取控制状态；非持久 UI pending 不代表任务未执行。
保留旧详情时，读取失败的重试必须保存原整页/分页模式，不能因旧详情仍存在而误走分页或变成无操作。

回滚仅移除新入口/控件，保留已持久暂停、撤权、命令与 UNKNOWN，不降库/清库或重放。
真实模型/云设备、Release/Mate50、完整 TalkBack、完整项目及独立外部审查不由本增量放行。



## v1.10 任务详情精确恢复入口（2026-10-15）

来源：A-TASK-RESUME-20261015/CHANGE_CONTRACT；本条是源码契约增量，实际测试与适用产物以该批 TEST_RESULTS 为准。Room32/sidecar8/DTO codec162 均无变化，原规范与失败证据保留。

- AgentTaskDetailPage 的恢复控件使用原 taskCommand，绑定 owner/conversation/task/request/epoch/revision/fence；action 固定 resume、payload 固定空，不扩大授权，不解除其他 blocker，不重新捕获模型。
- 宿主对新 resume 意图先复核当前队首或检查点。队首须是同一 accepted input，并在队列提交时与命令接受的 PendingInput 完整相等；已进入消息树的输入绑定不可变 USER messageId，正常 edit 会生成新 ID，可信 regex/材料预处理后的正文不得与原始 raw intent hash 强作相等。
- 检查点恢复显式传入原 acceptedTask，创建 job 前、prepare 与等待全局执行位后核对保存的当前消息/空队列。不能在延迟执行时以会话最新任务替换。缺原会话、无对应输入或无有效检查点拒绝；无可绑定 messageId 的旧 regenerated task 不猜测重建绑定，需重新提交。
- 已持久命令的同 request/boundary 重试沿原 task_commands 回执重放；只有仍 QUEUED 才可修补原 native→Room/Job 缺口，已运行/完成不重新生成。新分支的执行由原选择 guard 拒绝。
- 控件保留未知 ACK 的原请求/会话/修订/fence，刷新不丢失；查询原请求可能继续此前未启动的同一任务。新 DTO 是状态真源，ACK 不代表已开始或已完成。profile/epoch/资源切换丢弃旧回包，独立安全控制持续可用。
- 恢复支持范围与完整 App、真实模型质量、Release/Mate50、独立审查分别记录，不能用新入口表示 A 线完整完成。

## v1.11 持久恢复命令回执（2026-10-16）

来源：`HXai_CHANGE_REVIEW/A-RESUME-RECEIPT-20261016/CHANGE_CONTRACT.md`；实际字节/测试/产物见该批证据。
sidecar8→9 仅给原 task_commands 增 nullable resume_revision/resume_fence；空 payload 的 resume 在原命令事务中记录原边界，与原 input_hash 交叉验证。旧行不反解摘要、不补造参数。Room32 不变，Pigeon 在末尾新增 codec163 TaskResumeReceiptDto 及 getTaskResumeReceipt，必须同源生成两端并重建 AAR/宿主。

AgentTaskResumeControl → 只读 getTaskResumeReceipt(owner,task,optional request,epoch) → StudyHostFacade → 唯一 Scheduler → AgentTaskStore.readResumeReceipt。查询事务验证归属、参数类型/范围/摘要；不查凭据、不要求会话尚存在、不调用 resume/reconcile/dispatch、不释放 UNKNOWN/slot。RECORDED 只说明命令持久，不能声称任务启动、结果成功或用户已看到 ACK。

跨页面/进程重开只发现已有恢复命令。精确 request 查询优先于“最新”查询；旧行无元数据返回空，损坏或不匹配拒绝。查询失败阻止新恢复，重查仍只读。显式“继续协调原恢复”使用原 request/revision/fence 走既有 taskCommand；新授权/设备/input/分支与模型锁门禁保持。只有 QUEUED 且 revision=原 revision+1、fence 未变、独立 blocker 全清才允许协调；后续暂停/撤权/修订或已有执行推进不能借旧 ACK 启动任务。重复 ACK 的宿主执行入口也验证原边界，防止 UI 外的旧命令重放。

v1.10 中“查询原请求可能继续任务”由本增量改为两个明确动作：查询只读；继续协调必须用户再点明确按钮。未持久命令没有原子提交的恢复效果；同一控件保留未知请求并可先精确查询，确认无记录后手动重试同一参数。Flutter 销毁之前尚未到宿主/未提交的本地意图不作为已接受命令。跨 owner/epoch 的旧异步回包丢弃，安全控制独立可用。

旧库实物来源、迁移事务回滚、真实进程退出后查询原命令、正常恢复/后续暂停与 UNKNOWN 的测试分别记录。回滚停新入口并保留 schema9；不降版、清库、重放旧命令或改原 Pro 规范。独立及完整项目审查 NOT_EVALUATED，本增量不授予完整 A/P0、Release/Mate50/云设备/生产通过。

## v1.12 重新生成的检查点身份（2026-10-17）

出处：`HXai_CHANGE_REVIEW/A-CHECKPOINT-IDENTITY-20261017/CHANGE_CONTRACT.md`及实际源码/日志；本节不预授验收通过。Room32/sidecar9/Pigeon163保持，无新执行器、授权或模型路由。

`TaskCheckpointIdentity`定义 `regen1:<source UUID>:<input prefix count>:<branch SHA256>:<nonce UUID>` 请求身份。指纹对选中输入前缀的 node UUID/message UUID/role 作规范JSON摘要，包含确切分支身份，不以相同正文推断。nonce区分同输入的不同明确重新生成请求。原TaskStore request/owner/conversation与接受时模型快照固定；goal来自指定目标前缀的最后USER，而非会话末尾其他USER。

`ChatService.regenerateAtMessage` 接受时检查选中目标及空队列，接受后、等待全局slot之后再次核对目标/原输入前缀；不符合即拒绝进入原Loop。`ConversationSession.checkpointGeneration` 从可信 `AgentExecution` 取taskId，先验证owner/conversation，再将最后消息的可空本地 `UIMessage.generationTaskId` 与内容在同一原Room事务提交。模型文字不能创建此来源。字段在既有消息JSON中持久，旧数据默认null，旧兼容reader忽略未知字段；Provider DTO和Pigeon消息不传这个本地控制字段。

`AgentScheduler.current` 有来源时读取对应owner任务并校验输入/分支/消息marker；无效或缺失任务拒绝，不回退到更高fence。因而选中历史回答分支可定位原任务。新regen请求的恢复由Store request中的原source/prefix指纹与消息marker双向核对；task详情、隐式原会话恢复、等待slot后的再次检查保持相同接受任务。直接USER request的旧任务保留原兼容；旧随机request没有确定来源时继续拒绝，不能用goal同文本或当前模型设置补造映射。备份中的marker本身不是授权，没有同owner Store及有效原授权不能恢复。

原模型锁、单slot/递增fence、UNKNOWN、副作用journal、独立等待和验收谓词保持；旧成果、消息分支及Pro失败证据不清除。实际进程退出/同文本分支/较新fence/错误来源/正确预处理/早期目标、原故障链分别记录，不将本增量写成完整A、真实模型质量、云设备、Release/真机或生产验收。

## v1.13 手动记忆删除绑定请求身份（2026-10-17）

出处：`HXai_CHANGE_REVIEW/A-MANUAL-MEMORY-TOMBSTONE-20261017/CHANGE_CONTRACT.md`。Room32/sidecar9/Pigeon163/MemoryState schema1与fingerprint1保持，验收以该批实际源码/日志为准。

手动记忆ID沿用原owner/requestId确定性生成，类型不是请求身份的一部分。原删除事实HMAC `$type:手动添加:$id` 保留原值；`ManualMemoryIdentity.isDeleted` 在同owner指纹域核对全部已知MemoryType的旧标记。`LearningMemoryStore.addManual` 在原Room事务内按此判定拒绝原请求，不接受客户端换type解除删除。新的明确requestId不受原ID删除标记影响。

删除手动记录同时在原suppressedFacts集合加入HMAC `manual-request:$id`，与可由新明确“记住”恢复的事实标记分离。`saveExplicit`允许新来源/新UUID事实；若命中topic=手动添加、fact=旧手动ID的历史类型标记，先保全请求身份HMAC再解除对应事实标记。该兼容提升在原事务内完成，无schema变化；新事实不能解封原手动请求。helper与备份同时识别新身份标记和旧类型标记，不按旧库不确定信息猜测正文。

`MemoryRestore.merge` 对saved/live删除标记并集使用相同身份判定，不相信备份记录当前type/topic/origin标签。原键域检查、源/事实/主题删除并集、较新人工修订、旧任务取消和启动前二次合并保持；不增加外部权限，不清除墓碑或记录已删除正文。历史已被旧漏洞复活并写入当前库的记录不自动物理删除，本批不声称完成全量历史清理。未知版本仍用既有拒绝门禁。

新增来源映射：add_memory_dialog → StudyHostFacade.addMemory → LearningMemoryStore.addManual/ManualMemoryIdentity；BackupManager.confirmRestore/applyPendingRestore → BackupDeletionMerge → MemoryRestore/ManualMemoryIdentity。仅针对手动请求身份级删除保护，不改变Agent工具授权/模型/队列、自动抽取分类或UI DTO。原生反例、Room/备份staging、真实进程退出和旧兼容分别验收；独立/全项目与真实设备/生产另列。


## v1.14 历史已复活手动记忆的上下文抑制（2026-10-17）

出处：`HXai_CHANGE_REVIEW/A-MEMORY-CONTEXT-SUPPRESSION-20261017/CHANGE_CONTRACT.md`。精确字节、旧版失败/修复结果及产物由本批 TEST_RESULTS 固定，本节不预授验收通过。Room32/sidecar9/Pigeon163/MemoryState1/fingerprint1 无迁移。

`ChatService.handleMessageComplete -> LearningMemoryStore.context -> relevant -> ManualMemoryIdentity.isDeleted`：当前库中已由旧漏洞换类型复活的记录，在 ACTIVE/来源筛选前核对同owner全部已知旧类型事实HMAC与独立请求HMAC。不信任记录当前type/topic/fact/origin，不按相同正文推断身份。排除的记录不序列化到Provider记忆上下文，亦不记入本次usedMemories；合法新request/新明确来源与其他owner仍独立。

read/observe/getMemories仍保留原记录供本机管理，本批不物理清理历史记录，不增加永久副本、恢复按钮或自动重放。relevant不写库；context仅更新既有使用投影，不改items/history/suppressed集合。缺保护密钥、未知数据版本仍沿原read保守拒绝，不重建库或丢墓碑。fingerprint0不可映射的旧删除约束、全历史会话/摘要清理与备份全域处置不由此单点身份过滤证明解决。

源码映射：唯一产品文件 `feature/memory/LearningMemoryStore.kt:relevant`；原helper为v1.13，原ChatService/GenerationLoop调用链、MemoryExtractionRunner用户消息抽取、MemoryRestore删除并集合并保持。本批验证当前库旧HMAC/独立身份、真实Chat HTTP请求及实际进程退出后重开；无Dart/Pigeon/授权/模型锁/任务调度/文件协议变化。回滚应停记忆上下文注入并保留原库/墓碑，不恢复泄露旧记录的过滤器。完整A/P0、独立与全项目审查、Release/Mate50/云设备/生产另评估。

## v1.15 旧记忆删除约束的上下文保护（2026-10-18）

来源：`HXai_CHANGE_REVIEW/A-LEGACY-MEMORY-CONTEXT-20261018/CHANGE_CONTRACT.md`；精确字节、反例、实测与产物由该批 TEST_RESULTS/SOURCE_AFTER 固定。本条不预授整树/App通过。Room32/sidecar9/Pigeon163/MemoryState1/fingerprint1 保持。

> 后续 v1.16 状态接线增量：见本文末节及 A-MEMORY-PROTECTION-UI-20261018 合同；以下为本批历史范围，不覆盖后续真实实现。

既有 fingerprint0→1 迁移无法逆解已丢弃正文的 fact/topic 墓碑时，保留原集合并设 recoveryBlocked；本增量将该保护用于原 `LearningMemoryStore.relevant/context`，整个受影响 profile 暂停记忆检索和 Provider 记忆投影，不再猜测哪些旧记录可用。迁移对原 recoveryBlocked 做 OR，不能因未看到剩余墓碑就放松已有保护。context 在原 Room 事务中先读保护状态，受保护时返回空且不更新 usedMemories；既有一次迁移仍可取消旧 job、epoch+1，后续投影不改 payload。

`ChatService.handleMessageComplete` 的正常完成回调仍调用原 enqueue；受保护时 enqueue 返回0，不创建抽取 job，不把普通闲聊标成失败。claim/commit/MemoryExtractionRunner 的原保护保持，saveExplicit 的验收门禁不解除。原 TaskStore/模型锁/授权/UNKNOWN/执行位与用量均不变。

管理原件继续从 read/observe/getMemories 读取，允许既有 edit/delete；不增加旧正文副本或物理清理。Compose 既有保护说明同步提示上下文暂停；Flutter 尚无 recoveryBlocked DTO，保护状态展示仍是后续接线项，不能把记忆开关或 ACTIVE 标签当作本次已注入证据。新档案、另一 owner 与无未知约束的合法旧档案保持原能力；坏/缺密钥拒绝并保留库/key，不自动生成替代键。

源码地图：Chat → StudyHostFacade → ChatService → LearningMemoryStore.context/relevant/read/enqueue → 原 Room learning_memory_state；管理 `getMemories/read/observe/edit/delete`、`MemoryRestore.merge` 的 sticky OR 为相邻保全链。定向 Android 合成反例、真实 loopback 请求与 force-stop 后两次重开分别验收；独立及完整项目审查 NOT_EVALUATED。历史会话正文/摘要治理、v0 孤立 source 墓碑与全部备份语料尚未由本批证明解决。回滚停记忆上下文保留原库/墓碑/key，禁止降库/清库或恢复旧投影。

## v1.16 Flutter 记忆保护状态（2026-10-18）

恢复补强：`MathStudyApp.didChangeAppLifecycleState`在已存在保护provider时调用refresh，进行中的设置保持pending，完成后由其独立读回刷新，不用invalidate丢掉在途操作。即使owner/epoch不变，后台修改后的保护状态也须重查。`getMemoryProtection`先等待真实Settings初始化，超时/错误走未知通道，不能把dummy设置当持久值。真实Activity恢复与启动默认值原生反例（旧2项2失败）及修复证据随本批保存；schema与codec不另变化。

首次build尚未完成时刷新使用invalidateSelf取消旧只读回执；普通刷新保留revision/owner/epoch校验。在途设置由mutation标记保持pending，并使用专用_reload独立读回，不因恢复/刷新被丢失。该乱序原反例与保存中恢复阳性对照均纳入本批，不依赖UI动画判定。

来源：`HXai_CHANGE_REVIEW/A-MEMORY-PROTECTION-UI-20261018/CHANGE_CONTRACT.md`；实测由该批 TEST_RESULTS 与 SOURCE_AFTER 固定。末尾追加 codec164 `MemoryProtectionDto(profileId,contextEpoch,configuredEnabled,recoveryBlocked)`，原128—163类型映射保持。Room32/sidecar9/MemoryState1不变，v1.15后端保护保持。

真实链路：MemoriesPage → memoryProtectionProvider → StudyHostApi.getMemoryProtection → StudyHostFacade → LearningMemoryStore.read / SettingsStore。读取前后校验owner/epoch，密钥/版本错误走失败通道；不能构造未保护的成功状态。设置与实际保护分别展示，加载/错误/刷新期间禁止开关；保护中原管理入口保持。页头随内容滚动，筛选Wrap支持小屏大字。

toggleAutoMemory追加expectedEpoch；启用前读取保护状态并拒绝protected enable，关闭不依赖可读密钥。DataStore edit内核对请求epoch与持久active owner；操作后重新读取持久设置，失败无成功提示，迟到成功/错误按profile/epoch丢弃，进行中不重复提交。开关只表示用户设置，不能据此声称具体请求已抽取或注入记忆。

源码地图：flutter_ui/pigeons/study_host_api.dart唯一协议源及生成Dart/Kotlin；StudyHostFacade.getMemoryProtection/toggleAutoMemory；PreferencesStore.setLearnerMemoryEnabled；study_providers.MemoryProtectionNotifier；memories_page。定向Flutter memory_protection_test与profile/detail/project/neutral旧回归，原生FlutterMemoryToggleTest/MemoryProtectionSurfaceTest与旧Chat/Legacy回归。Pigeon/AAR/宿主必须同源配套重建，不允许旧AAR混装。物理TalkBack/Mate50/Release与完整A/P0另验，独立与全项目审查NOT_EVALUATED。回滚配套桥与AAR/宿主，原库/key/墓碑保留。

## v1.17 历史来源与摘要投影（2026-10-18）

来源：`HXai_CHANGE_REVIEW/A-HISTORY-SOURCE-SUPPRESSION-20261018/CHANGE_CONTRACT.md`。当前 feature 脏树的精确源码、旧版反例与实际结果取该批 TEST_RESULTS；本节不预授整树/App通过。Room32/sidecar9/MemoryState1/fingerprint1/Pigeon164 不变。

`LearningMemoryStore.isSuppressed/hiddenMessageIds` 除原来源行外，直接核对实际候选用户消息的 owner-scoped portable HMAC（message ID / ID+content hash），避免仅因备份缺少 source 行就失去匹配。`projectHistory` 在同一状态快照上过滤完整被删轮次及后续回答/工具；同文新 ID 与其他 owner 不按文字猜删。任何来源墓碑、显式 hidden ID 或 recoveryBlocked 存在时，无来源清单的历史摘要及其轮次保守不投影。这里只改变外发视图，不物理删除原聊天或清除墓碑。

主请求：`GenerationLoop.generateInternal` 在工具 prompt、输入变换、预算/自动摘要/缓存 fingerprint 之前投影实际历史；当前请求自身已被删除则拒绝续发。手动压缩：`ChatService.compressConversation` 先对完整选中历史投影，再分块/保留最近消息；每个请求前及提交前重查历史投影，删除变化使迟到压缩结果失效，原聊天保持。模型快照、独立角色准入、任务权限/UNKNOWN/单执行位不变。

边界：旧摘要无来源清单，即使其中含安全片段也无法证明可保留；本批新手动摘要也未增加来源签名，已有删除约束的档案后续会保守跳过这些摘要。丢弃 preimage 的 v0 孤立 source/fact/topic 墓碑、原文语义衍生内容、title/suggestion 等辅助角色与全部备份语料仍须接续，不将本批便携哈希匹配称为全历史遗忘。已发出的请求不能撤回；缺密钥继续拒绝并保留原件。回滚先停受影响投影/压缩，再按本批增量三方回退，保留库/key/墓碑。独立与完整项目审查 NOT_EVALUATED。

## v1.18 标题、建议与发布事务（2026-10-19）

出处：同批 `CONTINUATION_CONTRACT_20261019.md`，接续未完成 v1.17；原始合同/失败证据保持。Room32/sidecar9/MemoryState1/Pigeon164 不变。普通构建、反例、源码和 APK 哈希以该批最终 TEST_RESULTS 为准，不预授完整 A/P0 或整树通过。

源码地图补充：`ChatService.generateTitle/generateSuggestion` → `LearningMemoryStore.projectHistory` → 原 `AgentScheduler.requestRole`/角色账本 → 原 Provider → `ConversationSession.applyBackgroundMetadata` → `LearningMemoryStore.commitWithHistory` → `ConversationRepository.updateGeneratedMetadata`。先对完整历史过滤完整轮次，再取角色末尾窗口。无可用来源则零请求；请求前（已取得执行位后）与回包后重查，删除变化使迟到结果失效。新 ID 的用户显式重述仍可用，不按同文猜删。主模型/角色配置、权限与执行位保持。

`commitWithHistory` 在原 Room 事务中重查投影并提交本机元数据；调用者先持有 Session 的既有锁，锁顺序保持 Session→Room。事务不跨 Provider/网络请求。手动压缩在调度排队之后请求前再查，最终通过 `ConversationRepository.updateConversationWithHistory` 在仓库原有写事务里检查投影及保存，全文索引和附件清理仍在提交之后。不能把含 IO 线程原始 SQLite 索引写的 persistConversation 整段包进外层事务；该初版阻塞反例已保留。事务异常回滚保留原件，不清库/key/墓碑。

本次仅补 title/suggestion，不能据此宣布所有辅助调用均治理完成。既有生成标题/建议的追溯清理、翻译/其他辅助输入、v0 孤立 source 约束迁移与全备份语料继续 PARTIAL；无法逆解的正文不猜测删除。测试清理会话统一调用 ChatService，生产生命周期校验不放松。按 Pro v1.1 新功能/跨数据适用门禁验证；独立及完整项目审查 NOT_EVALUATED。
