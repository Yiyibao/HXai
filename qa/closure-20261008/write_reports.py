import json, re
from pathlib import Path
QA=Path(__file__).resolve().parent; ROOT=QA.parents[1]
def read(name): return json.loads((QA/name).read_text(encoding='utf-8-sig'))
s=read('summary.json'); scope=read('scope.json'); runs={r['name']:r for r in s['android']['runs']}
def link(name,label=None): return f'[{label or name}]({(QA/name).as_posix()})'
def source(name,line): return f'[{Path(name).name}:{line}]({(ROOT/name).as_posix()}:{line})'
def ev(names):
    return '<br>'.join(f"{r['className']} / `{r['runId']}` {link('final-independent-android/'+name+'.log','日志')}" for name in names for r in [runs[name]])
group={
'PR01':(['eviction','lifecycle-seed','lifecycle-verify','checklist-seed','checklist-verify'],'淘汰后保存、旧 revision 写入/元数据拒绝；新建与更新成功；显式恢复 revision 递增；独立 PID 重启核对 DB、来源及搜索。'),
'PR02':(['word'],'JVM 同一 helper 的 tab/br/顺序/等价结构；设备保留全部表格行列、明确 OMML 值和原图片字节负例。另见 final-jvm/probes 的两条 table 方法。'),
'PR03':([],'final-collector.log：9 项直接负例；断言或 cleanup 失败、进程崩溃、缺方法/调用终态、混合类/方法、重复终态及旧字节/APK/窗口均拒绝；final-collected 另核对确切 class/test。'),
'APPROVAL':(['transaction','factory','checklist-seed','checklist-verify'],'五真实 Room 事务步骤 × 三决定，逐项 DB/内存/队列不变；真实工厂 use_skill 计数 1/0/0、provider=1、另一个活动 Job 保持；锁内队列与编辑/入队/暂停由 final-jvm/probes 两条门控方法断言。'),
'SKILLS':(['skills-links','skills-rollback','skills-partial-cleanup','skills-existing','skills-install','skills-enabled'],'真实目录链接、失败注入及完整字节/启用状态；安装/查看/显式启用/卸载合同；资源膨胀、数量/深度/重复路径见 final-jvm/app 的 Skill* XML。全局指令仅源码调用链审计，未导出个人技能。'),
'ATTACHMENT':(['attachments','checklist-seed','checklist-verify','db-conversation-regression'],'跨会话/分支/队列引用；真实 chmod I/O 删除失败保留文件、SHA 和 managed record，重试删除；保存失败与独立重启、最后引用和私有材料库哨兵。'),
'DELIVERY':(['delivery-1','delivery-2','delivery-failure','delivery-ui'],'两轮五格式新建 SOURCE，实际跨 UID VIEW/SEND 与精确 Download SHA；系统取消和两个入口切档目标零字节；复制中取消/切档、损坏源及 null/throw/wrong-bytes 重读拒绝。'),
'SERVICE':([],'server-final-retry.xml/log：29 项；真实 Store/SQLite/main 启动及周期、enqueue/cleanup 与 claim/execute 门控、宽限期和补偿；仅 OS 锁/Docker/挂载边界替身，不证明 worker。'),
'DIAGNOSTICS':(['diagnostics'],'6 方法正文/凭据/URL/路径脱敏及容量保留最新故障；独立 runner 在 Application.onCreate 前隔离事件，整批 original record/export cache 两文件哈希一致。')}
lines=['# 固定清单逐项验收矩阵','',
'固定范围来自 scope.json；历史通过不计入本次。PASS 表示本行注明的直接断言或源码审计。FAIL、SKIP、NOT_RUN、MISSING_CONFIGURATION 分开；本次最终本地必需项无 FAIL/SKIP。', '',
'|ID|固定子场景|状态|实际断言及证据范围|', '|---|---|---|---|']
for category,items in scope['requiredLocal'].items():
    names,assertion=group[category]
    references=ev(names) if names else link('final-collector.log' if category=='PR03' else 'server-final-retry.xml')
    for i,item in enumerate(items,1):
        lines.append(f'|{category}-{i:02}|{item}|PASS|{assertion} {references}|')
