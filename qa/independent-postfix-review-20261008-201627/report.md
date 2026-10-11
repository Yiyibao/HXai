# 当前 MathStudyAgent 修复后的独立复审

发现 **1项确定生产代码缺陷（PR01/P1）和2项测试/证据缺陷（PR02、PR03/P2）**。未修改生产代码；没有提交、推送或部署。可执行问题及直接证据见 [findings.md](findings.md)，逐项结果与未验证条件见 [acceptance-matrix.md](acceptance-matrix.md)，机器记录见 [summary.json](summary.json)。

PR01：会话删除后，session淘汰导致删除标记丢失，旧保存重新插入原消息。PR02：Word表格helper忽略tab，错误内容仍比较通过。PR03：manifest=PASS但JUnit随后FAILED，采集器仍记录PASS。缺口和历史观察不列为确定生产缺陷。

## 版本和实际范围

先阅读适用 `client/AGENTS.md` 和用户指定14份QA文件；根/祖先没有其他AGENTS。没有触及Web源码，Web专属AGENTS不适用于本轮改动。独立目录为 `qa/independent-postfix-review-20261008-201627`。方法/UUID和Unix毫秒窗口分别保存在各run-context及results文件；用户时区为Asia/Taipei。本轮始于2026-10-08 20:16，核心设备执行20:18–20:19，正常交付20:19–20:21，新增实质设备探针20:29执行。

审查集中在ChatService、ConversationSession/Manager、审批Repository事务、MessageQueue、附件清理、SkillsManager/Paths/TreeDeletion/ImportLimits/VM/Tools/Factory、Word断言及材料测试、Diagnostics隔离和指纹、服务Store/main/清理、证据生产与采集器。不是全项目每个变更行或全部UI审计。

[946个原输入文件哈希](input-hashes-after.json)结束时零变化；用户修改保留。指定修复源哈希与当前输入相符：[source-comparison.json](source-comparison.json)。本轮只写新QA源/脚本、证据以及临时构建缓存，临时合成google-services配置finally移除，未读取/导出真实配置或凭据。

原主x86_64 APK SHA256=`A9B22B310E5ED5F91D5C97AD0988D9E7C4FA4337DFF698EFF58597E10384D8F8`；原测试APK=`0483C9E4A0FA0AB2AB27F719DC740D8DF40C87ADD6728AFCD8C21F15CB594EFC`。核心/交付使用该对。新增探针只改变测试输入，实质复验测试APK=`951CF1E9FA00ABE7D5ACDA4ACDAEDE0C4837F48D79E72D5C5ECF0482C01C594D`。首次夹具错误包也单独留档。完整三ABI、测试包和探针见 [apk-final.json](apk-final.json)。

设备emulator-5554/API34/x86_64/4096页；核心seed/force-stop/verify PID分别21834/21925。结束恢复标准测试构建和原已安装测试APK；[最终已安装哈希](installed-apk-final.json)与原主/测试包相符。

## 独立批次，不累加

