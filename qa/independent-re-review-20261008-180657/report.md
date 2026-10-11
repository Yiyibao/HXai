# 当前工作区修复后独立复审

发现 **1项确定生产代码缺陷（P2）**、**1项确定证据采集缺陷（P2）**和**1项测试隔离缺陷（P3）**。详见 [可执行问题与证据](findings.md)、[逐项验收矩阵](acceptance-matrix.md)；这不是全项目验收结论。

## 版本与审查范围

开始前已读取用户指定5份材料、`client/AGENTS.md` 及 Web 的适用约束。根/祖先没有发现其他AGENTS文件。保存 [原工作区状态](worktree-before.txt)、[源码哈希](source-hashes.json)、[APK哈希](apk-hashes-before.json)。修复报告最后记录的源码 After 值与当前源码核对见 [fix-source-hash-check.json](fix-source-hash-check.json)。本轮结束生产源码输入变化为零：[source-delta.json](source-delta.json)。

主APK SHA-256：`36EA07F35412A9DC5083CAC8EA3482C3521A626C87658ED370AAA13CF9BA02BB`；测试包：`CED0B0EBA6ADF194FE0434E6B3C855A7ABA3DFB83BEBDC8EBA8F462885C93DD7`。三ABI及测试包均与最终候选记录一致，重建后哈希也不变；还直接对[设备已安装APK](installed-apk-sha256.txt)计算哈希，与主/测试包一致。没有仅以历史通过结论当作当前验证。

设备为 emulator-5554 / API34 / x86_64 / 4096字节页。当前复审的fixture UUID为 `4f0bb159-ecdc-45e5-bb99-afa90e41df13`，合成档案为 `fdb6f940-6db5-496f-aa00-5bc9110fc6c6`。[受控档案清单](controlled-manifest.json)仅包含本次20条记录；重开核对18条存在与2条删除，seed/verify日志中的PID不同。

## 分批结果

|本轮批次|实际结果|范围和限制|
|---|---|---|
|Android受控边界/Skills/Word|8通过、0失败、0跳过|seed、独立verify各1；Skills安装/启用各1；Word4；[批次](android-batch.json)|
|Android页面与正常交付|3通过、0失败、0跳过|MaterialPageTest、两套交付方法各1；[批次](delivery-batch.json)|
|精确正常交付|10/10哈希一致|这是上面两方法内的10格式组，不再加成10个测试；[SOURCE/VIEW/SEND/目标](exact-delivery-hashes.json)|
|app JVM|322通过、0失败/错误/跳过|先cleanTestDebugUnitTest再实际运行，XML归档jvm/；未声称非app全部模块647项|
|服务端|27通过|[当前XML](server.xml)、[日志](server.log)；真实Store/SQLite/main/cleanup，平台锁/Docker及循环终止替换|
|构建与Lint|三ABI/测试包目标成功；0错误/42警告|构建及lint部分任务缓存命中，非强制全量重新编译；[复跑日志](build-jvm-lint-placeholder-retry.log)|
|Web|typecheck与react-router build成功|没有执行会向后端复制资源的copy.ts；非部署|
|新增Skills资源负例|1失败|当前生产ZIP解析器触发OOM；单独探针，不并入322项|
|新增采集器陈旧负例|1失败、1通过|已有manifest拒绝失败；无manifest拒绝通过；模拟ADB且全合成文件|
|真实远端教学/材料|NOT_RUN|上一修复轮首次真实包2项通过为历史证据；未自动重发任何历史失败/过滤请求|
|Android诊断类|NOT_RUN|既有测试整目录清空不符合本次精确清理约束，见IR03|

历史44项回归、18项候选边界和首次修复包2项真实方法各自保留原批次，不与本轮相加。所有子场景以矩阵为准；测试方法或测试类通过不能替代未直接断言的情形。

## 首次失败、环境及复跑

首次本地门禁因缺 `google-services.json` 未完成：[原日志](build-jvm-lint.log)。随后采用仓库 `qa/s0/google-services.example.json` 的合成占位配置复跑，并在finally移除，仅删除本次创建的确切文件。不是产品断言失败，也不是正式配置验收。

资源探针第一次因init脚本误作用于included build失败（skill-import-probe.log）；第二次测试源没有接入Kotlin编译任务，没有找到测试（skill-import-probe-harness-retry.log）。接入后第三次才实际执行并获得OOM失败（skill-import-probe-source-retry.log）。这些均保留，前两次不算产品失败；第三次失败不复跑掩盖。init脚本和额外测试源都在本独立QA目录，通过临时Gradle输入执行，没有写入app源码目录或改变APK。

服务端出现一条Starlette/httpx依赖弃用警告，未隐藏。没有以此断言当前生产服务故障。

## 仍需验证

审批成功后的准确工具执行计数和另一活动任务隔离、非空队列双失败后重开一致性、Skills ZIP/真实GitHub/卸载/符号链接和真实已安装内容检查、Word源/目标单元格保真、Android安全诊断容量复验及完整B/C/D子场景仍有缺口。现有静态工厂没有自动拼入学习档案的路径，但本轮没有导出真实技能包，不能对所有安装内容作无泄漏保证。

历史真实教学/材料失败当前仍根因未知；上一修复轮通过不等于原因已修复。图片生成仍缺模型配置，不算成功。分享窗口本轮通过有当前证据，但只是一轮页面/交付样本，没有长期稳定性证明，也没有重建历史那次窗口时序。

保留既有修改；没有提交、推送、发布或部署。没有导出个人聊天/凭据、改写被过滤请求或重放不确定工具。受控verify按本次manifest的确切会话ID、所属档案和QA标题删除测试会话及登记私有材料文件。正常交付新产生的QA副本保留为证据，未对未知档案目录作递归清理。完整机器结果见 [summary.json](summary.json)。
