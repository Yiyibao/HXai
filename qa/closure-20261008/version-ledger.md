# 批次与版本账本

历史目录不变、不计入本次。候选复跑均有独立日志/目录；失败不覆盖、不合计为最终通过。

|版本/批次|复跑目的及首次结果处理|版本证据|
|---|---|---|
|原负例|PR01 淘汰复活；PR02 tab；PR03 manifest/JUnit 矛盾均实际失败。原环境 tempfile/native DLL 错误也保留，随后按正确 host/temp 运行。|pr01-before.log、pr02-before-xml、pr03-before-qa-temp.log；原 APK/探针取自用户指定独立复查目录，未伪称新构建。|
|candidate 1–3|QA Gradle 源集未发现/duplicate helper 错误；纠正接入而非改变产品断言。|candidate-1/2/3 构建日志及 exit|
|candidate 4|三个 PR 修复；发现 Skills rollback 产品失败；memory 注入无实际写入、broken resolver null 过强是夹具错误。|candidate-4-android 首次全部日志/APK/UUID；保留首次断言|
|candidate 5|新增有序 Word 等价/错误与并发 queue JVM 门控通过。|candidate-5-probe-xml|
|candidate 6|真实工厂准确计数/Skills rename fallback；两轮正常交付；COMMIT 恢复和 UI 夹具仍失败。首次 JVM qa.root 缺失保留，后续仅修设置。|source-candidate-6、candidate-6-android/collected、candidate-6-jvm-first|
|candidate 7|JVM/Lint；改为真实 memory 写入与事务内 DDL；暴露 native COMMIT 污染下一事务。|candidate-7-tests；transaction-fixture-7 首次残留 trigger 与 cleanup-retry 分开|
|candidate 8–9|恢复方案失败：close 导致 Room 缓存失效；session ROLLBACK 不能恢复 native writer。|各构建日志与 transaction-product-8/9 首次及 cleanup-retry|
|candidate 10|Skills 部分删除原负例先失败，再增加完整不跟随链接回滚副本。|partial-cleanup-original；partial-cleanup-before-test.apk|
|candidate 11|固定 primary connection affinity 仍由 executeSpecial 转 Java rollback；失败及原异常保留。|transaction-product-11/registered-exceptions.txt 与 bytecode|
|candidate 12|QA runner 在 projectsEvaluated 修改 DSL 过晚：构建错误保留；注册 finalizeDsl 修正。|candidate-12.log|
|candidate 13|原池 writer 直接 rollback 后 15 故障通过；Skills 三方法通过；UI仍失败。|source-candidate-13；transaction/skills-product-13；delivery-ui-product-13 首次 FAILED 已严格采集|
|fixture 14–15|加强 UI 前态/目标证据；按钮大小写与 UiAutomation shell 引号夹具错误保留，不降低零字节断言。|ui-fixture-14/15.log；delivery-ui-fixture-14/15-collected；DeliveryUiClosureTest-first.kt|
|fixture 16|修正引号后同一零字节负例 actual=19，确认新 P1；仍未修改生产交付。|delivery-ui-fixture-16 日志/安装 APK SHA/collected|
|candidate 17–18|修复实时档案授权，添加复制中切档及两个 UI 入口；产品18两方法通过。|candidate-17/18.log；delivery-ui-product-18；source-final-candidate-18|
|final-quality|最终同一源码 app332/ai200、QA4重跑、Lint0/42、三 Debug 与 test APK；QA4已包含在app332，不重复累计。|final-jvm、final-lint.xml、final-quality.log|
|final-independent-android|固定一次独立验收：22调用/41方法，含 Room备份3+会话4；没有失败后忽略。|final-independent-android、final-collected：59产物、10组精确交付|
|final service/collector|collector9；服务首个sandbox命令超时保留 exit=-1，定位并结束仅本QA进程，服务目录host执行29通过；无 worker/远端。|server-final.log 与 server-final-retry.xml/log；final-collector.log|
|finalization|汇总脚本首次把 pass 作 Python关键字导致语法错误；改名 passed 后检查通过，不涉及产品/测试断言。|finalization-first-error.txt、finalization.log|

## 最终 APK

|产物|SHA-256|
|---|---|
|client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk|`0556dd58e0cb4ca641317ff8afc101c6f00c82c827837a4aef331c5c3c0e7919`|
|client/app/build/outputs/apk/debug/app-universal-debug.apk|`45e62f4741d6d4fffa072776b296b780ab2e0732b49ec6aed7b2760ee9d64ef9`|
|client/app/build/outputs/apk/debug/app-x86_64-debug.apk|`f8f8ab311ecabb051cb296bd9660bdfa460dea8dfacf465aa4918a324685ac82`|
|client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk|`9c694f7c902d2b53911c92f01b4c1948a09562ee3c369140ac88ec49d243a11f`|

安装主包/测试包与上述 x86_64/test APK 一致；source-final-candidate-18/hashes.json 再核对源码 0 差异。完整日志、上下界、设备 mtime/字节记录、证据哈希见 summary.json、run-context.json、fixture-manifest.json、evidence-hashes.json。
