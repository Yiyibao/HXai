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