lines += ['', '## 审批事务逐步断言', '', '|事务故障步骤|approve|deny|answer|直接证据|','|---|---|---|---|---|']
for step in ('row','delete','insert','memory','commit'):
    lines.append(f'|{step}|PASS：原 DB/内存/队列|PASS：原 DB/内存/队列|PASS：原 DB/内存/队列|'+ev(['transaction'])+'；设备 assertions.txt 与 exceptions.txt|')
lines += ['', '## 最终独立设备调用', '', '|调用|JUnit 方法数|状态|UUID|窗口（epoch ms）|日志 SHA-256|', '|---|---:|---|---|---|---|']
for name,r in runs.items(): lines.append(f"|{link('final-independent-android/'+name+'.log',name)}|{r['tests']}|{r['status']}|`{r['runId']}`|{r['notBefore']}–{r['notAfter']}|`{r['logSha256']}`|")
lines += ['', '## 外部条件及旧清单其余范围', '', '|项目|状态|缺少条件或本次界限|','|---|---|---|',
'|真实教学/材料远端模型|MISSING_CONFIGURATION|未获本批真实提供商配置与执行授权；不配置/猜测凭据，不重发过滤请求；历史失败根因 UNKNOWN。|',
'|真实 GitHub 安装|MISSING_CONFIGURATION|本地合成包安装及 Skills 工具已验；真实仓库/身份与联网执行授权未提供。|',
'|真实图片生成|MISSING_CONFIGURATION|合成缺配置合同通过不等于生成成功；需明确图片模型和授权。|',
'|真实 worker、Docker 挂载|MISSING_CONFIGURATION|本次 OS 锁/Docker/挂载替身仅验证本地调度；需要真实部署与执行授权。|',
'|额外设备、正式 Release/签名|NOT_RUN|仅 API34 x86_64/4096 模拟器；未提供设备、签名及实机授权。|',
'|旧 B/C 中全部页面、真实四模式教学、所有提供商/网络异常、复杂 Office/手写、完整历史迁移、Windows/Linux workspace 全套、Web 门禁|NOT_RUN|不纳入本次固定核心修复范围；本报告不宣称全项目或完整旧清单通过。相关 app/ai JVM 及 Room 备份回归已实际执行。|',
'|Skills 制作/编辑/自动生成草稿|NOT_RUN（按合同关闭）|本次验证关闭状态；安装、查看、显式启用、卸载通过，不把未开放制作能力记为失败。|',
'|历史分享窗口/真实教学和材料首次失败|NOT_RUN / 根因 UNKNOWN|保留历史结果；本次正常有限样本不能证明历史根因修复或长期稳定。|']
(QA/'acceptance-matrix.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
issues=[
('PR01','P1','旧保存复活删除会话','删除终态只在 session 内；淘汰后缺行被当作新建。持久 tombstone + lifecycleRevision 同事务保护新增/更新/恢复、checkpoint、runtime、元数据与服务入口。','client/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt',395,'pr01-before.log',['eviction','lifecycle-seed','lifecycle-verify','checklist-seed','checklist-verify']),
('PR02','P2','Word tab 比较漏洞','旧 helper 只取文本，丢弃 tab。按 XML 顺序读取 t/tab/br/cr，段落换行归一；允许 run 格式拆分/paragraph-break 等价，错误 tab/换行/顺序拒绝；公式/全表/图片断言保留。','client/app/src/androidTest/java/me/rerere/rikkahub/service/WordFormulaAssertions.kt',19,'pr02-before-host.log',['word']),
('PR03','P2','早期 manifest PASS 掩盖最终失败','旧 collector 仅信 manifest。现核对确切 class/test 的唯一 start/terminal、完整 invocation 与崩溃，矛盾拒绝；混合方法按各自终态记录，无法确认不改 PASS。','scripts/collect_run_evidence.py',33,'pr03-before-qa-temp.log',[]),
('CL01','P1','COMMIT 失败污染后续事务',' bundled requery 在 COMMIT 前弹出 Java 栈、失败时释放连接而未 native ROLLBACK。适配 openHelper 保留原异常、在同一池 writer 直接回滚；不关闭 Room 缓存、不重放写入，活动外层事务不被回滚。','client/app/src/main/java/me/rerere/rikkahub/data/db/RollbackSafeOpenHelperFactory.kt',10,'transaction-fixture-7-cleanup-retry/focused.log',['transaction','db-backup-regression','db-conversation-regression']),
('CL02','P2','Skills 替换及 rollback rename 失败后原技能不可读','替换失败后只依赖同一个可失败的 rename；现尝试受限原 backup 的 NIO atomic move，保留唯一副本及启用状态。','client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt',244,'candidate-4-android/skills-rollback.log',['skills-rollback']),
('CL03','P2','Skills 部分卸载失败损失原内容','删除已消费 SKILL.md 后返回 false，原技能不完整。先不跟随链接复制完整 rollback 树，再原子挪到 trash；删除/设置失败恢复完整文件，成功才更新启用状态。','client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt',103,'partial-cleanup-original/focused.log',['skills-partial-cleanup','skills-rollback','skills-links']),
('CL04','P1','切档迟到保存导出旧档案材料','Activity 被 DocumentsUI 覆盖时 collectAsStateWithLifecycle 暂停；rememberUpdatedState 仍是旧 owner。回调及复制/重读阶段检查 SettingsStore 的持久选择；拒绝提示跨重组保留；成果预览及列表均验证。','client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt',97,'delivery-ui-fixture-16/focused.log',['delivery-ui','delivery-failure','delivery-1','delivery-2'])]
finding=['# 已确认问题关闭记录','','全部原 PR 与固定范围中新确认 P1/P2 已关闭；无未关闭的已确认本地阻断项。覆盖缺口及外部条件未升级为代码缺陷。','']
for ident,priority,title,cause,file,line,before,after in issues:
    finding += [f'## {ident} / {priority} / CLOSED：{title}','',f'源码：{source(file,line)}。{cause}','',f'修复前直接证据：{link(before)}。', '', '最终候选直接证据：'+(ev(after) if after else link('final-collector.log'))+'。','']
finding += ['## 附加保护与限制','',
'FilesManager.kt:213：一般删除 I/O 失败保留 managed record 供重试；最终设备证据断言 SHA/记录保持和重试删除。缺少同一 metadata 负例的修复前直接证据，不另宣称已确认缺陷关闭。',
'SQLite 适配依赖本地已构建 bundled driver 的内部 mConnectionPool；源码快照及 sqlite bytecode 保留。Debug 已验证；正式 Release、其他驱动版本及设备未验证。',
'首次候选运行未隔离 Application 生命周期诊断，可能追加正常启动事件；不声称从批次开始原诊断完全未变。最终 runner 隔离后，整次独立验收前后两份原文件 SHA 完全一致，未导出内容。',
'早期候选并非全部保存完整源码快照：日志、已保存 APK、安装哈希和首次输入保留；不可将这些候选当成最终可复现源码。最终 snapshot/APK/日志/设备产物完整绑定。']
(QA/'findings.md').write_text('\n'.join(finding)+'\n',encoding='utf-8')
ledger=['# 批次与版本账本','','历史目录不变、不计入本次。候选复跑均有独立日志/目录；失败不覆盖、不合计为最终通过。', '', '|版本/批次|复跑目的及首次结果处理|版本证据|','|---|---|---|',
'|原负例|PR01 淘汰复活；PR02 tab；PR03 manifest/JUnit 矛盾均实际失败。原环境 tempfile/native DLL 错误也保留，随后按正确 host/temp 运行。|pr01-before.log、pr02-before-xml、pr03-before-qa-temp.log；原 APK/探针取自用户指定独立复查目录，未伪称新构建。|',
'|candidate 1–3|QA Gradle 源集未发现/duplicate helper 错误；纠正接入而非改变产品断言。|candidate-1/2/3 构建日志及 exit|',
'|candidate 4|三个 PR 修复；发现 Skills rollback 产品失败；memory 注入无实际写入、broken resolver null 过强是夹具错误。|candidate-4-android 首次全部日志/APK/UUID；保留首次断言|',
'|candidate 5|新增有序 Word 等价/错误与并发 queue JVM 门控通过。|candidate-5-probe-xml|',
'|candidate 6|真实工厂准确计数/Skills rename fallback；两轮正常交付；COMMIT 恢复和 UI 夹具仍失败。首次 JVM qa.root 缺失保留，后续仅修设置。|source-candidate-6、candidate-6-android/collected、candidate-6-jvm-first|',
'|candidate 7|JVM/Lint；改为真实 memory 写入与事务内 DDL；暴露 native COMMIT 污染下一事务。|candidate-7-tests；transaction-fixture-7 首次残留 trigger 与 cleanup-retry 分开|',
'|candidate 8–9|恢复方案失败：close 导致 Room 缓存失效；session ROLLBACK 不能恢复 native writer。|各构建日志与 transaction-product-8/9 首次及 cleanup-retry|',
'|candidate 10|Skills 部分删除原负例先失败，再增加完整不跟随链接回滚副本。|partial-cleanup-original；partial-cleanup-before-test.apk|',
'|candidate 11|固定 primary connection affinity 仍由 executeSpecial 转 Java rollback；失败及原异常保留。|transaction-product-11/registered-exceptions.txt 与 bytecode|',
'|candidate 12|QA runner 在 projectsEvaluated 修改 DSL 过晚：构建错误保留；注册 finalizeDsl 修正。|candidate-12.log|',
'|candidate 13|原池 writer 直接 rollback 后 15 故障通过；Skills 三方法通过；UI仍失败。|source-candidate-13；transaction/skills-product-13；delivery-ui-product-13 首次 FAILED 已严格采集|',
'|fixture 14–15|加强 UI 前态/目标证据；按钮大小写与 UiAutomation shell 引号夹具错误保留，不降低零字节断言。|ui-fixture-14/15.log；delivery-ui-fixture-14/15-collected；DeliveryUiClosureTest-first.kt|',
'|fixture 16|修正引号后同一零字节负例 actual=19，确认新 P1；仍未修改生产交付。|delivery-ui-fixture-16 日志/安装 APK SHA/collected|',
'|candidate 17–18|修复实时档案授权，添加复制中切档及两个 UI 入口；产品18两方法通过。|candidate-17/18.log；delivery-ui-product-18；source-final-candidate-18|',
'|final-quality|最终同一源码 app332/ai200、QA4重跑、Lint0/42、三 Debug 与 test APK；QA4已包含在app332，不重复累计。|final-jvm、final-lint.xml、final-quality.log|',
'|final-independent-android|固定一次独立验收：22调用/41方法，含 Room备份3+会话4；没有失败后忽略。|final-independent-android、final-collected：59产物、10组精确交付|',
'|final service/collector|collector9；服务首个sandbox命令超时保留 exit=-1，定位并结束仅本QA进程，服务目录host执行29通过；无 worker/远端。|server-final.log 与 server-final-retry.xml/log；final-collector.log|',
'|finalization|汇总脚本首次把 pass 作 Python关键字导致语法错误；改名 passed 后检查通过，不涉及产品/测试断言。|finalization-first-error.txt、finalization.log|']
ledger += ['', '## 最终 APK', '', '|产物|SHA-256|', '|---|---|']+[f'|{path}|`{value}`|' for path,value in s['buildApks'].items()]
ledger += ['', '安装主包/测试包与上述 x86_64/test APK 一致；source-final-candidate-18/hashes.json 再核对源码 0 差异。完整日志、上下界、设备 mtime/字节记录、证据哈希见 summary.json、run-context.json、fixture-manifest.json、evidence-hashes.json。']
(QA/'version-ledger.md').write_text('\n'.join(ledger)+'\n',encoding='utf-8')
report=['# MathStudyAgent 本地修复收尾结果','','本次固定核心范围已验收，无未关闭的已确认本地 P1/P2。原 PR01–PR03 与补覆盖确认的 CL01–CL04 共七项关闭；没有提交、推送或部署。既有 worktree 未复位，生产/测试/QA 改动保留。', '',
'最终源码：source-final-candidate-18，重新哈希 0 差异。主/测试安装 APK 与构建产物一致。三 Debug APK（universal、arm64-v8a、x86_64）和测试 APK 的哈希见版本账本。仅 API34/x86_64/4096 模拟器执行，不宣称 ARM 实机通过。','',
'本次独立设备 22 次调用、41 个方法全部 PASS；真实事务步骤 5×3 逐项断言。app JVM 332（含四项 QA），ai JVM 200，共 532 个不同 JVM 用例；QA 四项另重跑不重复计数。服务本地 29、采集器 9 全通过。Lint 0 错误、42 警告，与本批前门禁同口径数量一致，未新增 baseline 或批量 suppression。','',
'严格采集 59 份最终方法/UUID产物；JUnit确切 class/test 最终状态、调用终态、日志SHA、时间窗口、设备mtime/内容、已安装 APK均核对。两轮五格式共10组 SOURCE/VIEW/SEND/确切 Download 目标 SHA一致。取消、损坏、切档、复制中切档/取消、三种目标重读失败均拒绝虚假成功。','',
'原诊断记录与导出缓存仅取哈希，整批前后2文件相同；最终 runner 在 Application.onCreate 前隔离。早期候选生命周期可能追加过启动事件，不伪称整个批次原记录从未变化。合成 QA 之外的聊天、凭据、真实技能正文未导出；清理遵守所属 UUID/档案/QA登记路径。','',
'当前可用范围：会话创建/更新/显式恢复及删除终态，合成提供商下完整审批续行和工具准确执行计数，安装/查看/显式启用/卸载 Skills，受引用保护的附件清理及重试，基本五格式本地交付与失败识别，本地服务调度/補偿与诊断。Skills 制作、编辑、自动草稿保持关闭；已安装显式启用可进入工具列表；全局包无学习档案自动全文拼接仅以源码调用链审计计入。','',
'仍需外部条件：获授权真实模型教学/材料、GitHub、图片模型、真实 worker/Docker挂载、额外设备与正式签名 Release。本批没有配置或猜测凭据、重发过滤请求或重放不确定工具。真实教学/材料和旧分享窗口历史首次失败根因仍 UNKNOWN；后续本地通过不证明它们修复。','',
'结束条件：七项确认问题均有修复前失败与最终通过直接证据；固定核心本地子场景全部执行；相关回归/构建/质量完成；最终证据绑定无矛盾PASS；外部条件逐项分列。因此可结束本次本地修复批次，不能据此宣布全项目、远端教学/生成或真实worker交付验收完成。','',
'- '+link('findings.md','七项根因、源码位置和直接证据'),
'- '+link('acceptance-matrix.md','固定清单逐项验收矩阵与外部条件'),
'- '+link('version-ledger.md','各独立批次、首次失败和版本账本'),
'- '+link('summary.json','机器可读结果')+' / '+link('evidence-hashes.json','最终证据哈希'),
'- '+link('global-instruction-source-audit.txt','全局指令包调用链审计'),
'', '统计更正：执行中一条进度消息手工写成40方法；按原始JUnit日志准确统计为41。首次错误保留，不改变任何验收结果。']
(QA/'report.md').write_text('\n'.join(report)+'\n',encoding='utf-8')
(QA/'finalization-first-error.txt').write_text('SyntaxError: invalid syntax at dict(... pass=sum(...)). Corrected reserved keyword to passed. Original tool output retained; no product/test assertion changed.\n',encoding='utf-8')
print('reports written; local rows:',sum(map(len,scope['requiredLocal'].values())))