|本轮批次|实际结果|范围|
|---|---|---|
|app JVM标准输入|328 PASS，0 FAIL/ERROR/SKIP，44 suites|先cleanTestDebugUnitTest再执行；XML归档jvm-core；不是历史XML沿用。|
|Android原核心|21 PASS，0 FAIL/SKIP；8次invocation|Skills3、Word7、Diagnostics6、context privacy1、Skills合同2、seed/verify2；详细UUID/窗口在acceptance-android。|
|正常交付|同一方法2次PASS，10格式组哈希匹配|新的UUID `c549393e-…`、`8cb8c86f-…`；实际VIEW/SEND和确切另存目标。[精确结果](exact-delivery-hashes.json)。|
|服务端|28 PASS，0 FAIL/ERROR/SKIP|真实Store/SQLite/main/cleanup；平台锁、Docker和external worker替身，不能宣称真实worker/挂载验收。|
|采集器原基线|6 PASS|合成device；首先沙箱临时目录权限失败为环境错误，QA临时目录复跑。|
|新增JVM首批|2 FAIL|PR02实质测试判断失败；队列probe异常对象identity过严是新QA断言错误。首次XML保留。|
|新增JVM夹具修正批|1 PASS|只重跑门控队列/失败不发布方法，比较异常cause链保留原对象；未再覆盖PR02失败。|
|新增Android首批|2 FAIL（QA夹具错误）|超长档案名称在setup失败，尚未触及目标生产断言；原源码/包/日志保留。|
|新增Android夹具修正批|1 FAIL/1 PASS|PR01删除淘汰旧保存FAIL；完整service/factory合成provider续行PASS，方法内部三决定不算三JUnit方法。|
|新增采集器终态探针|1 FAIL|PASS finalized manifest后同方法JUnit失败仍被输出PASS；合成输入/输出留档。|
|构建/Lint|原标准三ABI/测试包目标成功，0错误/42警告|多数构建/分析任务UP-TO-DATE；不称强制全量重新分析。探针构建首次init路径错误独立保留。|

新增完整审批路径使用真实工厂 `use_skill` 和真实服务/Loop/Room，只替换provider为纯本地实现，不调用模型网络。Approved观察到实际合成技能内容，Denied/Answered没有该内容；三决定门控期间重复和冲突及完成后的迟到回调不改变首次决定/另一活动Job，合成provider每决定1调用。**provider次数不是execute函数次数**；原分段1/0/0保持分段结论，准确工厂execute拦截计数仍有缺口。

本轮Word五格式在新方法/UUID目录实际写入生产Writer样例，再生产Reader核对中文；核心及交付42份产物首次/重复采集均重新核对安装包、log SHA、窗口、mtime和字节。新增探针另采2份，含FAILED，不修改其状态。本轮只读登记的确切Download UUID目标，不扫描个人文件。

## 首次失败、复跑和限制

- 沙箱ADB daemon启动失败，主机连接成功；没有把首次环境错误当产品FAIL。首次collector默认TEMP权限错误保留在collector-tests.log，改用本轮QA临时目录后6PASS。
- 沙箱服务TestClient停滞被中断，server-first.log保留；同输入在主机本地临时目录复跑28PASS，Starlette/httpx弃用警告保留。未连接Docker/worker。
- 新Gradle init相对路径按client解析导致找不到脚本，probe-build-first.log保留；绝对路径复跑实际编译并触发PR02。随后仅修正QA档案名长度/异常cause断言，分包分日志复跑，不改生产实现。
- collector-status-probe首次runner只输出JUnit失败日志但shell退出码0，因此按实际断言FAIL记账，不能使用shell0当PASS；这是本轮额外QA runner的退出码缺陷，不与生产collector PR03混同。首次源和日志另存；只修正QA runner退出码后复跑同一合成负例，仍FAIL且退出码1，见collector-status-probe-exit-fixed.log；两次不累计为两项独立场景。

没有发真实远端模型、图片、GitHub或额外设备请求，没有改写或自动重发过滤请求、重放不确定工具。真实图片正例NOT_RUN；历史缺配置条件仍不能当成功。历史真实教学/材料失败根因UNKNOWN，后来通过不证明根因修复。

仍未验证的本地子场景包括每个事务步骤故障、Skills嵌套/断链/清理与替换失败、跨会话附件和I/O清理重试、所有UI/模型回调、交付取消/损坏/切档/目标重读失败。真实worker/挂载、额外平台/Release/迁移与全提供商回归另列NOT_RUN。本轮不是全项目验收通过。

所有清理只针对本轮合成档案的确切会话ID、QA技能UUID和已登记文件；新失败的合成复活行在finally精确删除；真实用户技能内容没有导出。诊断6方法自带原记录/导出缓存哈希保全断言，未清空生产诊断目录。证据、第一次失败和QA源码保留在此独立目录。
